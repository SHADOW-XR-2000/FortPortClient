package ru.fortport.client

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

/**
 * Фон верхних и нижних панелей в стиле Windows 7 Aero (панель задач из dock-panel.html):
 * стеклянный вертикальный градиент, светлая кромка сверху, тёмная снизу и рамка со стороны контента.
 * Светлая и тёмная темы — те же цвета, что в CSS-переменных макета (--tb-1…--tb-7, --tb-border, --tb-hi, --tb-lo).
 * Размытия под панелью (backdrop-filter) на Android нет, поэтому под градиентом лежит сплошная подложка.
 */
class AeroBarDrawable(night: Boolean, private val bottom: Boolean, private val density: Float) : Drawable() {

    private fun c(r: Int, g: Int, b: Int, a: Float) = Color.argb((a * 255).toInt(), r, g, b)

    private val base: Int
    private val stops: IntArray
    private val border: Int
    private val hi: Int
    private val lo: Int

    init {
        if (night) {
            base = 0xFF0A162A.toInt()
            stops = intArrayOf(
                c(120, 170, 220, .55f), c(70, 120, 180, .72f), c(15, 30, 55, .88f), c(10, 22, 42, .90f),
                c(20, 42, 68, .90f), c(50, 90, 135, .78f), c(90, 140, 190, .60f)
            )
            border = c(140, 190, 240, .55f); hi = c(200, 225, 250, .35f); lo = c(0, 0, 0, .55f)
        } else {
            base = 0xFFC3D9EE.toInt()
            stops = intArrayOf(
                c(255, 255, 255, .95f), c(225, 240, 252, .92f), c(190, 215, 240, .92f), c(180, 205, 232, .92f),
                c(195, 220, 242, .92f), c(215, 232, 248, .90f), c(240, 248, 255, .85f)
            )
            border = c(140, 180, 220, .65f); hi = c(255, 255, 255, .95f); lo = c(80, 120, 160, .30f)
        }
    }

    private val positions = floatArrayOf(0f, .04f, .12f, .50f, .88f, .96f, 1f)
    private val fill = Paint()
    private val line = Paint()
    private val basePaint = Paint().apply { color = base }

    override fun onBoundsChange(b: Rect) {
        fill.shader = LinearGradient(0f, b.top.toFloat(), 0f, b.bottom.toFloat(), stops, positions, Shader.TileMode.CLAMP)
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val l = b.left.toFloat(); val r = b.right.toFloat()
        val px = maxOf(1f, density) // линия в 1dp
        canvas.drawRect(b, basePaint)
        canvas.drawRect(b, fill)
        // светлая кромка сверху и тёмная снизу (inset-тени макета)
        line.color = hi; canvas.drawRect(l, b.top.toFloat(), r, b.top + px, line)
        line.color = lo; canvas.drawRect(l, b.bottom - px, r, b.bottom.toFloat(), line)
        // рамка со стороны, обращённой к контенту: у нижней панели — сверху, у верхней — снизу
        line.color = border
        if (bottom) canvas.drawRect(l, b.top.toFloat(), r, b.top + px, line)
        else canvas.drawRect(l, b.bottom - px, r, b.bottom.toFloat(), line)
    }

    override fun setAlpha(alpha: Int) { fill.alpha = alpha; basePaint.alpha = alpha }
    override fun setColorFilter(cf: ColorFilter?) { fill.colorFilter = cf; basePaint.colorFilter = cf }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.OPAQUE
}

object AeroBar {
    private const val TEAL_ON_DARK = 0xFF2AF5C0.toInt()
    private const val TEAL_ON_LIGHT = 0xFF0B8F72.toInt()
    private const val INK_LIGHT = 0xFF0E2C49.toInt()

    fun skin(ctx: Context, bottom: Boolean): Drawable =
        AeroBarDrawable(UiUtil.isNight(ctx), bottom, ctx.resources.displayMetrics.density)

    /**
     * В светлой теме панель светлая: белый текст на ней не читается — перекрашиваем подписи в тёмный
     * (акцентный бирюзовый — в более тёмный бирюзовый). В тёмной теме цвета остаются прежними.
     */
    fun adaptTexts(bar: View) {
        if (UiUtil.isNight(bar.context)) return
        if (bar is TextView && bar !is android.widget.Button) {
            when (bar.currentTextColor) {
                Color.WHITE -> bar.setTextColor(INK_LIGHT)
                TEAL_ON_DARK -> bar.setTextColor(TEAL_ON_LIGHT)
            }
        }
        if (bar is ViewGroup) for (i in 0 until bar.childCount) adaptTexts(bar.getChildAt(i))
    }
}
