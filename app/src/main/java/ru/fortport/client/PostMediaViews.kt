package ru.fortport.client

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.MediaPlayer
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.MediaController
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import org.json.JSONArray
import org.json.JSONObject
import ru.fortport.client.UiUtil.dp
import kotlin.math.abs

/**
 * Блок медиа поста (1.4 концепции): фото / видео / GIF по одному с переходом
 * (стрелки и свайп), тап по фото открывает нативный просмотрщик.
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class MediaCarouselView(
    context: Context,
    private val api: SiteApi,
    private val items: List<MediaItem>
) : FrameLayout(context) {

    private var index = 0
    private val content = FrameLayout(context)
    private val counter = UiUtil.text(context, 12f, Color.WHITE, bold = true)
    private val prev = arrow("‹")
    private val next = arrow("›")
    private var video: VideoView? = null

    private val detector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onSingleTapUp(e: MotionEvent): Boolean { openViewer(); return true }
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (e1 == null || abs(vx) < abs(vy) * 1.5f || abs(e2.x - e1.x) < dp(context, 60)) return false
            go(if (e2.x < e1.x) 1 else -1)
            return true
        }
    })

    init {
        setBackgroundColor(0xFF0B0F1A.toInt())
        minimumHeight = dp(context, 160)
        addView(content, UiUtil.frameLp(UiUtil.MATCH, UiUtil.WRAP))
        counter.setBackgroundColor(0x99000000.toInt())
        counter.setPadding(dp(context, 8), dp(context, 2), dp(context, 8), dp(context, 2))
        addView(counter, UiUtil.frameLp(UiUtil.WRAP, UiUtil.WRAP, Gravity.END or Gravity.BOTTOM))
        addView(prev, UiUtil.frameLp(dp(context, 44), dp(context, 44), Gravity.START or Gravity.CENTER_VERTICAL))
        addView(next, UiUtil.frameLp(dp(context, 44), dp(context, 44), Gravity.END or Gravity.CENTER_VERTICAL))
        prev.setOnClickListener { go(-1) }
        next.setOnClickListener { go(1) }
        show()
    }

    private fun arrow(s: String) = TextView(context).apply {
        text = s; textSize = 28f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
        background = UiUtil.rect(0x66000000, 0, 0, 22, context)
        visibility = if (items.size > 1) View.VISIBLE else View.GONE
    }

    override fun onTouchEvent(event: MotionEvent): Boolean = detector.onTouchEvent(event) || super.onTouchEvent(event)

    /** Останавливает видео (вызывать при уходе карточки с экрана). */
    fun release() {
        video?.let { try { it.stopPlayback() } catch (_: Exception) {} }
        video = null
    }

    private fun go(d: Int) {
        if (items.size < 2) return
        index = (index + d + items.size) % items.size
        show()
    }

    private fun show() {
        release()
        content.removeAllViews()
        val m = items[index]
        counter.text = "${index + 1}/${items.size}"
        counter.visibility = if (items.size > 1) View.VISIBLE else View.GONE
        if (m.type == "video") showVideoPoster(m) else showImage(m)
    }

    private fun showImage(m: MediaItem) {
        val iv = ImageView(context).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            maxHeight = dp(context, 460)
            minimumHeight = dp(context, 160)
        }
        content.addView(iv, UiUtil.frameLp(UiUtil.MATCH, UiUtil.WRAP, Gravity.CENTER))
        api.loadImage(iv, m.path, 1280)
        if (m.type == "gif") {
            content.addView(
                UiUtil.text(context, 11f, Color.WHITE, bold = true).apply {
                    text = "GIF"; setBackgroundColor(0x99000000.toInt())
                    setPadding(dp(context, 6), dp(context, 2), dp(context, 6), dp(context, 2))
                },
                UiUtil.frameLp(UiUtil.WRAP, UiUtil.WRAP, Gravity.START or Gravity.TOP)
            )
        }
    }

    private fun showVideoPoster(m: MediaItem) {
        val box = FrameLayout(context).apply { minimumHeight = dp(context, 220) }
        val play = TextView(context).apply {
            text = "▶"; textSize = 40f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
        }
        box.addView(play, UiUtil.frameLp(UiUtil.MATCH, dp(context, 220), Gravity.CENTER))
        content.addView(box, UiUtil.frameLp(UiUtil.MATCH, UiUtil.WRAP))
        box.setOnClickListener { startVideo(box, m) }
    }

    private fun startVideo(box: FrameLayout, m: MediaItem) {
        box.removeAllViews()
        val vv = VideoView(context)
        val spinner = ProgressBar(context)
        box.addView(vv, UiUtil.frameLp(UiUtil.MATCH, dp(context, 260), Gravity.CENTER))
        box.addView(spinner, UiUtil.frameLp(dp(context, 40), dp(context, 40), Gravity.CENTER))
        val mc = MediaController(context)
        mc.setAnchorView(vv)
        vv.setMediaController(mc)
        vv.setOnPreparedListener { spinner.visibility = View.GONE; vv.start() }
        vv.setOnErrorListener { _, _, _ ->
            spinner.visibility = View.GONE
            Toast.makeText(context, "Не удалось воспроизвести видео", Toast.LENGTH_SHORT).show()
            true
        }
        vv.setVideoURI(Uri.parse(api.absUrl(m.path)), api.mediaHeaders())
        video = vv
    }

    private fun openViewer() {
        val cur = items[index]
        if (cur.type == "video") return
        val arr = JSONArray()
        items.forEach { m ->
            arr.put(
                JSONObject().put("url", api.absUrl(m.path)).put("type", m.type)
                    .put("mediaId", m.id ?: JSONObject.NULL)
            )
        }
        val payload = JSONObject().put("items", arr).put("index", index)
            .put("deviceId", api.deviceId).put("ua", api.userAgent).toString()
        context.startActivity(
            Intent(context, PhotoViewerActivity::class.java).putExtra(PhotoViewerActivity.EXTRA_PAYLOAD, payload)
        )
    }
}

