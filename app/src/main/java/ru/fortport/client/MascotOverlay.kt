package ru.fortport.client

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.webkit.WebViewAssetLoader
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Жека — маскот на нативных экранах. Это перенос сценария сайта (mascot.js) в приложение:
 *
 *  - клик по маскоту: приветствие → фразы про страницу со статистикой (профиль, порт) → случайные фразы;
 *    у фраз есть эмоции (/E0/…/E7/ → peek, lookout, wave, killed, stand, think, blush, yell);
 *  - новости от сервера (/api/users/mascot/news) читаются по клику и помечаются прочитанными;
 *  - подсказки «hand holding» по страницам (лента, порты, порт, профиль, настройки), один раз на страницу,
 *    пока не нажато «Я всё понял!»; включаются настройкой «Подсказки» (по умолчанию — handHolding с сервера);
 *  - первое знакомство (bricked == -1): «Кинуть кирпич» убирает маскота, «Не надо!» оставляет;
 *  - поглаживание по голове (движение пальцем влево-вправо по верхней половине), перетаскивание, «прилипание»
 *    к левому и правому краю (маскот поворачивается ногами к стене) и к низу экрана (смотрит к центру).
 *
 * Сами анимации — прозрачные WebM (VP9 с альфа-каналом). Android-проигрыватели альфа-канал не показывают
 * (будет чёрный фон), его умеет только Chromium, поэтому маскот рисуется в маленьком прозрачном WebView
 * со страницей assets/ui/mascot/mascot.html. Тексты фраз — assets/ui/mascot/phrases.json (из mascot.js).
 */
