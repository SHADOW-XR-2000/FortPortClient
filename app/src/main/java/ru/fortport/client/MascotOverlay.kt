package ru.fortport.client

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.webkit.WebViewAssetLoader
import org.json.JSONObject

/**
 * Маскот на нативных экранах (раньше он жил только в веб-версии сайта).
 *
 * Анимации — прозрачные WebM с альфа-каналом (assets/ui/mascot/mascot-*.webm). Android-проигрыватели
 * альфа-канал VP9 не показывают (вместо прозрачности был бы чёрный фон), его умеет только Chromium,
 * поэтому маскот — маленький прозрачный WebView со страницей assets/ui/mascot/mascot.html.
 *
 * Поведение (настройки — «Кастомизация → Маскот», хранятся в prefs "settings_ui"):
 *  - mascot_enabled — показывать ли маскота вообще;
 *  - mascot_hints   — подсказки о страницах (по умолчанию — handHolding с сервера, GET /api/session/bootstrap);
 *  - касание — поглаживание (blush), частые касания — недовольство (yell); счётчик хранится на устройстве;
 *  - mascot.bricked с сервера — маскот «убит» (killed) и молчит.
 *
 * Тексты подсказок и соответствие анимаций событиям — рабочие, их можно править в [HINTS] и [onTap].
 */
