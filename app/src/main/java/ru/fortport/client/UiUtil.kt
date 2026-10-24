package ru.fortport.client

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Мелкие помощники для программной вёрстки нативных экранов с постами. */
object UiUtil {

    fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density + 0.5f).toInt()

    private var dayPanel: Bitmap? = null
    private var nightPanel: Bitmap? = null

    private fun isNight(ctx: Context) =
        (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    /** Фон «royal» (assets/ui/day_royal_panel.png и night_royal_panel.png) — растягивается на всю строку. */
    fun royal(ctx: Context): Drawable {
        val night = isNight(ctx)
        var bmp = if (night) nightPanel else dayPanel
        if (bmp == null) {
            bmp = try {
                ctx.assets.open(if (night) "ui/night_royal_panel.png" else "ui/day_royal_panel.png")
                    .use { BitmapFactory.decodeStream(it) }
            } catch (e: Exception) { null }
            if (night) nightPanel = bmp else dayPanel = bmp
        }
        return if (bmp != null) BitmapDrawable(ctx.resources, bmp).apply { gravity = Gravity.FILL }
        else GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xFF4DA3FF.toInt(), 0xFF1F6FD1.toInt()))
    }

    fun rect(fill: Int, stroke: Int = 0, strokeDp: Int = 0, radiusDp: Int = 0, ctx: Context): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            if (strokeDp > 0) setStroke(dp(ctx, strokeDp), stroke)
            cornerRadius = dp(ctx, radiusDp).toFloat()
        }

    fun text(
        ctx: Context, size: Float, color: Int, bold: Boolean = false, maxLines: Int = 0
    ): TextView = TextView(ctx).apply {
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        if (maxLines > 0) { this.maxLines = maxLines; ellipsize = android.text.TextUtils.TruncateAt.END }
    }

    fun lp(w: Int, h: Int, weight: Float = 0f): LinearLayout.LayoutParams = LinearLayout.LayoutParams(w, h, weight)

    val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    fun frameLp(w: Int, h: Int, gravity: Int = Gravity.NO_GRAVITY) = FrameLayout.LayoutParams(w, h, gravity)

    private val dateFmt by lazy { SimpleDateFormat("dd.MM.yyyy - HH.mm", Locale.getDefault()) }

    /** «дд.мм.гггг - чч.мм» (как в концепции) по местному времени. */
    fun formatDate(millis: Long): String = if (millis <= 0) "" else dateFmt.format(Date(millis))

    fun gone(v: View, gone: Boolean) { v.visibility = if (gone) View.GONE else View.VISIBLE }

    const val BORDER = 0xFF1B2A4A.toInt()
    const val WHITE = Color.WHITE
    const val TEXT = 0xFF15141C.toInt()
    const val SUB = 0xFF555555.toInt()
    const val PLUS = 0xFF00BFA5.toInt()
    const val MINUS = 0xFF9C5BFF.toInt()
}
