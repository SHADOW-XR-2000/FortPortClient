package ru.fortport.client

import android.graphics.Bitmap
import android.util.LruCache
import android.webkit.CookieManager
import android.widget.ImageView
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.Executors

/**
 * Дополнения к SiteApi для экранов с постами. Вынесены в отдельный файл как расширения
 * (используют только публичные члены SiteApi), чтобы не трогать сам SiteApi.kt.
 */
private val postImgPool = Executors.newFixedThreadPool(3)
private val postImgCache = object : LruCache<String, Bitmap>(
    (Runtime.getRuntime().maxMemory() / 16L).toInt().coerceAtLeast(2 * 1024 * 1024)
) {
    override fun sizeOf(key: String, value: Bitmap) = value.byteCount
}

/**
 * Картинка поста/награды произвольного размера (до [maxSide] по длинной стороне), с кэшем в памяти.
 * [onResult] вызывается в UI-потоке, если ImageView всё ещё ждёт именно этот адрес.
 */
fun SiteApi.loadImage(iv: ImageView, path: String, maxSide: Int, onResult: ((Boolean) -> Unit)? = null) {
    if (path.isBlank()) { onResult?.invoke(false); return }
    val url = absUrl(path)
    val key = "$url@$maxSide"
    iv.tag = key
    postImgCache.get(key)?.let { iv.setImageBitmap(it); onResult?.invoke(true); return }
    iv.setImageDrawable(null)
    postImgPool.execute {
        val bmp = try {
            client.newCall(Request.Builder().url(url).get().build()).execute().use { r ->
                if (!r.isSuccessful) null else r.body?.bytes()?.let { decodeSampled(it, maxSide) }
            }
        } catch (e: Exception) { null }
        if (bmp != null) postImgCache.put(key, bmp)
        iv.post {
            if (iv.tag != key) return@post
            if (bmp != null) iv.setImageBitmap(bmp)
            onResult?.invoke(bmp != null)
        }
    }
}

/** Заголовки для потокового воспроизведения медиа (MediaPlayer/VideoView) с сессией сайта. */
fun SiteApi.mediaHeaders(): Map<String, String> {
    val h = HashMap<String, String>()
    CookieManager.getInstance().getCookie(SiteApi.ORIGIN)?.let { h["Cookie"] = it }
    if (userAgent.isNotBlank()) h["User-Agent"] = userAgent
    if (deviceId.isNotBlank()) h["X-Device-Id"] = deviceId
    return h
}

/** DELETE с JSON-телом (обычный SiteApi.send для DELETE тело не отправляет). */
fun SiteApi.deleteWithBody(path: String, json: String): SiteApi.Resp {
    val body = json.toRequestBody("application/json; charset=utf-8".toMediaType())
    val req = Request.Builder().url(SiteApi.ORIGIN + path).method("DELETE", body).build()
    client.newCall(req).execute().use { return SiteApi.Resp(it.code, it.body?.string().orEmpty()) }
}
