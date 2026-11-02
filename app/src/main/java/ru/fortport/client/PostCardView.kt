package ru.fortport.client

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.text.method.LinkMovementMethod
import android.text.util.Linkify
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import ru.fortport.client.UiUtil.dp

/**
 * Целый блок поста по концепции (пункты 1.2–1.7 / 2.2–2.7 / 3.2–3.6):
 * шапка (автор или порт + «▼») → награды → медиа/аудио/опрос → текст → «+ балл / число / − балл» → «Смотреть комментарии».
 * Верхняя и нижняя панели приложения этот блок не затрагивает.
 */
@SuppressLint("ViewConstructor")
class PostCardView(context: Context) : LinearLayout(context) {

    private lateinit var host: PostHost
    private var post: Post? = null
    private var showComments = true

    private val headerHolder = FrameLayout(context)
    private val awardsHolder = LinearLayout(context)
    private val bodyHolder = LinearLayout(context)
    private val scoreHolder = LinearLayout(context)
    private val commentsHolder = FrameLayout(context)

    private var carousel: MediaCarouselView? = null

    init {
        orientation = VERTICAL
        background = UiUtil.rect(Color.WHITE, UiUtil.BORDER, 2, 6, context)
        setPadding(dp(context, 2), dp(context, 2), dp(context, 2), dp(context, 2))
        clipToOutline = true
        bodyHolder.orientation = VERTICAL
        scoreHolder.orientation = HORIZONTAL
        awardsHolder.orientation = HORIZONTAL
        awardsHolder.gravity = Gravity.CENTER_VERTICAL
        addView(headerHolder, UiUtil.lp(UiUtil.MATCH, UiUtil.WRAP))
        addView(awardsHolder, UiUtil.lp(UiUtil.MATCH, UiUtil.WRAP))
        addView(bodyHolder, UiUtil.lp(UiUtil.MATCH, UiUtil.WRAP))
        addView(scoreHolder, UiUtil.lp(UiUtil.MATCH, dp(context, 44)))
        addView(commentsHolder, UiUtil.lp(UiUtil.MATCH, UiUtil.WRAP))
    }

    /** Останавливает видео внутри карточки (уход с экрана / переиспользование). */
    fun release() {
        carousel?.release()
        carousel = null
    }

    fun bind(p: Post, host: PostHost, showCommentsButton: Boolean = true) {
        release()
        this.host = host
        this.post = p
        this.showComments = showCommentsButton
        buildHeader(p)
        buildAwards(p)
        buildBody(p)
        buildScore(p)
        buildCommentsButton(p)
        ensureCommunity(p)
        ensureAwards(p)
    }

    // ------------------------------------------------------------------ шапка (1.2 / 2.2 / 3.2)

    private fun buildHeader(p: Post) {
        headerHolder.removeAllViews()
        val ctx = context
        val row = LinearLayout(ctx).apply {
            orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = UiUtil.royal(ctx)
            setPadding(dp(ctx, 8), dp(ctx, 8), dp(ctx, 8), dp(ctx, 6))
        }
        val community = p.communityId?.let { host.repo.cachedCommunity(it) }
        val author = if (p.isAnonymous || p.userId == null) null else host.repo.users[p.userId]
        val authorName = when {
            p.isAnonymous -> "Аноним"
            p.username.isNotBlank() -> p.username
            else -> author?.name.orEmpty()
        }

        val big = ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = UiUtil.rect(Color.WHITE, Color.WHITE, 2, 4, ctx)
            setPadding(dp(ctx, 2), dp(ctx, 2), dp(ctx, 2), dp(ctx, 2))
            clipToOutline = true
        }
        row.addView(big, UiUtil.lp(dp(ctx, 60), dp(ctx, 60)))
        val bigPath = if (p.communityId != null) community?.avatar.orEmpty() else author?.avatar.orEmpty()
        host.api.loadAvatar(big, bigPath)

        val col = LinearLayout(ctx).apply { orientation = VERTICAL }
        row.addView(col, UiUtil.lp(0, UiUtil.WRAP, 1f).apply { marginStart = dp(ctx, 10) })

