package ru.fortport.client

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ru.fortport.client.UiUtil.dp

/**
 * Нативный поиск по постам (страница сайта /search), тема aero.
 * Сверху (под верхней панелью раздела) — строка поиска и «Найти», ниже — посты-результаты теми же карточками, что в ленте.
 *
 * Запрос (проверен по логу сайта): GET /api/posts/search?text={запрос}&sort=new&offset={n} →
 * {posts:[…как в ленте…], total, offset, nextOffset}; страница = 15 постов, следующая — offset = nextOffset.
 * В логе только sort=new — другие виды сортировки и фильтры сайта не снимались, поэтому здесь поиск идёт по новым.
 */
class SearchController(
    override val activity: AppCompatActivity,
    override val api: SiteApi,
    override val repo: PostsRepo,
    private val host: FrameLayout,
    private val onOpenPost: (Post) -> Unit,
    private val onOpenProfile: (Long) -> Unit,
    private val onOpenCommunity: (Long) -> Unit,
    private val onBalanceChanged: () -> Unit,
    private val onAuthLost: () -> Unit
) : PostHost {

    private companion object {
        const val TAG = "SearchController"
        const val VIEW_DELAY_MS = 2500L
        const val TOPBAR_DP = 70
    }

    private val main = Handler(Looper.getMainLooper())
    private val posts = ArrayList<Post>()
    private val viewed = HashSet<Long>()

    private var started = false
    private var searched = false
    private var loading = false
    private var failed = false
    private var hasMore = false
    private var usersLoading = false
    private var generation = 0
    private var activeQuery = ""
    private var nextOffset = 0
    private var total = -1
    private var focusPending = true

    private val adapter = Adapter()
    private val recycler = RecyclerView(activity)
    private val input = EditText(activity)
    private val count = TextView(activity)

    init {
        val col = LinearLayout(activity)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(0, dp(activity, TOPBAR_DP), 0, 0)

        // Строка поиска: поле ввода и «Найти» на aero-панели.
        val panel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = AeroUi.panelBackground(activity)
            setPadding(dp(activity, 10), dp(activity, 10), dp(activity, 10), dp(activity, 10))
        }
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        AeroUi.input(input)
        input.hint = "Поиск по постам"
        input.textSize = 16f
        input.inputType = InputType.TYPE_CLASS_TEXT
        input.setSingleLine(true)
        input.imeOptions = EditorInfo.IME_ACTION_SEARCH
        input.setOnEditorActionListener { _, action, event ->
            if (action == EditorInfo.IME_ACTION_SEARCH || (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)) {
                runSearch(); true
            } else false
        }
        row.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val go = TextView(activity).apply {
            text = "Найти"; textSize = 16f; gravity = Gravity.CENTER
            setTextColor(AeroButton.textColor(activity)); setTypeface(typeface, android.graphics.Typeface.BOLD)
            AeroButton.style(this)
            setPadding(dp(activity, 14), 0, dp(activity, 14), 0)
            setOnClickListener { runSearch() }
        }
        row.addView(go, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(activity, 46)).apply { leftMargin = dp(activity, 8) })
        panel.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        count.textSize = 13f
        count.setTextColor(AeroUi.labelColor(activity))
        count.visibility = View.GONE
        panel.addView(count, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(activity, 8) })
        col.addView(panel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { setMargins(dp(activity, 10), dp(activity, 8), dp(activity, 10), dp(activity, 8)) })

        val lm = LinearLayoutManager(activity)
        recycler.layoutManager = lm
        recycler.adapter = adapter
        recycler.itemAnimator = null
        recycler.setItemViewCacheSize(4)
        recycler.clipToPadding = false
        recycler.setPadding(dp(activity, 8), 0, dp(activity, 8), dp(activity, 24))
        recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0 || loading || !hasMore || posts.isEmpty()) return
                if (lm.findLastVisibleItemPosition() >= adapter.itemCount - 4) loadMore()
            }
            override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) hideKeyboard()
            }
        })
        col.addView(recycler, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        host.addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    // ------------------------------------------------------------------ жизненный цикл

    fun start(userId: String, deviceId: String, userAgent: String) {
        api.setSession(userId, deviceId, userAgent)
        started = true
        // Первый заход — сразу ввод запроса; возврат из поста клавиатуру не поднимает.
        if (focusPending && !searched) {
            focusPending = false
            input.post { input.requestFocus(); activity.getSystemService(InputMethodManager::class.java)?.showSoftInput(input, 0) }
        }
    }

    fun stop() {
        started = false
        hideKeyboard()
        InlineAudio.stop()
    }

    /** Открыли поиск заново (из ленты): пустое поле и пустой список. */
    fun reset() {
        generation++
        posts.clear(); viewed.clear()
        searched = false; loading = false; failed = false; hasMore = false
        activeQuery = ""; nextOffset = 0; total = -1
        focusPending = true
        input.setText("")
        count.visibility = View.GONE
        adapter.notifyDataSetChanged()
    }

    fun destroy() { generation++ }

    private fun hideKeyboard() {
        activity.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(input.windowToken, 0)
        input.clearFocus()
    }

    private fun onUi(r: () -> Unit) {
        main.post { if (!activity.isFinishing && !activity.isDestroyed) r() }
    }

    // ------------------------------------------------------------------ поиск

    private fun runSearch() {
        val q = input.text.toString().trim()
        if (q.isEmpty()) {
            Toast.makeText(activity, "Введите запрос", Toast.LENGTH_SHORT).show()
            return
        }
        hideKeyboard()
        generation++
        activeQuery = q
        searched = true
        loading = true; failed = false; hasMore = false
        nextOffset = 0; total = -1
        posts.clear()
        count.visibility = View.GONE
        adapter.notifyDataSetChanged()
        fetch(generation, 0)
    }

    private fun loadMore() {
        if (loading || !hasMore || posts.isEmpty()) return
        loading = true; failed = false
        adapter.notifyItemChanged(adapter.itemCount - 1)
        fetch(generation, nextOffset)
    }

    private fun retry() {
        if (loading) return
        loading = true; failed = false
        adapter.notifyItemChanged(adapter.itemCount - 1)
        fetch(generation, if (posts.isEmpty()) 0 else nextOffset)
    }

    private fun fetch(gen: Int, offset: Int) {
        val q = activeQuery
        api.io.execute {
            try {
                when (api.ensureUserId()) {
                    1 -> { onUi { if (gen == generation) { loading = false; onAuthLost() } }; return@execute }
                    2 -> throw IllegalStateException("session")
                }
                if (!repo.usersLoaded) loadUsersAsync()
                val page = repo.searchPosts(q, offset)
                onUi {
                    if (gen != generation) return@onUi
                    val known = posts.mapTo(HashSet()) { p -> p.id }
                    val fresh = page.posts.filter { p -> p.id !in known }
                    val start = posts.size
                    posts.addAll(fresh)
                    nextOffset = page.nextOffset
                    total = page.total
                    hasMore = page.posts.size >= PostsRepo.PAGE_SIZE && (page.total < 0 || page.nextOffset < page.total)
                    loading = false
                    if (page.total >= 0) { count.text = "Найдено постов: ${page.total}"; count.visibility = View.VISIBLE }
                    if (start == 0) adapter.notifyDataSetChanged()
                    else {
                        if (fresh.isNotEmpty()) adapter.notifyItemRangeInserted(start, fresh.size)
                        adapter.notifyItemChanged(adapter.itemCount - 1)
                    }
                }
            } catch (e: PostsRepo.AuthLostException) {
                onUi { if (gen == generation) { loading = false; onAuthLost() } }
            } catch (e: Exception) {
                Log.w(TAG, "Поиск «$q», offset=$offset", e)
                onUi {
                    if (gen != generation) return@onUi
                    loading = false; failed = true
                    adapter.notifyItemChanged(adapter.itemCount - 1)
                }
            }
        }
    }

    /** GET /api/users — аватарки авторов (в постах их нет); один раз за сессию. */
    private fun loadUsersAsync() {
        if (usersLoading) return
        usersLoading = true
        repo.pool.execute {
            val ok = try { repo.loadUsers() } catch (e: Exception) { false }
            onUi {
                usersLoading = false
                if (ok) adapter.notifyDataSetChanged()
            }
        }
    }

    // ------------------------------------------------------------------ PostHost

    override fun openPost(post: Post) = onOpenPost(post)
    override fun openProfile(userId: Long) = onOpenProfile(userId)
    override fun openCommunity(communityId: Long) = onOpenCommunity(communityId)
    override fun balanceChanged() = onBalanceChanged()
    override fun authLost() = onAuthLost()
    override fun removePost(post: Post) = dropPost(post.id)

    /** Список надо перечитать (закреп, скрытый пользователь) — повторяем текущий запрос. */
    override fun reloadList() {
        if (!searched || activeQuery.isEmpty()) return
        input.setText(activeQuery)
        runSearch()
    }

    fun dropPost(id: Long) {
        val i = posts.indexOfFirst { p -> p.id == id }
        if (i < 0) return
        posts.removeAt(i)
        adapter.notifyItemRemoved(i)
    }

    /** Вернулись со страницы поста: перерисовать карточки (баллы, опрос, число комментариев). */
    fun refreshCards() {
        if (posts.isNotEmpty()) adapter.notifyItemRangeChanged(0, posts.size)
    }

    // ------------------------------------------------------------------ адаптер

    private inner class Adapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private val POST = 1
        private val FOOTER = 2

        override fun getItemCount() = posts.size + 1
        override fun getItemViewType(position: Int) = if (position == itemCount - 1) FOOTER else POST

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val ctx: Context = parent.context
            return if (viewType == POST) {
                val card = PostCardView(ctx)
                card.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { setMargins(0, 0, 0, dp(ctx, 12)) }
                object : RecyclerView.ViewHolder(card) {}
            } else {
                val t = UiUtil.text(ctx, 14f, AeroUi.labelColor(ctx)).apply {
                    gravity = Gravity.CENTER
                    setPadding(dp(ctx, 12), dp(ctx, 16), dp(ctx, 12), dp(ctx, 16))
                }
                t.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                object : RecyclerView.ViewHolder(t) {}
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            if (holder.itemViewType == POST) {
                (holder.itemView as PostCardView).bind(posts[position], this@SearchController)
                return
            }
            (holder.itemView as TextView).apply {
                setOnClickListener(null)
                text = when {
                    failed -> "Не удалось выполнить поиск. Нажмите, чтобы повторить"
                    loading && posts.isEmpty() -> "Поиск..."
                    loading -> "Загрузка..."
                    !searched -> "Введите запрос и нажмите «Найти»"
                    posts.isEmpty() -> "Ничего не найдено"
                    !hasMore -> "Это все результаты"
                    else -> ""
                }
                if (failed) setOnClickListener { retry() }
            }
        }

        override fun onViewAttachedToWindow(holder: RecyclerView.ViewHolder) {
            if (holder.itemViewType != POST) return
            val id = posts.getOrNull(holder.bindingAdapterPosition)?.id ?: return
            if (id in viewed) return
            // Как trackPostView на сайте: «просмотр» засчитываем, когда пост побыл на экране.
            val r = Runnable { viewed.add(id); repo.pool.execute { repo.view(id) } }
            holder.itemView.setTag(R.id.feed_view_task, r)
            main.postDelayed(r, VIEW_DELAY_MS)
        }

        override fun onViewDetachedFromWindow(holder: RecyclerView.ViewHolder) {
            (holder.itemView.getTag(R.id.feed_view_task) as? Runnable)?.let { r -> main.removeCallbacks(r) }
            holder.itemView.setTag(R.id.feed_view_task, null)
            (holder.itemView as? PostCardView)?.release()
        }
    }
}
