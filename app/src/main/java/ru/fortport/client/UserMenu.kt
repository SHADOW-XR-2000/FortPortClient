package ru.fortport.client

import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import org.json.JSONObject

/**
 * Меню пользователя в стиле Aero Start Menu (эталон Aero-StartMenu.html, светлая и тёмная тема).
 *
 * Шапка: аватар + имя (клик — своя страница). Левая колонка: баланс CD (клик — пополнение), Настройки,
 * Поддержка, О нас, Конфиденциальность, Условия, Проверить обновления. Правая колонка (разнесена шире,
 * чтобы не задевать соседние пункты): Музыка, Порты, Друзья, Переписки. Внизу — «Завершить сессию».
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
    private val onLogout: () -> Unit,
    private val onMusic: () -> Unit = {},
    private val onPorts: () -> Unit = {},
    private val onFriends: () -> Unit = {},
    private val onChats: () -> Unit = {}
) {
    private class Pal(
        val menu1: Int, val menu2: Int, val menuBorder: Int,
        val head1: Int, val head2: Int, val headLine: Int,
        val text: Int, val dim: Int, val name: Int, val icon: Int, val gold: Int,
        val hover1: Int, val hover2: Int, val hoverBorder: Int,
        val rightBg: Int, val rightLine: Int, val divider: Int,
        val foot1: Int, val foot2: Int, val footLine: Int,
        val bal1: Int, val bal2: Int, val balBorder: Int, val balValue: Int,
        val av1: Int, val av2: Int, val avBorder: Int,
        val end1: Int, val end2: Int, val end3: Int, val end4: Int, val endBorder: Int, val endText: Int
    )

    private val night = UiUtil.isNight(activity)
    private val c = if (night) Pal(
        0xFF182A44.toInt(), 0xFF0E1A2C.toInt(), 0xFF4A6F96.toInt(),
        0xFF2D4B73.toInt(), 0xFF1C3250.toInt(), 0xFF000000.toInt(),
        0xFFDBE9F7.toInt(), 0x99B4D2F0.toInt(), 0xFFECF5FF.toInt(), 0xFF9DC8E8.toInt(), 0xFFF0B840.toInt(),
        0xFF2F5F8F.toInt(), 0xFF25507A.toInt(), 0xFF5A86B5.toInt(),
        0xFF0F1D31.toInt(), 0xFF05101C.toInt(), 0x4D78AADC,
        0xFF0E1A2C.toInt(), 0xFF08101E.toInt(), 0xFF000000.toInt(),
        0xFF24476C.toInt(), 0xFF1A3558.toInt(), 0xFF3F6A9A.toInt(), 0xFF7FD0FF.toInt(),
        0xFF4A90D9.toInt(), 0xFF2A5A8A.toInt(), 0xFFA0CDFF.toInt(),
        0xFF4682C3.toInt(), 0xFF285A91.toInt(), 0xFF193C69.toInt(), 0xFF2D5F9B.toInt(), 0xFF78AFE6.toInt(), 0xFFE8F4FF.toInt()
    ) else Pal(
        0xFFF8FCFF.toInt(), 0xFFD6E8F8.toInt(), 0xFF78A5D2.toInt(),
        0xFFE8F4FF.toInt(), 0xFFC4DCF4.toInt(), 0xFF78A0C8.toInt(),
        0xFF17395C.toInt(), 0xB32D557D.toInt(), 0xFF0E2C49.toInt(), 0xFF2F6BA3.toInt(), 0xFFC98A10.toInt(),
        0xFFC8E4FF.toInt(), 0xFF9ECDF8.toInt(), 0xFF6EA5DC.toInt(),
        0xFFDDEBF8.toInt(), 0xFF6E9BC8.toInt(), 0x666E9BC8,
        0xFFD2E4F6.toInt(), 0xFFB2CDE8.toInt(), 0xFF6E9BC8.toInt(),
        0xFFD2EBFF.toInt(), 0xFFAAD0F0.toInt(), 0xFF69A0D7.toInt(), 0xFF0D5DA8.toInt(),
        0xFF6FB0E8.toInt(), 0xFF3A7CB0.toInt(), 0xFFFFFFFF.toInt(),
        0xFFB4D7FA.toInt(), 0xFF8CBEEB.toInt(), 0xFF73AADC.toInt(), 0xFF9BC8F0.toInt(), 0xFF508CC8.toInt(), 0xFF0E2C49.toInt()
    )

    private val overlay = FrameLayout(activity)
    private val panel = LinearLayout(activity)
    private val nameView = TextView(activity)

    /** Аватарка в шапке меню (MainActivity выставляет в неё картинку пользователя). */
    val avatar = ImageView(activity)

    /** Баланс CD (MainActivity подставляет сюда ответ /api/payments/balance). */
    val coinsValue = TextView(activity)

    private var generation = 0

    val isShowing: Boolean get() = overlay.visibility == View.VISIBLE

    private fun dp(v: Int) = UiUtil.dp(activity, v)

    private fun g(c1: Int, c2: Int, stroke: Int = 0, strokeDp: Int = 0, radiusDp: Int = 0): GradientDrawable =
        GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(c1, c2)).apply {
            if (strokeDp > 0) setStroke(dp(strokeDp), stroke)
            cornerRadius = dp(radiusDp).toFloat()
        }

    private fun iconView(res: Int, tint: Int, sizeDp: Int = 22): ImageView = ImageView(activity).apply {
        setImageResource(res)
        setColorFilter(tint, PorterDuff.Mode.SRC_IN)
        scaleType = ImageView.ScaleType.FIT_CENTER
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }

    init {
        overlay.visibility = View.GONE
        overlay.isClickable = true
        overlay.setOnClickListener { hide() }
        overlay.elevation = 16f * activity.resources.displayMetrics.density
        overlay.layoutParams = ConstraintLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

        val width = (activity.resources.displayMetrics.widthPixels * 0.94f).toInt()
        panel.orientation = LinearLayout.VERTICAL
        panel.isClickable = true // клики по меню не закрывают его
        panel.background = g(c.menu1, c.menu2, c.menuBorder, 2, 10)
        panel.clipToOutline = true
        panel.outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        overlay.addView(panel, FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = dp(70) // сразу под верхней панелью
        })

        panel.addView(header())

        val body = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        val left = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(4), dp(8))
        }
        left.addView(balanceRow())
        left.addView(divider())
        left.addView(row(R.drawable.ic_m_settings, "Настройки", 8) { act(onSettings) })
        left.addView(row(R.drawable.ic_m_help, "Поддержка", 8) { act { onPage("/support") } })
        left.addView(row(R.drawable.ic_m_info, "О нас", 8) { act { onPage("/about") } })
        left.addView(row(R.drawable.ic_m_lock, "Конфиденциальность", 8) { act { onPage("/privacy") } })
        left.addView(row(R.drawable.ic_m_doc, "Условия", 8) { act { onPage("/terms") } })
        left.addView(divider())
        left.addView(row(R.drawable.ic_m_refresh, "Проверить обновления", 8) { act(onUpdate) })

        val right = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(c.rightBg)
            setPadding(dp(6), dp(8), dp(8), dp(8))
        }
        // Пункты разнесены сильнее левых, чтобы не задеть соседний.
        right.addView(row(R.drawable.ic_m_music, "Музыка", 14) { act(onMusic) })
        right.addView(row(R.drawable.ic_m_ports, "Порты", 14) { act(onPorts) })
        right.addView(row(R.drawable.ic_m_friends, "Друзья", 14) { act(onFriends) })
        right.addView(row(R.drawable.ic_m_chat, "Переписки", 14) { act(onChats) })

        body.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.35f))
        body.addView(View(activity).apply { setBackgroundColor(c.rightLine) }, LinearLayout.LayoutParams(1, ViewGroup.LayoutParams.MATCH_PARENT))
        body.addView(right, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        panel.addView(body)
        panel.addView(footer())

        root.addView(overlay)
    }

    /** Закрывает меню и выполняет действие пункта. */
    private fun act(block: () -> Unit) { hide(); block() }

    private fun header(): View = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = g(c.head1, c.head2)
        setPadding(dp(14), dp(10), dp(14), dp(10))
        avatar.scaleType = ImageView.ScaleType.CENTER_CROP
        avatar.background = g(c.av1, c.av2, c.avBorder, 2, 4)
        avatar.setPadding(dp(2), dp(2), dp(2), dp(2))
        avatar.setImageResource(R.drawable.avatar_placeholder)
        addView(avatar, LinearLayout.LayoutParams(dp(54), dp(54)))
        val col = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        nameView.text = "Пользователь"
        nameView.textSize = 18f
        nameView.setTypeface(null, android.graphics.Typeface.BOLD)
        nameView.setTextColor(c.name)
        nameView.maxLines = 1
        nameView.ellipsize = android.text.TextUtils.TruncateAt.END
        col.addView(nameView)
        col.addView(TextView(activity).apply { text = "В сети"; textSize = 12f; setTextColor(c.dim) })
        addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(12) })
        setOnClickListener { act(onProfile) }
    }

    private fun itemBg(): Drawable = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), g(c.hover1, c.hover2, c.hoverBorder, 1, 3))
        addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
    }

    private fun divider(): View = View(activity).apply {
        setBackgroundColor(c.divider)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply { setMargins(dp(4), dp(6), dp(4), dp(6)) }
    }

    private fun balanceRow(): View = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = g(c.bal1, c.bal2, c.balBorder, 1, 3)
        setPadding(dp(10), dp(10), dp(10), dp(10))
        isClickable = true; isFocusable = true
        addView(iconView(R.drawable.ic_m_coin, c.gold))
        addView(TextView(activity).apply { text = "CD-баланс"; textSize = 15f; setTextColor(c.text) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(10) })
        coinsValue.text = "—"
        coinsValue.textSize = 15f
        coinsValue.setTypeface(null, android.graphics.Typeface.BOLD)
        coinsValue.setTextColor(c.balValue)
        addView(coinsValue)
        setOnClickListener { act(onTopUp) }
    }

    private fun row(icon: Int, label: String, vPadDp: Int, onClick: () -> Unit): View = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = itemBg()
        isClickable = true; isFocusable = true
        setPadding(dp(10), dp(vPadDp), dp(10), dp(vPadDp))
        addView(iconView(icon, c.icon))
        addView(TextView(activity).apply {
            text = label
            textSize = 15f
            setTextColor(c.text)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(10) })
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(if (vPadDp > 8) 6 else 1)
        }
    }

    private fun footer(): View = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.END or Gravity.CENTER_VERTICAL
        background = g(c.foot1, c.foot2)
        setPadding(dp(10), dp(8), dp(10), dp(8))
        val btn = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(9), dp(18), dp(9))
            isClickable = true; isFocusable = true
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_pressed), g(c.end3, c.end2, c.endBorder, 1, 4))
                addState(intArrayOf(), GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(c.end1, c.end2, c.end3, c.end4))
                    .apply { setStroke(dp(1), c.endBorder); cornerRadius = dp(4).toFloat() })
            }
            addView(iconView(R.drawable.ic_m_power, c.endText, 18))
            addView(TextView(activity).apply {
                text = "Завершить сессию"; textSize = 15f; setTextColor(c.endText)
                setTypeface(null, android.graphics.Typeface.BOLD)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(8) })
            setOnClickListener { act(onLogout) }
        }
        addView(btn)
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
