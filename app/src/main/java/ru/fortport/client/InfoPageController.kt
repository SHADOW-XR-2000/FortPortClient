package ru.fortport.client

import android.content.Context
import android.text.Html
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.method.LinkMovementMethod
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Нативные текстовые страницы «Поддержка», «О нас», «Конфиденциальность», «Условия» (/support, /about, /privacy, /terms).
 *
 * У страниц нет своего API: в логах только запросы шапки сайта, а сам текст лежит в HTML страницы. Поэтому текст берётся
 * запросом GET {путь} и разбирается в блоки: заголовки (h1–h6), абзацы, пункты списков, горизонтальные линии. Жирный текст
 * и ссылки (http, mailto, tel) сохраняются. Последняя удачно загруженная версия кэшируется — страница открывается без сети.
 *
 * Разбор не завязан на классы и id сайта: берётся всё от первого <h1> (всё, что выше, — шапка сайта) до <footer> или конца страницы.
 * Если разметка страниц изменится так, что блоки не найдутся, показывается сообщение с кнопкой «Повторить».
 */
class InfoPageController(
    private val activity: AppCompatActivity,
    private val api: SiteApi,
    private val host: FrameLayout
) {
    private companion object {
        const val TAG = "InfoPage"
        const val PREFS = "info_pages"
        const val TOPBAR_DP = 70
        const val ORIGIN = "https://fortport.ru"
    }

    private enum class Kind { HEADING, PARAGRAPH, BULLET, RULE }
    private class Block(val kind: Kind, val level: Int, val text: CharSequence)

    private val d = activity.resources.displayMetrics.density
    private fun dp(v: Int) = (v * d + 0.5f).toInt()
    private val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private var path = ""
    private var started = false
    private var loaded = false
    private var generation = 0
    private var shownHtml: String? = null

    private val scroll = ScrollView(activity)
    private val content = LinearLayout(activity)

    init {
        scroll.clipToPadding = false
        scroll.setPadding(0, dp(TOPBAR_DP), 0, dp(28))
        content.orientation = LinearLayout.VERTICAL
        scroll.addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        host.addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    // ------------------------------------------------------------------ жизненный цикл

    /** Какую страницу показывать. Смена страницы сбрасывает состояние. */
    fun setPage(p: String) {
        if (p == path) return
        path = p
        generation++
        loaded = false
        shownHtml = null
        content.removeAllViews()
    }

    fun start(userId: String, deviceId: String, userAgent: String) {
        api.setSession(userId, deviceId, userAgent)
        started = true
        if (loaded) return
        val cached = prefs.getString(path, null)
        if (cached != null) show(cached)
        load(hasCache = cached != null)
    }

    fun stop() {
        started = false
        generation++
    }

    fun destroy() { stop() }

    // ------------------------------------------------------------------ загрузка

    private fun load(hasCache: Boolean) {
        val gen = ++generation
        val p = path
        if (!hasCache) message("Загрузка…", retry = false)
        api.io.execute {
            var html: String? = null
            var err = "Не удалось загрузить страницу."
            try {
                val r = api.get(p)
                when {
                    r.code in 200..299 && r.body.isNotBlank() -> html = r.body
                    r.code == 404 -> err = "Страница не найдена."
                }
            } catch (e: Exception) {
                Log.w(TAG, "GET $p", e)
                err = "Нет связи с сервером."
            }
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed || gen != generation || !started) return@runOnUiThread
                val h = html
                when {
                    h == null -> if (!hasCache) message(err, retry = true) // есть кэш — он уже на экране
                    parse(h).isEmpty() -> if (!hasCache) message("Не удалось разобрать страницу.", retry = true)
                    else -> {
                        prefs.edit().putString(p, h).apply()
                        if (h != shownHtml) show(h)
                    }
                }
            }
        }
    }

    private fun message(text: String, retry: Boolean) {
        content.removeAllViews()
        content.addView(UiUtil.text(activity, 16f, AeroUi.labelColor(activity)).apply {
            this.text = text
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(48), dp(24), dp(16))
        }, UiUtil.lp(UiUtil.MATCH, UiUtil.WRAP))
        if (retry) {
            content.addView(UiUtil.text(activity, 18f, AeroButton.textColor(activity), bold = true).apply {
                this.text = "Повторить"
                gravity = Gravity.CENTER
                AeroButton.style(this)
                setPadding(dp(8), dp(14), dp(8), dp(14))
                setOnClickListener { load(hasCache = false) }
            }, UiUtil.lp(UiUtil.MATCH, dp(52)).apply { setMargins(dp(24), dp(8), dp(24), 0) })
        }
    }

    // ------------------------------------------------------------------ отрисовка

    private fun show(html: String) {
        val blocks = parse(html)
        if (blocks.isEmpty()) return
        shownHtml = html
        loaded = true
        content.removeAllViews()
        val label = AeroUi.labelColor(activity)
        val night = UiUtil.isNight(activity)
        val link = if (night) 0xFF7FB6FF.toInt() else 0xFF1E6FD9.toInt()

        val bubble = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = AeroUi.panelBackground(activity)
            setPadding(dp(16), dp(14), dp(16), dp(18))
        }
        var first = true
        for (b in blocks) {
            val top = if (first) 0 else when (b.kind) {
                Kind.HEADING -> if (b.level <= 2) 22 else 14
                Kind.BULLET -> 6
                Kind.RULE -> 14
                Kind.PARAGRAPH -> 10
            }
            first = false
            val v: View = when (b.kind) {
                Kind.HEADING -> UiUtil.text(activity, when (b.level) { 1 -> 24f; 2 -> 19f; else -> 16f }, AeroUi.titleColor(activity), bold = true).apply {
                    text = b.text.toString()
                    if (b.level == 1) gravity = Gravity.CENTER
                }
                Kind.PARAGRAPH -> body(b.text, label, link)
                Kind.BULLET -> LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(UiUtil.text(activity, 15f, label).apply { text = "•"; setPadding(dp(4), 0, dp(8), 0) }, UiUtil.lp(UiUtil.WRAP, UiUtil.WRAP))
                    addView(body(b.text, label, link), UiUtil.lp(0, UiUtil.WRAP, 1f))
                }
                Kind.RULE -> View(activity).apply { setBackgroundColor(label); alpha = 0.25f }
            }
            val lp = if (b.kind == Kind.RULE) UiUtil.lp(UiUtil.MATCH, 1.coerceAtLeast(dp(1))) else UiUtil.lp(UiUtil.MATCH, UiUtil.WRAP)
            lp.topMargin = dp(top)
            if (b.kind == Kind.RULE) lp.bottomMargin = dp(2)
            bubble.addView(v, lp)
        }
        content.addView(bubble, UiUtil.lp(UiUtil.MATCH, UiUtil.WRAP).apply { setMargins(dp(10), dp(12), dp(10), 0) })
        scroll.scrollTo(0, 0)
    }

    private fun body(text: CharSequence, label: Int, link: Int): TextView = UiUtil.text(activity, 15f, label).apply {
        this.text = text
        setLineSpacing(0f, 1.15f)
        setLinkTextColor(link)
        movementMethod = LinkMovementMethod.getInstance()
    }

    // ------------------------------------------------------------------ разбор HTML

    private fun parse(html: String): List<Block> {
        var s = html
        // Лишнее: комментарии и всё, что не текст.
        s = s.replace(Regex("(?s)<!--.*?-->"), "")
        s = s.replace(Regex("(?is)<(script|style|noscript|svg|template|button|select|form|iframe|nav)\\b.*?</\\1\\s*>"), "")
        // Контент начинается с первого <h1> (выше — шапка сайта), заканчивается <footer> либо концом страницы.
        val h1 = Regex("(?i)<h1\\b").find(s)?.range?.first
        val bodyStart = Regex("(?i)<body\\b[^>]*>").find(s)?.range?.last?.plus(1)
        s = s.substring(h1 ?: bodyStart ?: 0)
        Regex("(?i)<footer\\b").find(s)?.range?.first?.let { if (it > 0) s = s.substring(0, it) }
        s = s.replace(Regex("(?i)</body>.*"), "")
        // Ссылки без домена — на сайт.
        s = s.replace(Regex("(?i)href\\s*=\\s*\"(/[^\"]*)\""), "href=\"$ORIGIN$1\"")
        // Границы блоков: маркер \u0000 + тип + \u0001. Вложенные списки и div-ы это переносит без потерь.
        s = s.replace(Regex("(?i)<h([1-6])\\b[^>]*>"), "\u0000H$1\u0001")
        s = s.replace(Regex("(?i)<li\\b[^>]*>"), "\u0000L\u0001")
        s = s.replace(Regex("(?i)<hr\\b[^>]*>"), "\u0000R\u0001")
        s = s.replace(Regex("(?i)<(p|div|section|article|main|blockquote|tr)\\b[^>]*>"), "\u0000P\u0001")
        s = s.replace(Regex("(?i)</(h[1-6]|p|li|div|section|article|main|ul|ol|blockquote|tr|table)\\s*>"), "\u0000P\u0001")

        val out = ArrayList<Block>()
        for (chunk in s.split('\u0000')) {
            val i = chunk.indexOf('\u0001')
            if (i < 1) continue
            val type = chunk.substring(0, i)
            val raw = chunk.substring(i + 1)
            if (type == "R") { out.add(Block(Kind.RULE, 0, "")); continue }
            val text = trim(Html.fromHtml(raw, Html.FROM_HTML_MODE_COMPACT))
            if (text.isEmpty()) continue
            out.add(
                when {
                    type.startsWith("H") -> Block(Kind.HEADING, type.substring(1).toInt(), text)
                    type == "L" -> Block(Kind.BULLET, 0, text)
                    else -> Block(Kind.PARAGRAPH, 0, text)
                }
            )
        }
        // Линии в начале/конце и подряд идущие не нужны.
        val cleaned = ArrayList<Block>()
        for (b in out) {
            if (b.kind == Kind.RULE && (cleaned.isEmpty() || cleaned.last().kind == Kind.RULE)) continue
            cleaned.add(b)
        }
        while (cleaned.isNotEmpty() && cleaned.last().kind == Kind.RULE) cleaned.removeAt(cleaned.size - 1)
        return cleaned
    }

    private fun trim(t: CharSequence): CharSequence {
        val sb = SpannableStringBuilder(t)
        while (sb.isNotEmpty() && sb[sb.length - 1].isWhitespace()) sb.delete(sb.length - 1, sb.length)
        while (sb.isNotEmpty() && sb[0].isWhitespace()) sb.delete(0, 1)
        return sb as Spannable
    }
}
