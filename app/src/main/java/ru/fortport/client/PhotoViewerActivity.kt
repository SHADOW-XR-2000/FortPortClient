package ru.fortport.client

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.drawable.Animatable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Drawable
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.util.LruCache
import android.view.View
import android.webkit.CookieManager
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory
import androidx.core.view.WindowCompat
import androidx.core.widget.ImageViewCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.util.concurrent.Executors

/**
 * Нативный полноэкранный просмотр фото из постов, комментариев и аватарок.
 *
 * Запускается из MainActivity (через мост AndroidPhoto), когда страница вызывает
 * window.openLightbox(...). Закрыть / листать / сохранить себе / скачать —
 * нативные кнопки, поэтому верхняя и нижняя панели основного экрана их
 * больше не перекрывают.
 *
 * Иконки берутся из assets/ui/icons: close_ico.png, favor_ico.png, download_ico.png.
 */
class PhotoViewerActivity : AppCompatActivity() {

    private class Item(val url: String, val type: String, val mediaId: Long?)

    private class Meta(val username: String, val avatarUrl: String?, val isSaved: Boolean)

    private lateinit var items: List<Item>
    private var index = 0
    private var deviceId = ""
    private var userAgent: String? = null

    private lateinit var imageView: ZoomImageView
    private lateinit var progress: ProgressBar
    private lateinit var errorView: TextView
    private lateinit var nickView: TextView
    private lateinit var avatarView: ImageView
    private lateinit var prevButton: ImageButton
    private lateinit var nextButton: ImageButton
    private lateinit var favoriteButton: ImageButton
    private lateinit var downloadButton: ImageButton