/** Единственный активный встроенный аудиоплеер для постов и комментариев (громкость — системная, 100%). */
object InlineAudio {

    interface Listener {
        fun onState(playing: Boolean)
        fun onProgress(pos: Int, dur: Int)
    }

    private var mp: MediaPlayer? = null
    private var key: String? = null
    private var listener: Listener? = null
    private var prepared = false
    private val ui = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            val p = mp ?: return
            if (prepared) try { listener?.onProgress(p.currentPosition, p.duration) } catch (_: Exception) {}
            ui.postDelayed(this, 400)
        }
    }

    fun isActive(k: String) = key == k

    fun attach(k: String, l: Listener) { if (key == k) listener = l }

    fun toggle(ctx: Context, api: SiteApi, url: String, k: String, l: Listener) {
        val cur = mp
        if (cur != null && key == k) {
            listener = l
            if (!prepared) return
            if (cur.isPlaying) { cur.pause(); l.onState(false) } else { cur.start(); l.onState(true) }
            return
        }
        stop()
        key = k
        listener = l
        prepared = false
        val p = MediaPlayer()
        mp = p
        try {
            p.setDataSource(ctx, Uri.parse(url), api.mediaHeaders())
            p.setOnPreparedListener { prepared = true; it.start(); listener?.onState(true); ui.post(ticker) }
            p.setOnCompletionListener { listener?.onState(false); listener?.onProgress(0, 1) }
            p.setOnErrorListener { _, _, _ ->
                Toast.makeText(ctx, "Не удалось воспроизвести аудио", Toast.LENGTH_SHORT).show()
                stop(); true
            }
            p.prepareAsync()
        } catch (e: Exception) {
            stop()
        }
    }

    fun seekTo(k: String, fraction: Float) {
        val p = mp ?: return
        if (key == k && prepared) p.seekTo((p.duration * fraction).toInt())
    }

    fun stop() {
        ui.removeCallbacks(ticker)
        listener?.onState(false)
        try { mp?.release() } catch (_: Exception) {}
        mp = null; key = null; listener = null; prepared = false
    }
}