        if (p.communityId != null) {
            // 2.2: название порта, под ним мелкая аватарка автора и его имя (у анонимного — без них).
            col.addView(titleText(community?.name ?: "Порт"))
            col.addView(
                LinearLayout(ctx).apply {
                    orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    if (!p.isAnonymous) {
                        val small = ImageView(ctx).apply {
                            scaleType = ImageView.ScaleType.CENTER_CROP
                            // Как у основной аватарки: белая рамка и слегка скруглённые углы.
                            background = UiUtil.rect(Color.WHITE, Color.WHITE, 1, 4, ctx)
                            setPadding(dp(ctx, 2), dp(ctx, 2), dp(ctx, 2), dp(ctx, 2))
                            clipToOutline = true
                        }
                        addView(small, UiUtil.lp(dp(ctx, 28), dp(ctx, 28)))
                        host.api.loadAvatar(small, author?.avatar.orEmpty())
                        small.setOnClickListener { p.userId?.let { host.openProfile(it) } }
                    }
                    addView(
                        UiUtil.text(ctx, 14f, Color.WHITE, maxLines = 1).apply {
                            text = authorName; setShadowLayer(2f, 0f, 1f, 0x99000000.toInt())
                            if (!p.isAnonymous) setOnClickListener { p.userId?.let { host.openProfile(it) } }
                        },
                        UiUtil.lp(UiUtil.WRAP, UiUtil.WRAP).apply { marginStart = dp(ctx, 6) }
                    )
                }
            )
            big.setOnClickListener { host.openCommunity(p.communityId) }
            col.getChildAt(0).setOnClickListener { host.openCommunity(p.communityId) }
        } else {
            col.addView(titleText(authorName))
            if (!p.isAnonymous && p.userId != null) {
                val open = View.OnClickListener { host.openProfile(p.userId) }
                big.setOnClickListener(open); col.getChildAt(0).setOnClickListener(open)
            }
        }
        val role = if (p.isAnonymous) "" else p.role.badge.ifBlank { p.role.displayRole }
        val date = UiUtil.formatDate(p.createdAt) + (if (p.isPinned) "  📌" else "") + (if (role.isNotBlank()) "  ·  $role" else "")
        col.addView(UiUtil.text(ctx, 12f, Color.WHITE).apply { text = date; setShadowLayer(2f, 0f, 1f, 0x99000000.toInt()) })

