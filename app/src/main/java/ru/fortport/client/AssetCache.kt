package ru.fortport.client

import android.net.Uri
import android.util.Log
import android.webkit.WebResourceResponse
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Принудительный дисковый кэш для статических ассетов интерфейса (иконки,
 * логотипы, шрифты и т.п.). В отличие от стандартного HTTP-кэша WebView,
 * не зависит от заголовков Cache-Control/Expires с сервера: один раз
 * скачанный файл переиспользуется, пока не будет очищен вручную или пока
 * не будет превышен лимит места.
 */
object AssetCache {

    private const val TAG = "AssetCache"
    private const val CACHE_DIR_NAME = "ui_assets_cache"
    private const val MAX_CACHE_BYTES = 30L * 1024 * 1024 // 30 МБ

    // Пути/расширения, которые считаем "статикой интерфейса" и кэшируем навсегда.
    private val CACHEABLE_PATH_PREFIXES = listOf("/ui/", "/static/", "/assets/")
    private val CACHEABLE_EXTENSIONS = listOf(
        ".webp", ".png", ".jpg", ".jpeg", ".gif", ".svg", ".ico",
        ".woff", ".woff2", ".ttf", ".otf",
        ".webm", ".mp4"
    )

    fun isCacheable(url: Uri, siteHost: String): Boolean {
        val host = url.host ?: return false
        if (!host.endsWith(siteHost)) return false
        val path = url.path ?: return false
        val prefixMatch = CACHEABLE_PATH_PREFIXES.any { path.startsWith(it) }
        val extensionMatch = CACHEABLE_EXTENSIONS.any { path.endsWith(it, ignoreCase = true) }
        return prefixMatch || extensionMatch
    }

    /**
     * Возвращает ответ из локального файла, если он уже загружен, иначе
     * синхронно скачивает файл (вызывается WebView в фоновом потоке),
     * сохраняет на диск и отдаёт результат. Возвращает null при ошибке —
     * тогда WebView просто загрузит ресурс обычным способом.
     */
    fun load(cacheDir: File, requestUrl: Uri): WebResourceResponse? {
        val dir = File(cacheDir, CACHE_DIR_NAME).apply { mkdirs() }
        val file = File(dir, fileNameFor(requestUrl))
        val metaFile = File(dir, fileNameFor(requestUrl) + ".mime")

        if (file.exists() && file.length() > 0) {
            val mime = if (metaFile.exists()) metaFile.readText().trim() else guessMime(requestUrl)
            return buildResponse(file, mime)
        }

        return try {
            val connection = URL(requestUrl.toString()).openConnection() as HttpURLConnection
            connection.connectTimeout = 8000
            connection.readTimeout = 8000
            connection.instanceFollowRedirects = true
            connection.connect()

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                connection.disconnect()
                return null
            }

            val mime = connection.contentType?.substringBefore(";")?.trim()
                ?: guessMime(requestUrl)

            enforceCacheLimit(dir)

            connection.inputStream.use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
            metaFile.writeText(mime)
            connection.disconnect()

            buildResponse(file, mime)
        } catch (e: Exception) {
            Log.w(TAG, "Не удалось закэшировать ${requestUrl}: ${e.message}")
            file.delete()
            null
        }
    }

    private fun buildResponse(file: File, mime: String): WebResourceResponse {
        return WebResourceResponse(mime, "UTF-8", FileInputStream(file))
    }

    fun guessMime(url: Uri): String {
        val path = url.path.orEmpty()
        return when {
            path.endsWith(".webp") -> "image/webp"
            path.endsWith(".png") -> "image/png"
            path.endsWith(".jpg") || path.endsWith(".jpeg") -> "image/jpeg"
            path.endsWith(".gif") -> "image/gif"
            path.endsWith(".svg") -> "image/svg+xml"
            path.endsWith(".ico") -> "image/x-icon"
            path.endsWith(".woff2") -> "font/woff2"
            path.endsWith(".woff") -> "font/woff"
            path.endsWith(".ttf") -> "font/ttf"
            path.endsWith(".otf") -> "font/otf"
            path.endsWith(".webm") -> "video/webm"
            path.endsWith(".mp4") -> "video/mp4"
            else -> "application/octet-stream"
        }
    }

    private fun fileNameFor(url: Uri): String {
        val digest = MessageDigest.getInstance("MD5")
            .digest(url.toString().toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** Если кэш разросся сверх лимита, удаляем самые старые файлы. */
    private fun enforceCacheLimit(dir: File) {
        val files = dir.listFiles() ?: return
        var total = files.sumOf { it.length() }
        if (total <= MAX_CACHE_BYTES) return

        val sorted = files.sortedBy { it.lastModified() }
        for (f in sorted) {
            if (total <= MAX_CACHE_BYTES) break
            total -= f.length()
            f.delete()
        }
    }

    /** Полностью очистить кэш иконок/статики (например, по кнопке в настройках). */
    fun clear(cacheDir: File) {
        File(cacheDir, CACHE_DIR_NAME).deleteRecursively()
    }
}
