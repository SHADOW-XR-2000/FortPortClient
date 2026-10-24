package ru.fortport.client

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.text.method.LinkMovementMethod
import android.text.util.Linkify
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
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
import ru.fortport.client.UiUtil.dp

/**
 * Нативная лента порта (концепция 5): блок информации (5.1), кнопка «Правила» с раскрывающимся
 * списком (5.2, только если правила заполнены) и посты порта (5.3).
 *
 * Запросы: GET /api/communities/{id}, POST /api/feed?communityId={id}, POST /api/communities/{id}/join|leave.
 * Подписчик — id в subscribers; «Редактировать» — владелец и модераторы; писать в порт могут
 * владелец, модераторы, а в «сообществе» (type=community) — все.
 */
class PortController(
    override val activity: AppCompatActivity,
    override val api: SiteApi,
    override val repo: PostsRepo,
    private val swipe: SwipeRefreshLayout,
    private val recycler: RecyclerView,
    private val bg: ImageView,
    private val onOpenPost: (Post) -> Unit,
    private val onOpenProfile: (Long) -> Unit,
    /** Путь страницы сайта (например «/community/settings?id=1»), открывается в WebView. */
    private val onOpenWeb: (String) -> Unit,
    /** Сведения о порте загружены (можно ли в него писать) — обновить кнопку «+». */
    private val onInfoLoaded: () -> Unit,
    private val onBalanceChanged: () -> Unit,
    private val onAuthLost: () -> Unit
) : PostHost {

    companion object {
        private const val TAG = "PortController"
        private const val VIEW_DELAY_MS = 2500L
        private const val BLUE = 0xFF2D9CDB.toInt()
    }

    private class Info(
        val id: Long, val name: String, val avatar: String, val type: String,
        val status: String, val description: String, val rules: String,
        val subscribers: Int, val createdAt: Long, val background: String,
        val subscribed: Boolean, val canEdit: Boolean, val canPost: Boolean
    )

    private val main = Handler(Looper.getMainLooper())
    private val posts = ArrayList<Post>()
    private var info: Info? = null
    private var notFound = false
    private var rulesOpen = false

    private var targetId = 0L
    private var targetSet = false

    private var started = false
    private var loadedOnce = false
    private var loading = false
    private var hasMore = true
    private var failed = false
    private var generation = 0
    private var usersLoading = false
    private val viewed = HashSet<Long>()

    /** Можно ли публиковать в этот порт (для кнопки «+»). */
    val canPost: Boolean get() = info?.canPost == true

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

    /** Какой порт показывать. true — порт сменился и состояние сброшено. */
    fun setCommunity(id: Long): Boolean {
        if (targetSet && id == targetId) return false
        targetSet = true
        targetId = id
        generation++
        posts.clear()
        info = null; notFound = false; rulesOpen = false
        loadedOnce = false; loading = false; hasMore = true; failed = false
        swipe.isRefreshing = false
        started = false
        bg.setImageBitmap(api.ownBackgroundBitmap())
        adapter.notifyDataSetChanged()
        recycler.scrollToPosition(0)
        return true
    }

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

    fun destroy() {
        generation++
    }

    // ------------------------------------------------------------------ загрузка

    private fun onUi(r: () -> Unit) {
        main.post { if (!activity.isDestroyed && !activity.isFinishing) r() }
    }

    private fun longs(a: JSONArray?): List<Long> {
        if (a == null) return emptyList()
        val l = ArrayList<Long>(a.length())
        for (i in 0 until a.length()) l.add(a.optLong(i, 0L))
        return l
    }

    private fun parseInfo(o: JSONObject, me: Long): Info {
        val subs = longs(o.optJSONArray("subscribers"))
        val mods = longs(o.optJSONArray("moderators"))
        val owner = o.optLong("owner", 0L)
        val type = api.str(o, "type")
        val isOwner = owner == me
        val isMod = mods.contains(me)
        return Info(
            id = o.optLong("id"),
            name = api.str(o, "username").ifBlank { "Без названия" },
            avatar = api.str(o, "profilePicture"),
            type = type,
            status = api.str(o, "status"),
            description = api.str(o, "description"),
            rules = api.str(o, "rules"),
            subscribers = o.optInt("subscriberCount", subs.size),
            createdAt = PostParser.parseIso(api.str(o, "createdAt")),
            background = api.str(o, "profileBackground"),
            subscribed = subs.contains(me),
            canEdit = isOwner || isMod,
            canPost = isOwner || isMod || type == "community"
        )
    }

    private fun reload() {
        val gen = ++generation
        loading = true
        failed = false
        hasMore = true
        if (!loadedOnce) swipe.isRefreshing = true
        val id = targetId
        api.io.execute {
            try {
                when (api.ensureUserId()) {
                    1 -> { onUi { loading = false; swipe.isRefreshing = false; onAuthLost() }; return@execute }
                    2 -> throw IllegalStateException("session")
                }
                val me = repo.me
                val cr = api.get("/api/communities/$id")
                if (cr.code == 401) throw PostsRepo.AuthLostException()
                if (cr.code == 404 || cr.code == 400) {
                    onUi {
                        if (gen != generation) return@onUi
                        notFound = true; loadedOnce = true; loading = false; swipe.isRefreshing = false
                        adapter.notifyDataSetChanged()
                    }
                    return@execute
                }
                if (cr.code != 200) throw IllegalStateException("community ${cr.code}")
                val newInfo = parseInfo(JSONObject(cr.body), me)
                val page = repo.loadPosts(FeedSource.Community(id), null)
                if (!repo.usersLoaded) loadUsersAsync()
                onUi {
                    if (gen != generation) return@onUi
                    info = newInfo
                    repo.putCommunity(CommunityLite(id, newInfo.name, newInfo.avatar, newInfo.type))
                    posts.clear(); posts.addAll(page)
                    hasMore = page.size >= PostsRepo.PAGE_SIZE
                    loadedOnce = true; loading = false; swipe.isRefreshing = false
                    adapter.notifyDataSetChanged()
                    onInfoLoaded()
                }
                // Фон страницы — у порта (как на сайте); нет своего — обычный фон приложения.
                if (newInfo.background.isNotBlank()) {
                    val bmp: Bitmap? = api.downloadBitmap(newInfo.background, 1600)
                    onUi { if (gen == generation && bmp != null) bg.setImageBitmap(bmp) }
                }
            } catch (e: PostsRepo.AuthLostException) {
                onUi { loading = false; swipe.isRefreshing = false; onAuthLost() }
            } catch (e: Exception) {
                Log.w(TAG, "Не удалось загрузить порт", e)
                onUi {
                    if (gen != generation) return@onUi
                    loading = false; swipe.isRefreshing = false; failed = true
                    adapter.notifyDataSetChanged()
                }
            }
        }
    }

    private fun loadMore() {
        if (loading || !hasMore || posts.isEmpty()) return
        val gen = generation
        val id = targetId
        loading = true
        failed = false
        adapter.notifyItemChanged(adapter.itemCount - 1)
        val cursor = posts.last().createdAt / 1000L
        api.io.execute {
            try {
                val page = repo.loadPosts(FeedSource.Community(id), cursor)
                onUi {
                    if (gen != generation) return@onUi
                    val known = posts.mapTo(HashSet()) { p -> p.id }
                    val fresh = page.filter { p -> p.id !in known }
                    val start = posts.size
                    posts.addAll(fresh)
                    hasMore = page.size >= PostsRepo.PAGE_SIZE
                    loading = false
                    if (fresh.isNotEmpty()) adapter.notifyItemRangeInserted(start + 1, fresh.size)
                    adapter.notifyItemChanged(adapter.itemCount - 1)
                }
            } catch (e: PostsRepo.AuthLostException) {
                onUi { loading = false; onAuthLost() }
            } catch (e: Exception) {
                Log.w(TAG, "Не удалось подгрузить посты порта", e)
                onUi {
                    if (gen != generation) return@onUi
                    loading = false; failed = true
                    adapter.notifyItemChanged(adapter.itemCount - 1)
                }
            }
        }
    }

    /** GET /api/users — аватарки авторов (в постах их нет); тяжёлый запрос, один раз за сессию. */
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
    override fun openCommunity(communityId: Long) {
        if (communityId == targetId) recycler.smoothScrollToPosition(0)
    }
    override fun balanceChanged() = onBalanceChanged()
    override fun authLost() = onAuthLost()
    override fun reloadList() = reload()
    override fun removePost(post: Post) = dropPost(post.id)

    fun dropPost(id: Long) {
        val i = posts.indexOfFirst { p -> p.id == id }
        if (i < 0) return
        posts.removeAt(i)
        adapter.notifyItemRemoved(i + 1)
    }

    /** Вернулись со страницы поста: перерисовать карточки (баллы, опрос, число комментариев). */
    fun refreshCards() {
        if (posts.isNotEmpty()) adapter.notifyItemRangeChanged(1, posts.size)
    }

    private fun toast(s: String) = Toast.makeText(activity, s, Toast.LENGTH_SHORT).show()

    // ------------------------------------------------------------------ подписка

    private fun subscribe(join: Boolean) {
        val id = targetId
        val gen = generation
        api.io.execute {
            var ok = false
            var err = ""
            var fresh: Info? = null
            try {
                val r = api.send("POST", "/api/communities/$id/${if (join) "join" else "leave"}", null)
                val o = try { JSONObject(r.body) } catch (e: Exception) { null }
                ok = r.code in 200..299 && (o == null || o.optBoolean("success", true))
                if (!ok) err = if (o != null) api.str(o, "error") else ""
                if (ok) {
                    val cr = api.get("/api/communities/$id")
                    if (cr.code == 200) fresh = parseInfo(JSONObject(cr.body), repo.me)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Подписка не выполнена", e)
            }
            val nf = fresh
            onUi {
                if (!ok) { toast("Ошибка: " + err.ifBlank { "не удалось выполнить действие" }); return@onUi }
                toast(if (join) "Вы присоединились к порту" else "Вы отключились от порта")
                if (nf != null && gen == generation) {
                    info = nf
                    adapter.notifyItemChanged(0)
                    onInfoLoaded()
                }
            }
        }
    }

    private fun confirmLeave() {
        AlertDialog.Builder(activity)
            .setMessage("Отключиться от этого порта?")
            .setPositiveButton(android.R.string.ok) { _, _ -> subscribe(false) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ------------------------------------------------------------------ шапка порта (5.1 / 5.2)

    private fun bindHeader(card: LinearLayout) {
        card.removeAllViews()
        val ctx = card.context
        val i = info
        if (i == null) {
            card.addView(
                UiUtil.text(ctx, 15f, UiUtil.SUB).apply {
                    gravity = Gravity.CENTER
                    setPadding(dp(ctx, 12), dp(ctx, 24), dp(ctx, 12), dp(ctx, 24))
                    text = when {
                        notFound -> "Порт не найден"
                        failed -> "Не удалось загрузить порт. Потяните вниз, чтобы повторить"
                        else -> "Загрузка..."
                    }
                },
                UiUtil.lp(UiUtil.MATCH, UiUtil.WRAP)
            )
            return
        }

        val head = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = UiUtil.royal(ctx)
            setPadding(dp(ctx, 10), dp(ctx, 10), dp(ctx, 10), dp(ctx, 10))
        }
        val avatar = ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = UiUtil.rect(Color.WHITE, Color.WHITE, 2, 4, ctx)
            setPadding(dp(ctx, 2), dp(ctx, 2), dp(ctx, 2), dp(ctx, 2))
            clipToOutline = true
        }
        head.addView(avatar, UiUtil.lp(dp(ctx, 88), dp(ctx, 88)))
        api.loadAvatar(avatar, i.avatar)
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        head.addView(col, UiUtil.lp(0, UiUtil.WRAP, 1f).apply { marginStart = dp(ctx, 12) })
        col.addView(UiUtil.text(ctx, 20f, Color.WHITE, bold = true).apply {
            text = i.name; setShadowLayer(2f, 0f, 1f, 0x99000000.toInt())
        })
        col.addView(UiUtil.text(ctx, 15f, Color.WHITE).apply {
            text = "Подписчики: ${i.subscribers}"; setShadowLayer(2f, 0f, 1f, 0x99000000.toInt())
            setPadding(0, dp(ctx, 6), 0, 0)
        })
        card.addView(head, UiUtil.lp(UiUtil.MATCH, UiUtil.WRAP))

        val body = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 12), dp(ctx, 8), dp(ctx, 12), dp(ctx, 10))
        }
        card.addView(body, UiUtil.lp(UiUtil.MATCH, UiUtil.WRAP))

        fun line(s: String, bold: Boolean = false) {
            body.addView(UiUtil.text(ctx, 14f, UiUtil.TEXT, bold = bold).apply {
                text = s; setPadding(0, dp(ctx, 2), 0, dp(ctx, 2))
            })
        }
        if (i.status.isNotBlank()) line(i.status, true)
        if (i.createdAt > 0) {
            val d = java.text.SimpleDateFormat("dd.MM.yyyy", java.util.Locale.getDefault()).format(java.util.Date(i.createdAt))
            line("Дата создания: $d")
        }
        line(if (i.type == "page") "Страница" else "Сообщество")
        if (i.description.isNotBlank()) {
            body.addView(UiUtil.text(ctx, 15f, UiUtil.TEXT).apply {
                text = i.description.replace("\r\n", "\n")
                setPadding(0, dp(ctx, 8), 0, dp(ctx, 4))
                Linkify.addLinks(this, Linkify.WEB_URLS)
                movementMethod = LinkMovementMethod.getInstance()
            })
        }

        // Действие: редактировать / отключиться / присоединиться (как на сайте).
        val action = UiUtil.text(ctx, 14f, if (i.subscribed && !i.canEdit) UiUtil.TEXT else Color.WHITE).apply {
            gravity = Gravity.CENTER
            text = when {
                i.canEdit -> "Редактировать"
                i.subscribed -> "Отключиться"
                else -> "Присоединиться"
            }
            background = if (i.subscribed && !i.canEdit) UiUtil.rect(Color.WHITE, UiUtil.BORDER, 2, 6, ctx) else UiUtil.royal(ctx)
            setPadding(dp(ctx, 8), dp(ctx, 10), dp(ctx, 8), dp(ctx, 10))
            setOnClickListener {
                when {
                    i.canEdit -> onOpenWeb("/community/settings?id=${i.id}")
                    i.subscribed -> confirmLeave()
                    else -> subscribe(true)
                }
            }
        }
        body.addView(action, UiUtil.lp(UiUtil.MATCH, UiUtil.WRAP).apply { topMargin = dp(ctx, 8) })

        // «Правила» — только если они заполнены.
        val rules = i.rules.split("\n").map { s -> s.trim() }.filter { s -> s.isNotEmpty() }
        if (rules.isEmpty()) return

        val openBtn = UiUtil.text(ctx, 14f, BLUE).apply {
            text = "Правила"; gravity = Gravity.CENTER
            background = UiUtil.rect(Color.WHITE, BLUE, 2, 4, ctx)
            setPadding(dp(ctx, 8), dp(ctx, 10), dp(ctx, 8), dp(ctx, 10))
        }
        val block = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = UiUtil.rect(Color.WHITE, UiUtil.BORDER, 2, 4, ctx)
            setPadding(dp(ctx, 12), dp(ctx, 8), dp(ctx, 12), dp(ctx, 12))
        }
        rules.forEach { r ->
            block.addView(UiUtil.text(ctx, 15f, UiUtil.TEXT).apply {
                text = r; setPadding(0, dp(ctx, 4), 0, dp(ctx, 4))
            })
        }
        val hideBtn = UiUtil.text(ctx, 14f, UiUtil.TEXT).apply {
            text = "Скрыть"; gravity = Gravity.CENTER
            background = UiUtil.rect(Color.WHITE, UiUtil.BORDER, 2, 4, ctx)
            setPadding(dp(ctx, 8), dp(ctx, 6), dp(ctx, 8), dp(ctx, 6))
        }
        openBtn.setOnClickListener {
            rulesOpen = true
            openBtn.visibility = View.GONE
            hideBtn.visibility = View.VISIBLE
            animate(block, true)
        }
        hideBtn.setOnClickListener {
            rulesOpen = false
            hideBtn.visibility = View.GONE
            openBtn.visibility = View.VISIBLE
            animate(block, false)
        }
        block.visibility = if (rulesOpen) View.VISIBLE else View.GONE
        openBtn.visibility = if (rulesOpen) View.GONE else View.VISIBLE
        hideBtn.visibility = if (rulesOpen) View.VISIBLE else View.GONE
        body.addView(openBtn, UiUtil.lp(UiUtil.MATCH, UiUtil.WRAP).apply { topMargin = dp(ctx, 10) })
        body.addView(block, UiUtil.lp(UiUtil.MATCH, UiUtil.WRAP).apply { topMargin = dp(ctx, 10) })
        body.addView(hideBtn, LinearLayout.LayoutParams(dp(ctx, 160), UiUtil.WRAP).apply {
            topMargin = dp(ctx, 6); gravity = Gravity.CENTER_HORIZONTAL
        })
    }

    /** Эффект слайдера: высота блока плавно меняется от 0 до полной (и обратно). */
    private fun animate(block: View, open: Boolean) {
        val parent = block.parent as? View
        val w = if (parent != null) parent.width - parent.paddingLeft - parent.paddingRight else 0
        if (w <= 0) {
            block.visibility = if (open) View.VISIBLE else View.GONE
            return
        }
        block.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val full = block.measuredHeight
        val from = if (open) 0 else block.height
        val to = if (open) full else 0
        block.layoutParams.height = from
        block.visibility = View.VISIBLE
        block.requestLayout()
        ValueAnimator.ofInt(from, to).apply {
            duration = 250
            addUpdateListener { a ->
                block.layoutParams.height = a.animatedValue as Int
                block.requestLayout()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    block.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
                    if (!open) block.visibility = View.GONE
                    block.requestLayout()
                }
            })
            start()
        }
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
                    val card = LinearLayout(ctx).apply {
                        orientation = LinearLayout.VERTICAL
                        background = UiUtil.rect(Color.WHITE, UiUtil.BORDER, 2, 6, ctx)
                        setPadding(dp(ctx, 2), dp(ctx, 2), dp(ctx, 2), dp(ctx, 2))
                    }
                    margins(card, 12)
                    object : RecyclerView.ViewHolder(card) {}
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
                HEADER -> bindHeader(holder.itemView as LinearLayout)
                POST -> (holder.itemView as PostCardView).bind(posts[position - 1], this@PortController)
                FOOTER -> (holder.itemView as TextView).apply {
                    setOnClickListener(null)
                    text = when {
                        info == null -> ""
                        failed -> "Не удалось загрузить. Нажмите, чтобы повторить"
                        loading && loadedOnce -> "Загрузка..."
                        posts.isEmpty() && loadedOnce -> "В порте пока нет постов"
                        !hasMore -> "Это все посты"
                        else -> ""
                    }
                    if (failed && info != null) setOnClickListener { loadMore() }
                }
            }
        }

        override fun onViewAttachedToWindow(holder: RecyclerView.ViewHolder) {
            if (holder.itemViewType != POST) return
            val pos = holder.bindingAdapterPosition
            val id = posts.getOrNull(pos - 1)?.id ?: return
            if (id in viewed) return
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
