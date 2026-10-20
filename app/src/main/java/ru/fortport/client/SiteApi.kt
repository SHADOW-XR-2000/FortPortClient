package ru.fortport.client

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.webkit.CookieManager
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import android.util.LruCache
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Общий слой для нативных разделов (переписки, друзья): HTTP-клиент с сессией сайта
 * (куки WebView + X-Device-Id), загрузка аватарок с кэшем и фон страницы.
 *
 * Фон: стандартный (assets/styles/bg.webp) или пользовательский — поле profileBackground
 * из /api/users/bio/{id}. Пользовательский скачивается один раз и кэшируется в файл; если он
 * не задан или не скачался — остаётся стандартный (или прошлый закэшированный).
 */
class SiteApi(private val activity: AppCompatActivity) {

    companion object {
        const val ORIGIN = "https://fortport.ru"
        const val HOST = "fortport.ru"
        private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

        /**
         * 401/403 на плохой сети — не всегда «сессия умерла»: куки могла обновить WebView
         * параллельно, а ответ с Set-Cookie мог потеряться по таймауту. Перед тем как
         * признать сессию потерянной, повторяем запрос с паузами (куки читаются заново).
         */
        val AUTH_RETRY_DELAYS_MS = longArrayOf(800L, 2500L)
    }

    class Resp(val code: Int, val body: String)

    @Volatile var userId = ""
    @Volatile var deviceId = ""
    @Volatile var userAgent = ""