    private val io = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())

    /** Увеличивается при каждой смене фото — устаревшие ответы игнорируются. */
    private var loadToken = 0

    private var currentBytes: ByteArray? = null
    private val savedIds = HashSet<Long>()
    private val metaCache = HashMap<Long, Meta>()
    private val avatarCache = HashMap<String, Bitmap>()
    private val bytesCache = object : LruCache<String, ByteArray>(CACHE_BYTES) {
        override fun sizeOf(key: String, value: ByteArray): Int = value.size
    }

    private var iconColor = 0
    private var pendingSave: (() -> Unit)? = null

    private val storagePermission =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted ->
            val action = pendingSave
            pendingSave = null
            if (granted && action != null) action() else toast("Нет разрешения на сохранение файлов")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!parsePayload()) {
            finish()
            return
        }
        setContentView(R.layout.activity_photo_viewer)
        setupSystemBars()

        imageView = findViewById(R.id.viewerImage)
        progress = findViewById(R.id.viewerProgress)
        errorView = findViewById(R.id.viewerError)
        nickView = findViewById(R.id.viewerNick)
        avatarView = findViewById(R.id.viewerAvatar)
        prevButton = findViewById(R.id.viewerPrev)
        nextButton = findViewById(R.id.viewerNext)
        favoriteButton = findViewById(R.id.viewerFavorite)
        downloadButton = findViewById(R.id.viewerDownload)

        iconColor = ContextCompat.getColor(this, R.color.fp_on_background)

        val closeButton = findViewById<ImageButton>(R.id.viewerClose)
        setAssetIcon(closeButton, "ui/icons/close_ico.png")
        setAssetIcon(favoriteButton, "ui/icons/favor_ico.png")
        setAssetIcon(downloadButton, "ui/icons/download_ico.png")
        // Стрелки — наши векторные, они монохромные и красятся под тему
        ImageViewCompat.setImageTintList(prevButton, ColorStateList.valueOf(iconColor))
        ImageViewCompat.setImageTintList(nextButton, ColorStateList.valueOf(iconColor))

        closeButton.setOnClickListener { finish() }
        prevButton.setOnClickListener { show((index - 1 + items.size) % items.size) }
        nextButton.setOnClickListener { show((index + 1) % items.size) }
        favoriteButton.setOnClickListener { onFavoriteClick() }
        downloadButton.setOnClickListener { onDownloadClick() }

        val multiple = items.size > 1
        prevButton.visibility = if (multiple) View.VISIBLE else View.GONE
        nextButton.visibility = if (multiple) View.VISIBLE else View.GONE

        show(index)
    }

    override fun onDestroy() {
        loadToken++
        io.shutdownNow()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- данные

    private fun parsePayload(): Boolean {
        return try {
            val root = JSONObject(intent.getStringExtra(EXTRA_PAYLOAD) ?: return false)
            deviceId = root.optString("deviceId", "")
            userAgent = root.optString("ua", "").ifBlank { null }
            val arr = root.optJSONArray("items") ?: JSONArray()
            val selected = root.optInt("index", 0)

            val list = ArrayList<Item>()
            var newIndex = 0
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val type = o.optString("type", "image")
                val url = o.optString("url", "")
                // Видео в просмотрщике не показываем — оно играет в самом посте.
                if (type == "video" || !url.startsWith("https://")) continue
                if (i == selected) newIndex = list.size
                list.add(Item(url, type, o.optLong("mediaId", 0L).takeIf { it > 0L }))
            }
            if (list.isEmpty()) return false
            items = list
            index = newIndex
            true
        } catch (e: Exception) {
            Log.w(TAG, "Не удалось разобрать данные для просмотра фото", e)
            false
        }
    }

    private fun show(i: Int) {
        index = i
        val item = items[i]
        val token = ++loadToken

        currentBytes = null
        imageView.setImageDrawable(null)
        errorView.visibility = View.GONE
        progress.visibility = View.VISIBLE
        applyMeta(null, item, token)

        io.execute {
            try {
                val bytes = bytesCache.get(item.url) ?: httpGet(item.url).also { bytesCache.put(item.url, it) }
                val drawable = decodeDrawable(bytes)
                main.post {
                    if (token != loadToken || isFinishing) return@post
                    currentBytes = bytes
                    imageView.setImageDrawable(drawable)
                    (drawable as? Animatable)?.start()
                    progress.visibility = View.GONE
                }
                prefetchNeighbours(i)
            } catch (e: Throwable) {
                Log.w(TAG, "Не удалось загрузить фото ${item.url}", e)
                main.post {
                    if (token != loadToken || isFinishing) return@post
                    progress.visibility = View.GONE
                    errorView.text = "Не удалось загрузить фото"
                    errorView.visibility = View.VISIBLE
                }
            }
        }

        val id = item.mediaId
        if (id != null) {
            val cached = metaCache[id]
            if (cached != null) {
                applyMeta(cached, item, token)
            } else {
                io.execute {
                    try {
                        val meta = fetchMeta(id)
                        main.post {
                            if (isFinishing) return@post
                            metaCache[id] = meta
                            if (token == loadToken) applyMeta(meta, item, token)
                        }
                    } catch (e: Throwable) {
                        Log.w(TAG, "Не удалось загрузить данные фото $id", e)
                    }
                }
            }
        }
    }

    private fun prefetchNeighbours(from: Int) {
        if (items.size < 2) return
        val next = items[(from + 1) % items.size]
        if (bytesCache.get(next.url) != null) return
        try {
            bytesCache.put(next.url, httpGet(next.url))
        } catch (e: Throwable) {
            // не критично — загрузим при переходе
        }
    }

    private fun fetchMeta(id: Long): Meta {
        val body = String(httpGet("$SITE_ORIGIN/api/photo/$id"), Charsets.UTF_8)
        val json = JSONObject(body)
        return Meta(
            username = json.optString("username", ""),
            avatarUrl = json.optString("profilePicture", "").ifBlank { null },
            isSaved = json.optBoolean("isSaved", false)
        )
    }

    // ------------------------------------------------------------- интерфейс

    private fun applyMeta(meta: Meta?, item: Item, token: Int) {
        val id = item.mediaId
        when {
            id == null -> {
                nickView.text = "Медиафайл"
                avatarView.visibility = View.INVISIBLE
            }
            meta == null -> {
                nickView.text = "Загрузка..."
                avatarView.visibility = View.VISIBLE
                avatarView.setImageDrawable(
                    GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = 8f * resources.displayMetrics.density
                        setColor(PLACEHOLDER_COLOR)
                    }
                )
            }
            else -> {
                nickView.text = meta.username.ifBlank { "Аноним" }
                avatarView.visibility = View.VISIBLE
                if (meta.isSaved) savedIds.add(id)
                loadAvatar(meta.avatarUrl ?: "/default-avatar.jpg", token)
            }
        }
        updateFavoriteState(item)
    }

    private fun loadAvatar(rawUrl: String, token: Int) {
        val url = resolveUrl(rawUrl) ?: return
        avatarCache[url]?.let {
            setAvatar(it)
            return
        }
        io.execute {
            try {
                val bmp = decodeSampled(httpGet(url), AVATAR_MAX_SIDE) ?: return@execute
                main.post {
                    if (isFinishing) return@post
                    avatarCache[url] = bmp
                    if (token == loadToken) setAvatar(bmp)
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Не удалось загрузить аватар $url", e)
            }
        }
    }

    /**
     * Аватарка — квадрат со скруглёнными углами. Сначала обрезаем bitmap до
     * квадрата по центру, затем скругляем углы (радиус — доля от стороны).
     */
    private fun setAvatar(bitmap: Bitmap) {
        val side = minOf(bitmap.width, bitmap.height)
        val square = if (bitmap.width == bitmap.height) {
            bitmap
        } else {
            Bitmap.createBitmap(
                bitmap,
                (bitmap.width - side) / 2,
                (bitmap.height - side) / 2,
                side,
                side
            )
        }
        val rounded = RoundedBitmapDrawableFactory.create(resources, square)
        rounded.cornerRadius = side * AVATAR_CORNER_RATIO
        rounded.setAntiAlias(true)
        avatarView.setImageDrawable(rounded)
    }

    private fun updateFavoriteState(item: Item) {
        val id = item.mediaId
        val saved = id != null && savedIds.contains(id)
        favoriteButton.isEnabled = id != null && !saved
        // Иконка цветная и без «заливки», поэтому состояние показываем прозрачностью
        // (как на сайте: сохранённое — полупрозрачное и неактивное).
        favoriteButton.alpha = when {
            id == null -> 0.35f
            saved -> 0.5f
            else -> 1f
        }
    }

    private fun setAssetIcon(button: ImageButton, assetPath: String) {
        try {
            assets.open(assetPath).use { button.setImageBitmap(BitmapFactory.decodeStream(it)) }
        } catch (e: IOException) {
            Log.w(TAG, "Иконка $assetPath не найдена в assets", e)
        }
    }

    private fun setupSystemBars() {
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val bg = ContextCompat.getColor(this, R.color.fp_background)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.isAppearanceLightStatusBars = !night
        controller.isAppearanceLightNavigationBars = !night
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    // ------------------------------------------------------ сохранить / скачать

    private fun onFavoriteClick() {
        val id = items[index].mediaId ?: return
        if (savedIds.contains(id)) return
        favoriteButton.isEnabled = false
        io.execute {
            try {
                val (code, body) = httpRequest(
                    "$SITE_ORIGIN/api/photos/save",
                    "POST",
                    """{"photoId":$id}""",
                    0
                )
                val json = try { JSONObject(String(body, Charsets.UTF_8)) } catch (e: Exception) { null }
                val ok = json?.optBoolean("success", false) == true
                main.post {
                    if (isFinishing) return@post
                    if (ok) {
                        savedIds.add(id)
                        toast("Сохранено в ваши фото")
                    } else {
                        val message = when {
                            code == 401 -> "Войдите, чтобы сохранять"
                            json != null && json.optString("error").isNotBlank() ->
                                "Ошибка: " + json.optString("error")
                            else -> "Не удалось сохранить"
                        }
                        toast(message)
                    }
                    updateFavoriteState(items[index])
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Ошибка сохранения фото $id", e)
                main.post {
                    if (isFinishing) return@post
                    toast("Ошибка сервера")
                    updateFavoriteState(items[index])
                }
            }
        }
    }

    private fun onDownloadClick() {
        val bytes = currentBytes
        if (bytes == null) {
            toast("Фото ещё загружается")
            return
        }
        val item = items[index]
        val start = { saveToGallery(bytes, item) }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            pendingSave = start
            storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            start()
        }
    }

    private fun saveToGallery(bytes: ByteArray, item: Item) {
        val (mime, ext) = sniffType(bytes)
        val name = "fortport_" + (item.mediaId?.toString() ?: System.currentTimeMillis().toString())
        io.execute {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, "$name.$ext")
                        put(MediaStore.Images.Media.MIME_TYPE, mime)
                        put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/FortPort")
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    }
                    val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                        ?: throw IOException("MediaStore insert failed")
                    try {
                        contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                            ?: throw IOException("openOutputStream failed")
                        values.clear()
                        values.put(MediaStore.Images.Media.IS_PENDING, 0)
                        contentResolver.update(uri, values, null, null)
                    } catch (e: Exception) {
                        contentResolver.delete(uri, null, null)
                        throw e
                    }
                } else {
                    @Suppress("DEPRECATION")
                    val dir = File(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                        "FortPort"
                    )
                    dir.mkdirs()
                    val file = File(dir, "$name.$ext")
                    file.writeBytes(bytes)
                    MediaScannerConnection.scanFile(this, arrayOf(file.absolutePath), arrayOf(mime), null)
                }
                main.post { if (!isFinishing) toast("Сохранено в галерею (Pictures/FortPort)") }
            } catch (e: Throwable) {
                Log.w(TAG, "Не удалось сохранить файл в галерею", e)
                main.post { if (!isFinishing) toast("Не удалось сохранить файл") }
            }
        }
    }

    // ------------------------------------------------------------------ сеть

    private fun httpGet(url: String): ByteArray {
        val (code, body) = httpRequest(url, "GET", null, 0)
        if (code !in 200..299) throw IOException("HTTP $code для $url")
        return body
    }

    /**
     * Запрос с куками сессии и X-Device-Id (как это делает сам сайт в своих fetch).
     * Куки и device-id отправляются только на fortport.ru; редиректы идут вручную,
     * чтобы не унести их на сторонний хост.
     */
    private fun httpRequest(url: String, method: String, body: String?, depth: Int): Pair<Int, ByteArray> {
        val u = URL(url)
        require(u.protocol == "https") { "Только https" }
        val conn = u.openConnection() as HttpURLConnection
        try {
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.requestMethod = method
            userAgent?.let { conn.setRequestProperty("User-Agent", it) }
            if (isSiteHost(u.host)) {
                CookieManager.getInstance().getCookie(url)?.let { conn.setRequestProperty("Cookie", it) }
                if (deviceId.isNotBlank()) conn.setRequestProperty("X-Device-Id", deviceId)
            }
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            if (code in 300..399 && depth < MAX_REDIRECTS) {
                val location = conn.getHeaderField("Location")
                    ?: throw IOException("Redirect без Location")
                val next = resolveUrl(location) ?: throw IOException("Некорректный redirect")
                // POST после редиректа не повторяем — для наших запросов это не нужно.
                return httpRequest(next, "GET", null, depth + 1)
            }
            val stream = if (code in 200..399) conn.inputStream else conn.errorStream
            return code to (stream?.use { it.readBytes() } ?: ByteArray(0))
        } finally {
            conn.disconnect()
        }
    }

    private fun isSiteHost(host: String): Boolean =
        host.equals(SITE_HOST, ignoreCase = true) || host.endsWith(".$SITE_HOST", ignoreCase = true)

    private fun resolveUrl(raw: String): String? = try {
        val abs = URL(URL(SITE_ORIGIN), raw).toString()
        if (abs.startsWith("https://")) abs else null
    } catch (e: Exception) {
        null
    }

    // -------------------------------------------------------------- картинки

    /** Для API 28+ — ImageDecoder (понимает анимированные GIF/WebP), ниже — статичный кадр. */
    private fun decodeDrawable(bytes: ByteArray): Drawable {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(ByteBuffer.wrap(bytes))
            return ImageDecoder.decodeDrawable(source) { decoder, info, _ ->
                val w = info.size.width
                val h = info.size.height
                val longest = maxOf(w, h)
                if (longest > MAX_SIDE) {
                    val k = MAX_SIDE.toFloat() / longest
                    decoder.setTargetSize(
                        (w * k).toInt().coerceAtLeast(1),
                        (h * k).toInt().coerceAtLeast(1)
                    )
                }
            }
        }
        val bmp = decodeSampled(bytes, MAX_SIDE) ?: throw IOException("Не удалось декодировать изображение")
        return BitmapDrawable(resources, bmp)
    }

    private fun decodeSampled(bytes: ByteArray, maxSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }

    /** Определяем тип по сигнатуре файла: у ссылок вида /photo/123 расширения нет. */
    private fun sniffType(b: ByteArray): Pair<String, String> {
        fun at(offset: Int, text: String): Boolean =
            b.size >= offset + text.length &&
                text.indices.all { b[offset + it] == text[it].code.toByte() }
        return when {
            b.size >= 4 && b[0] == 0x89.toByte() && at(1, "PNG") -> "image/png" to "png"
            b.size >= 2 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() -> "image/jpeg" to "jpg"
            at(0, "GIF8") -> "image/gif" to "gif"
            at(0, "RIFF") && at(8, "WEBP") -> "image/webp" to "webp"
            else -> "image/jpeg" to "jpg"
        }
    }

    companion object {
        const val EXTRA_PAYLOAD = "payload"

        private const val TAG = "PhotoViewer"
        private const val SITE_HOST = "fortport.ru"
        private const val SITE_ORIGIN = "https://fortport.ru"
        private const val CACHE_BYTES = 24 * 1024 * 1024
        private const val MAX_SIDE = 4096
        private const val AVATAR_MAX_SIDE = 256
        private const val MAX_REDIRECTS = 3
        private const val PLACEHOLDER_COLOR = 0x33808080

        /** Скругление аватарки: доля от стороны (0.5 — круг, 0 — квадрат). */
        private const val AVATAR_CORNER_RATIO = 0.2f
    }
}
