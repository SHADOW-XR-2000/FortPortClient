package ru.fortport.client

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import ru.fortport.client.UiUtil.dp

/** Файл, прикреплённый к комментарию с устройства. */
class Attachment(
    val uri: Uri?, val name: String, val mime: String,
    /** Файл из медиатеки сайта: путь вида /photo/{id}, /video/{id}, /audio/{id} и тип (image|video|audio). */
    val libPath: String? = null, val libType: String? = null,
    /** Библиотечный файл — GIF (при публикации поста у него тип «gif», а не «image»). */
    val isGif: Boolean = false
)

/**
 * Блок ввода комментария (3.7): фон royal, поле ввода, слева внизу — «прикрепить медиа», справа внизу — «отправить».
 * Используется и для ответа на комментарий (тот же вид, меньше по высоте).
 */
@SuppressLint("ViewConstructor")
class ComposerView(
    context: Context,
    hintText: String,
    minRows: Int,
    private val onPick: (done: (Attachment) -> Unit) -> Unit,
    private val onSend: (text: String, att: Attachment?, done: (Boolean) -> Unit) -> Unit
) : LinearLayout(context) {

    val input = EditText(context)
    private val chip = TextView(context)
    private val sendBtn = TextView(context)
    private var attachment: Attachment? = null
    private var busy = false

    init {
        orientation = VERTICAL
        background = UiUtil.royal(context)
        setPadding(dp(context, 12), dp(context, 10), dp(context, 12), dp(context, 10))

        input.apply {
            hint = hintText
            minLines = minRows
            maxLines = 8
            gravity = Gravity.TOP or Gravity.START
            textSize = 15f
            setTextColor(UiUtil.TEXT)
            setHintTextColor(UiUtil.SUB)
            background = UiUtil.rect(Color.WHITE, UiUtil.BORDER, 2, 4, context)
            setPadding(dp(context, 10), dp(context, 8), dp(context, 10), dp(context, 8))
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        addView(input, UiUtil.lp(UiUtil.MATCH, UiUtil.WRAP))

        chip.apply {
            textSize = 13f; setTextColor(Color.WHITE); visibility = View.GONE
            setPadding(0, dp(context, 6), 0, 0)
            setOnClickListener { attachment = null; visibility = View.GONE }
        }
        addView(chip)

        val row = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val attach = pill("+")
        sendBtn.apply {
            text = "➤"; textSize = 18f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
            background = UiUtil.rect(0x3300E5FF, 0xFF8FF7FF.toInt(), 2, 10, context)
        }
        row.addView(attach, UiUtil.lp(dp(context, 56), dp(context, 34)))
        row.addView(View(context), UiUtil.lp(0, 1, 1f))
        row.addView(sendBtn, UiUtil.lp(dp(context, 64), dp(context, 34)))
        addView(row, UiUtil.lp(UiUtil.MATCH, UiUtil.WRAP).apply { topMargin = dp(context, 8) })

        attach.setOnClickListener { onPick { setAttachment(it) } }
        sendBtn.setOnClickListener { send() }
    }

    private fun pill(t: String) = TextView(context).apply {
        text = t; textSize = 20f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
        background = UiUtil.rect(0x3300E5FF, 0xFF8FF7FF.toInt(), 2, 10, context)
    }

    private fun setAttachment(a: Attachment) {
        attachment = a
        chip.text = "📎 ${a.name}  ✕"
        chip.visibility = View.VISIBLE
    }

    private fun send() {
        if (busy) return
        val text = input.text.toString().trim()
        if (text.isEmpty() && attachment == null) return
        busy = true; sendBtn.alpha = 0.5f
        onSend(text, attachment) { ok ->
            busy = false; sendBtn.alpha = 1f
            if (ok) reset()
        }
    }

    fun reset() {
        input.setText("")
        attachment = null
        chip.visibility = View.GONE
    }
}

/**
 * Нативная страница открытого поста (концепция 3): сверху — стрелка «назад» (рисует MainActivity),
 * далее сам пост (3.2–3.6) без кнопки «Смотреть комментарии», блок ввода комментария (3.7)
 * и комментарии (3.8–3.10) с ответами. Нижняя панель на этом экране скрыта.
 *
 * API: GET /api/posts/{id} (комментарии лежат внутри поста), POST /api/posts/{id}/comments,
 * POST /api/posts/{id}/comments/{cid}/like, DELETE /api/posts/{id}/comments/{cid}.
 */
class PostPageController(
    override val activity: AppCompatActivity,
    override val api: SiteApi,
    override val repo: PostsRepo,
    private val swipe: SwipeRefreshLayout,
    private val recycler: RecyclerView,
    private val onOpenProfile: (Long) -> Unit,
    private val onOpenCommunity: (Long) -> Unit,
    private val onBalanceChanged: () -> Unit,
    private val onAuthLost: () -> Unit,
    /** Системный выбор файла: результат приходит в done. */
    private val pickFile: (done: (Uri) -> Unit) -> Unit,
    /** Пост удалён — закрыть экран. */
    private val onClose: () -> Unit
) : PostHost {

    companion object {
        private const val TAG = "PostPage"
        private const val MAX_UPLOAD = 60L * 1024 * 1024
    }

    /** key — устойчивый идентификатор строки, sig — всё, от чего зависит её вид (для DiffUtil). */
    private sealed class Row(val key: String, val sig: String) {
        object PostRow : Row("post", "")
        object Composer : Row("composer", "")
        class Cmt(val c: PostComment, val depth: Int, val replies: Int, sig: String) : Row("c${c.id}", sig)
        class Reply(val parent: Long, val depth: Int) : Row("r$parent", "$depth")
        class Info(val text: String) : Row("info", text)
    }

    private val ui = Handler(Looper.getMainLooper())

    private var postId = 0L
    private var curPost: Post? = null
    private var comments: List<PostComment> = emptyList()
    private var rows: List<Row> = listOf(Row.PostRow, Row.Composer)
    private var freshBound = false
    private var started = false
    private var loadedFor = 0L
    private var loading = false
    private var generation = 0

    private val expanded = HashSet<Long>()
    private val revealed = HashSet<Long>()
    private var replyTo: Long? = null
    private var replyDraft = ""

    private lateinit var composer: ComposerView
    private val adapter = Adapter()

    init {
        recycler.layoutManager = LinearLayoutManager(activity)
        recycler.adapter = adapter
        recycler.itemAnimator = null
        val d = activity.resources.displayMetrics.density
        swipe.setProgressViewOffset(false, (70 * d).toInt(), (130 * d).toInt())
        swipe.setColorSchemeResources(R.color.fp_accent)
        swipe.setDistanceToTriggerSync((32 * d).toInt())
        swipe.setOnRefreshListener { load(silent = false) }
    }

    // ------------------------------------------------------------------ жизненный цикл

    /** Какой пост показывать. Тот же id — состояние (прокрутка, раскрытые ответы) сохраняется. */
    fun setPost(id: Long, preloaded: Post?) {
        if (id == postId) return
        generation++
        postId = id
        curPost = preloaded
        loading = false
        comments = preloaded?.comments ?: emptyList()
        freshBound = false
        loadedFor = 0L
        expanded.clear(); revealed.clear(); replyTo = null; replyDraft = ""; focusId = 0L
        if (::composer.isInitialized) composer.reset()
        InlineAudio.stop()
        rows = listOf(Row.PostRow, Row.Composer)
        adapter.notifyDataSetChanged()
        recycler.scrollToPosition(0)
        rebuildRows(if (preloaded == null) "Загрузка..." else null)
    }

    /** Комментарий, к которому надо прокрутить (переход из уведомления). */
    private var focusId = 0L

    fun focusComment(commentId: Long) {
        focusId = commentId
        tryFocus()
    }

    /** Раскрывает цепочку ответов до комментария и прокручивает к нему; если комментарии ещё не загружены — ждёт загрузки. */
    private fun tryFocus() {
        val fid = focusId
        if (fid == 0L) return
        val byId = HashMap<Long, PostComment>()
        comments.forEach { c -> byId[c.id] = c }
        val target = byId[fid] ?: return
        var p = target.reference
        var guard = 0
        while (p != null && guard++ < 100) {
            expanded.add(p)
            p = byId[p]?.reference
        }
        focusId = 0L
        rebuildRows(null)
        val idx = rows.indexOfFirst { r -> r.key == "c$fid" }
        if (idx >= 0) {
            recycler.post {
                (recycler.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager)
                    ?.scrollToPositionWithOffset(idx, (80 * activity.resources.displayMetrics.density).toInt())
            }
        }
    }

    fun start(userId: String, deviceId: String, userAgent: String) {
        api.setSession(userId, deviceId, userAgent)
        if (started) return
        started = true
        if (postId != 0L) load(silent = loadedFor == postId)
    }

    fun stop() {
        started = false
        swipe.isRefreshing = false
        InlineAudio.stop()
        hideKeyboard()
    }

    fun destroy() { generation++ }

    private fun hideKeyboard() {
        val v = activity.currentFocus ?: return
        (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(v.windowToken, 0)
    }

    // ------------------------------------------------------------------ загрузка

    private fun load(silent: Boolean) {
        if (postId == 0L || loading) { swipe.isRefreshing = false; return }
        val gen = generation
        val id = postId
        loading = true
        if (!silent && curPost == null) swipe.isRefreshing = true
        api.io.execute {
            try {
                when (api.ensureUserId()) {
                    1 -> { post { loading = false; swipe.isRefreshing = false; onAuthLost() }; return@execute }
                    2 -> throw IllegalStateException("session")
                }
                if (!repo.usersLoaded) repo.pool.execute { try { repo.loadUsers() } catch (_: Exception) {}; post { adapter.notifyDataSetChanged() } }
                val fresh = repo.loadPost(id)
                post {
                    loading = false; swipe.isRefreshing = false
                    if (gen != generation) return@post
                    if (fresh == null) {
                        if (curPost == null) rebuildRows("Пост не найден или недоступен")
                        return@post
                    }
                    comments = fresh.comments
                    curPost?.commentCount = comments.size
                    loadedFor = id
                    if (!freshBound) {
                        // Первый полный ответ: обновляем карточку (в ленте порта/пользователя комментариев нет, только число).
                        val cur = curPost
                        if (cur != null && cur.id == fresh.id) {
                            // Остаёмся на том же объекте, что лежит в ленте: оценки, опрос и счётчик попадут и туда.
                            cur.likeCount = fresh.likeCount; cur.dislikeCount = fresh.dislikeCount
                            cur.likedByMe = fresh.likedByMe; cur.dislikedByMe = fresh.dislikedByMe
                            cur.isPinned = fresh.isPinned; cur.poll = fresh.poll
                        } else curPost = fresh
                        freshBound = true
                        adapter.notifyItemChanged(0)
                    }
                    rebuildRows(null)
                    tryFocus()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Не удалось загрузить пост", e)
                post {
                    loading = false; swipe.isRefreshing = false
                    if (gen == generation && curPost == null) rebuildRows("Не удалось загрузить пост. Потяните вниз, чтобы повторить")
                }
            }
        }
    }

    private fun post(r: () -> Unit) { ui.post { if (!activity.isDestroyed && !activity.isFinishing) r() } }

    // ------------------------------------------------------------------ строки списка

    private fun buildRows(info: String?): List<Row> {
        val out = ArrayList<Row>()
        out.add(Row.PostRow)
        out.add(Row.Composer)
        val byParent = HashMap<Long, MutableList<PostComment>>()
        val ids = comments.mapTo(HashSet()) { it.id }
        val roots = ArrayList<PostComment>()
        for (c in comments) {
            val ref = c.reference
            if (ref != null && ref in ids) byParent.getOrPut(ref) { ArrayList() }.add(c) else roots.add(c)
        }
        // Как на сайте: сначала новые, на каждом уровне.
        roots.sortByDescending { it.createdAt }
        byParent.values.forEach { l -> l.sortByDescending { it.createdAt } }

        fun walk(c: PostComment, depth: Int) {
            val kids = byParent[c.id].orEmpty()
            out.add(
                Row.Cmt(
                    c, depth, kids.size,
                    "$depth|${kids.size}|${c.id in expanded}|${c.id in revealed}|${c.likedByMe}|${c.likeCount}|${replyTo == c.id}"
                )
            )
            if (replyTo == c.id) out.add(Row.Reply(c.id, depth + 1))
            if (c.id in expanded) kids.forEach { walk(it, depth + 1) }
        }
        roots.forEach { walk(it, 0) }
        if (info != null) out.add(Row.Info(info))
        else if (comments.isEmpty()) out.add(Row.Info("Комментариев пока нет"))
        return out
    }

    /** Пересобирает только комментарии: карточка поста и поле ввода не перерисовываются (не теряют текст и видео). */
    private fun rebuildRows(info: String?) {
        val old = rows
        val new = buildRows(info)
        // Минимальные изменения вместо «удалить всё и вставить заново»: видимые комментарии не пересоздаются,
        // позиция прокрутки не прыгает при раскрытии ответов, а поле ответа не теряет текст.
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = old.size
            override fun getNewListSize() = new.size
            override fun areItemsTheSame(o: Int, n: Int) = old[o].key == new[n].key
            override fun areContentsTheSame(o: Int, n: Int) = old[o].sig == new[n].sig
        }, false)
        rows = new
        diff.dispatchUpdatesTo(adapter)
    }

    // ------------------------------------------------------------------ действия

    private fun readAttachment(a: Attachment): ByteArray? = try {
        a.uri?.let { u -> activity.contentResolver.openInputStream(u)?.use { it.readBytes() } }
    } catch (e: Exception) { null }

    /** «+» в поле ввода: медиатека сайта (фото, GIF, видео, аудио) или файл с устройства. */
    private fun pickAttachment(done: (Attachment) -> Unit) {
        MediaLibraryPicker.show(this, pickFile, done) { describe(it) }
    }

    private fun sendComment(text: String, att: Attachment?, refId: Long?, done: (Boolean) -> Unit) {
        val id = postId
        repo.pool.execute {
            var bytes: ByteArray? = null
            if (att != null && att.libPath == null) {
                bytes = readAttachment(att)
                if (bytes == null || bytes.size > MAX_UPLOAD) {
                    post { toast(if (bytes == null) "Не удалось прочитать файл" else "Файл слишком большой"); done(false) }
                    return@execute
                }
            }
            val r = repo.addComment(id, text, bytes, att?.name ?: "", att?.mime ?: "", refId, att?.libPath, att?.libType)
            val fresh = if (r.ok) repo.loadPost(id) else null
            post {
                if (id != postId) return@post
                if (r.authLost) { done(false); onAuthLost(); return@post }
                if (!r.ok) { toast(r.error.ifBlank { "Не удалось отправить комментарий" }); done(false); return@post }
                done(true)
                if (refId != null) { expanded.add(refId); replyTo = null; replyDraft = "" }
                if (fresh != null) comments = fresh.comments
                curPost?.commentCount = comments.size
                rebuildRows(null)
            }
        }
    }

    private fun toast(s: String) = Toast.makeText(activity, s, Toast.LENGTH_SHORT).show()

    private fun toggleLike(c: PostComment, counter: TextView, btn: TextView) {
        repo.pool.execute {
            val r = repo.likeComment(postId, c.id)
            post {
                val o = r.extra
                if (!r.ok || o == null) { toast("Не удалось поставить оценку"); return@post }
                c.likedByMe = o.optBoolean("liked", !c.likedByMe)
                c.likeCount = o.optInt("likeCount", c.likeCount)
                counter.text = c.likeCount.toString()
                paintLike(btn, c.likedByMe)
            }
        }
    }

    private fun paintLike(btn: TextView, liked: Boolean) {
        btn.background = UiUtil.rect(if (liked) 0xFFD6FFEF.toInt() else Color.WHITE, 0xFF2AF0B0.toInt(), 2, 8, activity)
        btn.setTypeface(null, if (liked) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
    }

    private fun confirmDelete(c: PostComment) {
        AlertDialog.Builder(activity).setMessage("Удалить комментарий?")
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Удалить") { _, _ ->
                repo.pool.execute {
                    val r = repo.deleteComment(postId, c.id)
                    val fresh = if (r.ok) repo.loadPost(postId) else null
                    post {
                        if (!r.ok) { toast("Не удалось удалить"); return@post }
                        if (fresh != null) comments = fresh.comments else comments = comments.filter { it.id != c.id }
                        curPost?.commentCount = comments.size
                        rebuildRows(null)
                    }
                }
            }.show()
    }

    // ------------------------------------------------------------------ PostHost

    override fun openPost(post: Post) {}
    override fun openProfile(userId: Long) = onOpenProfile(userId)
    override fun openCommunity(communityId: Long) = onOpenCommunity(communityId)
    override fun removePost(post: Post) = onClose()
    override fun reloadList() = load(silent = true)
    override fun balanceChanged() = onBalanceChanged()
    override fun authLost() = onAuthLost()

    // ------------------------------------------------------------------ адаптер

    private inner class Adapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        override fun getItemCount() = rows.size

        private fun rowAt(position: Int): Row = rows[position]

        override fun getItemViewType(position: Int) = when (rowAt(position)) {
            is Row.PostRow -> 0
            is Row.Composer -> 1
            is Row.Cmt -> 2
            is Row.Reply -> 3
            is Row.Info -> 4
        }

        private fun lp(bottomDp: Int, startDp: Int = 0) =
            RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(dp(activity, startDp), 0, 0, dp(activity, bottomDp)) }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val ctx = parent.context
            val v: View = when (viewType) {
                0 -> PostCardView(ctx).apply { layoutParams = lp(10) }
                1 -> {
                    if (!::composer.isInitialized) {
                        composer = ComposerView(
                            ctx, "Поле ввода комментария", 3,
                            onPick = { done -> pickAttachment(done) },
                            onSend = { t, a, done -> sendComment(t, a, null, done) }
                        )
                    }
                    (composer.parent as? ViewGroup)?.removeView(composer)
                    composer.layoutParams = lp(10)
                    composer
                }
                4 -> UiUtil.text(ctx, 14f, UiUtil.SUB).apply {
                    gravity = Gravity.CENTER
                    setPadding(dp(ctx, 12), dp(ctx, 16), dp(ctx, 12), dp(ctx, 16))
                    layoutParams = lp(0)
                }
                else -> FrameLayout(ctx).apply { layoutParams = lp(8) }
            }
            return object : RecyclerView.ViewHolder(v) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = rowAt(position)) {
                is Row.PostRow -> {
                    // Пока пост грузится по ссылке, строка схлопнута (индексы остальных строк не меняются).
                    val card = holder.itemView as PostCardView
                    val p = curPost
                    val l = card.layoutParams as RecyclerView.LayoutParams
                    if (p == null) {
                        l.height = 0; l.bottomMargin = 0; card.visibility = View.GONE
                    } else {
                        l.height = ViewGroup.LayoutParams.WRAP_CONTENT; l.bottomMargin = dp(activity, 10); card.visibility = View.VISIBLE
                        card.bind(p, this@PostPageController, showCommentsButton = false)
                    }
                    card.layoutParams = l
                }
                is Row.Composer -> {}
                is Row.Info -> (holder.itemView as TextView).text = row.text
                is Row.Cmt -> {
                    val box = holder.itemView as FrameLayout
                    box.removeAllViews()
                    (box.layoutParams as RecyclerView.LayoutParams).leftMargin = dp(activity, minOf(row.depth, 3) * 14)
                    box.addView(commentView(row))
                }
                is Row.Reply -> {
                    val box = holder.itemView as FrameLayout
                    box.removeAllViews()
                    (box.layoutParams as RecyclerView.LayoutParams).leftMargin = dp(activity, minOf(row.depth, 3) * 14)
                    box.addView(replyView(row))
                }
            }
        }

        override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
            (holder.itemView as? PostCardView)?.release()
            (holder.itemView as? FrameLayout)?.let { box ->
                for (i in 0 until box.childCount) releaseDeep(box.getChildAt(i))
            }
        }

        private fun releaseDeep(v: View) {
            if (v is MediaCarouselView) v.release()
            if (v is ViewGroup) for (i in 0 until v.childCount) releaseDeep(v.getChildAt(i))
        }
    }

    private fun describe(uri: Uri): Attachment {
        var name = "file"
        try {
            activity.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) name = it.getString(0) ?: name
            }
        } catch (_: Exception) {}
        return Attachment(uri, name, activity.contentResolver.getType(uri).orEmpty())
    }

    // ------------------------------------------------------------------ вид комментария (3.8)

    private fun commentView(row: Row.Cmt): View {
        val c = row.c
        val ctx = activity
        val shade = 246 - minOf(row.depth, 3) * 10
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = UiUtil.rect(Color.rgb(shade, shade + 1, shade + 3), UiUtil.BORDER, 2, 6, ctx)
        }

        // Шапка: аватар, имя, дата, «Удалить» (для своих).
        val head = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = UiUtil.royal(ctx)
            setPadding(dp(ctx, 8), dp(ctx, 6), dp(ctx, 8), dp(ctx, 6))
        }
        val av = ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = UiUtil.rect(Color.WHITE, Color.WHITE, 2, 4, ctx)
            setPadding(dp(ctx, 2), dp(ctx, 2), dp(ctx, 2), dp(ctx, 2))
            clipToOutline = true
            setOnClickListener { onOpenProfile(c.userId) }
        }
        head.addView(av, LinearLayout.LayoutParams(dp(ctx, 44), dp(ctx, 44)))
        api.loadAvatar(av, c.profilePicture)
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val role = c.role.badge.ifBlank { c.role.displayRole }
        col.addView(UiUtil.text(ctx, 16f, Color.WHITE, bold = true, maxLines = 1).apply {
            text = c.username.ifBlank { "Неизвестный" } + if (role.isNotBlank()) "  · $role" else ""
            setShadowLayer(2f, 0f, 1f, 0x99000000.toInt())
            setOnClickListener { onOpenProfile(c.userId) }
        })
        col.addView(UiUtil.text(ctx, 12f, Color.WHITE).apply { text = UiUtil.formatDate(c.createdAt); setShadowLayer(2f, 0f, 1f, 0x99000000.toInt()) })
        head.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(ctx, 8) })
        if (c.userId == repo.me) {
            head.addView(
                TextView(ctx).apply {
                    text = "удалить"; textSize = 12f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
                    background = UiUtil.rect(0x33FFFFFF, Color.WHITE, 1, 6, ctx)
                    setPadding(dp(ctx, 8), dp(ctx, 4), dp(ctx, 8), dp(ctx, 4))
                    setOnClickListener { confirmDelete(c) }
                }
            )
        }
        card.addView(head)

        // Тело: текст и вложение; спойлер скрыт до нажатия.
        val covered = c.isSpoiler && c.id !in revealed
        if (covered) {
            card.addView(
                UiUtil.text(ctx, 14f, UiUtil.SUB).apply {
                    text = "Спойлер. Нажмите, чтобы показать"; gravity = Gravity.CENTER
                    setPadding(dp(ctx, 12), dp(ctx, 18), dp(ctx, 12), dp(ctx, 18))
                    setOnClickListener { revealed.add(c.id); rebuildRows(null) }
                }
            )
        } else {
            if (c.content.isNotBlank()) {
                card.addView(UiUtil.text(ctx, 15f, UiUtil.TEXT).apply {
                    text = c.content.replace("\r\n", "\n")
                    setPadding(dp(ctx, 10), dp(ctx, 8), dp(ctx, 10), dp(ctx, 4))
                    android.text.util.Linkify.addLinks(this, android.text.util.Linkify.WEB_URLS)
                    movementMethod = android.text.method.LinkMovementMethod.getInstance()
                })
            }
            if (c.attachment.isNotBlank()) {
                val mediaId = Regex("/photo/(\\d+)").find(c.attachment)?.groupValues?.get(1)?.toLongOrNull()
                val item = MediaItem(c.attachment, c.attachmentType.ifBlank { "image" }, mediaId, "")
                val holder = FrameLayout(ctx).apply { setPadding(dp(ctx, 8), dp(ctx, 4), dp(ctx, 8), dp(ctx, 4)) }
                if (item.type == "audio") holder.addView(AudioRowView(ctx, api, repo, item))
                else holder.addView(MediaCarouselView(ctx, api, listOf(item), fixedHeightDp = 220))
                card.addView(holder)
            }
        }

        // Подвал: «Ответить», «раскрыть/скрыть», справа «Да» и число.
        val foot = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(ctx, 10), dp(ctx, 4), dp(ctx, 10), dp(ctx, 8))
        }
        foot.addView(TextView(ctx).apply {
            text = "Ответить"; textSize = 14f; setTextColor(0xFF1B5FD1.toInt())
            setPadding(0, dp(ctx, 6), dp(ctx, 12), dp(ctx, 6))
            setOnClickListener {
                replyTo = if (replyTo == c.id) null else c.id
                replyDraft = ""
                rebuildRows(null)
            }
        })
        if (row.replies > 0) {
            val open = c.id in expanded
            foot.addView(TextView(ctx).apply {
                text = if (open) "скрыть (${row.replies})" else "раскрыть (${row.replies})"
                textSize = 14f; setTextColor(0xFF1B5FD1.toInt())
                setPadding(0, dp(ctx, 6), dp(ctx, 12), dp(ctx, 6))
                setOnClickListener { if (open) expanded.remove(c.id) else expanded.add(c.id); rebuildRows(null) }
            })
        }
        foot.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
        val like = TextView(ctx).apply {
            text = "Да"; textSize = 14f; setTextColor(UiUtil.TEXT); gravity = Gravity.CENTER
            setPadding(dp(ctx, 14), dp(ctx, 6), dp(ctx, 14), dp(ctx, 6))
        }
        val counter = UiUtil.text(ctx, 14f, UiUtil.TEXT, bold = true).apply {
            text = c.likeCount.toString(); gravity = Gravity.CENTER
            background = UiUtil.rect(Color.WHITE, UiUtil.BORDER, 2, 8, ctx)
            setPadding(dp(ctx, 12), dp(ctx, 6), dp(ctx, 12), dp(ctx, 6))
        }
        paintLike(like, c.likedByMe)
        like.setOnClickListener { toggleLike(c, counter, like) }
        foot.addView(like)
        foot.addView(counter, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(ctx, 6) })
        card.addView(foot)
        return card
    }

    private fun replyView(row: Row.Reply): View {
        val v = ComposerView(
            activity, "Написать ответ...", 2,
            onPick = { done -> pickAttachment(done) },
            onSend = { t, a, done -> sendComment(t, a, row.parent, done) }
        )
        v.input.setText(replyDraft)
        v.input.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { replyDraft = s?.toString().orEmpty() }
        })
        return v
    }
}