/** Строка аудио в посте/комментарии: рамка, play/pause, тайм-лайн, меню «сохранить себе / скачать». */
@SuppressLint("ViewConstructor")
class AudioRowView(
    context: Context,
    private val api: SiteApi,
    private val repo: PostsRepo,
    private val item: MediaItem
) : LinearLayout(context), InlineAudio.Listener {

    private val key = "audio:" + item.path
    private val play = TextView(context)
    private val seek = SeekBar(context)
    private val time = UiUtil.text(context, 12f, UiUtil.SUB)
    private var userSeeking = false

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = UiUtil.rect(0xFFF2FAFF.toInt(), 0xFF8FDADB.toInt(), 2, 10, context)
        setPadding(dp(context, 8), dp(context, 6), dp(context, 4), dp(context, 6))

        play.apply {
            text = "▶"; textSize = 18f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
            background = UiUtil.rect(0xFF3D7BFF.toInt(), 0, 0, 20, context)
        }
        addView(play, lp(dp(context, 40), dp(context, 40)))

        val mid = LinearLayout(context).apply { orientation = VERTICAL }
        mid.addView(
            UiUtil.text(context, 14f, UiUtil.TEXT, bold = true, maxLines = 1).apply {
                text = item.name.ifBlank { "Аудио" }
            }
        )
        val line = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        line.addView(seek, UiUtil.lp(0, UiUtil.WRAP, 1f))
        line.addView(time)
        mid.addView(line)
        addView(mid, UiUtil.lp(0, UiUtil.WRAP, 1f).apply { marginStart = dp(context, 8) })

        val more = TextView(context).apply {
            text = "⋮"; textSize = 22f; gravity = Gravity.CENTER; setTextColor(UiUtil.SUB)
        }
        addView(more, lp(dp(context, 36), dp(context, 40)))
        more.setOnClickListener { menu(it) }

        seek.max = 1000
        time.text = "0:00"
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {}
            override fun onStartTrackingTouch(s: SeekBar) { userSeeking = true }
            override fun onStopTrackingTouch(s: SeekBar) { userSeeking = false; InlineAudio.seekTo(key, s.progress / 1000f) }
        })
        play.setOnClickListener { InlineAudio.toggle(context, api, api.absUrl(item.path), key, this) }
        if (InlineAudio.isActive(key)) InlineAudio.attach(key, this)
    }

    private fun lp(w: Int, h: Int) = LayoutParams(w, h)

    override fun onState(playing: Boolean) { play.text = if (playing) "❚❚" else "▶" }

    override fun onProgress(pos: Int, dur: Int) {
        if (dur <= 0) return
        if (!userSeeking) seek.progress = (pos * 1000L / dur).toInt()
        time.text = fmt(pos) + " / " + fmt(dur)
    }

    private fun fmt(ms: Int): String { val s = ms / 1000; return "%d:%02d".format(s / 60, s % 60) }

    private fun menu(anchor: View) {
        PopupMenu(context, anchor).apply {
            menu.add(0, 1, 0, "Сохранить себе")
            menu.add(0, 2, 1, "Скачать на устройство")
            setOnMenuItemClickListener {
                when (it.itemId) {
                    1 -> save()
                    2 -> download()
                }
                true
            }
        }.show()
    }

    private fun save() {
        val id = item.id ?: Regex("/audio/(\\d+)").find(item.path)?.groupValues?.get(1)?.toLongOrNull() ?: return
        repo.pool.execute {
            val r = repo.saveAudio(id)
            post { Toast.makeText(context, if (r.ok) "Добавлено в вашу музыку" else r.error.ifBlank { "Не удалось добавить" }, Toast.LENGTH_SHORT).show() }
        }
    }

    private fun download() {
        try {
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val name = (item.name.ifBlank { "audio" }).replace(Regex("[\\\\/:*?\"<>|]"), "_") + ".mp3"
            val req = DownloadManager.Request(Uri.parse(api.absUrl(item.path)))
                .setTitle(name)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            api.mediaHeaders().forEach { (k, v) -> req.addRequestHeader(k, v) }
            try {
                req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            } catch (e: Exception) {
                req.setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, name)
            }
            dm.enqueue(req)
            Toast.makeText(context, "Загрузка началась", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(context, "Не удалось скачать", Toast.LENGTH_SHORT).show()
        }
    }
}