class MascotOverlay(
    private val activity: AppCompatActivity,
    private val root: ConstraintLayout,
    private val api: SiteApi,
    private val bottomBars: List<View>
) {
    companion object {
        private const val HOST = "appassets.androidplatform.net"
        private const val PAGE = "https://$HOST/assets/ui/mascot/mascot.html"
        private const val W_DP = 90
        private const val H_DP = 124
        private const val EDGE_DP = 72
        private const val FORTPORT_COMMUNITY_ID = 1773926513828L

        /** Эмоции из фраз: /E0/ … /E7/ → имя видео mascot-<имя>.webm. */
        private val EMOTIONS = mapOf(
            "E0" to "peek", "E1" to "lookout", "E2" to "wave", "E3" to "killed",
            "E4" to "stand", "E5" to "think", "E6" to "blush", "E7" to "yell"
        )

        /**
         * В какую сторону смотрит маскот на самих видео. Внизу экрана он должен смотреть к центру
         * (на сайте: класс mascot-facing-left при offset > 0.5); если на видео он смотрит вправо, поставить false.
         */
        private const val ART_FACES_LEFT = true

        private val EMOTION_RE = Regex("/E([0-9])/")
        private val CHOICE_RE = Regex("""\$\{choiceOne=`([^`]*)`\}\s*\$\{choiceTwo=`([^`]*)`\}""")
    }

    private val settingsPrefs = activity.getSharedPreferences("settings_ui", Context.MODE_PRIVATE)
    private val prefs = activity.getSharedPreferences("mascot", Context.MODE_PRIVATE)
    private val ui = Handler(Looper.getMainLooper())
    private val bg = Executors.newSingleThreadExecutor() // отдельный поток: не задерживаем загрузки экранов (api.io)
    private val d = activity.resources.displayMetrics.density
    private fun dp(v: Int) = (v * d + 0.5f).toInt()
    private fun dpf(v: Int) = v * d

    // ------------------------------------------------------------------ вьюхи

    @SuppressLint("SetJavaScriptEnabled")
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
    private val box = FrameLayout(activity).apply {
        elevation = dpf(12)
        visibility = View.GONE
        addView(web, FrameLayout.LayoutParams(UiUtil.MATCH, UiUtil.MATCH))
    }

    private val bubbleClose = TextView(activity).apply {
        text = "⨉"; textSize = 18f; gravity = Gravity.END
        setTextColor(UiUtil.TEXT)
        setPadding(dp(10), 0, dp(2), dp(2))
        setOnClickListener { closeBubble() }
    }
    private val bubbleText = TextView(activity).apply { textSize = 14f; setTextColor(UiUtil.TEXT) }
    private val bubbleButtons = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private val bubble = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        elevation = dpf(13)
        visibility = View.GONE
        background = UiUtil.rect(Color.WHITE, UiUtil.BORDER, 2, 14, activity)
        setPadding(dp(12), dp(6), dp(12), dp(12))
        addView(bubbleClose, LinearLayout.LayoutParams(UiUtil.MATCH, UiUtil.WRAP))
        addView(bubbleText, LinearLayout.LayoutParams(UiUtil.MATCH, UiUtil.WRAP))
        addView(bubbleButtons, LinearLayout.LayoutParams(UiUtil.MATCH, UiUtil.WRAP).apply { topMargin = dp(8) })
    }

    // ------------------------------------------------------------------ состояние

    private enum class Pose { BOTTOM, LEFT, RIGHT, FREE }

    private var pose = Pose.BOTTOM
    private var dockOffset = 0.66f
    private var boxW = dp(W_DP)
    private var boxH = dp(H_DP)
    private var pageReady = false
    private var curVideo = ""
    private var lastBaseline = -1
    private var dragging = false

    // данные сессии
    private var sessionLoaded = false
    private var sessionKey: String? = null // кто сейчас в сессии: тестовый режим или реальный аккаунт
    private var username = "друг"
    private var bricked = 0
    private var handHoldingServer = true
    private var pettingRecord = 0
    private var pettingCount = 0
    private var totalUsers = "?"
    private var totalPorts = "?"
    private var newsLines: List<String> = emptyList()
    private var newsIds: List<Long> = emptyList()
    private var lastNewsFetch = 0L
    private var phrases: Map<String, List<String>> = emptyMap()

    // состояние «страницы» (как перезагрузка страницы на сайте)
    private var kind: String? = null
    private var pageId = 0L
    private var pageKey: String? = null
    private var generation = 0
    private var started = false
    private var pageData: JSONObject? = null
    private var pageTitle = ""
    private var hhPhrases = ArrayList<String>()
    private var hhSecondary = ArrayList<String>()
    private var newsPhrases = ArrayList<String>()
    private var newsOriginal = 0
    private var firstClick = true
    private var profileIndex = 0
    private var communityIndex = 0
    private var fortportStep = 0
    private var fortportGreeted = false
    private var initialMode = "normal" // normal | news | hh: что делает самый первый клик
    private var introActive = false

    // ------------------------------------------------------------------ настройки

    private fun enabled() = settingsPrefs.getBoolean("mascot_enabled", true)
    private fun handHolding() = settingsPrefs.getBoolean("mascot_hints", handHoldingServer)

    // ------------------------------------------------------------------ инициализация

    init {
        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(activity))
            .build()
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                // Только свои файлы из assets: в сеть страница маскота не ходит.
                if (request.url.host != HOST) {
                    return WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                }
                return loader.shouldInterceptRequest(request.url)
            }
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
            override fun onPageFinished(view: WebView, url: String?) {
                pageReady = true
                applyPoseJs()
                if (curVideo.isNotEmpty()) { val v = curVideo; curVideo = ""; playVideo(v, v != "killed") }
            }
        }
        web.addJavascriptInterface(object { @JavascriptInterface fun noop() {} }, "Mascot")
        web.setOnTouchListener { _, e -> handleTouch(e); true }

        root.addView(box, ConstraintLayout.LayoutParams(boxW, boxH).apply {
            startToStart = ConstraintLayout.LayoutParams.PARENT_ID
            topToTop = ConstraintLayout.LayoutParams.PARENT_ID
        })
        root.addView(bubble, ConstraintLayout.LayoutParams(dp(280), UiUtil.WRAP).apply {
            startToStart = ConstraintLayout.LayoutParams.PARENT_ID
            topToTop = ConstraintLayout.LayoutParams.PARENT_ID
        })
        root.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, oldR, ob ->
            if (r - l != oldR - ol || b - t != ob - ot) root.post { refreshPosition() }
        }
        root.viewTreeObserver.addOnGlobalLayoutListener {
            // нижняя панель показалась/спряталась — «земля» под маскотом сместилась
            if (box.visibility == View.VISIBLE && !dragging && pose == Pose.BOTTOM && bottomLimit() != lastBaseline) refreshPosition()
        }
        loadDock()
        web.loadUrl(PAGE)
        bg.execute { loadPhrases() }
    }

    private fun loadPhrases() {
        try {
            val text = activity.assets.open("ui/mascot/phrases.json").bufferedReader().use { it.readText() }
            val o = JSONObject(text)
            val m = HashMap<String, List<String>>()
            for (k in o.keys()) {
                val a = o.getJSONArray(k)
                m[k] = List(a.length()) { a.getString(it) }
            }
            phrases = m
        } catch (e: Exception) {
            android.util.Log.w("Mascot", "phrases.json", e)
        }
    }

    private fun list(key: String): List<String> = phrases[key] ?: emptyList()

    // ------------------------------------------------------------------ жизненный цикл

    /**
     * Вызывается при смене нативного экрана. [k]: feed | communities | community | profile | settings | friends | other,
     * null — нативного экрана нет. [id] — id порта или пользователя (0 — свой профиль). Аналог загрузки страницы на сайте.
     */
    fun onPage(k: String?, id: Long = 0L) {
        if (k == null || !enabled()) {
            pageKey = null; generation++; started = false
            hide()
            return
        }
        val key = "$k:$id:${sessionIdentity()}"
        if (key == pageKey && started) { box.visibility = View.VISIBLE; return }
        pageKey = key; kind = k; pageId = id; started = false
        val gen = ++generation
        resetPageState()
        bg.execute {
            val (data, title) = gather(k, id)
            ui.post {
                if (gen == generation) { pageData = data; pageTitle = title; startMascot() }
            }
        }
    }

    fun hide() {
        bubble.visibility = View.GONE
        box.visibility = View.GONE
    }

    fun onResume() { web.onResume() }
    fun onPause() { web.onPause() }

    fun destroy() {
        generation++
        ui.removeCallbacksAndMessages(null)
        bg.shutdownNow()
        root.removeView(box)
        root.removeView(bubble)
        web.removeJavascriptInterface("Mascot")
        web.destroy()
    }

    private fun resetPageState() {
        bubble.visibility = View.GONE
        hhPhrases = ArrayList(); hhSecondary = ArrayList(); newsPhrases = ArrayList(); newsOriginal = 0
        firstClick = true; profileIndex = 0; communityIndex = 0
        pageData = null; pageTitle = ""; introActive = false; initialMode = "normal"
        pettingCount = 0
    }

    // ------------------------------------------------------------------ данные с сервера (фоновый поток)

    private fun getJson(path: String): JSONObject? = try {
        val r = api.get(path)
        if (r.code == 200) JSONObject(r.body) else null
    } catch (e: Exception) { null }

    /** Грузит данные; возвращает то, что относится к конкретной странице (статистика и название). */
    private fun gather(k: String, id: Long): Pair<JSONObject?, String> {
        val sk = sessionIdentity()
        if (sk != sessionKey) {
            // Сменился аккаунт (например, выход из тестового режима): забываем ник и данные прошлой сессии,
            // иначе маскот продолжает называть пользователя «Dev_Tester».
            sessionKey = sk
            sessionLoaded = false
            username = "друг"
            bricked = 0
            handHoldingServer = true
            pettingRecord = 0
            newsLines = emptyList(); newsIds = emptyList(); lastNewsFetch = 0L
        }
        if (!sessionLoaded) {
            getJson("/api/session/bootstrap")?.let { b ->
                b.optJSONObject("user")?.optString("username")?.takeIf { it.isNotBlank() }?.let { username = it }
                b.optJSONObject("mascot")?.let { m ->
                    bricked = if (m.optInt("bricked", 0) == -1) -1 else 0
                    handHoldingServer = m.optBoolean("handHolding", true)
                    pettingRecord = maxOf(pettingRecord, m.optInt("pettingRecord", 0))
                }
                sessionLoaded = true
            }
        }
        getJson("/api/mascot/data/feed")?.let {
            totalUsers = (it.optInt("totalUsers", -1)).takeIf { n -> n >= 0 }?.toString() ?: "?"
            totalPorts = (it.optInt("totalCommunities", -1)).takeIf { n -> n >= 0 }?.toString() ?: "?"
        }
        var data: JSONObject? = null
        var title = ""
        when (k) {
            "profile" -> {
                val uid = if (id == 0L) api.userId else id.toString()
                if (uid.isNotEmpty()) {
                    data = getJson("/api/mascot/data/user/$uid")
                    if (id != 0L && uid != api.userId) title = getJson("/api/users/bio/$uid")?.optString("username").orEmpty()
                }
            }
            "community" -> {
                data = getJson("/api/mascot/data/community/$id")
                title = getJson("/api/communities/$id")?.optString("username").orEmpty()
            }
        }
        if (bricked != -1 && System.currentTimeMillis() - lastNewsFetch > 30_000) {
            lastNewsFetch = System.currentTimeMillis()
            val lines = ArrayList<String>(); val ids = ArrayList<Long>()
            getJson("/api/users/mascot/news")?.optJSONArray("news")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val n = arr.optJSONObject(i) ?: continue
                    ids.add(n.optLong("id"))
                    n.optString("content").split("\n").filter { it.trim().isNotEmpty() }.forEach { lines.add(it) }
                }
            }
            newsLines = lines; newsIds = ids
        }
        return data to title
    }

    private fun sessionIdentity() = "${if (api.isDemo) "demo" else "real"}:${api.userId}"

    private fun isOwnProfile() = pageId == 0L || pageId.toString() == api.userId

    /** Имя страницы для ключей «подсказка показана» и выбора фраз: как currentPage в mascot.js. */
    private fun pageName(): String = when (kind) {
        "profile" -> if (isOwnProfile()) "own_profile" else "profile"
        "community", "communities", "feed", "settings", "friends" -> kind!!
        else -> "feed"
    }

    // ------------------------------------------------------------------ запуск (аналог initMascot)

    private fun startMascot() {
        started = true
        box.visibility = View.VISIBLE
        refreshPosition()

        val name = pageName()
        val hhKey = "hh_${name}_done"
        if (handHolding() && !prefs.getBoolean(hhKey, false)) {
            val title = pageTitle.ifBlank {
                when (name) { "community" -> "порт"; "profile" -> "пользователь"; "own_profile" -> "твой профиль"; else -> "" }
            }
            val src: List<String> = when (name) {
                "feed" -> if (kind == "feed") list("hh_feed") else emptyList()
                "communities" -> list("hh_communities")
                "community" -> list("hh_community")
                "profile" -> list("hh_profile")
                "own_profile" -> list("hh_own_profile")
                "settings" -> list("hh_settings")
                else -> emptyList()
            }
            hhPhrases = ArrayList(src.map { it.replace("\${pagetitle}", title) })
        }

        if (bricked == -1) {
            // Первое знакомство: сервер при показе помечает, что оно было.
            introActive = true
            val text = "ХЕЙ $username! МЕНЯ ЗОВУТ ЖЕКА! Я БУДУ ЗДЕСЬ КАЖДЫЙ РАЗ КОГДА САЙТ ОБНОВЛЯЕТСЯ! " +
                "ТЫ МОЖЕШЬ КИНУТЬ В МЕНЯ КИРПИЧ ЧТОБЫ УБРАТЬ, ЕСЛИ Я БУДУ ТЕБЕ МЕШАТЬ!"
            bg.execute { try { api.send("POST", "/api/users/mascot/status", null) } catch (e: Exception) {} }
            bricked = 0
            showBubble(text, listOf("Кинуть кирпич" to { throwBrick() }, "Не надо!" to { accept() }))
            updateVideo("E2")
            return
        }
        if (newsLines.isNotEmpty()) {
            newsPhrases = ArrayList(newsLines); newsOriginal = newsPhrases.size
            initialMode = "news"; updateVideo("E1"); return
        }
        if (hhPhrases.isNotEmpty()) { initialMode = "hh"; updateVideo("E1"); return }
        initialMode = "normal"
        updateVideo("E0")
    }

    // ------------------------------------------------------------------ сценарий реплик

    private fun onMascotClick() {
        if (introActive) return
        if (bubble.visibility == View.VISIBLE) { newPhrase(); return }
        if (initialMode != "normal") {
            // новости/подсказки: пузырь с «Дальше» и сразу первая реплика
            initialMode = "normal"
            showBubble("", listOf("Дальше" to { newPhrase() }))
            newPhrase()
        } else openBubble()
    }

    private fun openBubble() {
        updateVideo("E2")
        showBubble("", emptyList())
        newPhrase()
    }

    private fun closeBubble() {
        introActive = false
        bubble.visibility = View.GONE
        updateVideo("E4")
    }

    private fun accept() {
        bricked = 0
        bg.execute { try { api.send("PUT", "/api/users/mascot/status", "{\"bricked\":0}") } catch (e: Exception) {} }
        closeBubble()
    }

    private fun throwBrick() {
        updateVideo("E3", loop = false)
        bg.execute {
            try { api.send("POST", "/api/users/mascot/status", null) } catch (e: Exception) {}
            ui.postDelayed({
                closeBubble()
                hide()
                // На сайте «убрать» значит больше не показывать: здесь это выключатель из настроек (его можно вернуть там же).
                settingsPrefs.edit().putBoolean("mascot_enabled", false).apply()
                started = false; pageKey = null
            }, 800)
        }
    }

    private fun completeHH() {
        prefs.edit().putBoolean("hh_${pageName()}_done", true).apply()
        closeBubble()
        hhPhrases.clear(); hhSecondary.clear()
        updateVideo("E4")
    }

    private fun loadWhatis() {
        hhSecondary = ArrayList(list("hh_whatis_port"))
        setButtons(listOf("Дальше" to { newPhrase() }))
        newPhrase()
    }

    /** Разбирает «/E5/текст»: показывает текст, включает эмоцию. Возвращает очищенный текст. */
    private fun speak(raw: String, emotion: String = "E2") {
        var phrase = raw
        var emo = emotion
        EMOTION_RE.find(phrase)?.let { emo = "E" + it.groupValues[1]; phrase = phrase.replaceFirst(it.value, "") }
        bubbleText.text = phrase
        updateVideo(emo)
        layoutBubbleSoon()
    }

    private fun fillStats(p: String, withCommunity: Boolean): String {
        val pd = pageData ?: return p
        var s = p.replace("\${postCount}", pd.optString("postCount", "?"))
            .replace("\${likeCount}", pd.optString("likeCount", "?"))
            .replace("\${dislikeCount}", pd.optString("dislikeCount", "?"))
            .replace("\${commentCount}", pd.optString("commentCount", "?"))
        if (withCommunity) s = s.replace("\${topCommunity}", pd.optString("topCommunity", "?"))
        return s
    }

    private fun newPhrase() {
        // 1) вторичная ветка («что такое порты»)
        if (hhSecondary.isNotEmpty()) {
            speak(hhSecondary.removeAt(0).replace("\${username}", username))
            if (hhSecondary.isEmpty()) setButtons(listOf("Дальше" to { newPhrase() }))
            return
        }
        // 2) подсказки по странице
        if (hhPhrases.isNotEmpty()) {
            var phrase = hhPhrases.removeAt(0).replace("\${username}", username)
            var choice: Pair<String, String>? = null
            CHOICE_RE.find(phrase)?.let { choice = it.groupValues[1] to it.groupValues[2]; phrase = phrase.replace(it.value, "") }
            speak(phrase)
            val c = choice
            when {
                c != null -> setButtons(listOf(c.first to { loadWhatis() }, c.second to { newPhrase() }))
                hhPhrases.isEmpty() -> setButtons(listOf("Я всё понял!" to { completeHH() }))
                else -> setButtons(listOf("Дальше" to { newPhrase() }))
            }
            return
        }
        // 3) новости
        if (newsPhrases.isNotEmpty()) {
            if (newsPhrases.size == newsOriginal) markNewsRead()
            speak(newsPhrases.removeAt(0))
            if (newsPhrases.isEmpty()) setButtons(listOf("Прочитано!" to { closeBubble() }))
            else setButtons(listOf("Дальше" to { newPhrase() }))
            return
        }
        // 4) обычные реплики
        var phrase = ""
        var random = false
        val name = pageName()
        val pd = pageData
        if (firstClick) {
            phrase = list("greetings").randomOrNull()?.replace("\${username}", username).orEmpty()
            firstClick = false
        } else if (name == "profile" && pd != null && profileIndex < list("profile").size) {
            phrase = fillStats(list("profile")[profileIndex], true).replace("\${username}", username); profileIndex++
        } else if (name == "own_profile" && pd != null && profileIndex < list("own_profile").size) {
            phrase = fillStats(list("own_profile")[profileIndex], true).replace("\${username}", username); profileIndex++
        } else if (name == "community" && pd != null) {
            val port = list("port")
            if (pageId == FORTPORT_COMMUNITY_ID && !fortportGreeted) {
                val fp = list("fortport")
                if (fortportStep == 0) { phrase = fp.getOrElse(0) { "" }; fortportStep = 1 }
                else if (fortportStep == 1) { phrase = fp.getOrElse(1) { "" }; fortportStep = 2; fortportGreeted = true }
                else if (communityIndex < port.size) { phrase = fillStats(port[communityIndex], false); communityIndex++ }
                else random = true
            } else if (communityIndex < port.size) {
                phrase = fillStats(port[communityIndex], false).replace("\${username}", username); communityIndex++
            } else random = true
        } else random = true

        if (random || phrase.isEmpty()) {
            phrase = list("random").randomOrNull().orEmpty()
                .replace("\${username}", username).replace("\${totalusers}", totalUsers).replace("\${totalports}", totalPorts)
        }
        speak(phrase)
    }

    private fun markNewsRead() {
        val ids = newsIds
        newsLines = emptyList(); newsIds = emptyList()
        bg.execute {
            for (id in ids) try { api.send("POST", "/api/users/mascot/news", "{\"newsId\":$id}") } catch (e: Exception) {}
        }
    }

    // ------------------------------------------------------------------ пузырь

    private fun showBubble(text: String, buttons: List<Pair<String, () -> Unit>>) {
        bubbleText.text = text
        setButtons(buttons)
        bubble.visibility = View.VISIBLE
        layoutBubbleSoon()
    }

    private fun setButtons(buttons: List<Pair<String, () -> Unit>>) {
        bubbleButtons.removeAllViews()
        for ((label, action) in buttons) {
            val b = TextView(activity).apply {
                text = label; textSize = 15f; gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                background = UiUtil.rect(0xFF12D9C0.toInt(), UiUtil.BORDER, 2, 10, activity)
                setPadding(dp(10), dp(8), dp(10), dp(8))
                setOnClickListener { action() }
            }
            bubbleButtons.addView(b, LinearLayout.LayoutParams(UiUtil.MATCH, UiUtil.WRAP).apply { topMargin = dp(6) })
        }
        layoutBubbleSoon()
    }

    private fun layoutBubbleSoon() { bubble.post { layoutBubble() } }

    private fun layoutBubble() {
        if (bubble.visibility != View.VISIBLE) return
        val w = root.width; val h = root.height
        if (w == 0 || h == 0) return
        val bw = minOf(w - dp(16), dp(300))
        bubble.layoutParams = (bubble.layoutParams as ConstraintLayout.LayoutParams).also { it.width = bw }
        bubble.measure(
            View.MeasureSpec.makeMeasureSpec(bw, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.AT_MOST)
        )
        val bh = bubble.measuredHeight
        val gap = dp(4)
        val margin = dp(8)
        val mx = box.translationX; val my = box.translationY
        // Плашка никогда не перекрывает маскота: маскот в верхней половине экрана — плашка под ним,
        // в нижней — над ним (если не помещается с выбранной стороны, берём другую).
        val floor = minOf(h, bottomLimit())
        val below = my + boxH + gap
        val above = my - bh - gap
        val fitsBelow = below + bh <= floor - margin
        val fitsAbove = above >= margin
        val inUpperHalf = my + boxH / 2f < h / 2f
        var y = when {
            inUpperHalf && fitsBelow -> below
            !inUpperHalf && fitsAbove -> above
            fitsBelow -> below
            fitsAbove -> above
            else -> if (inUpperHalf) below else above
        }
        // По горизонтали: у левой/правой стены плашка прижата к тому же краю, иначе центрируется над маскотом.
        var x = when (pose) {
            Pose.LEFT -> mx
            Pose.RIGHT -> mx + boxW - bw
            else -> mx + boxW / 2f - bw / 2f
        }
        x = x.coerceIn(dpf(8), maxOf(dpf(8), (w - bw - dp(8)).toFloat()))
        y = y.coerceIn(dpf(8), maxOf(dpf(8), (h - bh - dp(8)).toFloat()))
        bubble.translationX = x; bubble.translationY = y
        // цвета зависят от темы
        val night = UiUtil.isNight(activity)
        val fill = if (night) 0xFF1E2640.toInt() else Color.WHITE
        val ink = if (night) 0xFFEAF1FF.toInt() else UiUtil.TEXT
        bubble.background = UiUtil.rect(fill, UiUtil.BORDER, 2, 14, activity)
        bubbleText.setTextColor(ink); bubbleClose.setTextColor(ink)
    }

    // ------------------------------------------------------------------ видео

    private fun updateVideo(code: String, loop: Boolean = true) {
        val name = EMOTIONS[code] ?: return
        if (name == curVideo) return
        playVideo(name, loop)
    }

    private fun playVideo(name: String, loop: Boolean) {
        curVideo = name
        if (!pageReady) return
        web.evaluateJavascript("play('$name',$loop)", null)
    }

    private fun applyPoseJs() {
        if (!pageReady) return
        val rot = when (pose) { Pose.LEFT -> 90; Pose.RIGHT -> -90; else -> 0 }
        val facingLeft = dockOffset > 0.5f
        val flip = pose == Pose.BOTTOM && facingLeft != ART_FACES_LEFT
        web.evaluateJavascript("setPose($rot,$flip)", null)
    }

    // ------------------------------------------------------------------ положение и перетаскивание

    private fun loadDock() {
        val e = prefs.getString("dock_edge", "bottom")
        pose = when (e) { "left" -> Pose.LEFT; "right" -> Pose.RIGHT; else -> Pose.BOTTOM }
        dockOffset = prefs.getFloat("dock_offset", 0.66f).coerceIn(0f, 1f)
    }

    private fun saveDock() {
        val e = when (pose) { Pose.LEFT -> "left"; Pose.RIGHT -> "right"; else -> "bottom" }
        prefs.edit().putString("dock_edge", e).putFloat("dock_offset", dockOffset).apply()
    }

    /** Низ экрана для маскота: верх нижней панели (если она показана), иначе низ корневого макета. */
    private fun bottomLimit(): Int {
        val rl = IntArray(2); root.getLocationInWindow(rl)
        var limit = root.height
        for (v in bottomBars) {
            if (!v.isShown || v.height == 0) continue
            val l = IntArray(2); v.getLocationInWindow(l)
            val top = l[1] - rl[1]
            if (top > root.height / 2 && top < limit) limit = top
        }
        return limit
    }

    private fun setBoxSize(w: Int, h: Int) {
        if (w == boxW && h == boxH) return
        boxW = w; boxH = h
        val lp = box.layoutParams
        lp.width = w; lp.height = h
        box.layoutParams = lp
    }

    /** Ставит маскота по сохранённой «стыковке»: pose + dockOffset. */
    private fun refreshPosition() {
        val w = root.width; val h = root.height
        if (w == 0 || h == 0) return
        when (pose) {
            Pose.LEFT -> {
                setBoxSize(dp(H_DP), dp(W_DP))
                box.translationX = 0f
                box.translationY = (h - boxH) * dockOffset
            }
            Pose.RIGHT -> {
                setBoxSize(dp(H_DP), dp(W_DP))
                box.translationX = (w - boxW).toFloat()
                box.translationY = (h - boxH) * dockOffset
            }
            else -> {
                pose = Pose.BOTTOM
                setBoxSize(dp(W_DP), dp(H_DP))
                lastBaseline = bottomLimit()
                box.translationX = (w - boxW) * dockOffset
                box.translationY = (lastBaseline - boxH).toFloat()
            }
        }
        applyPoseJs()
        layoutBubbleSoon()
    }

    private var startX = 0f; private var startY = 0f
    private var lastX = 0f; private var lastY = 0f
    private var headPetting = false
    private var lastPetX = 0f
    private var lastDir = 0
    private var dirChanges = 0
    private var travel = 0f
    private var petted = false
    private var candidate: Pose? = null
    private var candidateOffset = 0f

    private fun handleTouch(e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = e.rawX; startY = e.rawY; lastX = e.rawX; lastY = e.rawY
                headPetting = e.y <= web.height * 0.5f
                lastPetX = e.rawX; lastDir = 0; dirChanges = 0; travel = 0f
                dragging = false; petted = false; candidate = null
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.rawX - startX; val dy = e.rawY - startY
                if (headPetting && abs(dy) < dpf(44)) {
                    val step = e.rawX - lastPetX
                    val dir = if (abs(step) >= dpf(2)) (if (step > 0) 1 else -1) else 0
                    travel += abs(step)
                    if (dir != 0 && lastDir != 0 && dir != lastDir) dirChanges++
                    if (dir != 0) lastDir = dir
                    lastPetX = e.rawX
                    if (dirChanges >= 1 && travel >= dpf(36)) {
                        pettingCycle(); dirChanges = 0; travel = 0f; lastDir = dir
                    }
                    return
                }
                if (!dragging && hypot(dx, dy) < dpf(8)) return
                dragging = true; headPetting = false
                val stepX = e.rawX - lastX; val stepY = e.rawY - lastY
                lastX = e.rawX; lastY = e.rawY
                drag(e.rawX, e.rawY, stepX, stepY)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val wasDragging = dragging
                if (wasDragging) finishDrag()
                else if (e.actionMasked == MotionEvent.ACTION_UP && !petted) onMascotClick()
                dragging = false; headPetting = false; candidate = null
            }
        }
    }

    private fun pettingCycle() {
        petted = true
        pettingCount++
        if (pettingCount > pettingRecord) pettingRecord = pettingCount
        if (pettingRecord > prefs.getInt("petting_record", 0)) prefs.edit().putInt("petting_record", pettingRecord).apply()
        box.animate().cancel()
        box.animate().scaleX(1.06f).scaleY(1.06f).setDuration(90).withEndAction {
            box.animate().scaleX(1f).scaleY(1f).setDuration(90).start()
        }.start()
    }

    private fun drag(rawX: Float, rawY: Float, stepX: Float, stepY: Float) {
        val w = root.width; val h = root.height
        val loc = IntArray(2); root.getLocationOnScreen(loc)
        val x = (rawX - loc[0]).coerceIn(0f, w.toFloat())
        val y = (rawY - loc[1]).coerceIn(0f, h.toFloat())
        val edge = dpf(EDGE_DP)
        if (x <= edge || x >= w - edge) {
            // «лазает по стене»: повёрнут ногами к краю
            candidate = if (x <= edge) Pose.LEFT else Pose.RIGHT
            candidateOffset = y / maxOf(1, h)
            pose = candidate!!; dockOffset = candidateOffset
            refreshPosition()
        } else {
            // середина экрана — свободное перемещение, маскот стоит прямо
            candidate = null
            pose = Pose.FREE
            setBoxSize(dp(W_DP), dp(H_DP))
            box.translationX = (box.translationX + stepX).coerceIn(0f, (w - boxW).toFloat())
            box.translationY = (box.translationY + stepY).coerceIn(0f, (h - boxH).toFloat())
            applyPoseJs()
            layoutBubbleSoon()
        }
    }

    private fun finishDrag() {
        val w = root.width.toFloat()
        val cand = candidate
        if (cand != null) {
            pose = cand; dockOffset = candidateOffset
        } else {
            val cx = box.translationX + boxW / 2f
            val edge = dpf(EDGE_DP)
            when {
                box.translationX <= edge -> { pose = Pose.LEFT; dockOffset = 1f }
                box.translationX + boxW >= w - edge -> { pose = Pose.RIGHT; dockOffset = 1f }
                else -> { pose = Pose.BOTTOM; dockOffset = (cx / maxOf(1f, w)).coerceIn(0f, 1f) }
            }
        }
        saveDock()
        refreshPosition()
    }
}
