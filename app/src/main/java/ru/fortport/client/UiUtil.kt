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

    private var dayBar: Bitmap? = null
    private var nightBar: Bitmap? = null

    /**
     * Фон верхних и нижних панелей: assets/ui/day_royal_panel-invert.png (светлая тема) и
     * night_royal_panel-invert.png (тёмная), растянутые на всю панель. Каждый вызов даёт
     * отдельный Drawable. null — файла нет в assets (тогда остаётся прежняя окраска панели).
     */
    fun barSkin(ctx: Context): Drawable? {
        val night = isNight(ctx)
        var bmp = if (night) nightBar else dayBar
        if (bmp == null) {
            bmp = try {
                ctx.assets.open(if (night) "ui/night_royal_panel-invert.png" else "ui/day_royal_panel-invert.png")
                    .use { BitmapFactory.decodeStream(it) }
            } catch (e: Exception) { null }
            if (night) nightBar = bmp else dayBar = bmp
        }
        return bmp?.let { BitmapDrawable(ctx.resources, it).apply { gravity = Gravity.FILL } }
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

    /**
     * Тема берётся из системной конфигурации: Activity пересоздаётся при смене uiMode
     * (configChanges его не перехватывает), поэтому все экраны строятся заново с актуальными цветами.
     */
    val night: Boolean
        get() = (android.content.res.Resources.getSystem().configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    // Палитра «поверхностей»: светлая / тёмная.
    /** Основной фон блоков (тело поста, поля ввода, плашки). */
    val SURFACE: Int get() = if (night) 0xFF1B1D26.toInt() else Color.WHITE
    /** Чуть приподнятая поверхность (чипы, ячейки, активные вкладки). */
    val SURFACE_ALT: Int get() = if (night) 0xFF262A38.toInt() else 0xFFEAF6FA.toInt()
    /** Мягкий фон-подсветка (плитки, строки списков). */
    val SURFACE_SOFT: Int get() = if (night) 0xFF222633.toInt() else 0xFFF2FAFF.toInt()
    val BORDER: Int get() = if (night) 0xFF4A5A80.toInt() else 0xFF1B2A4A.toInt()
    /** Тонкие разделители. */
    val DIVIDER: Int get() = if (night) 0xFF343A4D.toInt() else 0xFFE0E4EC.toInt()
    val WHITE: Int get() = SURFACE
    /** Всегда белый — для текста на «royal»-панелях и цветных кнопках. */
    const val ON_PANEL = Color.WHITE
    val TEXT: Int get() = if (night) 0xFFF2F2F2.toInt() else 0xFF15141C.toInt()
    val SUB: Int get() = if (night) 0xFFBDBDBD.toInt() else 0xFF555555.toInt()
    val HINT: Int get() = if (night) 0xFF9AA0AE.toInt() else 0xFF777777.toInt()
    val ACCENT_TEXT: Int get() = if (night) 0xFF8FB4FF.toInt() else 0xFF2A62B8.toInt()

    /** Окрашивает EditText/CheckBox/кнопки диалога под текущую тему (для программных форм). */
    fun styleInputs(root: View) {
        when (root) {
            is android.widget.CheckBox -> {
                root.setTextColor(TEXT)
                root.buttonTintList = android.content.res.ColorStateList.valueOf(if (night) 0xFF6E9BFF.toInt() else 0xFF3D7BFF.toInt())
            }
            is android.widget.EditText -> {
                root.setTextColor(TEXT); root.setHintTextColor(HINT)
                root.backgroundTintList = android.content.res.ColorStateList.valueOf(if (night) 0xFF6E9BFF.toInt() else 0xFF3D7BFF.toInt())
            }
            is android.widget.Button -> if (night) {
                root.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF2B4A6B.toInt())
                root.setTextColor(Color.WHITE)
            }
            is android.widget.TextView -> if (root.currentTextColor == 0xFF15141C.toInt()) root.setTextColor(TEXT)
        }
        if (root is ViewGroup) for (i in 0 until root.childCount) styleInputs(root.getChildAt(i))
    }

    const val PLUS = 0xFF00BFA5.toInt()
    const val MINUS = 0xFF9C5BFF.toInt()
}
