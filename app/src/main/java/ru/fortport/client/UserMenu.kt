package ru.fortport.client

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import org.json.JSONObject

/**
 * Меню пользователя, раскрывающееся из правого угла верхней панели (как «Пуск»).
 *
 * Сверху вниз: плашка royal с аватаркой и именем (клик — своя страница); белый блок с балансом CD
 * и кнопкой «Пополнить», затем «Настройки», «Поддержка», «О нас», «Конфиденциальность», «Условия»,
 * «Проверить обновления» (заглушка); внизу плашка royal «Завершить сессию».
 *
 * Иконки берутся из assets/ui/icons: cd_coin.png, setting_icon.png, info.png, ic_nav_home.png,
 * confidence.png, terms.png, check_update.png, logout.png. Если файла пока нет — место под иконку пустое.
 *
 * Имя, аватар и баланс подгружаются при каждом открытии (GET /api/users/bio/{id}, /api/payments/balance).
 */
class UserMenu(
    private val activity: AppCompatActivity,
    private val api: SiteApi,
    root: ViewGroup,
    private val onProfile: () -> Unit,
    private val onTopUp: () -> Unit,
    private val onSettings: () -> Unit,
    private val onPage: (String) -> Unit,
    private val onUpdate: () -> Unit,
    private val onLogout: () -> Unit
) {
    private val night = (activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    private val cBody = if (night) 0xFF1B1D26.toInt() else Color.WHITE
    private val cText = if (night) 0xFFF2F2F2.toInt() else 0xFF15141C.toInt()
    private val cLine = if (night) 0xFF000000.toInt() else 0xFF111111.toInt()

    private val overlay = FrameLayout(activity)
    private val panel = LinearLayout(activity)
    private val nameView = TextView(activity)

    /** Аватарка в плашке (MainActivity выставляет в неё картинку пользователя). */
    val avatar = ImageView(activity)

    /** Баланс CD (MainActivity подставляет сюда ответ /api/payments/balance). */
    val coinsValue = TextView(activity)

    private var generation = 0

    val isShowing: Boolean get() = overlay.visibility == View.VISIBLE

    private fun dp(v: Int) = UiUtil.dp(activity, v)

    private fun icon(name: String): Bitmap? =
        try { activity.assets.open("ui/icons/$name").use { BitmapFactory.decodeStream(it) } } catch (e: Exception) { null }

    init {
        overlay.visibility = View.GONE
        overlay.isClickable = true
        overlay.setOnClickListener { hide() }
        overlay.elevation = 16f * activity.resources.displayMetrics.density
        overlay.layoutParams = ConstraintLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

        val width = (activity.resources.displayMetrics.widthPixels * 0.82f).toInt()
        panel.orientation = LinearLayout.VERTICAL
        panel.isClickable = true // клики по меню не закрывают его
        overlay.addView(panel, FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
            topMargin = dp(70) // сразу под верхней панелью
        })

        panel.addView(header())
        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = UiUtil.rect(cBody, cLine, 3, 0, activity)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        body.addView(coinsRow())
        body.addView(row("setting_icon.png", "Настройки") { act(onSettings) })
        body.addView(row("info.png", "Поддержка") { act { onPage("/support") } })
        body.addView(row("ic_nav_home.png", "О нас") { act { onPage("/about") } })
        body.addView(row("confidence.png", "Конфиденциальность") { act { onPage("/privacy") } })
        body.addView(row("terms.png", "Условия") { act { onPage("/terms") } })
        body.addView(row("check_update.png", "Проверить обновления") { act(onUpdate) })
        panel.addView(body)
        panel.addView(footer())

        root.addView(overlay)
    }

    /** Закрывает меню и выполняет действие пункта. */
    private fun act(block: () -> Unit) { hide(); block() }

    private fun header(): View = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = UiUtil.royal(activity)
        setPadding(dp(14), dp(10), dp(14), dp(10))
        avatar.scaleType = ImageView.ScaleType.CENTER_CROP
        avatar.setBackgroundColor(Color.WHITE)
        avatar.setPadding(dp(2), dp(2), dp(2), dp(2))
        avatar.setImageResource(R.drawable.avatar_placeholder)
        addView(avatar, LinearLayout.LayoutParams(dp(64), dp(64)))
        nameView.text = "Пользователь"
        nameView.textSize = 22f
        nameView.setTextColor(Color.WHITE)
        nameView.maxLines = 1
        nameView.ellipsize = android.text.TextUtils.TruncateAt.END
        addView(nameView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(16) })
        setOnClickListener { act(onProfile) }
    }

    private fun iconView(name: String): ImageView = ImageView(activity).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        icon(name)?.let { setImageBitmap(it) }
        layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
    }

    private fun coinsRow(): View = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(10), dp(8), dp(10), dp(8))
        addView(iconView("cd_coin.png"))
        coinsValue.text = "—"
        coinsValue.textSize = 20f
        coinsValue.setTextColor(cText)
        addView(coinsValue, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(12) })
        addView(Button(activity).apply {
            text = "Пополнить"
            isAllCaps = false
            textSize = 15f
            setTextColor(0xFF15141C.toInt())
            background = UiUtil.rect(0xFFD9D9D9.toInt(), 0xFF555555.toInt(), 2, 8, activity)
            minHeight = 0; minimumHeight = 0
            setPadding(dp(14), dp(8), dp(14), dp(8))
            setOnClickListener { act(onTopUp) }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)))
    }

    private fun row(iconName: String, label: String, onClick: () -> Unit): View = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(10), dp(8), dp(10), dp(8))
        addView(iconView(iconName))
        addView(TextView(activity).apply {
            text = label
            textSize = 18f
            setTextColor(cText)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(16) })
        setOnClickListener { onClick() }
    }

    private fun footer(): View = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = UiUtil.royal(activity)
        setPadding(dp(14), dp(10), dp(14), dp(10))
        addView(iconView("logout.png").apply { layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)) })
        addView(TextView(activity).apply {
            text = "Завершить сессию"
            textSize = 17f
            setTextColor(Color.WHITE)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(16) })
        setOnClickListener { act(onLogout) }
    }

    // ------------------------------------------------------------------ показ

    fun toggle() { if (isShowing) hide() else show() }

    fun show() {
        if (isShowing) return
        overlay.alpha = 0f
        overlay.visibility = View.VISIBLE
        panel.translationY = -dp(16).toFloat()
        overlay.animate().alpha(1f).setDuration(120).start()
        panel.animate().translationY(0f).setDuration(120).start()
        refresh()
    }

    fun hide() {
        generation++
        overlay.animate().cancel()
        panel.animate().cancel()
        overlay.visibility = View.GONE
    }

    /** Имя, аватар и баланс — с сервера (или из тестовой базы). Ошибки сети молча оставляют прежние значения. */
    private fun refresh() {
        val gen = ++generation
        api.io.execute {
            var name = ""; var pic = ""; var balance = ""
            try {
                if (api.ensureUserId() == 0) {
                    api.get("/api/users/bio/${api.userId}").takeIf { it.code == 200 }?.let {
                        val o = JSONObject(it.body); name = api.str(o, "username"); pic = api.str(o, "profilePicture")
                    }
                    api.get("/api/payments/balance").takeIf { it.code == 200 }?.let {
                        balance = api.str(JSONObject(it.body), "formatted")
                    }
                }
            } catch (e: Exception) { /* оставляем как есть */ }
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed || gen != generation) return@runOnUiThread
                if (name.isNotBlank()) { nameView.text = name; api.loadAvatar(avatar, pic) }
                if (balance.isNotBlank()) coinsValue.text = balance
            }
        }
    }
}