class MascotOverlay(
    private val activity: AppCompatActivity,
    private val root: ConstraintLayout,
    private val api: SiteApi,
    private val anchorAboveId: Int
) {
    companion object {
        private const val HOST = "appassets.androidplatform.net"
        private const val PAGE = "https://$HOST/assets/ui/mascot/mascot.html"
        private const val W_DP = 84
        private const val H_DP = 120

        /** Экраны, на которых маскота нет (закрывал бы поле ввода или кнопки): страница поста, настройки, уведомления. */
        private val HIDDEN_ON = setOf("post", "settings", "notifs")

        val HINTS = mapOf(
            "feed" to "Это лента. Потяните вниз, чтобы обновить её.",
            "profile" to "Это страница пользователя. «Подробнее» раскрывает остальную информацию.",
            "port" to "Это порт — сообщество. Здесь можно подписаться и читать его посты.",
            "chats" to "Здесь ваши переписки. Нажмите на диалог, чтобы открыть его.",
            "friends" to "Здесь друзья и заявки в друзья.",
            "communities" to "Здесь порты — сообщества.",
            "music" to "Здесь музыка. Она продолжит играть, даже если выйти из приложения."
        )
    }

    private val prefs = activity.getSharedPreferences("settings_ui", Context.MODE_PRIVATE)
    private val ui = Handler(Looper.getMainLooper())
    private val d = activity.resources.displayMetrics.density
    private fun dp(v: Int) = (v * d + 0.5f).toInt()

    private val bubble = TextView(activity).apply {
        textSize = 13f
        setTextColor(0xFF15141C.toInt())
        setTypeface(typeface, Typeface.NORMAL)
        maxWidth = dp(210)
        setPadding(dp(12), dp(8), dp(12), dp(8))
        background = UiUtil.rect(Color.WHITE, 0xFF1B2A4A.toInt(), 2, 12, activity)
        visibility = View.GONE
    }

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    private val web = WebView(activity).apply {
        setBackgroundColor(Color.TRANSPARENT)
        overScrollMode = View.OVER_SCROLL_NEVER
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        settings.javaScriptEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.allowFileAccess = false
        settings.allowContentAccess = false
    }

    private val box = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.END
        elevation = dp(12).toFloat()
        visibility = View.GONE
        addView(bubble, LinearLayout.LayoutParams(UiUtil.WRAP, UiUtil.WRAP).apply { bottomMargin = dp(2) })
        addView(web, LinearLayout.LayoutParams(dp(W_DP), dp(H_DP)))
    }

    private var pageReady = false
    private var wantedAnim: Pair<String, Boolean>? = null
    private var shownThisSession = false
    private val hinted = HashSet<String>()
    private var section: String? = null
    private var bricked = false
    private var serverHints = true
    private var bootstrapAsked = false
    private var recentTaps = 0
    private var lastTap = 0L
    private var bubbleUntil = 0L
    private val hideBubble = Runnable { bubble.visibility = View.GONE; playIdle() }

    init {
        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(activity))
            .build()
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                // Только свои файлы из assets: в сеть маскот не ходит.
                if (request.url.host != HOST) return WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                return loader.shouldInterceptRequest(request.url)
            }
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
            override fun onPageFinished(view: WebView, url: String?) { pageReady = true; applyAnim() }
        }
        web.addJavascriptInterface(object {
            @JavascriptInterface fun ended(name: String) { ui.post { onEnded(name) } }
            @JavascriptInterface fun failed(name: String) { ui.post { onFailed(name) } }
        }, "Mascot")
        val gd = GestureDetector(activity, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onSingleTapUp(e: MotionEvent): Boolean { onTap(); return true }
        })
        web.setOnTouchListener { _, e -> gd.onTouchEvent(e); true }

        val lp = ConstraintLayout.LayoutParams(UiUtil.WRAP, UiUtil.WRAP).apply {
            endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
            bottomToTop = anchorAboveId
            rightMargin = dp(6)
        }
        box.translationY = dp(6).toFloat() // «стоит» на нижней панели
        root.addView(box, lp)
        web.loadUrl(PAGE)
    }

    // ------------------------------------------------------------------ настройки

    private fun enabled() = prefs.getBoolean("mascot_enabled", true)
    private fun hintsOn() = prefs.getBoolean("mascot_hints", serverHints)

    // ------------------------------------------------------------------ экраны

    /** Вызывается при смене нативного экрана: [name] — "feed", "profile", … или null (нативного экрана нет). */
    fun onSection(name: String?) {
        section = name
        if (name == null || name in HIDDEN_ON || !enabled()) { hide(); return }
        if (!bootstrapAsked) askServer()
        val firstShow = box.visibility != View.VISIBLE
        box.visibility = View.VISIBLE
        if (bricked) { play("killed", true); return }
        var waved = false
        if (firstShow && !shownThisSession) {
            shownThisSession = true
            waved = true
            play("wave", false)
        } else if (firstShow) playIdle()
        val text = HINTS[name]
        // подсказка не перебивает приветственное «помахал»: после него маскот сам перейдёт в «думает» на время подсказки
        if (text != null && hintsOn() && hinted.add(name)) say(text, animate = !waved)
    }

    fun hide() {
        ui.removeCallbacks(hideBubble)
        bubble.visibility = View.GONE
        box.visibility = View.GONE
    }

    fun onResume() { web.onResume() }
    fun onPause() { web.onPause() }

    fun destroy() {
        ui.removeCallbacksAndMessages(null)
        root.removeView(box)
        web.removeJavascriptInterface("Mascot")
        web.destroy()
    }

    // ------------------------------------------------------------------ поведение

    private fun say(text: String, ms: Long = 6500, animate: Boolean = true) {
        bubble.text = text
        bubble.visibility = View.VISIBLE
        bubbleUntil = System.currentTimeMillis() + ms
        ui.removeCallbacks(hideBubble)
        ui.postDelayed(hideBubble, ms)
        if (animate && !bricked) play("think", true)
    }

    private fun onTap() {
        if (bricked) { say("…", 1500, animate = false); return }
        val now = System.currentTimeMillis()
        recentTaps = if (now - lastTap < 2000) recentTaps + 1 else 1
        lastTap = now
        val total = prefs.getInt("mascot_pets", 0) + 1
        prefs.edit().putInt("mascot_pets", total).apply()
        if (recentTaps >= 5) {
            recentTaps = 0
            play("yell", false)
            say("Эй, хватит!", 2500, animate = false)
        } else {
            play("blush", false)
            say("Погладили раз: $total", 2200, animate = false)
        }
    }

    private fun onEnded(name: String) {
        // Разовые анимации заканчиваются возвратом в покой (или в «думает», пока висит подсказка).
        if (name == "stand" || name == "think" || name == "killed") return
        playIdle()
    }

    private fun onFailed(name: String) {
        // Файла нет или не проигрывается — не зацикливаемся: просто прячем подсказку и остаёмся как есть.
        if (name != "stand") playIdle()
    }

    private fun playIdle() {
        if (bricked) { play("killed", true); return }
        if (bubble.visibility == View.VISIBLE) play("think", true) else play("stand", true)
    }

    private fun play(name: String, loop: Boolean) {
        wantedAnim = name to loop
        applyAnim()
    }

    private fun applyAnim() {
        val (name, loop) = wantedAnim ?: return
        if (!pageReady) return
        web.evaluateJavascript("play('$name',$loop)", null)
    }

    private fun askServer() {
        bootstrapAsked = true
        if (api.isDemo) return
        api.io.execute {
            try {
                val r = api.get("/api/session/bootstrap")
                if (r.code != 200) return@execute
                val m = JSONObject(r.body).optJSONObject("mascot") ?: return@execute
                ui.post {
                    serverHints = m.optBoolean("handHolding", true)
                    bricked = m.optInt("bricked", 0) == 1 || m.optBoolean("bricked", false)
                    if (bricked && box.visibility == View.VISIBLE) play("killed", true)
                }
            } catch (e: Exception) {
                bootstrapAsked = false // не вышло (нет сети) — спросим при следующем экране
            }
        }
    }
}
