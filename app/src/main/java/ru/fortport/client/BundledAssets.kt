package ru.fortport.client

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceResponse

/**
 * Отдаёт файлы из app/src/main/assets вместо запроса к сайту — используется
 * для иконок/лого, которые нужны сразу с первого запуска, даже без единого
 * подключения к интернету. Путь ассета внутри APK повторяет путь на сайте:
 * https://fortport.ru/ui/icons/play.webp -> assets/ui/icons/play.webp
 */
object BundledAssets {

    fun tryLoad(context: Context, requestUrl: Uri): WebResourceResponse? {
        val path = requestUrl.path?.removePrefix("/") ?: return null
        if (path.isBlank()) return null

        return try {
            val stream = context.assets.open(path)
            WebResourceResponse(AssetCache.guessMime(requestUrl), "UTF-8", stream)
        } catch (e: Exception) {
            null // такого ассета нет в APK — просто идём дальше обычным путём
        }
    }
}
