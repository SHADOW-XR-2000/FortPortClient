package ru.fortport.client

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.StateListDrawable
import android.widget.TextView

/**
 * Кнопки в стиле Aero (Windows 7) по эталонам aero-btn-day.html / aero_btn-dark.html:
 * глянцевый двухслойный градиент с разрезом 49%/50%, рамка 1px, верхний блик, скругление 3px.
 * Состояния: обычное, нажатие (active), фокус (focus) — как в CSS.
 */
object AeroButton {
    private class Skin(
        val c0: Int, val c1: Int, val c2: Int, val c3: Int,
        val border: Int, val gloss: Int, val glow: Int
    )

    private val DAY = Skin(0xFFF2F2F2.toInt(), 0xFFEBEBEB.toInt(), 0xFFDDDDDD.toInt(), 0xFFCFCFCF.toInt(), 0xFF707070.toInt(), 0x99FFFFFF.toInt(), 0)
    private val DAY_FOCUS = Skin(0xFFEAF6FD.toInt(), 0xFFD9F0FC.toInt(), 0xFFBEE6FD.toInt(), 0xFFA7D9F5.toInt(), 0xFF3C7FB1.toInt(), 0xB3FFFFFF.toInt(), 0x9952A8EC.toInt())
    private val DAY_DOWN = Skin(0xFFE1F0FA.toInt(), 0xFFC5E3F7.toInt(), 0xFFA2D2F2.toInt(), 0xFF8EC2E6.toInt(), 0xFF2C628B.toInt(), 0x33FFFFFF, 0)

    private val NIGHT = Skin(0xFF3A3A3A.toInt(), 0xFF2F2F2F.toInt(), 0xFF242424.toInt(), 0xFF1C1C1C.toInt(), 0xFF222222.toInt(), 0x26FFFFFF, 0)
    private val NIGHT_FOCUS = Skin(0xFF2C3B47.toInt(), 0xFF1E2933.toInt(), 0xFF131D26.toInt(), 0xFF0F171F.toInt(), 0xFF295D85.toInt(), 0x33FFFFFF, 0x992979FF.toInt())
    private val NIGHT_DOWN = Skin(0xFF1D262E.toInt(), 0xFF151D24.toInt(), 0xFF0D141A.toInt(), 0xFF0A0E12.toInt(), 0xFF17364E.toInt(), 0x1AFFFFFF, 0)

    private class AeroDrawable(private val ctx: Context, private val s: Skin, private val radiusDp: Float) : Drawable() {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val rect = RectF()
        private val density = ctx.resources.displayMetrics.density

        override fun draw(canvas: Canvas) {
            val b = bounds
            if (b.isEmpty) return
            val px = density.coerceAtLeast(1f)
            val r = radiusDp * px
            val glow = if (s.glow != 0) 3f * px else 0f
            rect.set(b.left + glow, b.top + glow, b.right - glow, b.bottom - glow)
            if (glow > 0f) {
                val g = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = s.glow and 0x00FFFFFF or 0x55000000; style = Paint.Style.STROKE; strokeWidth = glow * 1.6f }
                canvas.drawRoundRect(rect, r, r, g)
            }
            fill.shader = LinearGradient(
                0f, rect.top, 0f, rect.bottom,
                intArrayOf(s.c0, s.c1, s.c2, s.c3), floatArrayOf(0f, 0.49f, 0.5f, 1f), Shader.TileMode.CLAMP
            )
            canvas.drawRoundRect(rect, r, r, fill)
            // Верхний блик (inset 0 1px 0).
            line.shader = null
            line.strokeWidth = px
            line.color = s.gloss
            val in1 = RectF(rect.left + px * 1.5f, rect.top + px * 1.5f, rect.right - px * 1.5f, rect.bottom - px * 1.5f)
            canvas.drawRoundRect(in1, r, r, line)
            line.color = s.border
            line.strokeWidth = px
            val out = RectF(rect.left + px * 0.5f, rect.top + px * 0.5f, rect.right - px * 0.5f, rect.bottom - px * 0.5f)
            canvas.drawRoundRect(out, r, r, line)
        }

        override fun setAlpha(alpha: Int) { fill.alpha = alpha }
        override fun setColorFilter(cf: ColorFilter?) { fill.colorFilter = cf }
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    fun background(ctx: Context, radiusDp: Float = 3f): Drawable {
        val night = UiUtil.isNight(ctx)
        val normal = AeroDrawable(ctx, if (night) NIGHT else DAY, radiusDp)
        val focus = AeroDrawable(ctx, if (night) NIGHT_FOCUS else DAY_FOCUS, radiusDp)
        val down = AeroDrawable(ctx, if (night) NIGHT_DOWN else DAY_DOWN, radiusDp)
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), down)
            addState(intArrayOf(android.R.attr.state_focused), focus)
            addState(intArrayOf(), normal)
        }
    }

    fun textColor(ctx: Context) = if (UiUtil.isNight(ctx)) 0xFFE0E0E0.toInt() else 0xFF1E1E1E.toInt()

    /** Превращает TextView в кнопку Aero (фон, цвет текста, фокус). */
    fun <T : TextView> style(v: T, radiusDp: Float = 3f): T = v.apply {
        background = background(context, radiusDp)
        setTextColor(textColor(context))
        isClickable = true
        isFocusable = true
    }
}
