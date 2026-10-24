package ru.fortport.client

import android.graphics.Outline
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
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
 * Нативная вкладка «Порты» вместо веб-страницы /communities (scripts/pages/communities_page.js).
 *
 * API:
 *  - GET  /api/user/communities/{userId}   — {communities[]} подключённые порты;
 *  - GET  /api/communities/search?q=…      — поиск портов (массив, от 2 символов);
 *  - POST /api/communities/{id}/join       — подключиться → {success};
 *  - POST /api/communities/{id}/leave      — отключиться  → {success}.
 * Страница порта (/community?id=) и создание (/new_community) остаются веб-страницами.
 */
class CommunitiesListController(
    private val activity: AppCompatActivity,
    private val api: SiteApi,
    private val swipe: SwipeRefreshLayout,
    private val recycler: RecyclerView,
    private val searchInput: EditText,
    private val onOpenCommunity: (communityId: Long) -> Unit,
    private val onCreateCommunity: () -> Unit,
    private val onAuthLost: () -> Unit
) {
    companion object {
        private const val TAG = "CommunitiesList"
        private const val SEARCH_DEBOUNCE_MS = 300L
        private const val MIN_QUERY = 2
    }

    private class Comm(
        val id: Long,
        val name: String,
        val avatar: String,
        val isCommunity: Boolean,
        val subCount: Long?
    )

    private enum class Kind { MINE, SEARCH }

    private sealed class Row {
        class Header(val text: String, val createButton: Boolean = false) : Row()
        class Empty(val text: String) : Row()
        class Card(val comm: Comm, val kind: Kind, val subscribed: Boolean) : Row()
    }

    private val ui = Handler(Looper.getMainLooper())

    private var mine: List<Comm> = emptyList()
    private var searchResults: List<Comm>? = null
    private var rows: List<Row> = emptyList()

    /** Чьи порты показываем: null — свои. */
    private var viewedUserId: String? = null
    private var myIds: Set<Long> = emptySet()
    val viewing: Boolean get() = viewedUserId != null && viewedUserId != api.userId

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
        clearSearchText()
        searchResults = null
        rebuild()
    }

    fun destroy() { stop() }

    /** Порты другого пользователя (null — свои). true — выбор изменился, список сброшен. */
    fun setViewedUser(id: String?): Boolean {
        val n = id?.takeIf { it.isNotBlank() }
        if (n == viewedUserId) return false
        viewedUserId = n
        mine = emptyList(); searchResults = null
        loadedOnce = false
        clearSearchText()
        rebuild()
        return true
    }

    private fun clearSearchText() {
        ignoreTextChange = true
        searchInput.setText("")
        ignoreTextChange = false
    }

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
            var list: List<Comm> = emptyList()
            var mineIds: Set<Long> = emptySet()
            try {
                when (api.ensureUserId()) {
                    1 -> auth = true
                    2 -> {}
                    else -> {
                        val other = viewedUserId?.takeIf { it != api.userId }
                        if (other == null) {
                            val r = api.get("/api/user/communities/${api.userId}")
                            if (r.code == 401 || r.code == 403) auth = true
                            else if (r.code == 200) {
                                list = parseComms(JSONObject(r.body).optJSONArray("communities"))
                                ok = true
                            }
                        } else {
                            // Чужие порты: массив [{id, username, profilePicture, type}]; свои нужны для кнопок подключения.
                            val r = api.get("/api/users/$other/communities")
                            if (r.code == 401 || r.code == 403) auth = true
                            else if (r.code == 200) {
                                list = parseComms(JSONArray(r.body.trim()))
                                ok = true
                                val mr = api.get("/api/user/communities/${api.userId}")
                                if (mr.code == 200) {
                                    mineIds = parseComms(JSONObject(mr.body).optJSONArray("communities")).map { c -> c.id }.toSet()
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Не удалось загрузить порты", e)
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
                    ok -> { loadedOnce = true; mine = list; myIds = mineIds; rebuild() }
                    !silent || !loadedOnce -> if (rows.isEmpty()) {
                        rows = listOf(Row.Empty("Не удалось загрузить порты.\nПотяните вниз, чтобы повторить"))
                        adapter.notifyDataSetChanged()
                    }
                }
            }
        }
    }

    private fun parseComms(arr: JSONArray?): List<Comm> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optLong("id", 0L)
            if (id <= 0) return@mapNotNull null
            Comm(
                id = id,
                name = api.str(o, "username").ifBlank { "Без названия" },
                avatar = api.str(o, "profilePicture"),
                isCommunity = api.str(o, "type") == "community",
                subCount = if (o.has("subCount") && !o.isNull("subCount")) o.optLong("subCount", 0L) else null
            )
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
        if (query.length < MIN_QUERY) return // как на сайте: от 2 символов
        val task = Runnable { runSearchNow(query) }
        searchTask = task
        ui.postDelayed(task, SEARCH_DEBOUNCE_MS)
    }

    private fun runSearchNow(query: String) {
        searchTask?.let { ui.removeCallbacks(it) }
        if (query.isEmpty()) { searchGeneration++; searchResults = null; rebuild(); return }
        if (query.length < MIN_QUERY) return
        val gen = ++searchGeneration
        api.io.execute {
            val found: List<Comm>? = try {
                val r = api.get("/api/communities/search?q=" + URLEncoder.encode(query, "UTF-8"))
                if (r.code != 200) null else parseComms(JSONArray(r.body.trim()))
            } catch (e: Exception) {
                Log.w(TAG, "Ошибка поиска портов", e)
                null
            }
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed || gen != searchGeneration || !started) return@runOnUiThread
                if (found == null) {
                    Toast.makeText(activity, "Не удалось выполнить поиск", Toast.LENGTH_SHORT).show()
                } else {
                    searchResults = found
                    rebuild()
                }
            }
        }
    }

    // ------------------------------------------------------------------ строки

    private fun rebuild() {
        val out = ArrayList<Row>()
        val found = searchResults
        if (viewing) {
            out.add(Row.Header("Порты пользователя"))
            if (mine.isEmpty() && loadedOnce) out.add(Row.Empty("Нет подключённых портов"))
            mine.forEach { c -> out.add(Row.Card(c, Kind.SEARCH, myIds.contains(c.id))) }
        } else if (found != null) {
            out.add(Row.Header("Результаты поиска"))
            if (found.isEmpty()) out.add(Row.Empty("Порты не найдены"))
            found.forEach { c -> out.add(Row.Card(c, Kind.SEARCH, mine.any { it.id == c.id })) }
        } else {
            out.add(Row.Header("Мои порты (${mine.size})", createButton = true))
            if (mine.isEmpty()) out.add(Row.Empty("Нет подключённых портов!"))
            mine.forEach { out.add(Row.Card(it, Kind.MINE, true)) }
        }
        rows = out
        adapter.notifyDataSetChanged()
    }

    // ------------------------------------------------------------------ действия

    private fun toast(text: String) = Toast.makeText(activity, text, Toast.LENGTH_SHORT).show()

    private fun postAction(path: String, failText: String, onSuccess: () -> Unit) {
        api.io.execute {
            var ok = false
            var err = ""
            try {
                val r = api.send("POST", path, null)
                val o = try { JSONObject(r.body) } catch (e: Exception) { null }
                ok = r.code in 200..299 && o?.optBoolean("success") == true
                err = o?.let { api.str(it, "error") }.orEmpty()
            } catch (e: Exception) {
                Log.w(TAG, "Запрос $path не выполнен", e)
            }
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                if (ok) onSuccess() else toast("Ошибка: " + err.ifBlank { failText })
            }
        }
    }

    private fun join(c: Comm) = postAction("/api/communities/${c.id}/join", "Не удалось подключиться") {
        // как на сайте: после подключения выходим из режима поиска и обновляем «Мои порты»
        clearSearchText()
        searchGeneration++
        searchResults = null
        if (started) load(silent = true)
    }

    private fun confirmLeave(c: Comm) {
        AlertDialog.Builder(activity)
            .setMessage("Отключиться от этого порта?")
            .setPositiveButton(android.R.string.ok) { _, _ ->
                postAction("/api/communities/${c.id}/leave", "Не удалось отключиться") {
                    if (searchResults != null) {
                        clearSearchText()
                        searchGeneration++
                        searchResults = null
                    }
                    if (started) load(silent = true)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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
        private val create = TextView(box.context).apply {
            text = "Новый порт"
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(dp(14), 0, dp(14), 0)
            setBackgroundResource(R.drawable.bg_friend_btn)
            setTextColor(0xFF15141C.toInt())
            isClickable = true
            setOnClickListener { onCreateCommunity() }
        }

        init {
            box.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            box.addView(create, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34)))
        }

        fun bind(h: Row.Header) {
            title.text = h.text
            create.visibility = if (h.createButton) View.VISIBLE else View.GONE
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
            val c = row.comm
            name.text = c.name
            api.loadAvatar(avatar, c.avatar)
            val open = View.OnClickListener { onOpenCommunity(c.id) }
            avatar.setOnClickListener(open)
            name.setOnClickListener(open)

            // «сообщество / страница» + «N Участников / Подписчиков» — две строки, как на сайте.
            val type = if (c.isCommunity) "сообщество" else "страница"
            val sb = SpannableStringBuilder(type)
            sb.setSpan(ForegroundColorSpan(0xFF8891A8.toInt()), 0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (c.subCount != null) {
                val start = sb.length + 1
                sb.append("\n").append("${c.subCount} ${if (c.isCommunity) "Участников" else "Подписчиков"}")
                sb.setSpan(ForegroundColorSpan(0xFF1CA67E.toInt()), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            sub.text = sb
            sub.visibility = View.VISIBLE

            buttons.removeAllViews()
            val subscribed = row.subscribed
            val label = if (subscribed) "Отключить" else "Подключиться"
            val b = TextView(buttons.context).apply {
                text = label
                textSize = 13f
                gravity = Gravity.CENTER
                minWidth = dp(110)
                setPadding(dp(14), 0, dp(14), 0)
                setBackgroundResource(R.drawable.bg_friend_btn)
                setTextColor(0xFF15141C.toInt())
                isClickable = true
                setOnClickListener { if (subscribed) confirmLeave(c) else join(c) }
            }
            buttons.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)))
        }
    }
}
