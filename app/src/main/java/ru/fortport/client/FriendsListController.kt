package ru.fortport.client

import android.graphics.Outline
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Нативная вкладка «Друзья» вместо веб-страницы /friends (scripts/shared/friends.js).
 *
 * API:
 *  - GET  /api/friends/{userId}              — {friends[], pending[], subscribersCount};
 *  - GET  /api/users/search?q=…              — поиск пользователей (массив);
 *  - POST /api/friends/request  {toUserId}   — отправить заявку;
 *  - POST /api/friends/accept   {requesterUserId};
 *  - POST /api/friends/reject   {requesterUserId};
 *  - POST /api/friends/remove   {friendId};
 *  - POST /api/chats/create     {userId}     — открыть/создать диалог («Написать»): {chatId, chat{…}}.
 */
class FriendsListController(
    private val activity: AppCompatActivity,
    private val api: SiteApi,
    private val swipe: SwipeRefreshLayout,
    private val recycler: RecyclerView,
    private val searchInput: EditText,
    private val onOpenChat: (payloadJson: String) -> Unit,
    private val onOpenProfile: (userId: Long) -> Unit,
    private val onAuthLost: () -> Unit
) {
    companion object {
        private const val TAG = "FriendsList"
        private const val SEARCH_DEBOUNCE_MS = 300L
        private val CHAT_ID_RE = Regex("^\\d+(_\\d+)?$")
    }

    private class User(val id: Long, val name: String, val avatar: String, val status: String)

    private enum class Kind { FRIEND, PENDING, SEARCH }

    private sealed class Row {
        class Header(val text: String, val count: Int? = null) : Row()
        class Empty(val text: String) : Row()
        class Card(val user: User, val kind: Kind, val isFriend: Boolean) : Row()
    }

    private val ui = Handler(Looper.getMainLooper())

    private var friends: List<User> = emptyList()
    private var pending: List<User> = emptyList()
    private var searchResults: List<User>? = null
    private var rows: List<Row> = emptyList()

    private var started = false
    private var loadedOnce = false
    private var bgCheckPending = false
    private var generation = 0
    private var searchGeneration = 0
    private var ignoreTextChange = false
    private var searchTask: Runnable? = null

    private val adapter = Adapter()

    init {
        recycler.layoutManager = LinearLayoutManager(activity)
        recycler.adapter = adapter
        recycler.itemAnimator = null
        val d = activity.resources.displayMetrics.density
        // Список начинается под шапкой раздела (70dp) и строкой поиска — индикатор рисуем под ними.
        swipe.setProgressViewOffset(false, (128 * d).toInt(), (190 * d).toInt())
        swipe.setColorSchemeResources(R.color.fp_accent)
        swipe.setDistanceToTriggerSync((32 * d).toInt())
        swipe.setSize(SwipeRefreshLayout.LARGE)
        swipe.setOnRefreshListener { load(silent = false) }

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (ignoreTextChange) return
                scheduleSearch(s?.toString().orEmpty().trim())
            }
        })
        searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                runSearchNow(searchInput.text.toString().trim())
                hideKeyboard()
                true
            } else false
        }
    }

    // ------------------------------------------------------------------ жизненный цикл

    fun start(userId: String, deviceId: String, userAgent: String) {
        api.setSession(userId, deviceId, userAgent)
        if (started) { load(silent = true); return }
        started = true
        bgCheckPending = true
        if (!loadedOnce) swipe.isRefreshing = true
        load(silent = true)
    }

    fun stop() {
        started = false
        generation++
        searchGeneration++
        searchTask?.let { ui.removeCallbacks(it) }
        swipe.isRefreshing = false
        hideKeyboard()
    }

    /** Сброс поиска при уходе с вкладки (как при новой загрузке страницы на сайте). */
    fun reset() {
        ignoreTextChange = true
        searchInput.setText("")
        ignoreTextChange = false
        searchResults = null
        rebuild()
    }

    fun destroy() { stop() }

    private fun hideKeyboard() {
        val imm = activity.getSystemService(InputMethodManager::class.java)
        imm?.hideSoftInputFromWindow(searchInput.windowToken, 0)
        searchInput.clearFocus()
    }

    // ------------------------------------------------------------------ загрузка

    private fun load(silent: Boolean) {
        val gen = ++generation
        api.io.execute {
            var ok = false
            var auth = false
            var f: List<User> = emptyList()
            var p: List<User> = emptyList()
            try {
                when (api.ensureUserId()) {
                    1 -> auth = true
                    2 -> {}
                    else -> {
                        val r = api.get("/api/friends/${api.userId}")
                        if (r.code == 401 || r.code == 403) auth = true
                        else if (r.code == 200) {
                            val o = JSONObject(r.body)
                            f = parseUsers(o.optJSONArray("friends"))
                            p = parseUsers(o.optJSONArray("pending"))
                            ok = true
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Не удалось загрузить друзей", e)
            }
            if (ok && bgCheckPending) {
                bgCheckPending = false
                try { api.refreshBackground() } catch (e: Exception) { Log.w(TAG, "Не удалось обновить фон", e) }
            }
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed || gen != generation || !started) return@runOnUiThread
                swipe.isRefreshing = false
                when {
                    auth -> onAuthLost()
                    ok -> { loadedOnce = true; friends = f; pending = p; rebuild() }
                    !silent || !loadedOnce -> if (rows.isEmpty()) {
                        rows = listOf(Row.Empty("Не удалось загрузить друзей.\nПотяните вниз, чтобы повторить"))
                        adapter.notifyDataSetChanged()
                    }
                }
            }
        }
    }

    private fun parseUsers(arr: JSONArray?): List<User> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optLong("id", 0L)
            if (id <= 0) return@mapNotNull null
            User(id, api.str(o, "username"), api.str(o, "profilePicture"), api.str(o, "status"))
        }
    }

    // ------------------------------------------------------------------ поиск

    private fun scheduleSearch(query: String) {
        searchTask?.let { ui.removeCallbacks(it) }
        if (query.isEmpty()) {
            searchGeneration++
            searchResults = null
            rebuild()
            return
        }
        val task = Runnable { runSearchNow(query) }
        searchTask = task
        ui.postDelayed(task, SEARCH_DEBOUNCE_MS)
    }

    private fun runSearchNow(query: String) {
        searchTask?.let { ui.removeCallbacks(it) }
        if (query.isEmpty()) { searchResults = null; rebuild(); return }
        val gen = ++searchGeneration
        api.io.execute {
            val users: List<User>? = try {
                val r = api.get("/api/users/search?q=" + URLEncoder.encode(query, "UTF-8"))
                if (r.code != 200) null else {
                    val body = r.body.trim()
                    val arr = if (body.startsWith("[")) JSONArray(body) else JSONObject(body).optJSONArray("users")
                    parseUsers(arr).filter { it.id.toString() != api.userId }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Ошибка поиска", e)
                null
            }
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed || gen != searchGeneration || !started) return@runOnUiThread
                if (users == null) {
                    Toast.makeText(activity, "Не удалось выполнить поиск", Toast.LENGTH_SHORT).show()
                } else {
                    searchResults = users
                    rebuild()
                }
            }
        }
    }

    // ------------------------------------------------------------------ строки

    private fun rebuild() {
        val out = ArrayList<Row>()
        val found = searchResults
        if (found != null) {
            out.add(Row.Header("Результаты поиска"))
            if (found.isEmpty()) out.add(Row.Empty("Пользователи не найдены"))
            found.forEach { u -> out.add(Row.Card(u, Kind.SEARCH, friends.any { it.id == u.id })) }
        } else {
            out.add(Row.Header("Мои друзья"))
            if (friends.isEmpty()) out.add(Row.Empty("У вас пока нет друзей"))
            friends.forEach { out.add(Row.Card(it, Kind.FRIEND, true)) }
            out.add(Row.Header("Заявки в друзья", pending.size))
            if (pending.isEmpty()) out.add(Row.Empty("Нет новых заявок"))
            pending.forEach { out.add(Row.Card(it, Kind.PENDING, false)) }
        }
        rows = out
        adapter.notifyDataSetChanged()
    }

    // ------------------------------------------------------------------ действия

    private fun post(path: String, json: String, onDone: (JSONObject?, Int) -> Unit) {
        api.io.execute {
            var obj: JSONObject? = null
            var code = -1
            try {
                val r = api.send("POST", path, json)
                code = r.code
                obj = try { JSONObject(r.body) } catch (e: Exception) { null }
            } catch (e: Exception) {
                Log.w(TAG, "Запрос $path не выполнен", e)
            }
            activity.runOnUiThread {
                if (!activity.isFinishing && !activity.isDestroyed) onDone(obj, code)
            }
        }
    }

    private fun toast(text: String) = Toast.makeText(activity, text, Toast.LENGTH_SHORT).show()

    private fun reloadSoon() { if (started) load(silent = true) }

    private fun accept(u: User) = post("/api/friends/accept", JSONObject().put("requesterUserId", u.id).toString()) { o, _ ->
        if (o?.optBoolean("success") == true) reloadSoon() else toast("Не удалось принять заявку")
    }

    private fun reject(u: User) = post("/api/friends/reject", JSONObject().put("requesterUserId", u.id).toString()) { o, _ ->
        if (o?.optBoolean("success") == true) reloadSoon() else toast("Не удалось отклонить заявку")
    }

    private fun sendRequest(u: User) = post("/api/friends/request", JSONObject().put("toUserId", u.id).toString()) { o, _ ->
        if (o?.optBoolean("success") == true) {
            toast("Заявка отправлена!")
            reloadSoon()
        } else {
            val err = o?.let { api.str(it, "error") }.orEmpty()
            toast("Ошибка: " + err.ifBlank { "Не удалось отправить заявку" })
        }
    }

    private fun confirmRemove(u: User) {
        AlertDialog.Builder(activity)
            .setMessage("Удалить из друзей?")
            .setPositiveButton(android.R.string.ok) { _, _ ->
                post("/api/friends/remove", JSONObject().put("friendId", u.id).toString()) { o, _ ->
                    if (o?.optBoolean("success") == true) reloadSoon() else toast("Не удалось удалить из друзей")
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** «Написать»: создаём (или находим) диалог и открываем нативный чат. */
    private fun message(u: User) {
        post("/api/chats/create", JSONObject().put("userId", u.id.toString()).toString()) { o, code ->
            val chatId = o?.let { api.str(it, "chatId") }.orEmpty()
            if (code !in 200..299 || !CHAT_ID_RE.matches(chatId)) {
                val err = o?.let { api.str(it, "error") }.orEmpty()
                toast(err.ifBlank { "Не удалось открыть диалог" })
                return@post
            }
            val chat = o?.optJSONObject("chat")
            val payload = JSONObject()
                .put("chatId", chatId)
                .put("name", chat?.let { api.str(it, "username") }?.ifBlank { null } ?: u.name)
                .put("avatar", chat?.let { api.str(it, "profilePicture") }?.ifBlank { null } ?: u.avatar)
                .put("status", chat?.let { api.str(it, "status") } ?: u.status)
                .put("myId", api.userId)
                .put("deviceId", api.deviceId)
                .put("ua", api.userAgent)
            onOpenChat(payload.toString())
        }
    }

    // ------------------------------------------------------------------ адаптер

    private fun dp(v: Int) = (v * activity.resources.displayMetrics.density).toInt()

    private inner class Adapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = rows.size
        override fun getItemViewType(position: Int) = when (rows[position]) {
            is Row.Header -> 0
            is Row.Empty -> 1
            is Row.Card -> 2
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder = when (viewType) {
            0 -> HeaderHolder(LinearLayout(parent.context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                setPadding(dp(4), dp(8), dp(4), dp(10))
            })
            1 -> object : RecyclerView.ViewHolder(TextView(parent.context).apply {
                layoutParams = ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .also { it.bottomMargin = dp(12) }
                setBackgroundResource(R.drawable.bg_chat_card)
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(20), dp(16), dp(20))
                textSize = 14f
                setTextColor(context.getColor(R.color.fp_chat_sub))
            }) {}
            else -> CardHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_friend_card, parent, false))
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = rows[position]) {
                is Row.Header -> (holder as HeaderHolder).bind(row)
                is Row.Empty -> (holder.itemView as TextView).text = row.text
                is Row.Card -> (holder as CardHolder).bind(row)
            }
        }
    }

    private inner class HeaderHolder(private val box: LinearLayout) : RecyclerView.ViewHolder(box) {
        private val title = TextView(box.context).apply {
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(context.getColor(R.color.fp_chat_name))
        }
        private val count = TextView(box.context).apply {
            textSize = 12f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(2), dp(8), dp(2))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(0xFF4CAF50.toInt())
            }
        }

        init {
            box.addView(title)
            box.addView(count, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .also { it.marginStart = dp(8) })
        }

        fun bind(h: Row.Header) {
            title.text = h.text
            if (h.count != null) { count.text = h.count.toString(); count.visibility = View.VISIBLE }
            else count.visibility = View.GONE
        }
    }

    private inner class CardHolder(v: View) : RecyclerView.ViewHolder(v) {
        private val avatar: ImageView = v.findViewById(R.id.friendAvatar)
        private val name: TextView = v.findViewById(R.id.friendName)
        private val sub: TextView = v.findViewById(R.id.friendSub)
        private val buttons: LinearLayout = v.findViewById(R.id.friendButtons)

        init {
            val radius = 12f * v.resources.displayMetrics.density
            avatar.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, radius)
                }
            }
            avatar.clipToOutline = true
        }

        fun bind(row: Row.Card) {
            val u = row.user
            name.text = u.name
            api.loadAvatar(avatar, u.avatar)
            val open = View.OnClickListener { onOpenProfile(u.id) }
            avatar.setOnClickListener(open)
            name.setOnClickListener(open)

            if (row.kind == Kind.PENDING) {
                sub.text = "Хочет подключиться к вам в друзья"
                sub.setTextColor(0xFF4CAF50.toInt())
                sub.visibility = View.VISIBLE
            } else {
                sub.text = u.status
                sub.setTextColor(0xFF8891A8.toInt())
                sub.visibility = if (u.status.isBlank()) View.GONE else View.VISIBLE
            }

            buttons.removeAllViews()
            when (row.kind) {
                Kind.PENDING -> {
                    addButton("Подключить", R.drawable.bg_friend_btn_accept, true) { accept(u) }
                    addButton("Отклонить", R.drawable.bg_friend_btn_reject, true) { reject(u) }
                }
                Kind.FRIEND -> {
                    addButton("Написать", R.drawable.bg_friend_btn, false) { message(u) }
                    addButton("Отключить", R.drawable.bg_friend_btn, false) { confirmRemove(u) }
                }
                Kind.SEARCH -> if (row.isFriend) {
                    addButton("Написать", R.drawable.bg_friend_btn, false) { message(u) }
                    addButton("Отключить", R.drawable.bg_friend_btn, false) { confirmRemove(u) }
                } else {
                    addButton("Подключить", R.drawable.bg_friend_btn, false) { sendRequest(u) }
                    addButton("Написать", R.drawable.bg_friend_btn, false) { message(u) }
                }
            }
        }

        private fun addButton(label: String, bgRes: Int, whiteText: Boolean, onClick: () -> Unit) {
            val b = TextView(buttons.context).apply {
                text = label
                textSize = 13f
                gravity = Gravity.CENTER
                minWidth = dp(96)
                setPadding(dp(14), 0, dp(14), 0)
                setBackgroundResource(bgRes)
                setTextColor(if (whiteText) 0xFFFFFFFF.toInt() else 0xFF15141C.toInt())
                isClickable = true
                setOnClickListener { onClick() }
            }
            buttons.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36))
                .also { it.marginStart = dp(8) })
        }
    }
}