/** Опрос в посте: до голосования — варианты, после — проценты; «Отменить голос». */
@SuppressLint("ViewConstructor")
class PollView(
    context: Context,
    private val repo: PostsRepo,
    private val data: Post,
    private val onChanged: () -> Unit
) : LinearLayout(context) {

    private val selected = HashSet<Long>()

    init {
        orientation = VERTICAL
        setPadding(dp(context, 10), dp(context, 8), dp(context, 10), dp(context, 8))
        render()
    }

    private fun expired(p: Poll) = p.expiresAt != null && p.expiresAt * 1000 < System.currentTimeMillis()

    private fun render() {
        removeAllViews()
        val p = data.poll ?: return
        addView(UiUtil.text(context, 16f, UiUtil.TEXT, bold = true).apply { text = p.title })
        val showResults = p.hasVoted || expired(p)
        p.choices.forEach { c -> addView(choiceRow(p, c, showResults)) }

        val foot = UiUtil.text(context, 12f, UiUtil.SUB)
        foot.text = buildString {
            append("Голосов: ${p.totalVotes}")
            if (p.multiChoice) append(" · можно несколько")
            p.expiresAt?.let { append(if (expired(p)) " · завершён" else " · до " + UiUtil.formatDate(it * 1000)) }
        }
        addView(foot, UiUtil.lp(UiUtil.WRAP, UiUtil.WRAP).apply { topMargin = dp(context, 6) })

        if (!showResults && p.multiChoice) {
            addView(button("Проголосовать") { sendVotes(p, selected.toList()) })
        } else if (p.hasVoted && !expired(p)) {
            addView(button("Отменить голос") { undo(p) })
        }
    }

    private fun button(t: String, click: () -> Unit) = TextView(context).apply {
        text = t; textSize = 14f; gravity = Gravity.CENTER; setTextColor(0xFF1B6FD1.toInt())
        background = UiUtil.rect(Color.WHITE, 0xFF29A3FF.toInt(), 1, 8, context)
        setPadding(dp(context, 12), dp(context, 8), dp(context, 12), dp(context, 8))
        layoutParams = UiUtil.lp(UiUtil.MATCH, UiUtil.WRAP).apply { topMargin = dp(context, 6) }
        setOnClickListener { click() }
    }

    private fun choiceRow(p: Poll, c: PollChoice, results: Boolean): View {
        val row = FrameLayout(context)
        row.layoutParams = UiUtil.lp(UiUtil.MATCH, UiUtil.WRAP).apply { topMargin = dp(context, 6) }
        val percent = if (results && p.totalVotes > 0) (c.votes * 100f / p.totalVotes).toInt() else 0
        val on = c.userVoted || c.id in selected
        row.background = UiUtil.rect(if (on && !results) 0xFFD7ECFF.toInt() else 0xFFF6F8FA.toInt(), if (c.userVoted) 0xFF3D7BFF.toInt() else 0xFFCCCFE0.toInt(), 1, 8, context)
        if (results) {
            val bar = View(context).apply { background = UiUtil.rect(0x553D7BFF, 0, 0, 8, context) }
            row.addView(bar, UiUtil.frameLp(0, UiUtil.MATCH))
            row.post { bar.layoutParams = bar.layoutParams.apply { width = row.width * percent / 100 }; bar.requestLayout() }
        }
        val line = LinearLayout(context).apply {
            orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(context, 10), dp(context, 9), dp(context, 10), dp(context, 9))
        }
        line.addView(
            UiUtil.text(context, 14f, UiUtil.TEXT).apply { text = (if (c.userVoted) "✓ " else "") + c.text },
            UiUtil.lp(0, UiUtil.WRAP, 1f)
        )
        if (results) line.addView(UiUtil.text(context, 13f, UiUtil.SUB, bold = true).apply { text = "$percent%" })
        row.addView(line, UiUtil.frameLp(UiUtil.MATCH, UiUtil.WRAP))
        if (!results) {
            row.setOnClickListener {
                if (p.multiChoice) {
                    if (!selected.add(c.id)) selected.remove(c.id)
                    render()
                } else sendVotes(p, listOf(c.id))
            }
        }
        return row
    }

    private fun sendVotes(p: Poll, ids: List<Long>) {
        if (ids.isEmpty()) return
        repo.pool.execute {
            val r = repo.vote(ids)
            val fresh = if (r.ok) repo.loadPost(data.id)?.poll else null
            post { finish(r, fresh, "Не удалось проголосовать") }
        }
    }

    private fun undo(p: Poll) {
        val ids = p.choices.filter { it.userVoted }.map { it.id }
        repo.pool.execute {
            var ok = true; var err = ""
            for (id in ids) { val r = repo.unvote(id); if (!r.ok) { ok = false; err = r.error; break } }
            val fresh = if (ok) repo.loadPost(data.id)?.poll else null
            post { finish(Outcome(ok, err), fresh, "Ошибка при отмене голоса") }
        }
    }

    private fun finish(r: Outcome, fresh: Poll?, fail: String) {
        if (!r.ok) { Toast.makeText(context, r.error.ifBlank { fail }, Toast.LENGTH_SHORT).show(); return }
        selected.clear()
        if (fresh != null) data.poll = fresh
        render()
        onChanged()
    }
}
