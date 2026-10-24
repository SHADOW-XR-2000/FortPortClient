package ru.fortport.client

import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import ru.fortport.client.UiUtil.dp

/**
 * Нативная основная лента (концепции 1 и 2): две неизменные панели приложения сверху и снизу,
 * между ними — строка 1.1 («поиск» и «настройка ленты») и посты один за другим.
 * Посты берутся из POST /api/feed (вкладки — из GET /api/users/feedlist).
 *
 * Состояние (список, позиция прокрутки) живёт, пока жив контроллер: уход в открытый пост
 * и возврат не сбрасывают ленту — start() повторно ничего не грузит.
 */
class FeedController(
    override val activity: AppCompatActivity,
    override val api: SiteApi,
    private val swipe: SwipeRefreshLayout,
    private val recycler: RecyclerView,
    private val onOpenPost: (Post) -> Unit,
    private val onOpenProfile: (Long) -> Unit,
    private val onOpenCommunity: (Long) -> Unit,
    private val onBalanceChanged: () -> Unit,
    private val onAuthLost: () -> Unit
) : PostHost {

    companion object {
        private const val TAG = "FeedController"
        private const val PREFS = "feed_ui"
        private const val KEY_TAB = "tab_name"
        private const val VIEW_DELAY_MS = 2500L
    }

    override val repo = PostsRepo(api)

    private val ui = Handler(Looper.getMainLooper())
    private val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val posts = ArrayList<Post>()
    private var tabs: List<FeedTab> = emptyList()
    private var activeTab: FeedTab = repo.defaultTab()

    private var started = false
    private var loadedOnce = false
    private var loading = false
    private var hasMore = true
    private var failed = false
    private var generation = 0
    private var usersLoading = false
    private val viewed = HashSet<Long>()

    private val adapter = Adapter()

    init {
        val lm = LinearLayoutManager(activity)
        recycler.layoutManager = lm
        recycler.adapter = adapter
        recycler.itemAnimator = null
        recycler.setItemViewCacheSize(4)
        val d = activity.resources.displayMetrics.density
        swipe.setProgressViewOffset(false, (70 * d).toInt(), (130 * d).toInt())
        swipe.setColorSchemeResources(R.color.fp_accent)
        swipe.setDistanceToTriggerSync((32 * d).toInt())
        swipe.setOnRefreshListener { reload() }
        recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0 || loading || !hasMore || !loadedOnce) return
                if (lm.findLastVisibleItemPosition() >= adapter.itemCount - 4) loadMore()
            }
        })
    }

    // ------------------------------------------------------------------ жизненный цикл

    fun start(userId: String, deviceId: String, userAgent: String) {
        api.setSession(userId, deviceId, userAgent)
        if (started) return
        started = true
        if (!loadedOnce && !loading) reload()
    }

    fun stop() {
        started = false
        swipe.isRefreshing = false
        InlineAudio.stop()
    }

    fun reset() {}

    fun destroy() {
        generation++
        repo.destroy()
    }

    // ------------------------------------------------------------------ загрузка

    /** Первая страница заново (обновление свайпом, смена вкладки, закреп/скрытие). */
    private fun reload() {
        val gen = ++generation
        loading = true
        failed = false
        hasMore = true
        if (!loadedOnce) swipe.isRefreshing = true
        api.io.execute {
            try {
                when (api.ensureUserId()) {
                    1 -> { post { loading = false; swipe.isRefreshing = false; onAuthLost() }; return@execute }
                    2 -> throw IllegalStateException("session")
                }
                if (tabs.isEmpty()) {
                    val t = repo.loadFeedTabs()
                    if (t != null) {
                        tabs = t
                        val saved = prefs.getString(KEY_TAB, null)
                        activeTab = t.firstOrNull { it.name == saved } ?: t.firstOrNull() ?: repo.defaultTab()
                    }
                }
                if (repo.users.isEmpty()) loadUsersAsync()
                val page = repo.loadPosts(FeedSource.Tab(activeTab), null)
                post {
                    if (gen != generation) return@post
                    posts.clear(); posts.addAll(page)
                    hasMore = page.size >= PostsRepo.PAGE_SIZE
                    loadedOnce = true; loading = false; swipe.isRefreshing = false
                    adapter.notifyDataSetChanged()
                    recycler.scrollToPosition(0)
                }
            } catch (e: PostsRepo.AuthLostException) {
                post { loading = false; swipe.isRefreshing = false; onAuthLost() }
            } catch (e: Exception) {
                Log.w(TAG, "Не удалось загрузить ленту", e)
                post {
                    if (gen != generation) return@post
                    loading = false; swipe.isRefreshing = false; failed = true
                    adapter.notifyDataSetChanged()
                }
            }
        }
    }

    private fun loadMore() {
        if (loading || !hasMore || posts.isEmpty()) return
        val gen = generation
        loading = true
        failed = false
        adapter.notifyItemChanged(adapter.itemCount - 1)
        val cursor: Long = if (activeTab.sortType == "new") {
            posts.last().createdAt / 1000L
        } else posts.size.toLong()
        api.io.execute {
            try {
                val page = repo.loadPosts(FeedSource.Tab(activeTab), cursor)
                post {
                    if (gen != generation) return@post
                    val known = posts.mapTo(HashSet()) { it.id }
                    val fresh = page.filter { it.id !in known }
                    val start = posts.size
                    posts.addAll(fresh)
                    hasMore = page.size >= PostsRepo.PAGE_SIZE
                    loading = false
                    if (fresh.isNotEmpty()) adapter.notifyItemRangeInserted(start + 1, fresh.size)
                    adapter.notifyItemChanged(adapter.itemCount - 1)
                }
            } catch (e: PostsRepo.AuthLostException) {
                post { loading = false; onAuthLost() }
            } catch (e: Exception) {
                Log.w(TAG, "Не удалось подгрузить посты", e)
                post {
                    if (gen != generation) return@post
                    loading = false; failed = true
                    adapter.notifyItemChanged(adapter.itemCount - 1)
                }
            }
        }
    }

    /** GET /api/users — тяжёлый, поэтому в отдельном потоке; после загрузки аватарки подставятся при перерисовке. */
    private fun loadUsersAsync() {
        if (usersLoading) return
        usersLoading = true
        repo.pool.execute {
            val ok = try { repo.loadUsers() } catch (e: Exception) { false }
            post {
                usersLoading = false
                if (ok) adapter.notifyDataSetChanged()
            }
        }
    }

    private fun post(r: () -> Unit) {
        ui.post { if (!activity.isDestroyed && !activity.isFinishing) r() }
    }

    // ------------------------------------------------------------------ PostHost

    override fun openPost(post: Post) = onOpenPost(post)
    override fun openProfile(userId: Long) = onOpenProfile(userId)
    override fun openCommunity(communityId: Long) = onOpenCommunity(communityId)
    override fun balanceChanged() = onBalanceChanged()
    override fun authLost() = onAuthLost()
    override fun reloadList() = reload()

    override fun removePost(post: Post) {
        val i = posts.indexOfFirst { it.id == post.id }
        if (i < 0) return
        posts.removeAt(i)
        adapter.notifyItemRemoved(i + 1)
    }

    // ------------------------------------------------------------------ вкладки (шестерёнка в 1.1)

    private fun showTabsDialog() {
        if (tabs.isEmpty()) {
            Toast.makeText(activity, "Список лент ещё не загружен", Toast.LENGTH_SHORT).show()
            return
        }
        val names = tabs.map { it.name.ifBlank { "Лента" } }.toTypedArray()
        val cur = tabs.indexOfFirst { it.name == activeTab.name }.coerceAtLeast(0)
        AlertDialog.Builder(activity)
            .setTitle("Настройка ленты")
            .setSingleChoiceItems(names, cur) { d, which ->
                d.dismiss()
                val t = tabs[which]
                if (t.name != activeTab.name) {
                    activeTab = t
                    prefs.edit().putString(KEY_TAB, t.name).apply()
                    loadedOnce = false
                    posts.clear(); adapter.notifyDataSetChanged()
                    reload()
                }
            }
            .setNegativeButton("Закрыть", null)
            .show()
    }

    private fun showSearchStub() {
        Toast.makeText(activity, "Поиск по ленте появится позже", Toast.LENGTH_SHORT).show()
    }

    // ------------------------------------------------------------------ адаптер

    private inner class Adapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private val HEADER = 0
        private val POST = 1
        private val FOOTER = 2

        override fun getItemCount() = posts.size + 2
        override fun getItemViewType(position: Int) = when {
            position == 0 -> HEADER
            position == itemCount - 1 -> FOOTER
            else -> POST
        }

        private fun margins(v: View, bottomDp: Int) {
            v.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(0, 0, 0, dp(activity, bottomDp)) }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val ctx = parent.context
            return when (viewType) {
                HEADER -> {
                    val row = buildHeaderRow(ctx)
                    margins(row, 12)
                    object : RecyclerView.ViewHolder(row) {}
                }
                POST -> {
                    val card = PostCardView(ctx)
                    margins(card, 12)
                    object : RecyclerView.ViewHolder(card) {}
                }
                else -> {
                    val t = UiUtil.text(ctx, 14f, UiUtil.SUB).apply {
                        gravity = Gravity.CENTER
                        setPadding(dp(ctx, 12), dp(ctx, 16), dp(ctx, 12), dp(ctx, 16))
                    }
                    t.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    object : RecyclerView.ViewHolder(t) {}
                }
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (holder.itemViewType) {
                POST -> (holder.itemView as PostCardView).bind(posts[position - 1], this@FeedController)
                FOOTER -> (holder.itemView as TextView).apply {
                    setOnClickListener(null)
                    text = when {
                        failed -> "Не удалось загрузить. Нажмите, чтобы повторить"
                        loading && loadedOnce -> "Загрузка..."
                        !loadedOnce && loading -> ""
                        posts.isEmpty() && loadedOnce -> "В этой ленте пока нет постов"
                        !hasMore -> "Это все посты"
                        else -> ""
                    }
                    if (failed) setOnClickListener { if (loadedOnce) loadMore() else reload() }
                }
            }
        }

        override fun onViewAttachedToWindow(holder: RecyclerView.ViewHolder) {
            if (holder.itemViewType != POST) return
            val pos = holder.bindingAdapterPosition
            val id = posts.getOrNull(pos - 1)?.id ?: return
            if (id in viewed) return
            // Как trackPostView на сайте: «просмотр» засчитываем, когда пост побыл на экране.
            val r = Runnable { viewed.add(id); repo.pool.execute { repo.view(id) } }
            holder.itemView.setTag(R.id.feed_view_task, r)
            ui.postDelayed(r, VIEW_DELAY_MS)
        }

        override fun onViewDetachedFromWindow(holder: RecyclerView.ViewHolder) {
            (holder.itemView.getTag(R.id.feed_view_task) as? Runnable)?.let { ui.removeCallbacks(it) }
            holder.itemView.setTag(R.id.feed_view_task, null)
            (holder.itemView as? PostCardView)?.release()
        }
    }

    /** Строка 1.1: фон royal, слева «поиск», справа «настройка ленты». */
    private fun buildHeaderRow(ctx: Context): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = UiUtil.royal(ctx)
            setPadding(dp(ctx, 10), dp(ctx, 8), dp(ctx, 10), dp(ctx, 8))
        }
        fun btn(glyph: String, click: () -> Unit) = TextView(ctx).apply {
            text = glyph; textSize = 22f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
            background = UiUtil.rect(0x33FFFFFF, Color.WHITE, 2, 8, ctx)
            setOnClickListener { click() }
        }
        row.addView(btn("🔍") { showSearchStub() }, LinearLayout.LayoutParams(dp(ctx, 44), dp(ctx, 44)))
        row.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
        row.addView(btn("⚙") { showTabsDialog() }, LinearLayout.LayoutParams(dp(ctx, 44), dp(ctx, 44)))
        return row
    }
}
