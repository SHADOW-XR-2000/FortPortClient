package ru.fortport.client

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import ru.fortport.client.UiUtil.dp

/**
 * Aero-элементы форм (поле ввода, чекбокс, выпадающий список, панель) по эталону aero-example.html.
 * Цвета взяты из CSS-переменных светлой и тёмной темы.
 */
object AeroUi {

    private class Pal(
        val panel1: Int, val panel2: Int, val panelBorder: Int,
        val input1: Int, val input2: Int, val inputBorder: Int, val inputFocus: Int, val inputText: Int, val hint: Int,
        val label: Int, val title: Int,
        val cb1: Int, val cb2: Int, val cbBorder: Int,
        val g1: Int, val g2: Int, val g3: Int, val g4: Int, val glassBorder: Int, val surfaceText: Int, val arrow: Int,
        val list1: Int, val list2: Int, val listBorder: Int, val itemText: Int, val sel1: Int, val sel2: Int
    )

    private val LIGHT = Pal(
        0xFFFFFFFF.toInt(), 0xFFE0F0FF.toInt(), 0xFF6C96C0.toInt(),
        0xFFFFFFFF.toInt(), 0xFFE9F3FF.toInt(), 0xFF6C96C0.toInt(), 0xFF4D94DD.toInt(), 0xFF14324F.toInt(), 0x8C325A82.toInt(),
        0xFF0E2C49.toInt(), 0xFF10304F.toInt(),
        0xFFFFFFFF.toInt(), 0xFFDBEBFA.toInt(), 0xFF6C96C0.toInt(),
        0xFFFFFFFF.toInt(), 0xFFE8F4FF.toInt(), 0xFFCAE3FC.toInt(), 0xFFE2F1FF.toInt(), 0xFF9FC3E4.toInt(), 0xFF17395C.toInt(), 0xFF2C5A86.toInt(),
        0xFFF8FCFF.toInt(), 0xFFE4F2FF.toInt(), 0xFF8CB4DC.toInt(), 0xFF17395C.toInt(), 0xFF70ACE2.toInt(), 0xFF468CCD.toInt()
    )
    private val DARK = Pal(
        0xFF1A2C42.toInt(), 0xFF0C1828.toInt(), 0xFF3F5F82.toInt(),
        0xFF0A1626.toInt(), 0xFF142840.toInt(), 0xFF4F7AA6.toInt(), 0xFF6FB6FF.toInt(), 0xFFDBEAFF.toInt(), 0x73A0C3E6.toInt(),
        0xFFBCD9F7.toInt(), 0xFFD8ECFF.toInt(),
        0xFF16283E.toInt(), 0xFF0A1626.toInt(), 0xFF4F7AA6.toInt(),
        0xFF3B5B80.toInt(), 0xFF2A4565.toInt(), 0xFF1D3856.toInt(), 0xFF2E4F72.toInt(), 0xFF4F7AA6.toInt(), 0xFFCFE6FF.toInt(), 0xFFA9D0F5.toInt(),
        0xFF182A3E.toInt(), 0xFF0E1C2C.toInt(), 0xFF4A7099.toInt(), 0xFFC9E2FF.toInt(), 0xFF3C82C8.toInt(), 0xFF235A96.toInt()
    )

    private fun pal(ctx: Context) = if (UiUtil.isNight(ctx)) DARK else LIGHT

