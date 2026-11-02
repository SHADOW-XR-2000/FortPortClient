package ru.fortport.client

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
 * Нативная лента пользователя (концепция 4): блок информации (4.1), кнопка «Подробнее» с раскрывающимся
 * списком (4.2) и посты пользователя (4.3). Закреплённый пост идёт первым.
 *
 * Запросы: GET /api/users/{id}, GET /api/users/bio/{id}, GET /api/users/{id}/pinned-post,
 * POST /api/feed?userId={id}, а для чужого профиля — состояние дружбы по полям friends/pending
 * (как на сайте), действия POST /api/friends/{request|accept|reject|remove|cancel}, POST /api/chats/create.
 */
class ProfileController(
    override val activity: AppCompatActivity,
    override val api: SiteApi,
    override val repo: PostsRepo,
    private val swipe: SwipeRefreshLayout,
    private val recycler: RecyclerView,
    private val bg: ImageView,
    private val onOpenPost: (Post) -> Unit,
    private val onOpenProfile: (Long) -> Unit,
    private val onOpenCommunity: (Long) -> Unit,
    private val onOpenMusic: (Long) -> Unit,
    private val onOpenFriends: (Long) -> Unit,
    private val onOpenPorts: (Long) -> Unit,
    /** Путь страницы сайта (например «/settings»), открывается в WebView. */
    private val onOpenWeb: (String) -> Unit,
    private val onOpenChat: (String) -> Unit,
    private val onBalanceChanged: () -> Unit,
    private val onAuthLost: () -> Unit
) : PostHost {

    companion object {
        private const val TAG = "ProfileController"
        private const val VIEW_DELAY_MS = 2500L
        private val CHAT_ID_RE = Regex("^[0-9_]+$")
        private const val LABEL = 0xFF575757.toInt()
        private const val BLUE = 0xFF2D9CDB.toInt()
    }

    private class Info(
        val id: Long, val name: String, val avatar: String, val status: String,
        val role: UserRole
    )

    private class Bio(
        val city: String, val birth: String, val education: String, val work: String,
        val religion: String, val hobbies: String, val fandoms: String,
        val description: String, val status: String, val background: String
    )

    private enum class Rel { NONE, FRIEND, INCOMING, OUTGOING }

    private val main = Handler(Looper.getMainLooper())
    private val posts = ArrayList<Post>()
    private var info: Info? = null
    private var bio: Bio? = null
    private var rel = Rel.NONE
    private var isOwn = false
    private var notFound = false
    private var moreOpen = false

    /** 0 — свой профиль. */
    private var targetId = 0L
    private var targetSet = false
    private var profileId = 0L

    private var started = false
    private var loadedOnce = false
    private var loading = false
    private var hasMore = true
    private var failed = false
    private var generation = 0
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

    /** Какой профиль показывать (0 — свой). true — профиль сменился и состояние сброшено. */
    fun setUser(id: Long): Boolean {
        if (targetSet && id == targetId) return false
        targetSet = true
        targetId = id
        generation++
        posts.clear()
        info = null; bio = null; rel = Rel.NONE; notFound = false; moreOpen = false
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
        for (i in 0 until a.length()) {
            val v = a.optLong(i, 0L)
            if (v != 0L) l.add(v)
        }
        return l
    }

    private fun parseInfo(o: JSONObject) = Info(
        id = o.optLong("id"),
        name = api.str(o, "username"),
        avatar = api.str(o, "profilePicture"),
        status = api.str(o, "status"),
        role = UserRole(
            o.optBoolean("isOwner"), o.optBoolean("isDeveloper"), o.optBoolean("isModerator"),
            api.str(o, "displayRole")
        )
    )

    private fun parseBio(o: JSONObject) = Bio(
        city = api.str(o, "homeCountry"),
        birth = formatBirth(api.str(o, "dateOfBirth")),
        education = api.str(o, "education"),
        work = api.str(o, "workplace"),
        religion = api.str(o, "religion"),
        hobbies = api.str(o, "hobbies"),
        fandoms = api.str(o, "fandoms"),
        description = api.str(o, "description"),
        status = api.str(o, "status"),
        background = api.str(o, "profileBackground")
    )

    /** «2077-09-16» → «16.09.2077»; всё остальное показываем как есть. */
    private fun formatBirth(s: String): String {
        val p = s.trim().take(10).split("-")
        return if (p.size == 3 && p[0].length == 4) "${p[2]}.${p[1]}.${p[0]}" else s.trim()
    }

    /** Как на сайте: друг — id в friends у меня; входящая — id в pending у меня; исходящая — мой id в pending у него. Только из io-потока. */
    private fun fetchRelation(uid: Long, profile: JSONObject?): Rel {
        val me = repo.me
        if (uid == me || me == 0L) return Rel.NONE
        val meR = api.get("/api/users/$me")
        if (meR.code != 200) return Rel.NONE
        val meO = JSONObject(meR.body)
        val prof = profile ?: api.get("/api/users/$uid").takeIf { it.code == 200 }?.let { JSONObject(it.body) }
        return when {
            longs(meO.optJSONArray("friends")).contains(uid) -> Rel.FRIEND
            longs(meO.optJSONArray("pending")).contains(uid) -> Rel.INCOMING
            prof != null && longs(prof.optJSONArray("pending")).contains(me) -> Rel.OUTGOING
            else -> Rel.NONE
        }
    }

    private fun reload() {
        val gen = ++generation
        loading = true
        failed = false
        hasMore = true
        if (!loadedOnce) swipe.isRefreshing = true
        val wanted = targetId
        api.io.execute {
            try {
                when (api.ensureUserId()) {
                    1 -> { onUi { loading = false; swipe.isRefreshing = false; onAuthLost() }; return@execute }
                    2 -> throw IllegalStateException("session")
                }
                val me = repo.me
                val uid = if (wanted == 0L) me else wanted
                val ur = api.get("/api/users/$uid")
                if (ur.code == 401) throw PostsRepo.AuthLostException()
                if (ur.code == 404) {
                    onUi {
                        if (gen != generation) return@onUi
                        notFound = true; loadedOnce = true; loading = false; swipe.isRefreshing = false
                        adapter.notifyDataSetChanged()
                    }
                    return@execute
                }
                if (ur.code != 200) throw IllegalStateException("user ${ur.code}")
                val uo = JSONObject(ur.body)
                val newInfo = parseInfo(uo)
                val newBio = try {
                    val br = api.get("/api/users/bio/$uid")
                    if (br.code == 200) parseBio(JSONObject(br.body)) else null
                } catch (e: Exception) { null }
                val newRel = try { fetchRelation(uid, uo) } catch (e: Exception) { Rel.NONE }
                val pinned = try { repo.loadPinned(uid) } catch (e: PostsRepo.AuthLostException) { throw e } catch (e: Exception) { null }
                val page = repo.loadPosts(FeedSource.User(uid), null)
                onUi {
                    if (gen != generation) return@onUi
                    info = newInfo; bio = newBio; rel = newRel
                    profileId = uid
                    isOwn = uid == me
                    repo.putUser(UserLite(uid, newInfo.name, newInfo.avatar, newInfo.role))
                    posts.clear()
                    if (pinned != null) posts.add(pinned)
                    val pinnedId = pinned?.id
                    page.filterTo(posts) { p -> p.id != pinnedId }
                    hasMore = page.size >= PostsRepo.PAGE_SIZE
                    loadedOnce = true; loading = false; swipe.isRefreshing = false
                    adapter.notifyDataSetChanged()
                }
                // Фон страницы — у хозяина профиля (как на сайте); у себя фон уже установлен.
                val bgPath = newBio?.background.orEmpty()
                if (uid != me && bgPath.isNotBlank()) {
                    val bmp: Bitmap? = api.downloadBitmap(bgPath, 1600)
                    onUi { if (gen == generation && bmp != null) bg.setImageBitmap(bmp) }
                } else if (uid != me) {
                    onUi { if (gen == generation) bg.setImageBitmap(api.defaultBackgroundBitmap()) }
                }
            } catch (e: PostsRepo.AuthLostException) {
                onUi { loading = false; swipe.isRefreshing = false; onAuthLost() }
            } catch (e: Exception) {
                Log.w(TAG, "Не удалось загрузить профиль", e)
                onUi {
                    if (gen != generation) return@onUi
                    loading = false; swipe.isRefreshing = false; failed = true
                    adapter.notifyDataSetChanged()
                }
            }
        }
    }

    private fun loadMore() {
        if (loading || !hasMore || posts.isEmpty() || profileId == 0L) return
        val gen = generation
        val uid = profileId
        loading = true
        failed = false
        adapter.notifyItemChanged(adapter.itemCount - 1)
        val cursor = posts.last().createdAt / 1000L
        api.io.execute {
            try {
                val page = repo.loadPosts(FeedSource.User(uid), cursor)
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
                Log.w(TAG, "Не удалось подгрузить посты", e)
                onUi {
                    if (gen != generation) return@onUi
                    loading = false; failed = true
                    adapter.notifyItemChanged(adapter.itemCount - 1)
                }
            }
        }
    }

    // ------------------------------------------------------------------ PostHost

    override fun openPost(post: Post) = onOpenPost(post)
    override fun openProfile(userId: Long) { if (userId != profileId) onOpenProfile(userId) }
    override fun openCommunity(communityId: Long) = onOpenCommunity(communityId)
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

    // ------------------------------------------------------------------ друзья и чат

    private fun friendAction(path: String, body: JSONObject, doneMsg: String) {
        val uid = profileId
        val gen = generation
        api.io.execute {
            var ok = false
            var err = ""
            try {
                val r = api.send("POST", path, body.toString())
                val o = try { JSONObject(r.body) } catch (e: Exception) { null }
                ok = r.code in 200..299 && (o == null || o.optBoolean("success", true))
                if (!ok) err = if (o != null) api.str(o, "error") else ""
            } catch (e: Exception) {
                Log.w(TAG, "Действие не выполнено: $path", e)
            }
            var newRel: Rel? = null
            if (ok) {
                newRel = try { fetchRelation(uid, null) } catch (e: Exception) { null }
            }
            val nr = newRel
            onUi {
                if (ok) toast(doneMsg) else toast(err.ifBlank { "Не удалось выполнить действие" })
                if (nr != null && gen == generation) {
                    rel = nr
                    adapter.notifyItemChanged(0)
                }
            }
        }
    }

    private fun openChat() {
        val i = info ?: return
        api.io.execute {
            var payload: String? = null
            var err = ""
            try {
                val r = api.send("POST", "/api/chats/create", JSONObject().put("userId", i.id.toString()).toString())
                val o = try { JSONObject(r.body) } catch (e: Exception) { null }
                val chatId = if (o != null) api.str(o, "chatId") else ""
                if (r.code in 200..299 && CHAT_ID_RE.matches(chatId)) {
                    val chat = o?.optJSONObject("chat")
                    payload = JSONObject()
                        .put("chatId", chatId)
                        .put("name", chat?.let { c -> api.str(c, "username") }?.ifBlank { null } ?: i.name)
                        .put("avatar", chat?.let { c -> api.str(c, "profilePicture") }?.ifBlank { null } ?: i.avatar)
                        .put("status", chat?.let { c -> api.str(c, "status") } ?: i.status)
                        .put("myId", api.userId)
                        .put("deviceId", api.deviceId)
                        .put("ua", api.userAgent)
                        .toString()
                } else if (o != null) err = api.str(o, "error")
            } catch (e: Exception) {
                Log.w(TAG, "Не удалось открыть диалог", e)
            }
            onUi {
                val p = payload
                if (p != null) onOpenChat(p) else toast(err.ifBlank { "Не удалось открыть диалог" })
            }
        }
    }

    private fun confirmRemoveFriend() {
        val i = info ?: return
        AlertDialog.Builder(activity)
            .setMessage("Удалить из друзей: ${i.name}?")
            .setPositiveButton("Удалить") { _, _ ->
                friendAction("/api/friends/remove", JSONObject().put("friendId", i.id), "Пользователь удалён из друзей")
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ------------------------------------------------------------------ шапка профиля (4.1 / 4.2)

    private fun asset(path: String): Bitmap? = try {
        activity.assets.open(path).use { s -> BitmapFactory.decodeStream(s) }
    } catch (e: Exception) { null }

    private fun button(ctx: android.content.Context, label: String, primary: Boolean, click: () -> Unit) =
        TextView(ctx).apply {
            text = label
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(if (primary) Color.WHITE else UiUtil.TEXT)
            background = if (primary) UiUtil.royal(ctx) else UiUtil.rect(Color.WHITE, UiUtil.BORDER, 2, 6, ctx)
            setPadding(dp(ctx, 8), dp(ctx, 10), dp(ctx, 8), dp(ctx, 10))
            setOnClickListener { click() }
        }

    private fun iconLine(ctx: android.content.Context, iconPath: String, label: String): View =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(ctx, 4), 0, dp(ctx, 4))
            addView(ImageView(ctx).apply { setImageBitmap(asset(iconPath)) }, UiUtil.lp(dp(ctx, 26), dp(ctx, 26)))
            addView(
                UiUtil.text(ctx, 16f, UiUtil.TEXT, bold = true).apply { text = label },
                UiUtil.lp(0, UiUtil.WRAP, 1f).apply { marginStart = dp(ctx, 8) }
            )
        }

    private fun bindHeader(card: LinearLayout) {
        card.removeAllViews()
        val ctx = card.context
        val i = info
        if (i == null) {
            val t = UiUtil.text(ctx, 15f, UiUtil.SUB).apply {
                gravity = Gravity.CENTER
                setPadding(dp(ctx, 12), dp(ctx, 24), dp(ctx, 12), dp(ctx, 24))
                text = when {
                    notFound -> "Пользователь не найден"
                    failed -> "Не удалось загрузить профиль. Потяните вниз, чтобы повторить"
                    else -> "Загрузка..."
                }
            }
            card.addView(t, UiUtil.lp(UiUtil.MATCH, UiUtil.WRAP))
            return
        }
        val b = bio

        // Первичный блок: аватарка, имя, статус (royal).
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
        if (i.role.badge.isNotBlank()) {
            col.addView(UiUtil.text(ctx, 12f, 0xFFFFD866.toInt(), bold = true).apply { text = i.role.badge })
        }
        val status = i.status.ifBlank { b?.status.orEmpty() }
        if (status.isNotBlank()) {
            col.addView(UiUtil.text(ctx, 14f, Color.WHITE).apply {
                text = status; setShadowLayer(2f, 0f, 1f, 0x99000000.toInt())
                setPadding(0, dp(ctx, 4), 0, 0)
            })
        }
        card.addView(head, UiUtil.lp(UiUtil.MATCH, UiUtil.WRAP))

        // Остальное — на белом.
        val body = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 12), dp(ctx, 8), dp(ctx, 12), dp(ctx, 10))
        }
        card.addView(body, UiUtil.lp(UiUtil.MATCH, UiUtil.WRAP))

        if (!b?.city.isNullOrBlank()) body.addView(iconLine(ctx, "ui/icons/mini_map.webp", b!!.city))
        if (!b?.birth.isNullOrBlank()) body.addView(iconLine(ctx, "ui/icons/mini_cake.webp", b!!.birth))
        if (!b?.description.isNullOrBlank()) {
            body.addView(UiUtil.text(ctx, 15f, UiUtil.TEXT).apply {
                text = b!!.description.replace("\r\n", "\n")
                setPadding(0, dp(ctx, 8), 0, dp(ctx, 4))
                Linkify.addLinks(this, Linkify.WEB_URLS)
                movementMethod = LinkMovementMethod.getInstance()
            })
        }

        // Кнопки действий.
        val actions = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        fun add(label: String, primary: Boolean, click: () -> Unit) {
            actions.addView(
                button(ctx, label, primary, click),
                UiUtil.lp(0, UiUtil.WRAP, 1f).apply { setMargins(dp(ctx, 3), 0, dp(ctx, 3), 0) }
            )
        }
        if (isOwn) {
            add("Редактировать", true) { onOpenWeb("/settings") }
        } else when (rel) {
            Rel.FRIEND -> {
                add("Написать", true) { openChat() }
                add("Отключить друга", false) { confirmRemoveFriend() }
            }
            Rel.INCOMING -> {
                add("Принять подключение", true) {
                    friendAction("/api/friends/accept", JSONObject().put("requesterUserId", i.id), "Заявка принята")
                }
                add("Отклонить", false) {
                    friendAction("/api/friends/reject", JSONObject().put("requesterUserId", i.id), "Заявка отклонена")
                }
            }
            Rel.OUTGOING -> {
                add("Отменить заявку", false) {
                    friendAction("/api/friends/cancel", JSONObject().put("toUserId", i.id), "Заявка отменена")
                }
                add("Написать", true) { openChat() }
            }
            Rel.NONE -> {
                add("Добавить в друзья", true) {
                    friendAction("/api/friends/request", JSONObject().put("toUserId", i.id), "Заявка в друзья отправлена")
                }
                add("Написать", false) { openChat() }
            }
        }
        body.addView(actions, UiUtil.lp(UiUtil.MATCH, UiUtil.WRAP).apply { topMargin = dp(ctx, 8) })

        // «Подробнее» (4.2): блок раскрывается с анимацией, «Скрыть» убирает его.
        val moreBtn = UiUtil.text(ctx, 14f, BLUE).apply {
            text = "Подробнее"; gravity = Gravity.CENTER
            background = UiUtil.rect(Color.WHITE, BLUE, 2, 4, ctx)
            setPadding(dp(ctx, 8), dp(ctx, 10), dp(ctx, 8), dp(ctx, 10))
        }
        val block = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = UiUtil.rect(Color.WHITE, UiUtil.BORDER, 2, 4, ctx)
            setPadding(dp(ctx, 12), dp(ctx, 8), dp(ctx, 12), dp(ctx, 12))
        }
        fun field(label: String, value: String?) {
            if (value.isNullOrBlank()) return
            block.addView(TextView(ctx).apply {
                textSize = 15f
                setTextColor(UiUtil.TEXT)
                text = android.text.SpannableStringBuilder().apply {
                    append("$label: ")
                    setSpan(android.text.style.ForegroundColorSpan(LABEL), 0, length, 0)
                    append(value)
                }
                setPadding(0, dp(ctx, 6), 0, dp(ctx, 6))
            })
        }
        field("Образование", b?.education)
        field("Работа", b?.work)
        field("Религия", b?.religion)
        field("Хобби", b?.hobbies)
        field("Фандомы", b?.fandoms)
        fun link(icon: String, label: String, click: () -> Unit) {
            block.addView(
                LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = UiUtil.rect(Color.WHITE, UiUtil.BORDER, 2, 4, ctx)
                    setPadding(dp(ctx, 10), dp(ctx, 8), dp(ctx, 10), dp(ctx, 8))
                    addView(ImageView(ctx).apply { setImageBitmap(asset(icon)) }, UiUtil.lp(dp(ctx, 32), dp(ctx, 32)))
                    addView(
                        UiUtil.text(ctx, 15f, UiUtil.TEXT).apply { text = label; gravity = Gravity.CENTER },
                        UiUtil.lp(0, UiUtil.WRAP, 1f)
                    )
                    setOnClickListener { click() }
                },
                UiUtil.lp(UiUtil.MATCH, UiUtil.WRAP).apply { topMargin = dp(ctx, 8) }
            )
        }
        link("ui/icons/audios_aero.webp", "Музыка пользователя") { onOpenMusic(i.id) }
        link("ui/icons/communities_aero.webp", "Порты пользователя") { onOpenPorts(i.id) }
        link("ui/icons/friends_aero.webp", "Друзья пользователя") { onOpenFriends(i.id) }

        val hideBtn = UiUtil.text(ctx, 14f, UiUtil.TEXT).apply {
            text = "Скрыть"; gravity = Gravity.CENTER
            background = UiUtil.rect(Color.WHITE, UiUtil.BORDER, 2, 4, ctx)
            setPadding(dp(ctx, 8), dp(ctx, 6), dp(ctx, 8), dp(ctx, 6))
        }

        moreBtn.setOnClickListener {
            moreOpen = true
            moreBtn.visibility = View.GONE
            hideBtn.visibility = View.VISIBLE
            animateMore(block, true)
        }
        hideBtn.setOnClickListener {
            moreOpen = false
            hideBtn.visibility = View.GONE
            moreBtn.visibility = View.VISIBLE
            animateMore(block, false)
        }
        block.visibility = if (moreOpen) View.VISIBLE else View.GONE
        moreBtn.visibility = if (moreOpen) View.GONE else View.VISIBLE
        hideBtn.visibility = if (moreOpen) View.VISIBLE else View.GONE

        body.addView(moreBtn, UiUtil.lp(UiUtil.MATCH, UiUtil.WRAP).apply { topMargin = dp(ctx, 10) })
        body.addView(block, UiUtil.lp(UiUtil.MATCH, UiUtil.WRAP).apply { topMargin = dp(ctx, 10) })
        body.addView(hideBtn, LinearLayout.LayoutParams(dp(ctx, 160), UiUtil.WRAP).apply {
            topMargin = dp(ctx, 6); gravity = Gravity.CENTER_HORIZONTAL
        })
    }

    /** Эффект слайдера: высота блока плавно меняется от 0 до полной (и обратно). */
    private fun animateMore(block: View, open: Boolean) {
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
                POST -> (holder.itemView as PostCardView).bind(posts[position - 1], this@ProfileController)
                FOOTER -> (holder.itemView as TextView).apply {
                    setOnClickListener(null)
                    text = when {
                        info == null -> ""
                        failed -> "Не удалось загрузить. Нажмите, чтобы повторить"
                        loading && loadedOnce -> "Загрузка..."
                        posts.isEmpty() && loadedOnce -> "У пользователя пока нет постов"
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