        val menu = TextView(ctx).apply {
            text = "▼"; textSize = 16f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
            background = UiUtil.rect(0x33FFFFFF, Color.WHITE, 2, 8, ctx)
        }
        row.addView(menu, UiUtil.lp(dp(ctx, 40), dp(ctx, 40)))
        menu.setOnClickListener { PostDialogs.showPostMenu(host, it, p) }
        headerHolder.addView(row)
    }

    private fun titleText(s: String) = UiUtil.text(context, 18f, Color.WHITE, bold = true, maxLines = 1).apply {
        text = s; setShadowLayer(2f, 0f, 1f, 0x99000000.toInt())
    }

    /** Информация о порте не приходит с постом — подгружаем и перерисовываем только шапку. */
    private fun ensureCommunity(p: Post) {
        val id = p.communityId ?: return
        if (host.repo.cachedCommunity(id) != null) return
        host.repo.pool.execute {
            host.repo.community(id)
            host.activity.runOnUiThread { if (post === p && !host.activity.isDestroyed) buildHeader(p) }
        }
    }

    // ------------------------------------------------------------------ награды (1.3)

    private fun buildAwards(p: Post) {
        awardsHolder.removeAllViews()
        val ctx = context
        awardsHolder.setPadding(dp(ctx, 8), dp(ctx, 4), dp(ctx, 8), dp(ctx, 4))
        awardsHolder.background = UiUtil.rect(Color.WHITE, 0xFFE0E4EC.toInt(), 1, 0, ctx)
        val list = p.awards
        val scroll = HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false }
        val icons = LinearLayout(ctx).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        scroll.addView(icons)
        if (!list.isNullOrEmpty()) {
            list.groupBy { it.awardId }.values.forEach { group ->
                val cell = FrameLayout(ctx)
                val iv = ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
                cell.addView(iv, UiUtil.frameLp(dp(ctx, 32), dp(ctx, 32)))
                host.api.loadImage(iv, PostDialogs.awardImagePath(group[0].image, big = false), 128)
                if (group.size > 1) {
                    cell.addView(
                        UiUtil.text(ctx, 10f, Color.WHITE, bold = true).apply {
                            text = group.size.toString(); setBackgroundColor(0xCC1B2A4A.toInt())
                            setPadding(dp(ctx, 3), 0, dp(ctx, 3), 0)
                        },
                        UiUtil.frameLp(UiUtil.WRAP, UiUtil.WRAP, Gravity.END or Gravity.BOTTOM)
                    )
                }
                cell.setOnClickListener { PostDialogs.showAwardInfo(host, group) }
                icons.addView(cell, UiUtil.lp(UiUtil.WRAP, UiUtil.WRAP).apply { marginEnd = dp(ctx, 6) })
            }
        }
        awardsHolder.addView(scroll, UiUtil.lp(0, UiUtil.WRAP, 1f))
        val give = TextView(ctx).apply {
            text = "Наградить"; textSize = 13f; setTextColor(0xFFE67E22.toInt()); gravity = Gravity.CENTER
            background = UiUtil.rect(Color.WHITE, 0xFFE67E22.toInt(), 2, 10, ctx)
            setPadding(dp(ctx, 10), dp(ctx, 5), dp(ctx, 10), dp(ctx, 5))
        }
        give.setOnClickListener {
            PostDialogs.showAwardPicker(host, p) {
                p.awards = null
                ensureAwards(p, force = true)
            }
        }
        awardsHolder.addView(give, UiUtil.lp(UiUtil.WRAP, UiUtil.WRAP).apply { marginStart = dp(ctx, 8) })
    }

    private fun ensureAwards(p: Post, force: Boolean = false) {
        if (p.awards != null && !force) return
        host.repo.pool.execute {
            val a = host.repo.loadAwards(p.id)
            host.activity.runOnUiThread {
                if (host.activity.isDestroyed) return@runOnUiThread
                if (a != null) p.awards = a
                if (post === p) buildAwards(p)
            }
        }
    }

    // ------------------------------------------------------------------ медиа, опрос, текст (1.4 / 1.5)

    private fun buildBody(p: Post) {
        carousel?.release(); carousel = null
        bodyHolder.removeAllViews()
        val ctx = context
        val hasAnyBody = p.files.isNotEmpty() || p.poll != null || p.content.isNotBlank() || p.title.isNotBlank()
        if (!hasAnyBody) return

        if (p.isCovered) {
            val cover = LinearLayout(ctx).apply {
                orientation = VERTICAL; gravity = Gravity.CENTER
                setBackgroundColor(0xFF2B2F3A.toInt())
                setPadding(dp(ctx, 16), dp(ctx, 28), dp(ctx, 16), dp(ctx, 28))
            }
            cover.addView(UiUtil.text(ctx, 20f, Color.WHITE, bold = true).apply {
                text = if (p.nsfw) "Материал 18+" else "Спойлер"; gravity = Gravity.CENTER
            })
            if (p.spoilerPreview.isNotBlank()) {
                cover.addView(UiUtil.text(ctx, 14f, 0xFFCCD3E0.toInt()).apply {
                    text = "\"${p.spoilerPreview}\""; gravity = Gravity.CENTER
                    setPadding(0, dp(ctx, 8), 0, 0)
                })
            }
            cover.addView(UiUtil.text(ctx, 13f, 0xFF8FDADB.toInt()).apply {
                text = "Нажмите, чтобы показать"; gravity = Gravity.CENTER
                setPadding(0, dp(ctx, 10), 0, 0)
            })
            cover.setOnClickListener { p.revealed = true; buildBody(p) }
            bodyHolder.addView(cover)
            return
        }

        if (p.title.isNotBlank()) {
            bodyHolder.addView(
                UiUtil.text(ctx, 18f, UiUtil.TEXT, bold = true).apply {
                    text = p.title
                    setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 6))
                },
                UiUtil.lp(UiUtil.MATCH, UiUtil.WRAP)
            )
        }
        val visual = PostParser.visualMedia(p)
        val audio = PostParser.audioItems(p)
        if (visual.isNotEmpty()) {
            val c = MediaCarouselView(ctx, host.api, visual)
            carousel = c
            bodyHolder.addView(c, UiUtil.lp(UiUtil.MATCH, UiUtil.WRAP))
        }
        audio.forEach { a ->
            bodyHolder.addView(
                AudioRowView(ctx, host.api, host.repo, a),
                UiUtil.lp(UiUtil.MATCH, UiUtil.WRAP).apply { setMargins(dp(ctx, 8), dp(ctx, 6), dp(ctx, 8), dp(ctx, 2)) }
            )
        }
        if (p.poll != null) {
            bodyHolder.addView(PollView(ctx, host.repo, p) { }, UiUtil.lp(UiUtil.MATCH, UiUtil.WRAP))
        }
        if (p.content.isNotBlank()) {
            val t = UiUtil.text(ctx, 15f, UiUtil.TEXT).apply {
                text = p.content.replace("\r\n", "\n")
                setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 10))
                setTextIsSelectable(false)
                Linkify.addLinks(this, Linkify.WEB_URLS)
                movementMethod = LinkMovementMethod.getInstance()
            }
            bodyHolder.addView(t, UiUtil.lp(UiUtil.MATCH, UiUtil.WRAP))
        }
    }

    // ------------------------------------------------------------------ баллы (1.6)

    private fun buildScore(p: Post) {
        scoreHolder.removeAllViews()
        val ctx = context
        val plus = TextView(ctx).apply {
            text = "+ Балл"; textSize = 15f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
        }
        val minus = TextView(ctx).apply {
            text = "- Балл"; textSize = 15f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
        }
        val count = UiUtil.text(ctx, 16f, UiUtil.TEXT, bold = true).apply {
            gravity = Gravity.CENTER; setBackgroundColor(Color.WHITE)
        }
        fun paint() {
            plus.setBackgroundColor(if (p.likedByMe) 0xFF008C79.toInt() else UiUtil.PLUS)
            plus.setTypeface(null, if (p.likedByMe) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            minus.setBackgroundColor(if (p.dislikedByMe) 0xFF7A3FD6.toInt() else UiUtil.MINUS)
            minus.setTypeface(null, if (p.dislikedByMe) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            count.text = p.score.toString()
        }
        paint()
        val vote = { dislike: Boolean ->
            host.repo.pool.execute {
                val r = host.repo.like(p.id, dislike)
                host.activity.runOnUiThread {
                    if (r == null) { Toast.makeText(ctx, "Не удалось поставить оценку", Toast.LENGTH_SHORT).show(); return@runOnUiThread }
                    p.likedByMe = r.liked; p.dislikedByMe = r.disliked
                    p.likeCount = r.likeCount; p.dislikeCount = r.dislikeCount
                    if (post === p) paint()
                }
            }
        }
        plus.setOnClickListener { vote(false) }
        minus.setOnClickListener { vote(true) }
        // Зажатие числа — общее состояние: сколько «+» и «−» поставили.
        count.setOnLongClickListener {
            AlertDialog.Builder(ctx).setMessage("Плюсов: ${p.likeCount}\nМинусов: ${p.dislikeCount}")
                .setPositiveButton("Закрыть", null).show()
            true
        }
        scoreHolder.addView(plus, UiUtil.lp(0, UiUtil.MATCH, 1f))
        scoreHolder.addView(count, UiUtil.lp(0, UiUtil.MATCH, 1.1f))
        scoreHolder.addView(minus, UiUtil.lp(0, UiUtil.MATCH, 1f))
    }

    // ------------------------------------------------------------------ комментарии (1.7)

    private fun buildCommentsButton(p: Post) {
        commentsHolder.removeAllViews()
        commentsHolder.visibility = if (showComments) View.VISIBLE else View.GONE
        if (!showComments) return
        val ctx = context
        val t = UiUtil.text(ctx, 15f, Color.WHITE, bold = true).apply {
            text = "Смотреть комментарии" + if (p.commentCount > 0) " (${p.commentCount})" else ""
            gravity = Gravity.CENTER
            background = UiUtil.royal(ctx)
            setPadding(dp(ctx, 12), dp(ctx, 11), dp(ctx, 12), dp(ctx, 11))
            setShadowLayer(2f, 0f, 1f, 0x99000000.toInt())
        }
        t.setOnClickListener { host.openPost(p) }
        commentsHolder.addView(t)
    }
}