    /** Один поток для сетевых задач разделов (запросы идут по очереди). */
    val io: ExecutorService = Executors.newSingleThreadExecutor()
    private val imgPool = Executors.newFixedThreadPool(3)

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val req = chain.request()
            if (req.url.host != HOST) return@addInterceptor chain.proceed(req)
            val b = req.newBuilder()
            if (userAgent.isNotBlank()) b.header("User-Agent", userAgent)
            if (deviceId.isNotBlank()) b.header("X-Device-Id", deviceId)
            CookieManager.getInstance().getCookie(ORIGIN)?.let { b.header("Cookie", it) }
            val resp = chain.proceed(b.build())
            val set = resp.headers("Set-Cookie")
            if (set.isNotEmpty()) {
                set.forEach { CookieManager.getInstance().setCookie(ORIGIN, it) }
                CookieManager.getInstance().flush()
            }
            resp
        }
        .build()

    fun setSession(userId: String, deviceId: String, userAgent: String) {
        if (userId.isNotBlank()) this.userId = userId
        if (deviceId.isNotBlank()) this.deviceId = deviceId
        this.userAgent = userAgent
    }

    // ------------------------------------------------------------------ запросы

    fun get(path: String): Resp = send("GET", path, null)

    /** json == null — тело пустое (для POST) или отсутствует (GET/DELETE). */
    fun send(method: String, path: String, json: String?): Resp {
        val body = when {
            method == "GET" || method == "DELETE" -> null
            json != null -> json.toRequestBody(JSON_TYPE)
            else -> ByteArray(0).toRequestBody(null)
        }
        val req = Request.Builder().url(ORIGIN + path).method(method, body).build()
        var attempt = 0
        while (true) {
            val resp = client.newCall(req).execute().use { Resp(it.code, it.body?.string().orEmpty()) }
            if ((resp.code == 401 || resp.code == 403) && attempt < AUTH_RETRY_DELAYS_MS.size) {
                Thread.sleep(AUTH_RETRY_DELAYS_MS[attempt++]) // вызывается только из io-потоков
                continue
            }
            return resp
        }
    }

    /** 0 — id известен, 1 — нет авторизации, 2 — ошибка сети/сервера. */
    fun ensureUserId(): Int {
        if (userId.isNotBlank()) return if (userId.all { it.isDigit() }) 0 else 2
        val r = get("/api/session/bootstrap")
        if (r.code == 401 || r.code == 403) return 1
        if (r.code != 200) return 2
        val o = JSONObject(r.body)
        if (!o.optBoolean("authenticated", false)) return 1
        val id = o.optJSONObject("user")?.optLong("id", 0L) ?: 0L
        if (id <= 0) return 2
        userId = id.toString()
        return 0
    }

    fun absUrl(path: String): String = when {
        path.startsWith("http") -> path
        path.startsWith("/") -> ORIGIN + path
        else -> "$ORIGIN/$path"
    }

    fun str(o: JSONObject, k: String) = if (o.isNull(k)) "" else o.optString(k, "")

    // ------------------------------------------------------------------ картинки

    private val imgCache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 16L).toInt().coerceAtLeast(2 * 1024 * 1024)
    ) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    val defaultAvatar: Bitmap? by lazy {
        try { activity.assets.open("ui/default-avatar.jpg").use { BitmapFactory.decodeStream(it) } }
        catch (e: Exception) { null }
    }

    fun decodeSampled(bytes: ByteArray, maxSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxSide && bounds.outHeight / (sample * 2) >= maxSide) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    fun loadAvatar(iv: ImageView, path: String) {
        val url = if (path.isBlank()) null else absUrl(path)
        iv.tag = url
        if (url == null) { iv.setImageBitmap(defaultAvatar); return }
        imgCache.get(url)?.let { iv.setImageBitmap(it); return }
        iv.setImageBitmap(defaultAvatar)
        imgPool.execute {
            val bmp = try {
                client.newCall(Request.Builder().url(url).get().build()).execute().use { r ->
                    if (!r.isSuccessful) null else r.body?.bytes()?.let { decodeSampled(it, 256) }
                }
            } catch (e: Exception) { null }
            if (bmp != null) {
                imgCache.put(url, bmp)
                activity.runOnUiThread { if (iv.tag == url) iv.setImageBitmap(bmp) }
            }
        }
    }

    // ------------------------------------------------------------------ фон

    private val prefs = activity.getSharedPreferences("chats_ui", Context.MODE_PRIVATE)
    private val bgFile = File(activity.cacheDir, "chats_bg.bin")
    private val bgViews = ArrayList<ImageView>()
    private var defaultBackground: Bitmap? = null
    private var customBackground: Bitmap? = null

    /** Подключает ImageView, который показывает фон; вызывать из UI-потока. */
    fun attachBackground(view: ImageView, default: Bitmap?) {
        defaultBackground = default
        bgViews.add(view)
        view.setImageBitmap(customBackground ?: defaultBackground)
        if (customBackground == null && bgFile.exists() && !prefs.getString("bg_path", "").isNullOrEmpty()) {
            io.execute { // показываем закэшированный пользовательский фон сразу, без мигания
                if (customBackground != null) return@execute
                val bmp = try { decodeSampled(bgFile.readBytes(), 1600) } catch (e: Exception) { null }
                if (bmp != null) setCustomBackground(bmp)
            }
        }
    }

    /** Свой фон страницы (пользовательский или стандартный) — чтобы вернуть его после чужого. */
    fun ownBackgroundBitmap(): Bitmap? = customBackground ?: defaultBackground

    fun defaultBackgroundBitmap(): Bitmap? = defaultBackground

    /** Скачивает картинку по пути сайта (из io-потока); null — не удалось. */
    fun downloadBitmap(path: String, maxSide: Int): Bitmap? = try {
        client.newCall(Request.Builder().url(absUrl(path)).get().build()).execute().use { r ->
            if (!r.isSuccessful) null else r.body?.bytes()?.let { decodeSampled(it, maxSide) }
        }
    } catch (e: Exception) { null }

    private fun setCustomBackground(bmp: Bitmap?) {
        activity.runOnUiThread {
            if (activity.isDestroyed) return@runOnUiThread
            customBackground = bmp
            bgViews.forEach { it.setImageBitmap(bmp ?: defaultBackground) }
        }
    }

    /** Сверяет фон с профилем и при необходимости скачивает новый. Только из io-потока. */
    fun refreshBackground() {
        if (ensureUserId() != 0) return
        val r = get("/api/users/bio/$userId")
        if (r.code != 200) return // сеть/сервер — оставляем показанное
        val path = str(JSONObject(r.body), "profileBackground")
        val cachedPath = prefs.getString("bg_path", "").orEmpty()
        if (path.isBlank()) {
            if (cachedPath.isNotEmpty() || bgFile.exists()) {
                bgFile.delete()
                prefs.edit().remove("bg_path").apply()
                setCustomBackground(null)
            }
            return
        }
        if (path == cachedPath && bgFile.exists()) return // уже показан из кэша
        val bytes = client.newCall(Request.Builder().url(absUrl(path)).get().build()).execute().use { resp ->
            if (resp.isSuccessful) resp.body?.bytes() else null
        } ?: return
        val bmp = decodeSampled(bytes, 1600) ?: return // не картинка — остаётся прежний
        bgFile.writeBytes(bytes)
        prefs.edit().putString("bg_path", path).apply()
        setCustomBackground(bmp)
    }

    fun destroy() {
        io.shutdownNow()
        imgPool.shutdownNow()
    }
}
