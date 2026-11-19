package ru.fortport.client

import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import java.nio.ByteBuffer

/**
 * Анимированные картинки (GIF и animated WebP) для аватарок.
 * BitmapFactory читает только первый кадр, поэтому такие файлы декодируем через ImageDecoder 
 * (Android 9+, API 28); на Android 8.x остаётся статичный первый кадр.
 */
object AnimatedImages {

    /** Анимированные аватарки крупнее этого размера не оживляем (экономия памяти) — остаётся статичный кадр. */
    const val MAX_BYTES = 4 * 1024 * 1024

    val supported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P

    /** GIF по сигнатуре или WebP с блоком ANIM. */
    fun isAnimated(b: ByteArray): Boolean {
        if (b.size < 16 || b.size > MAX_BYTES) return false
        // "GIF8"
        if (b[0] == 'G'.code.toByte() && b[1] == 'I'.code.toByte() && b[2] == 'F'.code.toByte() && b[3] == '8'.code.toByte()) return true
        // "RIFF....WEBP" + блок "ANIM" в первых байтах (VP8X-заголовок анимации)
        if (b[0] == 'R'.code.toByte() && b[1] == 'I'.code.toByte() && b[2] == 'F'.code.toByte() && b[3] == 'F'.code.toByte() &&
            b[8] == 'W'.code.toByte() && b[9] == 'E'.code.toByte() && b[10] == 'B'.code.toByte() && b[11] == 'P'.code.toByte()) {
            val end = minOf(b.size - 4, 64)
            var i = 12
            while (i < end) {
                if (b[i] == 'A'.code.toByte() && b[i + 1] == 'N'.code.toByte() && b[i + 2] == 'I'.code.toByte() && b[i + 3] == 'M'.code.toByte()) return true
                i++
            }
        }
        return false
    }

    /**
     * Новый анимированный Drawable (по одному на каждую View — общий между View нельзя).
     * Уже запущен и зациклен; null — не удалось (тогда показываем обычный Bitmap).
     */
    fun decode(bytes: ByteArray, maxSide: Int): Drawable? {
        if (!supported) return null
        return try {
            val src = ImageDecoder.createSource(ByteBuffer.wrap(bytes))
            val d = ImageDecoder.decodeDrawable(src) { decoder, info, _ ->
                var sample = 1
                while (info.size.width / (sample * 2) >= maxSide && info.size.height / (sample * 2) >= maxSide) sample *= 2
                decoder.setTargetSampleSize(sample)
            }
            if (d is AnimatedImageDrawable) {
                d.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
                d.start()
            }
            d
        } catch (e: Throwable) { null }
    }
}
