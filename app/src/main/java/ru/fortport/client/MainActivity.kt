package ru.fortport.client

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.*
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var offlineView: View
    private lateinit var offlineImage: ImageView
    private var lastLoadFailed = false
    private lateinit var bottomNavBar: LinearLayout
    private lateinit var topNavBar: LinearLayout
    private lateinit var sectionHeader: LinearLayout
    private lateinit var sectionHeaderIcon: ImageView
    private lateinit var sectionHeaderTitle: TextView
    private lateinit var navHomeCenter: ImageButton
    private lateinit var siteApi: SiteApi
    private lateinit var sessionGuard: SessionGuard
    private lateinit var nativeChatsPanel: View
    private lateinit var nativeFriendsPanel: View
    private lateinit var nativeCommunitiesPanel: View
    private lateinit var nativeMusicPanel: View
    private lateinit var sectionHeaderAction: ImageButton
    private lateinit var chatsController: ChatsListController
    private lateinit var friendsController: FriendsListController
    private lateinit var communitiesController: CommunitiesListController
    private lateinit var musicController: MusicController

    /** Нативные экраны, которые показываются поверх WebView вместо веб-страниц /chats и /friends. */
    private enum class NativeSection { CHATS, FRIENDS, COMMUNITIES, MUSIC }
    private var nativeSection: NativeSection? = null
    /** Чья музыка откроется в нативной вкладке: null — своя (выставляется при перехвате /music?id=…). */
    private var pendingMusicUserId: String? = null
    /** Из нативного списка открыли профиль: «Назад» вернёт в этот список. */
    private var returnSection: NativeSection? = null
    /** Последний известный адрес страницы (в т.ч. после JS-роутинга сайта без перезагрузки). */
    private var currentUrl: String? = null
    private lateinit var navProfileAvatar: ImageView
    private lateinit var navNotifBadge: TextView
    private lateinit var navCoinsValue: TextView
    // Отдельный WebView для оплаты: основная страница остаётся загруженной и не теряет состояние.
    private var paymentWebView: WebView? = null
    private var paymentContainer: FrameLayout? = null
    private var paymentProgress: ProgressBar? = null

    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var cameraPhotoPath: String? = null
    private var pendingOpenPostCreator = false

    /** Вернулись из нативного чата — список переписок на странице нужно обновить. */
    private var reloadChatsOnResume = false

    companion object {
        private const val TAG = "FortPortClient"
        private const val REQUEST_CAMERA_PERMISSION = 5174
        private val SITE_HOST = "fortport.ru"
        private const val SITE_ORIGIN = "https://fortport.ru"

        /** Страница авторизации сайта. ПРОВЕРЬТЕ путь: если у сайта другой — поменяйте здесь. */
        private const val LOGIN_PATH = "/login"

        /** Страницы, которые можно открывать без входа (не перенаправляем с них на логин). */
        private val PUBLIC_PATH_PREFIXES = listOf(
            LOGIN_PATH, "/register", "/signup", "/about", "/privacy", "/terms", "/support"
        )
    }

    /** null — ещё не знаем, true/false — по ответу сервера. */
    @Volatile private var isLoggedIn: Boolean? = null
    /** Автоматически отправляем на логин только один раз за запуск, чтобы не зациклиться. */
    private var autoLoginRedirectDone = false

    private val fileChooserLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
            handleFileChooserResult(result.resultCode, result.data)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        swipeRefresh = findViewById(R.id.swipeRefresh)
        progressBar = findViewById(R.id.progressBar)
        offlineView = findViewById(R.id.offlineView)
        offlineImage = findViewById(R.id.offlineImage)
        loadOfflineImage()
        bottomNavBar = findViewById(R.id.bottomNavBar)
        topNavBar = findViewById(R.id.topNavBar)
        sectionHeader = findViewById(R.id.sectionHeader)
        sectionHeaderIcon = findViewById(R.id.sectionHeaderIcon)
        sectionHeaderTitle = findViewById(R.id.sectionHeaderTitle)
        navHomeCenter = findViewById(R.id.navHomeCenter)
        nativeChatsPanel = findViewById(R.id.nativeChatsPanel)
        nativeFriendsPanel = findViewById(R.id.nativeFriendsPanel)
        nativeCommunitiesPanel = findViewById(R.id.nativeCommunitiesPanel)
        nativeMusicPanel = findViewById(R.id.nativeMusicPanel)
        sectionHeaderAction = findViewById(R.id.sectionHeaderAction)
        siteApi = SiteApi(this)
        sessionGuard = SessionGuard(
            context = this,
            api = siteApi,
            onValid = {
                if (isLoggedIn != true) { isLoggedIn = true; updateBarsVisibility() }
            },
            onInvalid = {
                // Сервер трижды подряд подтвердил: сессии нет.
                hideNativeSection()
                isLoggedIn = false
                updateBarsVisibility()
                redirectToLoginIfNeeded()
            },
            onNetworkAvailable = {
                // Запуск без сети / обрыв при загрузке: сеть вернулась — перезагружаем сайт сами.
                if (lastLoadFailed || offlineView.visibility == View.VISIBLE) loadSite()
            }
        )
        val defaultBg = loadAssetIcon("styles/bg.webp")
        siteApi.attachBackground(findViewById(R.id.nativeChatsBg), defaultBg)
        siteApi.attachBackground(findViewById(R.id.nativeFriendsBg), defaultBg)
        siteApi.attachBackground(findViewById(R.id.nativeCommunitiesBg), defaultBg)
        siteApi.attachBackground(findViewById(R.id.nativeMusicBg), defaultBg)
        val openChat: (String) -> Unit = { json ->
            startActivity(Intent(this, ChatActivity::class.java).putExtra(ChatActivity.EXTRA_PAYLOAD, json))
        }
        // 401/403 на нативном экране — не повод выходить сразу: SessionGuard проверит сессию с повторами.
        val authLost: () -> Unit = { sessionGuard.requestVerify() }
        chatsController = ChatsListController(
            activity = this,
            api = siteApi,
            swipe = findViewById(R.id.chatsSwipe),
            recycler = findViewById(R.id.chatsRecycler),
            emptyView = findViewById(R.id.chatsEmpty),
            onOpenChat = openChat,
            onAuthLost = authLost
        )
        friendsController = FriendsListController(
            activity = this,
            api = siteApi,
            swipe = findViewById(R.id.friendsSwipe),
            recycler = findViewById(R.id.friendsRecycler),
            searchInput = findViewById(R.id.friendsSearch),
            onOpenChat = openChat,
            onOpenProfile = { id ->
                returnSection = nativeSection
                hideNativeSection()
                webView.loadUrl("$SITE_ORIGIN/profile?id=$id")
            },
            onAuthLost = authLost
        )
        communitiesController = CommunitiesListController(
            activity = this,
            api = siteApi,
            swipe = findViewById(R.id.communitiesSwipe),
            recycler = findViewById(R.id.communitiesRecycler),
            searchInput = findViewById(R.id.communitiesSearch),
            onOpenCommunity = { id ->
                returnSection = nativeSection
                hideNativeSection()
                webView.loadUrl("$SITE_ORIGIN/community?id=$id")
            },
            onCreateCommunity = {
                returnSection = nativeSection
                hideNativeSection()
                webView.loadUrl("$SITE_ORIGIN/new_community")
            },
            onAuthLost = authLost
        )
        musicController = MusicController(
            activity = this,
            api = siteApi,
            swipe = findViewById(R.id.musicSwipe),
            recycler = findViewById(R.id.musicRecycler),
            emptyView = findViewById(R.id.musicEmpty),
            bg = findViewById(R.id.nativeMusicBg),
            disc = findViewById(R.id.musicDisc),
            titleView = findViewById(R.id.musicTitle),
            artistView = findViewById(R.id.musicArtist),
            timeCurrent = findViewById(R.id.musicTimeCurrent),
            timeTotal = findViewById(R.id.musicTimeTotal),
            seek = findViewById(R.id.musicSeek),
            btnShuffle = findViewById(R.id.musicShuffle),
            btnPrev = findViewById(R.id.musicPrev),
            btnPlay = findViewById(R.id.musicPlay),
            btnNext = findViewById(R.id.musicNext),
            btnRepeat = findViewById(R.id.musicRepeat),
            onLibraryChanged = { if (nativeSection == NativeSection.MUSIC) applyTopChrome(barsShouldShow()) },
            onAuthLost = authLost
        )
        sectionHeaderAction.setOnClickListener { musicController.pickFromDevice() }
        navProfileAvatar = findViewById(R.id.navProfileAvatar)
        navNotifBadge = findViewById(R.id.navNotifBadge)
        navCoinsValue = findViewById(R.id.navCoinsValue)

        findViewById<Button>(R.id.retryButton).setOnClickListener {
            loadSite()
        }

        setupWebView()
        setupSwipeRefresh()
        setupBottomNav()
        setupTopNav()

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
        } else {
            loadSite()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        // Запрещаем pinch-to-zoom и системные элементы масштабирования в WebView.
        settings.setSupportZoom(false)
        settings.builtInZoomControls = false
        settings.displayZoomControls = false
        settings.mediaPlaybackRequiresUserGesture = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.cacheMode = WebSettings.LOAD_DEFAULT

        // Заставляем WebView полноценно использовать мобильный viewport страницы
        // (без этого некоторые сайты рендерят слишком широкий "десктопный" layout).
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.layoutAlgorithm = WebSettings.LayoutAlgorithm.NORMAL

        // WebView добавляет в User-Agent метку "; wv", по которой многие сайты
        // определяют его как "не мобильный браузер" и отдают чуть другую вёрстку
        // (например, немасштабированные модальные окна). Убираем эту метку и
        // приводим строку к виду обычного мобильного Chrome — это решает
        // большинство подобных проблем с адаптивностью без изменений на сайте.
        settings.userAgentString = buildMobileUserAgent(settings.userAgentString)

        // Разрешаем несколько окон (некоторые ссылки открываются через window.open)
        settings.setSupportMultipleWindows(false)
        settings.javaScriptCanOpenWindowsAutomatically = true

        // Куки, чтобы сессия входа сохранялась между запусками
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        // Мост со страницы в нативный код — используется, чтобы прятать/
        // показывать нижнюю панель навигации при скролле ленты.
        webView.addJavascriptInterface(ScrollBridge(), "AndroidNav")
        // Мост для нативного просмотра фото (см. injectPhotoViewerHook)
        webView.addJavascriptInterface(PhotoBridge(), "AndroidPhoto")

        // Тёмная тема: подстраиваем содержимое страницы под системную тему устройства
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            val isNightMode = (resources.configuration.uiMode and
                    android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                    android.content.res.Configuration.UI_MODE_NIGHT_YES
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, isNightMode)
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val url = request.url
                Log.d(TAG, "nav ${request.method} $url redirect=${request.isRedirect} gesture=${request.hasGesture()}")
                // Ссылки на списки переписок и друзей открываем нативными экранами, а не веб-страницами.
                if (isLoggedIn != false) {
                    val section = nativeSectionFor(url)
                    if (section != null) {
                        runOnUiThread { showNativeSection(section) }
                        return true
                    }
                }
                return if (url.host?.endsWith(SITE_HOST) == true) {
                    false // открываем внутри приложения
                } else {
                    // Внешние ссылки (например, соцсети, платёжные системы) — во внешнем браузере
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, url))
                    } catch (e: Exception) {
                        Log.w(TAG, "Не удалось открыть внешнюю ссылку: $url", e)
                    }
                    true
                }
            }

            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                val url = request.url

                // 1) Файлы, зашитые прямо в APK — доступны даже без единого запроса в сеть.
                BundledAssets.tryLoad(this@MainActivity, url)?.let { return it }

                // 2) Обычный статический ассет сайта — кэшируем на диск при первой загрузке.
                return if (AssetCache.isCacheable(url, SITE_HOST)) {
                    AssetCache.load(cacheDir, url) ?: super.shouldInterceptRequest(view, request)
                } else {
                    super.shouldInterceptRequest(view, request)
                }
            }

            // Срабатывает и при переходах внутри страницы (JS-роутинг сайта без
            // полной перезагрузки), поэтому состояние кнопки обновляем здесь.
            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                super.doUpdateVisitedHistory(view, url, isReload)
                if (url != null && isLoggedIn != false) {
                    val uri = Uri.parse(url)
                    // JS-переход сайта на список переписок/друзей (или закрытие веб-диалога) — показываем нативный.
                    nativeSectionFor(uri)?.let { showNativeSection(it) }
                }
                updateAddPostButton(url)
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                progressBar.visibility = View.VISIBLE
                lastLoadFailed = false
            }

            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                progressBar.visibility = View.GONE
                swipeRefresh.isRefreshing = false

                // lastLoadFailed может быть true даже если сеть на устройстве есть —
                // например, когда сам сервер сейчас не отвечает (не наш случай
                // "нет интернета", а именно "сервер недоступен"). В этом случае
                // WebView всё равно вызывает onPageFinished следом за
                // onReceivedError/onReceivedHttpError — не даём ему тут же скрыть
                // плашку и показать содержимое неудачной загрузки.
                if (!lastLoadFailed) {
                    offlineView.visibility = View.GONE
                    swipeRefresh.visibility = View.VISIBLE
                    injectMobileLayoutSafetyNet(view)
                    injectNavCleanup(view)
                    injectScrollTracking(view)
                    injectPhotoViewerHook(view)
                    injectChatWatcher(view)
                    updateBarsVisibility()
                    loadProfileAvatar()
                    refreshCdCoinsBalance()

                    if (pendingOpenPostCreator) {
                        pendingOpenPostCreator = false
                        runOpenPostCreatorJs()
                    }
                }
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                super.onReceivedError(view, request, error)
                if (request.isForMainFrame) {
                    lastLoadFailed = true
                    progressBar.visibility = View.GONE
                    swipeRefresh.isRefreshing = false
                    showOffline()
                }
            }

            // Срабатывает, когда сервер отвечает, но кодом ошибки (502/503 и т.п.) —
            // браузерная/прокси страница ошибки иначе отрисовалась бы прямо в WebView.
            override fun onReceivedHttpError(
                view: WebView,
                request: WebResourceRequest,
                errorResponse: WebResourceResponse
            ) {
                super.onReceivedHttpError(view, request, errorResponse)
                Log.w(
                    TAG,
                    "HTTP ${errorResponse.statusCode} ${request.method} ${request.url} " +
                        "mainFrame=${request.isForMainFrame}"
                )
                // Плашку "нет подключения" показываем только при сбоях сервера (5xx).
                // 4xx (404, 403 и т.п.) — это нормальный ответ сайта: пусть WebView
                // отрисует его страницу, а не прячет причину за нашей заглушкой.
                if (request.isForMainFrame && errorResponse.statusCode >= 500) {
                    lastLoadFailed = true
                    progressBar.visibility = View.GONE
                    swipeRefresh.isRefreshing = false
                    showOffline()
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progressBar.progress = newProgress
            }

            // Пока у <video> нет первого кадра (например, при смене анимации
            // маскота через video.src), Android WebView рисует стандартную
            // серую плашку с кнопкой play. Подменяем её прозрачным пикселем.
            private val transparentPoster: Bitmap by lazy {
                Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
            }

            override fun getDefaultVideoPoster(): Bitmap = transparentPoster

            // Пробрасываем console.log/warn/error со страницы в Logcat (тег
            // JsConsole) — без этого ошибки JS на странице (например, при
            // отправке поста) происходят молча и незаметно.
            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                Log.d(
                    "JsConsole",
                    "[${message.messageLevel()}] ${message.message()} " +
                        "(${message.sourceId()}:${message.lineNumber()})"
                )
                return true
            }

            // Без этого alert()/confirm() из JS сайта не показываются вообще —
            // стандартный WebChromeClient их просто игнорирует. Именно на них
            // завязаны сообщения об ошибках при отправке поста ("Ошибка: ...",
            // "Ошибка сервера" в post.js).
            override fun onJsAlert(
                view: WebView,
                url: String?,
                message: String?,
                result: JsResult
            ): Boolean {
                AlertDialog.Builder(this@MainActivity)
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok) { _, _ -> result.confirm() }
                    .setOnDismissListener { result.confirm() }
                    .setCancelable(false)
                    .show()
                return true
            }

            override fun onJsConfirm(
                view: WebView,
                url: String?,
                message: String?,
                result: JsResult
            ): Boolean {
                AlertDialog.Builder(this@MainActivity)
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok) { _, _ -> result.confirm() }
                    .setNegativeButton(android.R.string.cancel) { _, _ -> result.cancel() }
                    .setOnCancelListener { result.cancel() }
                    .show()
                return true
            }

            // Обработка <input type="file"> — выбор фото из галереи или съёмка с камеры
            override fun onShowFileChooser(
                webView: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams
            ): Boolean {
                filePathCallback?.onReceiveValue(null)
                filePathCallback = callback

                val chooserIntent = createFileChooserIntent(params)
                try {
                    fileChooserLauncher.launch(chooserIntent)
                } catch (e: Exception) {
                    Log.e(TAG, "Не удалось запустить выбор файла", e)
                    filePathCallback = null
                    return false
                }
                return true
            }
        }
    }

    private fun createFileChooserIntent(params: WebChromeClient.FileChooserParams): Intent {
        var takePictureIntent: Intent? = null
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            takePictureIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            val photoFile = createImageFile()
            if (photoFile != null) {
                cameraPhotoPath = photoFile.absolutePath
                val photoUri = FileProvider.getUriForFile(
                    this, "$packageName.fileprovider", photoFile
                )
                takePictureIntent.putExtra(MediaStore.EXTRA_OUTPUT, photoUri)
            } else {
                takePictureIntent = null
            }
        } else {
            requestCameraPermission()
        }

        val contentSelectionIntent = Intent(Intent.ACTION_GET_CONTENT)
        contentSelectionIntent.addCategory(Intent.CATEGORY_OPENABLE)
        contentSelectionIntent.type = "*/*"
        val mimeTypes = params.acceptTypes?.filter { it.isNotBlank() }
        if (!mimeTypes.isNullOrEmpty()) {
            contentSelectionIntent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes.toTypedArray())
        }

        val intentArray = if (takePictureIntent != null) arrayOf(takePictureIntent) else arrayOf()

        return Intent(Intent.ACTION_CHOOSER).apply {
            putExtra(Intent.EXTRA_INTENT, contentSelectionIntent)
            putExtra(Intent.EXTRA_TITLE, "Выберите файл")
            putExtra(Intent.EXTRA_INITIAL_INTENTS, intentArray)
        }
    }

    private fun requestCameraPermission() {
        androidx.core.app.ActivityCompat.requestPermissions(
            this, arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA_PERMISSION
        )
    }

    private fun createImageFile(): File? {
        return try {
            val storageDir = File(cacheDir, "images").apply { mkdirs() }
            File.createTempFile("PHOTO_", ".jpg", storageDir)
        } catch (e: Exception) {
            Log.e(TAG, "Не удалось создать файл для фото", e)
            null
        }
    }

    private fun handleFileChooserResult(resultCode: Int, data: Intent?) {
        if (filePathCallback == null) return

        var results: Array<Uri>? = null
        if (resultCode == Activity.RESULT_OK) {
            if (data == null || data.data == null) {
                // Файл мог быть создан камерой
                if (cameraPhotoPath != null) {
                    results = arrayOf(Uri.fromFile(File(cameraPhotoPath!!)))
                }
            } else {
                val clipData = data.clipData
                if (clipData != null) {
                    results = Array(clipData.itemCount) { i -> clipData.getItemAt(i).uri }
                } else {
                    results = arrayOf(data.data!!)
                }
            }
        }
        filePathCallback?.onReceiveValue(results)
        filePathCallback = null
    }

    private fun setupSwipeRefresh() {
        swipeRefresh.setColorSchemeResources(R.color.fp_accent)
        swipeRefresh.setOnRefreshListener {
            if (isNetworkAvailable()) {
                webView.reload()
            } else {
                swipeRefresh.isRefreshing = false
                showOffline()
            }
        }
    }

    /**
     * Нативная нижняя панель: Музыка / Порты (сообщества) / Добавить пост /
     * Друзья / Переписки. Иконки — те же .webp-файлы, что уже зашиты в
     * app/src/main/assets/ui/icons для WebView; тут они просто декодируются
     * ещё раз как Bitmap для обычных ImageButton.
     */
    private fun setupBottomNav() {
        findViewById<ImageButton>(R.id.navMusic).apply {
            setImageBitmap(loadAssetIcon("ui/icons/audios_aero.webp"))
            setOnClickListener { returnSection = null; pendingMusicUserId = null; showNativeSection(NativeSection.MUSIC) }
        }
        findViewById<ImageButton>(R.id.navCommunities).apply {
            setImageBitmap(loadAssetIcon("ui/icons/communities_aero.webp"))
            setOnClickListener { returnSection = null; showNativeSection(NativeSection.COMMUNITIES) }
        }
        findViewById<ImageButton>(R.id.navAddPost).apply {
            setImageBitmap(loadAssetIcon("ui/icons/new_post.webp"))
            setOnClickListener { openPostCreator() }
        }
        navHomeCenter.setOnClickListener { leaveNative(); webView.loadUrl("$SITE_ORIGIN") }
        findViewById<ImageButton>(R.id.navFriends).apply {
            setImageBitmap(loadAssetIcon("ui/icons/friends_aero.webp"))
            setOnClickListener { returnSection = null; showNativeSection(NativeSection.FRIENDS) }
        }
        findViewById<ImageButton>(R.id.navChats).apply {
            setImageBitmap(loadAssetIcon("ui/icons/chats_aero.webp"))
            setOnClickListener { returnSection = null; showNativeSection(NativeSection.CHATS) }
        }
    }

    private fun loadAssetIcon(path: String) = try {
        assets.open(path).use { BitmapFactory.decodeStream(it) }
    } catch (e: Exception) {
        Log.w(TAG, "Не удалось загрузить иконку из assets: $path", e)
        null
    }

    /**
     * Нативная верхняя панель — замена #main-top-bar сайта (createUtilityBar()/
     * updateAuthButtons() в auth.js), которую прячем через injectNavCleanup().
     */
    private fun setupTopNav() {
        findViewById<ImageButton>(R.id.navHome).setOnClickListener {
            leaveNative()
            webView.loadUrl("$SITE_ORIGIN")
        }
        findViewById<ImageView>(R.id.navCoinsIcon).setImageBitmap(loadAssetIcon("ui/icons/cd_coin.png"))
        findViewById<LinearLayout>(R.id.navCoins).setOnClickListener {
            showCdCoinsPurchaseDialog()
        }
        findViewById<ImageButton>(R.id.navNotifs).apply {
            setImageBitmap(loadAssetIcon("ui/icons/bell_aero.webp"))
            setOnClickListener {
                val js = """
                    if (typeof openNotificationsPanel === 'function') {
                        openNotificationsPanel(document.body);
                    }
                """.trimIndent()
                webView.evaluateJavascript(js, null)
            }
        }
        findViewById<LinearLayout>(R.id.navProfile).setOnClickListener { anchor ->
            if (isLoggedIn == false && !isPublicPath(webView.url)) {
                openLoginPage()
            } else {
                // На странице входа состояние могло смениться без перезагрузки — перепроверяем.
                if (isLoggedIn == false) refreshCdCoinsBalance()
                showProfileMenu(anchor)
            }
        }
    }

    private fun isPublicPath(url: String?): Boolean {
        val path = try { Uri.parse(url).path.orEmpty().trimEnd('/') } catch (e: Exception) { "" }
        return PUBLIC_PATH_PREFIXES.any { path == it || path.startsWith("$it/") }
    }

    /**
     * Нативные верхняя и нижняя панели нужны только авторизованному пользователю:
     * без сессии и на страницах входа/регистрации они скрыты.
     */
    private fun updateBarsVisibility() {
        if (paymentWebView != null) return // в платёжном режиме панели уже скрыты
        val show = barsShouldShow()
        if (!show) hideNativeSection()
        bottomNavBar.visibility = if (show) View.VISIBLE else View.GONE
        applyTopChrome(show)
        if (show) showBottomNav()
    }

    private fun barsShouldShow(): Boolean {
        val path = pathOf(currentUrl ?: webView.url)
        val authPage = isPublicPath(currentUrl ?: webView.url) &&
            PUBLIC_PATH_PREFIXES.take(3).any { path.startsWith(it) }
        return isLoggedIn != false && !authPage
    }

    private fun pathOf(url: String?): String =
        try { Uri.parse(url ?: "").path.orEmpty().trimEnd('/') } catch (e: Exception) { "" }

    /** Нативная «шапка раздела» (иконка + название) вместо полной верхней панели. */
    private class SectionChrome(val iconAsset: String, val titleRes: Int)

    private val chatsChrome = SectionChrome("ui/icons/chats_aero.webp", R.string.nav_chats)
    private val friendsChrome = SectionChrome("ui/icons/friends_aero.webp", R.string.nav_friends)
    private val communitiesChrome = SectionChrome("ui/icons/communities_aero.webp", R.string.nav_communities)
    private val musicChrome = SectionChrome("ui/icons/audios_aero.webp", R.string.nav_music)

    private fun activeSectionChrome(): SectionChrome? = when (nativeSection) {
        NativeSection.CHATS -> chatsChrome
        NativeSection.FRIENDS -> friendsChrome
        NativeSection.COMMUNITIES -> communitiesChrome
        NativeSection.MUSIC -> musicChrome
        null -> null
    }

    /** Показывает либо обычную верхнюю панель, либо шапку раздела, либо ничего. */
    private fun applyTopChrome(barsVisible: Boolean) {
        val section = if (barsVisible) activeSectionChrome() else null
        if (section != null) {
            sectionHeaderIcon.setImageBitmap(loadAssetIcon(section.iconAsset))
            sectionHeaderTitle.setText(section.titleRes)
            // Чужая библиотека: «Музыка: имя».
            if (nativeSection == NativeSection.MUSIC && !musicController.isOwnLibrary && musicController.viewedName.isNotBlank()) {
                sectionHeaderTitle.text = getString(section.titleRes) + ": " + musicController.viewedName
            }
        }
        sectionHeader.visibility = if (section != null) View.VISIBLE else View.GONE
        topNavBar.visibility = if (barsVisible && section == null) View.VISIBLE else View.GONE
        // Загрузка с устройства — только в своей библиотеке.
        sectionHeaderAction.visibility =
            if (section != null && nativeSection == NativeSection.MUSIC && musicController.isOwnLibrary) View.VISIBLE else View.GONE
    }

    /**
     * Перехватывает открытие диалога на странице /chats и показывает вместо него
     * нативный экран (ChatActivity). Сайт при открытии диалога ставит body-класс
     * `mobile-chat-open` и пишет `?chatId=…` в адрес (chats.js, openChat()), поэтому
     * достаточно следить за классом: берём chatId, имя/аватар собеседника из DOM,
     * передаём в приложение и закрываем веб-диалог его же функцией chatFeedmainClose()
     * (она останавливает long-poll сайта, чтобы он не «воровал» события у нативного экрана).
     */
    private fun injectChatWatcher(view: WebView) {
        val js = """
            (function() {
                if (window.__fpChatHooked) { if (window.__fpChatCheck) window.__fpChatCheck(); return; }
                window.__fpChatHooked = true;

                if (!document.getElementById('fortport-client-chat-style')) {
                    var st = document.createElement('style');
                    st.id = 'fortport-client-chat-style';
                    // Веб-диалог не мигает на экране: его место занимает нативный.
                    st.textContent = 'body.mobile-chat-open .chat-feedmain, body.mobile-chat-open #chat-feedmain { visibility: hidden !important; }';
                    document.head.appendChild(st);
                }

                // Веб-версия чата больше не должна слушать /api/chats/{id}/wait: этот long-poll
                // делит события с нативным экраном, и страница «забирала» бы часть сообщений.
                if (!window.__fpWaitBlocked) {
                    window.__fpWaitBlocked = true;
                    var origFetch = window.fetch;
                    window.fetch = function(input) {
                        try {
                            var u = (typeof input === 'string') ? input : ((input && input.url) || '');
                            if (/\/api\/chats\/[^\/]+\/wait/.test(u)) return new Promise(function() {});
                        } catch (e) {}
                        return origFetch.apply(this, arguments);
                    };
                }

                var launched = false;
                function ls(k) { try { return localStorage.getItem(k) || ''; } catch (e) { return ''; } }

                function check() {
                    var open = document.body && document.body.classList.contains('mobile-chat-open');
                    if (!open) { launched = false; return; }
                    if (launched) return;
                    var chatId = new URLSearchParams(location.search).get('chatId');
                    if (!chatId || !window.AndroidNav || !window.AndroidNav.openNativeChat) return;
                    launched = true;
                    var nameEl = document.getElementById('chat-partner-name');
                    var avEl = document.getElementById('chat-partner-avatar');
                    var stEl = document.getElementById('chat-partner-status');
                    var av = avEl ? (avEl.getAttribute('src') || '') : '';
                    if (av.indexOf('default-avatar') >= 0) av = '';
                    window.AndroidNav.openNativeChat(JSON.stringify({
                        chatId: chatId,
                        name: nameEl ? nameEl.textContent : '',
                        avatar: av,
                        status: stEl ? stEl.textContent : '',
                        myId: ls('userId'),
                        deviceId: ls('deviceId'),
                        ua: navigator.userAgent
                    }));
                    // Закрываем веб-диалог без записи в историю и убираем chatId из адреса.
                    try {
                        if (typeof window.chatFeedmainClose === 'function') window.chatFeedmainClose({ skipHistory: true });
                        var u = new URL(location.href);
                        u.searchParams.delete('chatId'); u.searchParams.delete('id');
                        history.replaceState({}, '', u);
                    } catch (e) {
                        var back = document.getElementById('chat-mobile-back');
                        if (back) back.click();
                    }
                    // openChat() сайта ещё доработает свои await'ы и может заново запустить опрос —
                    // повторно закрываем диалог, чтобы остановить его (см. блокировку fetch выше).
                    [800, 2500].forEach(function(ms) {
                        setTimeout(function() {
                            try { if (typeof window.chatFeedmainClose === 'function') window.chatFeedmainClose({ skipHistory: true }); } catch (e) {}
                        }, ms);
                    });
                }

                window.__fpChatCheck = check;
                new MutationObserver(check).observe(document.body, { attributes: true, attributeFilter: ['class'] });
                window.addEventListener('popstate', function() { setTimeout(check, 0); });
                check();
            })();
        """.trimIndent()
        view.evaluateJavascript(js, null)
    }

    private fun showNativeSection(section: NativeSection) {
        if (isLoggedIn == false) return
        if (section == NativeSection.MUSIC) {
            val changed = musicController.setViewedUser(pendingMusicUserId)
            if (changed && nativeSection == NativeSection.MUSIC) {
                // Уже на вкладке «Музыка», но открыли библиотеку другого человека (или вернулись к своей).
                updateAddPostButton(currentUrl)
                startNativeLoading()
                return
            }
        }
        if (nativeSection == section) return
        // Переключение между нативными вкладками: прежнюю останавливаем и прячем.
        stopNativeControllers()
        resetNativeSearch(nativeSection)
        nativeSection = section
        nativeChatsPanel.visibility = if (section == NativeSection.CHATS) View.VISIBLE else View.GONE
        nativeFriendsPanel.visibility = if (section == NativeSection.FRIENDS) View.VISIBLE else View.GONE
        nativeCommunitiesPanel.visibility = if (section == NativeSection.COMMUNITIES) View.VISIBLE else View.GONE
        nativeMusicPanel.visibility = if (section == NativeSection.MUSIC) View.VISIBLE else View.GONE
        updateAddPostButton(currentUrl)
        showBottomNav()
        startNativeLoading()
    }

    private fun startNativeLoading() {
        webView.evaluateJavascript(
            "(function(){try{return JSON.stringify({u:localStorage.getItem('userId')||'',d:localStorage.getItem('deviceId')||''});}catch(e){return '{}';}})()"
        ) { raw ->
            var uid = ""
            var did = ""
            try {
                val o = org.json.JSONObject(unwrapJsString(raw) ?: "{}")
                uid = o.optString("u", "")
                did = o.optString("d", "")
            } catch (e: Exception) {
                Log.w(TAG, "Не удалось прочитать сессию из WebView", e)
            }
            if (isFinishing || isDestroyed) return@evaluateJavascript
            val ua = webView.settings.userAgentString
            when (nativeSection) {
                NativeSection.CHATS -> chatsController.start(uid, did, ua)
                NativeSection.FRIENDS -> friendsController.start(uid, did, ua)
                NativeSection.COMMUNITIES -> communitiesController.start(uid, did, ua)
                NativeSection.MUSIC -> musicController.start(uid, did, ua)
                null -> {}
            }
        }
    }

    private fun hideNativeSection() {
        if (nativeSection == null) return
        val was = nativeSection
        nativeSection = null
        nativeChatsPanel.visibility = View.GONE
        nativeFriendsPanel.visibility = View.GONE
        nativeCommunitiesPanel.visibility = View.GONE
        nativeMusicPanel.visibility = View.GONE
        stopNativeControllers()
        resetNativeSearch(was)
        updateAddPostButton(currentUrl)
    }

    private fun stopNativeControllers() {
        chatsController.stop()
        friendsController.stop()
        communitiesController.stop()
        musicController.stop()
    }

    /** Строка поиска очищается при уходе с вкладки (как при новой загрузке страницы на сайте). */
    private fun resetNativeSearch(section: NativeSection?) {
        when (section) {
            NativeSection.FRIENDS -> friendsController.reset()
            NativeSection.COMMUNITIES -> communitiesController.reset()
            else -> {}
        }
    }

    /** Уход с нативной вкладки на веб-страницу кнопками панели. */
    private fun leaveNative() {
        returnSection = null
        hideNativeSection()
    }

    /** Нативный экран для адреса: /chats и /friends без параметров (с chatId/id — веб, как раньше). */
    private fun nativeSectionFor(uri: Uri): NativeSection? {
        if (uri.host?.endsWith(SITE_HOST) != true) return null
        val path = uri.path.orEmpty().trimEnd('/')
        if (path == "/music") {
            // Своя библиотека (без id или с собственным id) — нативный плеер; чужая — веб-страница.
            val id = uri.getQueryParameter("id")
            if (id != null && !id.all { it.isDigit() }) return null
            pendingMusicUserId = id?.takeIf { it.isNotEmpty() } // совпадение с собственным id определяет сам экран
            return NativeSection.MUSIC
        }
        if (uri.getQueryParameter("chatId") != null || uri.getQueryParameter("id") != null) return null
        return when (path) {
            "/chats" -> NativeSection.CHATS
            "/friends" -> NativeSection.FRIENDS
            "/communities" -> NativeSection.COMMUNITIES
            else -> null
        }
    }

    private fun openLoginPage() {
        webView.loadUrl("$SITE_ORIGIN$LOGIN_PATH")
    }

    /** При первом входе (сессии нет) сразу ведём на страницу авторизации. */
    private fun redirectToLoginIfNeeded() {
        if (autoLoginRedirectDone) return
        if (isPublicPath(webView.url)) return
        autoLoginRedirectDone = true
        openLoginPage()
    }

    /**
     * Нативное меню профиля (PopupMenu) — дублирует пункты из #user-dropdown
     * сайта (auth.js), поскольку сам #user-dropdown теперь недоступен: он
     * физически находится внутри скрытого #main-top-bar, а display:none на
     * родителе скрывает потомков безусловно, что бы мы ни делали с классом
     * .show у самого дропдауна.
     */
    private fun showProfileMenu(anchor: View) {
        val items = listOf(
            "Мой профиль" to "/profile",
            "Настройки" to "/settings",
            "Поддержка" to "/support",
            "О нас" to "/about",
            "Конфиденциальность" to "/privacy",
            "Условия" to "/terms",
            "Выйти" to null
        )
        val popup = PopupMenu(this, anchor)
        items.forEachIndexed { index, (label, _) -> popup.menu.add(0, index, index, label) }
        popup.setOnMenuItemClickListener { menuItem ->
            val (_, path) = items[menuItem.itemId]
            if (path == null) {
                webView.evaluateJavascript(
                    "if (typeof logout === 'function') { logout(); }",
                    null
                )
            } else {
                // Сначала используем штатную ссылку сайта: на некоторых страницах
                // переходы обрабатываются JavaScript-кодом сайта. Прямой loadUrl()
                // обходит обработчик ссылки и может оставить профиль на спиннере.
                val targetPath = path
                val targetUrl = "$SITE_ORIGIN$targetPath"
                val jsPath = targetPath.replace("\\", "\\\\").replace("'", "\\'")
                webView.evaluateJavascript("""
                    (function() {
                        var wanted = '$jsPath';
                        var links = Array.prototype.slice.call(document.querySelectorAll('#user-dropdown a[href], a[href]'));
                        var link = links.find(function(a) {
                            try { return new URL(a.href, location.href).pathname.replace(/\/$/, '') === wanted.replace(/\/$/, ''); }
                            catch (e) { return false; }
                        });
                        if (link) { link.click(); return 'clicked'; }
                        location.href = '$targetUrl';
                        return 'fallback';
                    })();
                """.trimIndent()) { result ->
                    Log.d(TAG, "Переход из меню профиля на $targetPath: $result")
                }
            }
            true
        }
        popup.show()
    }

    /**
     * Баланс и покупка CD-Coins используют same-origin API. Асинхронный fetch
     * возвращает результат в Android через JavascriptInterface: evaluateJavascript
     * сам по себе не ожидает завершения Promise.
     */
    private fun refreshCdCoinsBalance() {
        val js = """
            (function() {
              fetch('/api/payments/balance', {credentials: 'same-origin'})
                .then(function(r) {
                  if (r.status === 401 || r.status === 403) { AndroidNav.onAuthState(false); throw new Error('HTTP ' + r.status); }
                  if (!r.ok) throw new Error('HTTP ' + r.status);
                  AndroidNav.onAuthState(true);
                  return r.json();
                })
                .then(function(data) { AndroidNav.onCoinsBalance(JSON.stringify(data)); })
                .catch(function(e) { AndroidNav.onCoinsBalanceError(String(e)); });
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    private data class CdCoinOption(
        val id: Int,
        val label: String,
        val formattedPrice: String
    )

    private fun showCdCoinsPurchaseDialog() {
        val js = """
            (function() {
              fetch('/api/payments/options', {credentials: 'same-origin'})
                .then(function(r) {
                  if (!r.ok) throw new Error('HTTP ' + r.status);
                  return r.json();
                })
                .then(function(data) { AndroidNav.onCoinsOptions(JSON.stringify(data)); })
                .catch(function(e) { AndroidNav.onCoinsOptionsError(String(e)); });
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    private fun handleCdCoinsOptions(json: String) {
        if (isFinishing || isDestroyed) return
        try {
            val data = org.json.JSONObject(json)
            if (!data.optBoolean("enabled", false)) {
                showCdCoinsError("Покупка CD-Coins сейчас недоступна.")
                return
            }
            val array = data.optJSONArray("options") ?: org.json.JSONArray()
            val options = (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val id = item.optInt("id", -1)
                val label = item.optString("label", "")
                val price = item.optString("formatted", "")
                if (id < 0 || label.isBlank() || price.isBlank()) null
                else CdCoinOption(id, label, price)
            }
            if (options.isEmpty()) {
                showCdCoinsError("Сейчас нет доступных вариантов пополнения.")
                return
            }
            val labels = options.map { "${it.label} — ${it.formattedPrice}" }.toTypedArray()
            AlertDialog.Builder(this)
                .setTitle("Пополнение CD-Coins")
                .setItems(labels) { _, which -> createCdCoinsPayment(options[which]) }
                .setNegativeButton("Отмена", null)
                .show()
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка обработки вариантов CD-Coins", e)
            showCdCoinsError("Не удалось обработать варианты пополнения.")
        }
    }

    private fun createCdCoinsPayment(option: CdCoinOption) {
        val js = """
            (function() {
              fetch('/api/payments/create', {
                method: 'POST',
                credentials: 'same-origin',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify({optionId: ${option.id}})
              }).then(function(r) {
                return r.json().catch(function() { return {}; }).then(function(data) {
                  if (!r.ok) throw new Error(data.message || data.error || ('HTTP ' + r.status));
                  return data;
                });
              }).then(function(data) { AndroidNav.onCoinsPayment(JSON.stringify(data)); })
                .catch(function(e) { AndroidNav.onCoinsPaymentError(String(e)); });
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    private fun handleCdCoinsPayment(json: String) {
        if (isFinishing || isDestroyed) return
        try {
            val data = org.json.JSONObject(json)
            val confirmationUrl = data.optString("confirmationUrl", "")
            val uri = android.net.Uri.parse(confirmationUrl)
            val host = uri.host.orEmpty().lowercase()
            if (uri.scheme != "https" || host !in setOf("yoomoney.ru", "fortport.ru")) {
                val error = data.optString("message", data.optString("error", ""))
                showCdCoinsError(if (error.isNotBlank()) "Не удалось создать платёж. $error"
                    else "Сервер не вернул корректную ссылку на оплату.")
                return
            }
            openPaymentWebView(confirmationUrl)
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка создания платежа CD-Coins", e)
            showCdCoinsError("Не удалось обработать ответ платежа. Попробуйте ещё раз.")
        }
    }

    /**
     * Показывает платёж в отдельном WebView внутри Activity.
     * Основной WebView остаётся в памяти на прежней странице: это сохраняет SPA/DOM-состояние
     * и не заставляет пользователя заново авторизовываться после возврата из платёжной формы.
     * CookieManager общий для WebView приложения; third-party cookies разрешены для 3-D Secure.
     */
    @SuppressLint("SetJavaScriptEnabled")
    private fun openPaymentWebView(url: String) {
        if (paymentWebView != null) {
            paymentWebView?.loadUrl(url)
            return
        }

        val root = findViewById<ViewGroup>(R.id.rootLayout)
        val container = FrameLayout(this).apply {
            setBackgroundColor(android.graphics.Color.WHITE)
            elevation = 30f * resources.displayMetrics.density
            layoutParams = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        val payProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            visibility = View.VISIBLE
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (3 * resources.displayMetrics.density).toInt().coerceAtLeast(3),
                android.view.Gravity.TOP
            )
        }
        val payWebView = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.setSupportZoom(false)
            settings.builtInZoomControls = false
            settings.displayZoomControls = false
            settings.javaScriptCanOpenWindowsAutomatically = true
            settings.setSupportMultipleWindows(false)
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.userAgentString = webView.settings.userAgentString
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val uri = request.url
                    return when (uri.scheme?.lowercase()) {
                        "https", "http" -> false // платёжные редиректы и 3-D Secure остаются в приложении
                        "tel", "mailto" -> {
                            try { startActivity(Intent(Intent.ACTION_VIEW, uri)) } catch (e: Exception) {
                                Log.w(TAG, "Не удалось открыть ссылку из платёжной формы", e)
                            }
                            true
                        }
                        else -> true
                    }
                }

                override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                    payProgress.visibility = View.VISIBLE
                }

                override fun onPageFinished(view: WebView, url: String?) {
                    payProgress.visibility = View.GONE
                    CookieManager.getInstance().flush()
                    val finishedUri = Uri.parse(url ?: "")
                    Log.d(TAG, "Страница оплаты загружена: " + finishedUri.host + finishedUri.path)

                    // Некоторые кнопки выхода из платёжной формы ведут на API FortPort,
                    // который без отдельной авторизации показывает JSON {error: "not authenticated"}.
                    // Даём адресу загрузиться (чтобы сервер обработал переход), а затем
                    // возвращаем пользователя на главную сайта в основном WebView.
                    if (finishedUri.host.equals(SITE_HOST, ignoreCase = true) &&
                        finishedUri.path.orEmpty().startsWith("/api/")) {
                        Log.i(TAG, "Выход из оплаты на API-страницу; возвращаемся на главную")
                        closePaymentWebView()
                        webView.loadUrl(SITE_ORIGIN + "/")
                    }
                }

                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    super.onReceivedError(view, request, error)
                    if (request.isForMainFrame) {
                        payProgress.visibility = View.GONE
                        Log.e(TAG, "Ошибка загрузки платёжной страницы: " + error.description)
                    }
                }
            }
        }
        container.addView(payWebView)
        container.addView(payProgress)
        root.addView(container)

        paymentWebView = payWebView
        paymentContainer = container
        paymentProgress = payProgress
        // В платёжном режиме прячем обе нативные панели и отключаем pull-to-refresh.
        topNavBar.visibility = View.GONE
        sectionHeader.visibility = View.GONE
        bottomNavBar.visibility = View.GONE
        swipeRefresh.isEnabled = false
        swipeRefresh.visibility = View.GONE
        progressBar.visibility = View.GONE
        CookieManager.getInstance().flush()
        payWebView.loadUrl(url)
    }

    private fun closePaymentWebView() {
        val payWebView = paymentWebView ?: return
        paymentWebView = null
        paymentProgress = null
        paymentContainer?.let { (it.parent as? ViewGroup)?.removeView(it) }
        paymentContainer = null
        payWebView.stopLoading()
        payWebView.webChromeClient = null
        payWebView.webViewClient = WebViewClient()
        payWebView.destroy()
        swipeRefresh.visibility = View.VISIBLE
        swipeRefresh.isEnabled = true
        updateBarsVisibility()
        CookieManager.getInstance().flush()
        refreshCdCoinsBalance()
    }

    private fun showCdCoinsError(message: String) {
        if (isFinishing || isDestroyed) return
        AlertDialog.Builder(this)
            .setTitle("CD-Coins")
            .setMessage(message)
            .setPositiveButton("ОК", null)
            .show()
    }

    /**
     * Подтягивает текущую аватарку из localStorage.userAvatar (её выставляет
     * сайт после логина, см. updateAuthButtons() в auth.js) и загружает
     * картинку в фоновом потоке.
     */
    private fun loadProfileAvatar() {
        webView.evaluateJavascript(
            "(localStorage.getItem('userAvatar') || '');"
        ) { rawResult ->
            val path = unwrapJsString(rawResult)?.trim().orEmpty()
            val custom = path.isNotEmpty() && path != "null" && path != "undefined"
            if (custom) {
                val url = if (path.startsWith("http")) path else "$SITE_ORIGIN$path"
                fetchBitmapAsync(url) { bitmap ->
                    if (bitmap != null) navProfileAvatar.setImageBitmap(bitmap) else showDefaultAvatar()
                }
            } else {
                showDefaultAvatar() // аватар ещё не выбран владельцем аккаунта
            }
        }
    }

    /**
     * Аватар по умолчанию: сначала assets/default-avatar.jpg (если положить файл в APK),
     * затем /default-avatar.jpg с сайта, а если и это не вышло — нарисованный заполнитель.
     */
    private fun showDefaultAvatar() {
        loadAssetIcon("default-avatar.jpg")?.let {
            navProfileAvatar.setImageBitmap(it)
            return
        }
        navProfileAvatar.setImageResource(R.drawable.avatar_placeholder)
        fetchBitmapAsync("$SITE_ORIGIN/default-avatar.jpg") { bitmap ->
            if (bitmap != null) navProfileAvatar.setImageBitmap(bitmap)
        }
    }

    /** evaluateJavascript возвращает строку в виде JSON-литерала — снимаем кавычки/экранирование. */
    private fun unwrapJsString(raw: String?): String? {
        if (raw == null || raw == "null") return null
        var s = raw
        if (s.length >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            s = s.substring(1, s.length - 1)
        }
        return s.replace("\\\"", "\"").replace("\\\\", "\\")
    }

    private fun fetchBitmapAsync(url: String, onResult: (Bitmap?) -> Unit) {
        Thread {
            val bitmap = try {
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.connectTimeout = 8000
                connection.readTimeout = 8000
                connection.inputStream.use { BitmapFactory.decodeStream(it) }
            } catch (e: Exception) {
                Log.w(TAG, "Не удалось загрузить аватар: $url", e)
                null
            }
            runOnUiThread { onResult(bitmap) }
        }.start()
    }

    /**
     * Открывает форму создания поста через уже существующую на сайте функцию
     * window.openPostCreator() (post.js). Если она не определена на текущей
     * странице (эта функция доступна не на каждом экране сайта) — сначала
     * переходим на ленту и открываем форму сразу после её загрузки.
     */
    private fun openPostCreator() {
        // Форма поста штатно работает только на ленте. На любой другой вкладке
        // (музыка, чаты и т.д.) её скрипты не подгружены/не готовы и WebView
        // выдаёт ошибку, поэтому сначала переходим на ленту и открываем форму
        // тем же путём, что и на главной странице.
        // Если на текущей странице (профиль, порт/сообщество и т.д.) форма поста
        // уже есть — открываем её здесь же, не уводя пользователя на ленту: так
        // сайт публикует пост в контексте этой страницы. Только если функции нет
        // (музыка, чаты...) — переходим на ленту.
        webView.evaluateJavascript("typeof window.openPostCreator === 'function'") { result ->
            if (result == "true") {
                runOpenPostCreatorJs()
            } else {
                pendingOpenPostCreator = true
                webView.loadUrl("$SITE_ORIGIN/main")
            }
        }
    }

    /** Вызывает openPostCreator() сайта; если скрипт ещё не готов — повторяет до ~3 секунд. */
    private fun runOpenPostCreatorJs() {
        webView.evaluateJavascript("""
            (function() {
                var tries = 0;
                (function attempt() {
                    if (typeof openPostCreator === 'function') {
                        try { openPostCreator(); } catch (e) {}
                    } else if (tries++ < 15) {
                        setTimeout(attempt, 200);
                    }
                })();
            })();
        """.trimIndent(), null)
    }

    /** Разделы, где публиковать пост нельзя: вместо кнопки "+" — пустое место между двумя разделителями. */
    private val NO_POST_PATH_PREFIXES = listOf("/music", "/communities", "/friends", "/chats")

    private fun updateAddPostButton(url: String?) {
        currentUrl = url
        val path = pathOf(url)
        val noPost = nativeSection != null || NO_POST_PATH_PREFIXES.any { path == it || path.startsWith("$it/") }
        // INVISIBLE (а не GONE) сохраняет ширину кнопки — это и есть пустое место по центру.
        findViewById<View>(R.id.navAddPost).visibility = if (noPost) View.INVISIBLE else View.VISIBLE
        val dividerVis = if (noPost) View.VISIBLE else View.GONE
        findViewById<View>(R.id.navGapDividerLeft).visibility = dividerVis
        findViewById<View>(R.id.navGapDividerRight).visibility = dividerVis
        // В разделах с нативной шапкой «Домой» занимает место кнопки поста
        // (шапка заменяет верхнюю панель, где раньше была кнопка «Домой»).
        val hasSectionChrome = activeSectionChrome() != null
        navHomeCenter.visibility = if (hasSectionChrome) View.VISIBLE else View.GONE
        if (paymentWebView == null) applyTopChrome(barsShouldShow())
    }

    private fun hideBottomNav() {
        if (bottomNavBar.translationY == 0f) {
            bottomNavBar.animate().translationY(bottomNavBar.height.toFloat()).setDuration(200).start()
        }
    }

    private fun showBottomNav() {
        if (bottomNavBar.translationY != 0f) {
            bottomNavBar.animate().translationY(0f).setDuration(200).start()
        }
    }

    /**
     * Мост JS -> Android: авто-скрытие нижней панели при скролле ленты и
     * обновление бейджа уведомлений на нативной кнопке колокольчика (сайт
     * сам считает это число в updateNotificationBadge(), auth.js — мы просто
     * читаем готовый результат из DOM, см. injectNavCleanup()).
     */
    private inner class ScrollBridge {
        @JavascriptInterface
        fun onScroll(direction: String) {
            runOnUiThread {
                if (direction == "down") hideBottomNav() else showBottomNav()
            }
        }

        @JavascriptInterface
        fun openNativeChat(json: String) {
            runOnUiThread {
                reloadChatsOnResume = true
                startActivity(
                    Intent(this@MainActivity, ChatActivity::class.java)
                        .putExtra(ChatActivity.EXTRA_PAYLOAD, json)
                )
            }
        }

        @JavascriptInterface
        fun onNotifCount(countText: String) {
            runOnUiThread {
                if (countText.isBlank()) {
                    navNotifBadge.visibility = View.GONE
                } else {
                    navNotifBadge.text = countText
                    navNotifBadge.visibility = View.VISIBLE
                }
            }
        }

        @JavascriptInterface
        fun onCoinsBalance(json: String) {
            runOnUiThread {
                try {
                    val formatted = org.json.JSONObject(json).optString("formatted", "")
                    if (formatted.isNotBlank()) navCoinsValue.text = formatted
                } catch (e: Exception) {
                    Log.w(TAG, "Не удалось разобрать баланс CD-Coins", e)
                }
            }
        }

        @JavascriptInterface
        fun onAuthState(loggedIn: Boolean) {
            runOnUiThread {
                if (loggedIn) {
                    isLoggedIn = true
                    updateBarsVisibility()
                    return@runOnUiThread
                }
                // 401 по балансу ещё не значит "вышел" (короткий токен, гонка запросов, смена сети/VPN).
                // Решает только подтверждённая серией проверок /api/session/bootstrap, а не наличие куки.
                sessionGuard.requestVerify()
            }
        }

        @JavascriptInterface
        fun onCoinsBalanceError(error: String) {
            Log.w(TAG, "Не удалось загрузить баланс CD-Coins: $error")
        }

        @JavascriptInterface
        fun onCoinsOptions(json: String) {
            runOnUiThread { handleCdCoinsOptions(json) }
        }

        @JavascriptInterface
        fun onCoinsOptionsError(error: String) {
            Log.w(TAG, "Не удалось загрузить варианты CD-Coins: $error")
            runOnUiThread {
                showCdCoinsError("Не удалось загрузить варианты пополнения. Проверьте подключение и авторизацию.")
            }
        }

        @JavascriptInterface
        fun onCoinsPayment(json: String) {
            runOnUiThread { handleCdCoinsPayment(json) }
        }

        @JavascriptInterface
        fun onCoinsPaymentError(error: String) {
            Log.e(TAG, "Ошибка создания платежа CD-Coins: $error")
            runOnUiThread { showCdCoinsError("Не удалось создать платёж. $error") }
        }
    }

    /**
     * Отслеживаем скролл в document.addEventListener(..., true) — то есть
     * на фазе перехвата (capture), а не всплытия. Это единственный надёжный
     * способ поймать scroll-события из ЛЮБОГО вложенного прокручиваемого
     * блока на странице (а не только из window), не зная заранее точный
     * класс/id контейнера ленты на сайте, который может ещё и поменяться.
     */
    private fun injectScrollTracking(view: WebView) {
        val js = """
            (function() {
                if (window.__fortportScrollHooked) return;
                window.__fortportScrollHooked = true;
                var lastTop = {};
                var threshold = 8;
                function handle(e) {
                    var el = (e.target === document) ? (document.scrollingElement || document.documentElement) : e.target;
                    if (!el) return;
                    var top = (typeof el.scrollTop === 'number') ? el.scrollTop : window.scrollY;
                    var key = (el.id || el.className || 'win') + '';
                    var prev = lastTop[key];
                    if (prev === undefined) { lastTop[key] = top; return; }
                    var delta = top - prev;
                    if (Math.abs(delta) < threshold) return;
                    lastTop[key] = top;
                    if (window.AndroidNav) {
                        window.AndroidNav.onScroll(delta > 0 ? 'down' : 'up');
                    }
                }
                document.addEventListener('scroll', handle, true);
                window.addEventListener('scroll', handle, true);
            })();
        """.trimIndent()
        view.evaluateJavascript(js, null)
    }

    /**
     * Мост JS -> Android для нативного просмотра фото: страница вызывает
     * AndroidPhoto.open(json), мы запускаем PhotoViewerActivity.
     */
    private inner class PhotoBridge {
        @JavascriptInterface
        fun open(json: String) {
            runOnUiThread {
                startActivity(
                    Intent(this@MainActivity, PhotoViewerActivity::class.java)
                        .putExtra(PhotoViewerActivity.EXTRA_PAYLOAD, json)
                )
            }
        }
    }

    /**
     * Подменяет window.openLightbox (его вызывают клики по фото в постах,
     * вложениям комментариев и аватаркам, см. client.js/display-post.js) так,
     * чтобы вместо веб-оверлея открывался нативный просмотрщик.
     *
     * Через defineProperty, а не простое присваивание: если client.js
     * присвоит window.openLightbox позже нашей подмены, оригинал всё равно
     * окажется в замыкании (fallback на случай, если мост недоступен).
     */
    private fun injectPhotoViewerHook(view: WebView) {
        val js = """
            (function() {
                if (window.__fpPhotoHooked || !window.AndroidPhoto) return;
                window.__fpPhotoHooked = true;
                var orig = window.openLightbox;

                function abs(p) {
                    try { return new URL(String(p), location.origin).href; } catch (e) { return ''; }
                }
                function norm(it) {
                    if (!it) return null;
                    if (typeof it === 'string') {
                        var m = it.match(/^\/photo\/(\d+)/);
                        return { url: abs(it), type: 'image', mediaId: m ? Number(m[1]) : null };
                    }
                    var raw = it.path || it.url || '';
                    var m2 = String(raw).match(/^\/photo\/(\d+)/);
                    var id = Number(it.mediaId || it.photoId || (m2 && m2[1]) || 0) || null;
                    var t = (it.type === 'video' || it.type === 'gif') ? it.type : 'image';
                    return { url: abs(raw), type: t, mediaId: id };
                }

                function hooked(src, items) {
                    try {
                        var first = norm(src);
                        if (!first || !first.url) throw new Error('no url');
                        var list = (Array.isArray(items) && items.length ? items.map(norm) : [first])
                            .filter(function (x) { return x && x.url; });
                        var idx = -1;
                        list.forEach(function (x, i) {
                            if (idx < 0 && ((first.mediaId && x.mediaId && first.mediaId === x.mediaId) || x.url === first.url)) idx = i;
                        });
                        if (idx < 0) { list.unshift(first); idx = 0; }
                        window.AndroidPhoto.open(JSON.stringify({
                            items: list,
                            index: idx,
                            deviceId: (function () { try { return localStorage.getItem('deviceId') || ''; } catch (e) { return ''; } })(),
                            ua: navigator.userAgent
                        }));
                        return;
                    } catch (e) { /* падаем обратно на штатный лайтбокс сайта */ }
                    if (typeof orig === 'function') return orig.apply(this, arguments);
                }

                Object.defineProperty(window, 'openLightbox', {
                    configurable: true,
                    get: function () { return hooked; },
                    set: function (fn) { orig = fn; }
                });
            })();
        """.trimIndent()
        view.evaluateJavascript(js, null)
    }

    /**
     * Прячет тёмную строку служебных ссылок вверху страницы (#utility-bar —
     * Настройки/Поддержка/О нас/Конфиденциальность/Условия, создаётся в
     * createUtilityBar() в auth.js) и переносит эти же пункты (кроме
     * "Настройки" — он там уже есть) в выпадающее меню у аватарки
     * (#user-dropdown, туда же, где "Мой профиль"/"Настройки"/"Выйти").
     *
     * Сайт пересоздаёт оба блока через JS при каждой загрузке страницы
     * (и возможно повторно без полной перезагрузки — после логина/логаута),
     * поэтому держим MutationObserver, а не добавляем пункты один раз.
     */
    private fun injectNavCleanup(view: WebView) {
        val js = """
            (function() {
                if (!document.getElementById('fortport-client-nav-cleanup')) {
                    var style = document.createElement('style');
                    style.id = 'fortport-client-nav-cleanup';
                    style.textContent = '#utility-bar, #main-top-bar { display: none !important; }';
                    document.head.appendChild(style);
                }

                function addUtilityLinksToDropdown() {
                    var dropdown = document.getElementById('user-dropdown');
                    if (!dropdown) return;
                    if (dropdown.querySelector('.fortport-client-extra-link')) return;
                    var divider = dropdown.querySelector('.dropdown-divider');
                    var links = [
                        ['/support', 'Поддержка'],
                        ['/about', 'О нас'],
                        ['/privacy', 'Конфиденциальность'],
                        ['/terms', 'Условия']
                    ];
                    var frag = document.createDocumentFragment();
                    links.forEach(function(pair) {
                        var a = document.createElement('a');
                        a.href = pair[0];
                        a.className = 'dropdown-item fortport-client-extra-link';
                        a.textContent = pair[1];
                        frag.appendChild(a);
                    });
                    if (divider) {
                        dropdown.insertBefore(frag, divider);
                    } else {
                        dropdown.appendChild(frag);
                    }
                }

                addUtilityLinksToDropdown();

                // Бейдж на родном колокольчике сайта считает сам (updateNotificationBadge()
                // в auth.js) — мы лишь читаем готовое число из DOM и пробрасываем в нативную
                // кнопку, т.к. сам колокольчик сайта теперь скрыт вместе с #main-top-bar.
                var lastNotifText = null;
                function reportNotifBadge() {
                    var badge = document.querySelector('.user-notifs .notification-badge');
                    var text = (badge && badge.style.display !== 'none') ? badge.textContent : '';
                    if (text === lastNotifText) return;
                    lastNotifText = text;
                    if (window.AndroidNav) {
                        window.AndroidNav.onNotifCount(text);
                    }
                }

                reportNotifBadge();

                if (!window.__fortportNavObserverSet) {
                    window.__fortportNavObserverSet = true;
                    var observer = new MutationObserver(function() {
                        addUtilityLinksToDropdown();
                        reportNotifBadge();
                    });
                    observer.observe(document.body, { childList: true, subtree: true, characterData: true, attributes: true });
                }
            })();
        """.trimIndent()
        view.evaluateJavascript(js, null)
    }

    private fun loadSite() {
        if (isNetworkAvailable()) {
            offlineView.visibility = View.GONE
            swipeRefresh.visibility = View.VISIBLE
            webView.settings.cacheMode = WebSettings.LOAD_DEFAULT
            webView.loadUrl(getString(R.string.site_url))
        } else {
            // Пытаемся показать последнюю сохранённую версию из кэша, если она есть
            webView.settings.cacheMode = WebSettings.LOAD_CACHE_ELSE_NETWORK
            webView.loadUrl(getString(R.string.site_url))
            showOffline()
        }
    }

    private fun showOffline() {
        offlineView.visibility = View.VISIBLE
    }

    /**
     * Картинка для плашки "не удалось подключиться к серверу" — отдельные
     * файлы под светлую/тёмную тему, добавлены в assets/ui/logos/ как
     * net_failconnect_day.png / net_failconnect_dark.png. Смена темы
     * пересоздаёт Activity (configChanges не перехватывает uiMode), так что
     * достаточно выставить картинку один раз в onCreate.
     */
    private fun loadOfflineImage() {
        val isNight = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val path = if (isNight) "ui/logos/net_failconnect_dark.png" else "ui/logos/net_failconnect_day.png"
        offlineImage.setImageBitmap(loadAssetIcon(path))
    }

    /**
     * Убирает "; wv" из стандартного User-Agent WebView и следит, чтобы строка
     * заканчивалась как у обычного мобильного Chrome ("Mobile Safari/...").
     * Многие сайты определяют мобильную/десктопную вёрстку именно по этим
     * маркерам, поэтому такой User-Agent надёжнее заставляет их отдавать
     * мобильную версию интерфейса.
     */
    private fun buildMobileUserAgent(defaultUa: String): String {
        var ua = defaultUa.replace("; wv", "")
        if (!ua.contains("Mobile Safari")) {
            ua = ua.replace("Safari/", "Mobile Safari/")
        }
        return ua
    }

    /**
     * Страховка на случай, если конкретная версия WebView всё же не применит
     * мобильный @media-запрос сайта (@media (max-width: 830px) в base.css,
     * который делает .new-post-modal / .flag-modal / .add-lightbox шириной
     * 100%). Добавляем те же самые правила через <style> с !important —
     * они либо совпадут с уже применёнными сайтом (без видимого эффекта),
     * либо исправят раскладку, если по каким-то причинам сайт их не применил.
     * Условие window.innerWidth <= 830 не даёт правилу повлиять на настоящий
     * десктоп, если пользователь когда-нибудь откроет тот же движок на большом
     * экране (Chromebook, планшет в альбомной ориентации и т.п.).
     *
     * Этим же стилем прячем родные мобильные элементы сайта, которые теперь
     * дублируют нашу нижнюю панель: плавающую кнопку публикации поста
     * (.new-post-fab) и строку кнопок под шапкой (.nav-left-links, вместе
     * с .chats-toggle-btn внутри неё) — на мобильной вёрстке сайта это и
     * есть та самая "панель сверху" и "кнопка сбоку".
     */
    private fun injectMobileLayoutSafetyNet(view: WebView) {
        val js = """
            (function() {
                if (document.getElementById('fortport-client-safety-net')) return;
                if (window.innerWidth > 830) return;
                var style = document.createElement('style');
                style.id = 'fortport-client-safety-net';
                style.textContent = '.new-post-modal, .flag-modal, .add-lightbox {' +
                    'width: 100% !important;' +
                    'max-width: 100% !important;' +
                    'max-height: calc(100dvh - 20px) !important;' +
                    'box-sizing: border-box !important;' +
                '}' +
                '.new-post-fab, .nav-left-links {' +
                    'display: none !important;' +
                '}';
                document.head.appendChild(style);
            })();
        """.trimIndent()
        view.evaluateJavascript(js, null)
    }

    private fun isNetworkAvailable(): Boolean {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return false
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    override fun onBackPressed() {
        val payWebView = paymentWebView
        if (payWebView != null) {
            if (payWebView.canGoBack()) {
                payWebView.goBack()
            } else {
                closePaymentWebView()
            }
            return
        }
        if (nativeSection != null) {
            hideNativeSection()
            // Под списком может лежать веб-версия /chats или /friends — тогда идём назад по истории.
            if (pathOf(webView.url) !in setOf("/chats", "/friends", "/communities", "/music")) return
        } else if (returnSection != null && pathOf(webView.url) in setOf("/profile", "/community", "/new_community")) {
            // Профиль/порт открыли из нативного списка — «Назад» возвращает в него.
            val back = returnSection!!
            returnSection = null
            if (webView.canGoBack()) webView.goBack()
            showNativeSection(back)
            return
        }
        handleHistoryBack()
    }

    private fun handleHistoryBack() {
        if (webView.canGoBack()) {
            val list = webView.copyBackForwardList()
            var target = list.currentIndex - 1
            // Уже вошли — страницы входа/регистрации в истории пропускаем.
            if (isLoggedIn == true) {
                while (target >= 0 && isAuthPage(list.getItemAtIndex(target).url)) target--
            }
            // Открытие/закрытие нативного чата оставляет в истории дубли /chats — пропускаем их.
            val currentChatsUrl = list.getItemAtIndex(list.currentIndex).url
            if (currentChatsUrl != null && Uri.parse(currentChatsUrl).path.orEmpty().trimEnd('/') == "/chats") {
                while (target >= 0 && Uri.parse(list.getItemAtIndex(target).url).path.orEmpty().trimEnd('/') == "/chats") target--
            }
            if (target >= 0) {
                webView.goBackOrForward(target - list.currentIndex)
            } else {
                super.onBackPressed()
            }
        } else {
            super.onBackPressed()
        }
    }

    private fun isAuthPage(url: String?): Boolean {
        val path = try { Uri.parse(url).path.orEmpty().trimEnd('/') } catch (e: Exception) { "" }
        return listOf(LOGIN_PATH, "/register", "/signup").any { path == it || path.startsWith("$it/") }
    }

    override fun onResume() {
        super.onResume()
        // После нативного чата обновляем список переписок (последнее сообщение, счётчики).
        if (reloadChatsOnResume) {
            reloadChatsOnResume = false
            if (nativeSection != NativeSection.CHATS) webView.reload()
        }
        if (nativeSection != null) startNativeLoading()
        sessionGuard.start()
    }

    override fun onPause() {
        super.onPause()
        sessionGuard.stop()
        stopNativeControllers()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // Если разрешение на камеру только что выдано, повторный вызов onShowFileChooser
        // на стороне WebView предложит выбор камеры/галереи снова.
    }

    override fun onDestroy() {
        chatsController.destroy()
        friendsController.destroy()
        communitiesController.destroy()
        musicController.destroy()
        sessionGuard.destroy()
        siteApi.destroy()
        paymentWebView?.let { it.stopLoading(); it.destroy() }
        paymentWebView = null
        webView.destroy()
        super.onDestroy()
    }
}