    private fun grad(c1: Int, c2: Int, stroke: Int, strokeDp: Float, radiusDp: Float, ctx: Context) = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(c1, c2)
    ).apply {
        setStroke(maxOf(1, (strokeDp * ctx.resources.displayMetrics.density).toInt()), stroke)
        cornerRadius = radiusDp * ctx.resources.displayMetrics.density
    }

    fun labelColor(ctx: Context) = pal(ctx).label
    fun titleColor(ctx: Context) = pal(ctx).title

    /** Фон панели окна. */
    fun panelBackground(ctx: Context): Drawable = pal(ctx).let { grad(it.panel1, it.panel2, it.panelBorder, 2f, 10f, ctx) }

    /** Фон «поля» (список файлов и т.п.). */
    fun fieldBackground(ctx: Context): Drawable = pal(ctx).let { grad(it.input1, it.input2, it.inputBorder, 1f, 5f, ctx) }

    /** Поле ввода Aero: градиент, рамка, синее свечение в фокусе. */
    fun input(et: EditText): EditText = et.apply {
        val p = pal(context)
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), grad(p.input1, p.input2, p.inputFocus, 2f, 5f, context))
            addState(intArrayOf(), grad(p.input1, p.input2, p.inputBorder, 1f, 5f, context))
        }
        setTextColor(p.inputText)
        setHintTextColor(p.hint)
        textSize = 14f
        setPadding(dp(context, 11), dp(context, 8), dp(context, 11), dp(context, 8))
    }

    private class CheckDrawable(private val ctx: Context, private val checked: Boolean) : Drawable() {
        private val p = pal(ctx)
        private val d = ctx.resources.displayMetrics.density
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        override fun getIntrinsicWidth() = (20 * d).toInt()
        override fun getIntrinsicHeight() = (20 * d).toInt()
        override fun draw(c: Canvas) {
            val b = bounds
            val r = RectF(b.left + d, b.top + d, b.right - d, b.bottom - d)
            val (a, z, border) = if (checked) Triple(0xFF6FB8F2.toInt(), 0xFF2A7FD0.toInt(), if (UiUtil.isNight(ctx)) 0xFF3A8FDB.toInt() else 0xFF1F68AB.toInt())
                else Triple(p.cb1, p.cb2, p.cbBorder)
            fill.shader = android.graphics.LinearGradient(0f, r.top, 0f, r.bottom, a, z, android.graphics.Shader.TileMode.CLAMP)
            c.drawRoundRect(r, 4 * d, 4 * d, fill)
            line.shader = null; line.strokeWidth = d; line.color = border
            c.drawRoundRect(r, 4 * d, 4 * d, line)
            if (checked) {
                line.color = 0x66FFFFFF; c.drawRoundRect(RectF(r.left + d, r.top + d, r.right - d, r.bottom - d), 3 * d, 3 * d, line)
                line.color = 0xFFFFFFFF.toInt(); line.strokeWidth = 2.2f * d
                line.strokeCap = Paint.Cap.ROUND; line.strokeJoin = Paint.Join.ROUND
                val path = Path().apply {
                    moveTo(r.left + r.width() * 0.25f, r.top + r.height() * 0.52f)
                    lineTo(r.left + r.width() * 0.43f, r.top + r.height() * 0.70f)
                    lineTo(r.left + r.width() * 0.76f, r.top + r.height() * 0.30f)
                }
                c.drawPath(path, line)
            }
        }
        override fun setAlpha(alpha: Int) { fill.alpha = alpha }
        override fun setColorFilter(cf: ColorFilter?) { fill.colorFilter = cf }
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    /** Чекбокс Aero. */
    fun checkBox(ctx: Context, label: String): CheckBox = CheckBox(ctx).apply {
        text = label
        textSize = 14f
        setTextColor(pal(ctx).label)
        buttonDrawable = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_checked), CheckDrawable(ctx, true))
            addState(intArrayOf(), CheckDrawable(ctx, false))
        }
        setPadding(dp(ctx, 8), dp(ctx, 4), dp(ctx, 4), dp(ctx, 4))
        minHeight = 0; minimumHeight = 0
    }

    /**
     * Выпадающий список Aero. [items] запрашивается при каждом открытии (список портов приходит позже),
     * [onSelect] вызывается с индексом; подпись кнопки обновляйте через [TextView.setText] у возвращённой кнопки.
     */
    fun dropdown(ctx: Context, items: () -> List<String>, selected: () -> Int, onSelect: (Int) -> Unit): TextView {
        val p = pal(ctx)
        val btn = TextView(ctx).apply {
            textSize = 14f
            setTextColor(p.surfaceText)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(ctx, 12), dp(ctx, 8), dp(ctx, 28), dp(ctx, 8))
            background = android.graphics.drawable.LayerDrawable(arrayOf(
                GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(p.g1, p.g2, p.g3, p.g4)).apply {
                    setStroke(dp(ctx, 1), p.glassBorder); cornerRadius = dp(ctx, 6).toFloat()
                }
            ))
            isClickable = true; isFocusable = true
        }
        // Стрелка ▼ рисуется текстом справа.
        val wrap = android.widget.FrameLayout(ctx)
        val arrow = TextView(ctx).apply { text = "▼"; textSize = 10f; setTextColor(p.arrow) }
        wrap.addView(btn, android.widget.FrameLayout.LayoutParams(-1, -2))
        wrap.addView(arrow, android.widget.FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.CENTER_VERTICAL).apply { marginEnd = dp(ctx, 10) })
        btn.setOnClickListener { anchor ->
            val list = items()
            val col = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(ctx, 4), dp(ctx, 4), dp(ctx, 4), dp(ctx, 4))
                background = grad(p.list1, p.list2, p.listBorder, 1f, 6f, ctx)
            }
            var popupRef: PopupWindow? = null
            list.forEachIndexed { i, name ->
                val sel = i == selected()
                col.addView(TextView(ctx).apply {
                    text = name; textSize = 14f; maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(dp(ctx, 10), dp(ctx, 8), dp(ctx, 10), dp(ctx, 8))
                    setTextColor(if (sel) 0xFFFFFFFF.toInt() else p.itemText)
                    background = if (sel) grad(p.sel1, p.sel2, p.sel2, 1f, 4f, ctx) else StateListDrawable().apply {
                        addState(intArrayOf(android.R.attr.state_pressed), grad(p.sel1, p.sel2, p.sel2, 1f, 4f, ctx))
                        addState(intArrayOf(), android.graphics.drawable.ColorDrawable(0))
                    }
                    setOnClickListener { onSelect(i); popupRef?.dismiss() }
                })
            }
            val maxH = (ctx.resources.displayMetrics.heightPixels * 0.4f).toInt()
            val sv = object : ScrollView(ctx) {
                override fun onMeasure(w: Int, h: Int) { super.onMeasure(w, MeasureSpec.makeMeasureSpec(maxH, MeasureSpec.AT_MOST)) }
            }.apply { addView(col) }
            val pw = PopupWindow(sv, anchor.width, ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
                elevation = 12f * ctx.resources.displayMetrics.density
                setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0))
            }
            popupRef = pw
            pw.showAsDropDown(anchor, 0, dp(ctx, 2))
        }
        return btn.also { it.tag = wrap } // контейнер со стрелкой: используйте dropdownView()
    }

    /** Вью для вставки в разметку (кнопка со стрелкой). */
    fun dropdownView(btn: TextView): View = btn.tag as View
}
