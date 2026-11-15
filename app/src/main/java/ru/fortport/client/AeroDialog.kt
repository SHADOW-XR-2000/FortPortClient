package ru.fortport.client

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Окно в стиле Aero (концепты «Настройка ленты», «Новая лента», «Выбрать порт»): панель с заголовком по центру,
 * прокручиваемое содержимое [body] и необязательный нижний ряд [footer] с кнопками (не прокручивается).
 */
class AeroDialog(private val activity: AppCompatActivity, title: String) {

    private val d = activity.resources.displayMetrics.density
    private fun dp(v: Int) = (v * d + 0.5f).toInt()

    val dialog = Dialog(activity)
    val body = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    val footer = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private val titleView: TextView
    var onDismiss: (() -> Unit)? = null

    init {
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val maxH = (activity.resources.displayMetrics.heightPixels * 0.62f).toInt()
        val scroll = object : ScrollView(activity) {
            override fun onMeasure(w: Int, h: Int) = super.onMeasure(w, MeasureSpec.makeMeasureSpec(maxH, MeasureSpec.AT_MOST))
        }.apply { isVerticalScrollBarEnabled = false; addView(body, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)) }

        titleView = UiUtil.text(activity, 20f, AeroUi.titleColor(activity), bold = true).apply {
            text = title; gravity = Gravity.CENTER
        }
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = AeroUi.panelBackground(activity)
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        root.addView(titleView, UiUtil.lp(UiUtil.MATCH, UiUtil.WRAP).apply { bottomMargin = dp(10) })
        root.addView(scroll, UiUtil.lp(UiUtil.MATCH, UiUtil.WRAP))
        root.addView(footer, UiUtil.lp(UiUtil.MATCH, UiUtil.WRAP).apply { topMargin = dp(10) })
        dialog.setContentView(root)
        dialog.setCanceledOnTouchOutside(true)
        dialog.setOnDismissListener { onDismiss?.invoke() }
    }

    fun setTitle(t: String) { titleView.text = t }

    fun show() {
        if (activity.isFinishing || activity.isDestroyed) return
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            val w = (activity.resources.displayMetrics.widthPixels * 0.92f).toInt()
            setLayout(w, ViewGroup.LayoutParams.WRAP_CONTENT)
            setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
    }

    fun dismiss() { if (dialog.isShowing) dialog.dismiss() }

    /** Кнопка Aero на всю ширину (нижний ряд и формы). */
    fun button(label: String, click: () -> Unit): TextView =
        UiUtil.text(activity, 17f, AeroButton.textColor(activity), bold = true).apply {
            text = label; gravity = Gravity.CENTER
            AeroButton.style(this)
            setPadding(dp(8), dp(12), dp(8), dp(12))
            setOnClickListener { click() }
        }
}
