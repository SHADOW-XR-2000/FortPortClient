package ru.fortport.client

import android.graphics.Outline
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import org.json.JSONObject
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Нативный список переписок вместо веб-страницы /chats.
 *
 * API (как в scripts/pages/chats.js):
 *  - GET    /api/users/chats/{userId}            — {favouriteChats[], userChats[]};
 *  - POST   /api/users/chats/favourite/{chatId}  — закрепить / открепить;
 *  - POST   /api/users/chats/read/{chatId}       — прочитать всё;
 *  - DELETE /api/users/chats/{chatId}            — убрать переписку из своего списка.
 *
 * Последнее сообщение приходит зашифрованным (AES-GCM, ключ = SHA-256(chatId),
 * base64(iv[12] + ciphertext+tag)) — расшифровывается так же, как в ChatActivity.
 * Обновление — периодический опрос, пока список виден: long-poll /api/users/chats/wait
 * делит события с WebView-страницей сайта, и мы бы «воровали» их друг у друга.
 */
class ChatsListController(
    private val activity: AppCompatActivity,
    private val api: SiteApi,
    private val swipe: SwipeRefreshLayout,
    private val recycler: RecyclerView,
    private val emptyView: TextView,
    private val onOpenChat: (payloadJson: String) -> Unit,
    private val onAuthLost: () -> Unit
) {
    companion object {
        private const val TAG = "ChatsList"
        private const val POLL_MS = 7000L
        private val CHAT_ID_RE = Regex("^\\d+(_\\d+)?$")
    }

    private class Chat(
        val chatId: String,
        val username: String,
        val avatar: String,
        val status: String,
        val preview: String,
        val timeText: String,
        val unread: Int,
        val favourite: Boolean
    )

    private sealed class Row {
        class Header(val text: String) : Row()
        class Item(val chat: Chat) : Row()
    }

    private sealed class Result {
        class Ok(val favs: List<Chat>, val regular: List<Chat>) : Result()
        object Auth : Result()
        object Fail : Result()
    }

    private val ui = Handler(Looper.getMainLooper())
    private var bgCheckPending = false

    private val adapter = Adapter()
    private var rows: List<Row> = emptyList()
    private var signature = ""
    private var generation = 0
    private var started = false
    private var loadedOnce = false

    private val pollTask = object : Runnable {
        override fun run() {
            if (!started) return
            load(silent = true)
            ui.postDelayed(this, POLL_MS)
        }
    }

    init {
        recycler.layoutManager = LinearLayoutManager(activity)
        recycler.adapter = adapter
        recycler.itemAnimator = null
        // Список начинается под шапкой раздела (70dp): индикатор обновления рисуем ниже неё.
        val d = activity.resources.displayMetrics.density
        swipe.setProgressViewOffset(false, (70 * d).toInt(), (130 * d).toInt())
        swipe.setColorSchemeResources(R.color.fp_accent)
        // Обновление срабатывает от короткого свайпа (по умолчанию нужно тянуть 64dp).
        swipe.setDistanceToTriggerSync((32 * d).toInt())
        swipe.setSize(SwipeRefreshLayout.LARGE)
        swipe.setOnRefreshListener { load(silent = false) }
    }

    // ------------------------------------------------------------------ жизненный цикл

    /** Показали список (или вернулись на экран): грузим и запускаем опрос. */
    fun start(userId: String, deviceId: String, userAgent: String) {
        api.setSession(userId, deviceId, userAgent)
        if (started) { load(silent = true); return }
        started = true
        bgCheckPending = true
        if (!loadedOnce) swipe.isRefreshing = true
        load(silent = true)
        ui.postDelayed(pollTask, POLL_MS)
    }

    /** Список скрыт / экран свернут — опрос не нужен. */
    fun stop() {
        started = false
        ui.removeCallbacks(pollTask)
        generation++ // отбрасываем ответы запросов, отправленных до остановки
        swipe.isRefreshing = false
    }

    fun refreshNow() { if (started) load(silent = true) }

    fun destroy() { stop() }

    // ------------------------------------------------------------------ загрузка

    private fun load(silent: Boolean) {
        val gen = ++generation
        api.io.execute {
            val result = try { fetchChats() } catch (e: Exception) {
                Log.w(TAG, "Не удалось загрузить список переписок", e)
                Result.Fail
            }
            if (result is Result.Ok && bgCheckPending) {
                bgCheckPending = false
                try { api.refreshBackground() } catch (e: Exception) { Log.w(TAG, "Не удалось обновить фон", e) }
            }
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed || gen != generation || !started) return@runOnUiThread
                swipe.isRefreshing = false
                when (result) {
                    is Result.Ok -> { loadedOnce = true; show(result.favs, result.regular) }
                    is Result.Auth -> onAuthLost()
                    is Result.Fail -> if (!loadedOnce || !silent) {
                        if (rows.isEmpty()) showEmpty("Не удалось загрузить переписки.\nПотяните вниз, чтобы повторить")
                    }
                }
            }
        }
    }

    private fun fetchChats(): Result {
        when (api.ensureUserId()) { 1 -> return Result.Auth; 2 -> return Result.Fail }
        val r = api.get("/api/users/chats/${api.userId}")
        if (r.code == 401 || r.code == 403) return Result.Auth
        if (r.code != 200) return Result.Fail
        val data = JSONObject(r.body)
        val me = api.userId.toLongOrNull() ?: 0L
        fun parse(key: String, fav: Boolean): List<Chat> {
            val arr = data.optJSONArray(key) ?: return emptyList()
            return (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { parseChat(it, me, fav) } }
        }
        return Result.Ok(parse("favouriteChats", true), parse("userChats", false))
    }

    private fun str(o: JSONObject, k: String) = api.str(o, k)

    private fun parseChat(o: JSONObject, me: Long, fav: Boolean): Chat? {
        val chatId = str(o, "chatId")
        if (!CHAT_ID_RE.matches(chatId)) return null
        val raw = str(o, "lastMessage")
        val text = if (raw.isBlank()) "" else decrypt(chatId, raw)
        val body = when {
            text.isNotBlank() -> text
            !o.isNull("lastMessageFile") -> "[Вложение]"
            else -> "Нет сообщений"
        }
        val mine = me != 0L && o.optLong("lastMessageSenderId", -1L) == me
        return Chat(
            chatId = chatId,
            username = str(o, "username"),
            avatar = str(o, "profilePicture"),
            status = str(o, "status"),
            preview = (if (mine) "Вы: " else "") + body,
            timeText = formatTime(str(o, "lastMessageTime")),
            unread = o.optInt("unreadCount", 0),
            favourite = fav
        )
    }

    // ------------------------------------------------------------------ шифрование и время

    private fun decrypt(chatId: String, raw: String): String {
        if (raw.length <= 20) return raw
        return try {
            val key = SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(chatId.toByteArray(Charsets.UTF_8)), "AES")
            val data = Base64.decode(raw, Base64.DEFAULT)
            if (data.size <= 12) return raw
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, data.copyOfRange(0, 12)))
            String(cipher.doFinal(data, 12, data.size - 12), Charsets.UTF_8)
        } catch (e: Exception) {
            raw
        }
    }

    /** Как formatMessageTime() на сайте. */
    private fun formatTime(raw: String): String {
        if (raw.isBlank()) return ""
        val parsed: Date? = try {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(raw)
        } catch (e: Exception) {
            null
        }
        val date: Date = parsed ?: return ""
        val hm = SimpleDateFormat("HH:mm", Locale.getDefault()).format(date)
        val today = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val yesterday = (today.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }
        val weekAgo = (today.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -7) }
        val t = date.time
        return when {
            t >= today.timeInMillis -> hm
            t >= yesterday.timeInMillis -> "Вчера в $hm"
            t >= weekAgo.timeInMillis -> {
                val c = Calendar.getInstance().apply { time = date }
                listOf("Вск", "Пнд", "Втр", "Срд", "Чтв", "Птн", "Сбт")[c.get(Calendar.DAY_OF_WEEK) - 1]
            }
            else -> {
                val c = Calendar.getInstance().apply { time = date }
                val months = listOf("января", "февраля", "марта", "апреля", "мая", "июня",
                    "июля", "августа", "сентября", "октября", "ноября", "декабря")
                "${c.get(Calendar.DAY_OF_MONTH)} ${months[c.get(Calendar.MONTH)]}"
            }
        }
    }

    // ------------------------------------------------------------------ показ

    private fun show(favs: List<Chat>, regular: List<Chat>) {
        val newRows = ArrayList<Row>()
        if (favs.isNotEmpty()) {
            newRows.add(Row.Header("Закреплённые"))
            favs.forEach { newRows.add(Row.Item(it)) }
            if (regular.isNotEmpty()) newRows.add(Row.Header("Все переписки"))
        }
        regular.forEach { newRows.add(Row.Item(it)) }

        val sig = newRows.joinToString("|") { r ->
            when (r) {
                is Row.Header -> "h:${r.text}"
                is Row.Item -> with(r.chat) { "$chatId:$username:$avatar:$preview:$timeText:$unread:$favourite" }
            }
        }
        if (newRows.isEmpty()) {
            rows = newRows; signature = sig; adapter.notifyDataSetChanged()
            showEmpty("Переписок пока нет")
            return
        }
        emptyView.visibility = View.GONE
        if (sig != signature) {
            signature = sig
            rows = newRows
            adapter.notifyDataSetChanged()
        }
    }

    private fun showEmpty(text: String) {
        emptyView.text = text
        emptyView.visibility = View.VISIBLE
    }

    // ------------------------------------------------------------------ действия

    private fun openChat(c: Chat) {
        val payload = JSONObject()
            .put("chatId", c.chatId)
            .put("name", c.username)
            .put("avatar", c.avatar.takeIf { it.isNotBlank() } ?: "")
            .put("status", c.status)
            .put("myId", api.userId)
            .put("deviceId", api.deviceId)
            .put("ua", api.userAgent)
        onOpenChat(payload.toString())
    }

    private fun showMenu(anchor: View, c: Chat) {
        val popup = PopupMenu(activity, anchor)
        popup.menu.add(0, 0, 0, if (c.favourite) "Открепить" else "Закрепить")
        popup.menu.add(0, 1, 1, "Прочитать всё")
        popup.menu.add(0, 2, 2, "Убрать переписку")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                0 -> action("POST", "/api/users/chats/favourite/${c.chatId}")
                1 -> action("POST", "/api/users/chats/read/${c.chatId}")
                2 -> AlertDialog.Builder(activity)
                    .setMessage("Убрать переписку только из вашего списка? У другого участника сообщения сохранятся.")
                    .setPositiveButton(android.R.string.ok) { _, _ -> action("DELETE", "/api/users/chats/${c.chatId}") }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
            true
        }
        popup.show()
    }

    private fun action(method: String, path: String) {
        api.io.execute {
            val ok = try {
                api.send(method, path, null).code in 200..299
            } catch (e: Exception) {
                Log.w(TAG, "Действие $method $path не выполнено", e)
                false
            }
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                if (!ok) Toast.makeText(activity, "Не удалось выполнить действие", Toast.LENGTH_SHORT).show()
                refreshNow()
            }
        }
    }

    // ------------------------------------------------------------------ адаптер

    private inner class Adapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = rows.size
        override fun getItemViewType(position: Int) = if (rows[position] is Row.Header) 0 else 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            if (viewType == 0) {
                val d = parent.resources.displayMetrics.density
                val tv = TextView(parent.context).apply {
                    layoutParams = ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    setPadding((4 * d).toInt(), (4 * d).toInt(), (4 * d).toInt(), (8 * d).toInt())
                    textSize = 13f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setTextColor(parent.context.getColor(R.color.fp_chat_name))
                }
                return object : RecyclerView.ViewHolder(tv) {}
            }
            return CardHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_chat_card, parent, false))
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = rows[position]) {
                is Row.Header -> (holder.itemView as TextView).text = row.text
                is Row.Item -> (holder as CardHolder).bind(row.chat)
            }
        }
    }

    private inner class CardHolder(v: View) : RecyclerView.ViewHolder(v) {
        private val card: View = v.findViewById(R.id.chatCard)
        private val avatar: ImageView = v.findViewById(R.id.chatCardAvatar)
        private val name: TextView = v.findViewById(R.id.chatCardName)
        private val last: TextView = v.findViewById(R.id.chatCardLast)
        private val time: TextView = v.findViewById(R.id.chatCardTime)
        private val badge: TextView = v.findViewById(R.id.chatCardBadge)
        private val more: View = v.findViewById(R.id.chatCardMore)

        init {
            val radius = 12f * v.resources.displayMetrics.density
            avatar.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, radius)
                }
            }
            avatar.clipToOutline = true
        }

        fun bind(c: Chat) {
            name.text = c.username
            last.text = c.preview
            time.text = c.timeText
            card.setBackgroundResource(if (c.unread > 0) R.drawable.bg_chat_card_unread else R.drawable.bg_chat_card)
            if (c.unread > 0) { badge.text = c.unread.toString(); badge.visibility = View.VISIBLE }
            else badge.visibility = View.GONE
            api.loadAvatar(avatar, c.avatar)
            card.setOnClickListener { openChat(c) }
            card.setOnLongClickListener { showMenu(more, c); true }
            more.setOnClickListener { showMenu(more, c) }
        }
    }
}
