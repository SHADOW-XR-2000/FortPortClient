package ru.fortport.client

import android.graphics.Color
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import org.json.JSONObject

/**
 * Нативная форма входа поверх страницы /login: логин/пароль, код 2FA, «нужна почта» /
 * «подтвердите почту» и плашки сохранённых аккаунтов для автозаполнения.
 */
class LoginController(
    private val activity: AppCompatActivity,
    private val api: SiteApi,
    root: ViewGroup,
    /** Вход выполнен: данные пользователя из ответа сервера. */
    private val onLoggedIn: (JSONObject) -> Unit,
    /** Открыть веб-страницу сайта (регистрация, сброс пароля). */
    private val onOpenWeb: (String) -> Unit
) {
    private val ctx = activity
    private val saved = SavedAccounts(activity)

    private val overlay = FrameLayout(ctx)
    private val content = LinearLayout(ctx)
    private val platesBox = LinearLayout(ctx)
    private val platesTitle: TextView

    private var challengeId: String? = null
    private var busy = false
    private var lastIdentifier = ""
    private var lastPassword = ""
    private var remember = true
    private var destroyed = false

    val isShowing: Boolean get() = overlay.visibility == View.VISIBLE

    private fun dp(v: Int) = UiUtil.dp(ctx, v)

    init {
        overlay.setBackgroundColor(0xFFEDEFF4.toInt())
        overlay.visibility = View.GONE
        overlay.isClickable = true
        overlay.elevation = 20f * ctx.resources.displayMetrics.density
        overlay.layoutParams = ConstraintLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        )
        val scroll = ScrollView(ctx)
        scroll.isFillViewport = true
        content.orientation = LinearLayout.VERTICAL
        content.setPadding(dp(20), dp(40), dp(20), dp(24))
        scroll.addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        overlay.addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(overlay)

        platesTitle = UiUtil.text(ctx, 14f, 0xFF555555.toInt(), true).apply {
            text = "Сохранённые аккаунты"
            setPadding(dp(4), dp(24), 0, dp(8))
        }
        platesBox.orientation = LinearLayout.VERTICAL
        showLoginForm("")
    }

    fun show() {
        if (destroyed) return
        overlay.visibility = View.VISIBLE
        if (challengeId == null) refreshPlates()
    }

    fun hide() {
        overlay.visibility = View.GONE
        hideKeyboard()
    }

    /** Сбрасывает шаг кода/подтверждения и возвращает пользователя к обычному входу. */
    fun showLoginError(error: String) {
        challengeId = null
        showLoginForm(error)
        overlay.visibility = View.VISIBLE
    }

    fun destroy() {
        destroyed = true
    }

    /** Системная кнопка «назад»: из шага 2FA/почты — к форме. */
    fun handleBack(): Boolean {
        if (!isShowing) return false
        if (challengeId != null || content.tag == "mode_other") {
            challengeId = null
            showLoginForm("")
            return true
        }
        return false
    }

    // ------------------------------------------------------------------ вёрстка

    private fun card(): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(18), dp(18), dp(18))
        background = UiUtil.rect(Color.WHITE, 0xFFBCCEE2.toInt(), 2, 12, ctx)
    }

    private fun field(hint: String, password: Boolean): EditText = EditText(ctx).apply {
        this.hint = hint
        setSingleLine(true)
        inputType = if (password) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        textSize = 16f
        setTextColor(Color.BLACK)
        setHintTextColor(0xFF888888.toInt())
        setPadding(dp(12), dp(12), dp(12), dp(12))
        background = UiUtil.rect(0xFFEDEFF4.toInt(), 0xFFBCCEE2.toInt(), 2, 8, ctx)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(10)
        }
    }

    private fun primaryButton(label: String): Button = Button(ctx).apply {
        text = label
        isAllCaps = false
        textSize = 16f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(Color.BLACK)
        background = UiUtil.rect(0xFFBEF1FC.toInt(), 0xFF8CC9D8.toInt(), 1, 8, ctx)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(14) }
    }

    private fun linkText(label: String, onClick: () -> Unit): TextView =
        UiUtil.text(ctx, 14f, 0xFF2A62B8.toInt()).apply {
            text = label
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(10), dp(8), dp(10))
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

    private fun title(t: String) = UiUtil.text(ctx, 24f, Color.BLACK, true).apply {
        text = t
        gravity = Gravity.CENTER
        setPadding(0, 0, 0, dp(6))
    }

    private fun message(): TextView = UiUtil.text(ctx, 14f, 0xFFD02020.toInt()).apply {
        gravity = Gravity.CENTER
        setPadding(0, dp(10), 0, 0)
        visibility = View.GONE
    }

    private fun showMsg(tv: TextView, text: String, ok: Boolean = false) {
        tv.setTextColor(if (ok) 0xFF1E8E3E.toInt() else 0xFFD02020.toInt())
        tv.text = text
        tv.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun reset(mode: String) {
        content.removeAllViews()
        content.tag = mode
    }

    private fun showLoginForm(error: String, autoLogin: Boolean = false) {
        reset("login")
        val c = card()
        c.addView(title("Вход"))
        val ident = field("Логин или почта", false)
        val pass = field("Пароль", true)
        ident.setText(lastIdentifier)
        pass.setText(lastPassword)
        val msg = message()
        val rememberBox = CheckBox(ctx).apply {
            text = "Запомнить аккаунт на этом устройстве"
            isChecked = remember
            setTextColor(0xFF333333.toInt())
            textSize = 13f
            setOnCheckedChangeListener { _, v -> remember = v }
        }
        val btn = primaryButton("Войти")
        c.addView(ident); c.addView(pass); c.addView(rememberBox); c.addView(msg); c.addView(btn)
        c.addView(linkText("Забыли пароль?") { onOpenWeb("/reset-password") })
        c.addView(linkText("Нет аккаунта? Зарегистрироваться") { onOpenWeb("/register") })
        content.addView(c)
        if (error.isNotEmpty()) showMsg(msg, error)

        val submit = {
            val i = ident.text.toString().trim()
            val p = pass.text.toString()
            if (i.isEmpty() || p.isEmpty()) {
                showMsg(msg, "Заполните все поля")
            } else if (!busy) {
                lastIdentifier = i
                lastPassword = p
                doLogin(i, p, msg, btn)
            }
        }
        btn.setOnClickListener { submit() }
        pass.setOnEditorActionListener { _, _, _ -> submit(); true }
        if (autoLogin) btn.post { submit() }

        content.addView(platesTitle.also { (it.parent as? ViewGroup)?.removeView(it) })
        content.addView(platesBox.also { (it.parent as? ViewGroup)?.removeView(it) })
        refreshPlates()
    }

    private fun refreshPlates() {
        platesBox.removeAllViews()
        val list = if (content.tag == "login") saved.list() else emptyList()
        platesTitle.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
        for (a in list) platesBox.addView(plate(a))
    }

    private fun plate(a: SavedAccounts.Account): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(8), dp(10))
            background = UiUtil.rect(Color.WHITE, 0xFFBCCEE2.toInt(), 2, 12, ctx)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(8)
            }
        }
        val av = ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
        }
        api.loadAvatar(av, a.avatar)
        val names = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(12) }
        }
        names.addView(UiUtil.text(ctx, 16f, Color.BLACK, true, 1).apply { text = a.username })
        names.addView(UiUtil.text(ctx, 12f, 0xFF777777.toInt(), false, 1).apply { text = "Нажмите, чтобы войти" })
        val del = TextView(ctx).apply {
            text = "✕"
            textSize = 18f
            setTextColor(0xFF888888.toInt())
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setOnClickListener {
                saved.remove(a.userId)
                refreshPlates()
            }
        }
        row.addView(av); row.addView(names); row.addView(del)
        row.setOnClickListener {
            lastIdentifier = a.identifier
            lastPassword = a.password
            showLoginForm("", autoLogin = true)
        }
        return row
    }

    // ------------------------------------------------------------------ вход

    private fun setBusy(b: Boolean, btn: Button, label: String) {
        busy = b
        btn.isEnabled = !b
        btn.text = if (b) "Подождите…" else label
    }

    private fun parse(body: String): JSONObject? = try { JSONObject(body) } catch (e: Exception) { null }

    private fun doLogin(identifier: String, password: String, msg: TextView, btn: Button) {
        hideKeyboard()
        setBusy(true, btn, "Войти")
        showMsg(msg, "")
        val payload = JSONObject().put("identifier", identifier).put("password", password).toString()
        api.io.execute {
            var r: SiteApi.Resp? = null
            var netErr = false
            try { r = api.send("POST", "/api/login", payload) } catch (e: Exception) { netErr = true }
            activity.runOnUiThread {
                if (destroyed) return@runOnUiThread
                setBusy(false, btn, "Войти")
                if (netErr || r == null) { showMsg(msg, "Нет связи с сервером"); return@runOnUiThread }
                val o = parse(r.body)
                if (o == null) { showMsg(msg, "Сервер вернул некорректный ответ"); return@runOnUiThread }
                val ok = r.code in 200..299 && o.optBoolean("success", false)
                if (!ok) {
                    if (o.optBoolean("requiresVerification", false)) {
                        showVerification(api.str(o, "error").ifEmpty { "Нужно подтвердить email" }, api.str(o, "maskedEmail"))
                    } else {
                        showMsg(msg, api.str(o, "error").ifEmpty { "Ошибка входа" })
                    }
                    return@runOnUiThread
                }
                if (o.optBoolean("requires2FA", false)) {
                    show2fa(api.str(o, "challengeId"))
                    return@runOnUiThread
                }
                val user = o.optJSONObject("user")
                if (user == null || user.optLong("id", 0L) <= 0L || api.str(user, "username").isEmpty()) {
                    showMsg(msg, "Сервер вернул неполные данные пользователя")
                    return@runOnUiThread
                }
                if (o.optBoolean("needsEmail", false)) {
                    showNeedsEmail(api.str(user, "username"))
                    return@runOnUiThread
                }
                finishLogin(user)
            }
        }
    }

    private fun finishLogin(user: JSONObject) {
        if (remember) {
            saved.save(
                user.optLong("id", 0L).toString(), api.str(user, "username"),
                api.str(user, "profilePicture"), lastIdentifier, lastPassword
            )
        }
        lastPassword = ""
        challengeId = null
        onLoggedIn(user)
    }

    // ------------------------------------------------------------------ 2FA

    private fun show2fa(id: String) {
        challengeId = id
        reset("mode_other")
        val c = card()
        c.addView(title("Код из письма"))
        c.addView(UiUtil.text(ctx, 14f, 0xFF555555.toInt()).apply {
            text = "Мы отправили код на вашу почту. Введите его ниже, либо перейдите по ссылке из письма."
            gravity = Gravity.CENTER
        })
        val code = field("6 символов", false).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            filters = arrayOf(android.text.InputFilter.LengthFilter(6), android.text.InputFilter.AllCaps())
            gravity = Gravity.CENTER
            textSize = 22f
            letterSpacing = 0.3f
        }
        val msg = message()
        val btn = primaryButton("Подтвердить")
        c.addView(code); c.addView(msg); c.addView(btn)
        c.addView(linkText("Назад") { challengeId = null; showLoginForm("") })
        content.addView(c)
        code.requestFocus()

        val submit = {
            val v = code.text.toString().trim().uppercase()
            if (v.length != 6) {
                showMsg(msg, "Введите 6 символов")
            } else if (!busy) {
                hideKeyboard()
                setBusy(true, btn, "Подтвердить")
                val payload = JSONObject().put("challengeId", id).put("code", v).toString()
                api.io.execute {
                    var r: SiteApi.Resp? = null
                    try { r = api.send("POST", "/api/login/2fa/code", payload) } catch (e: Exception) { }
                    activity.runOnUiThread {
                        if (destroyed) return@runOnUiThread
                        setBusy(false, btn, "Подтвердить")
                        if (r == null) { showMsg(msg, "Нет связи с сервером"); return@runOnUiThread }
                        val o = parse(r.body)
                        if (o == null) { showMsg(msg, "Сервер вернул некорректный ответ"); return@runOnUiThread }
                        if (r.code !in 200..299 || !o.optBoolean("success", false)) {
                            showMsg(msg, api.str(o, "error").ifEmpty { "Неверный код" })
                            return@runOnUiThread
                        }
                        val user = o.optJSONObject("user")
                        if (user == null || user.optLong("id", 0L) <= 0L || api.str(user, "username").isEmpty()) {
                            showMsg(msg, "Сервер вернул неполные данные пользователя")
                            return@runOnUiThread
                        }
                        finishLogin(user)
                    }
                }
            }
        }
        btn.setOnClickListener { submit() }
        code.setOnEditorActionListener { _, _, _ -> submit(); true }
    }

    // ------------------------------------------------------------------ почта

    private fun logoutAndBack() {
        api.io.execute { try { api.send("POST", "/api/logout", null) } catch (e: Exception) { } }
        challengeId = null
        showLoginForm("")
    }

    private fun showNeedsEmail(username: String) {
        reset("mode_other")
        val c = card()
        c.addView(title("Требуется почта"))
        c.addView(UiUtil.text(ctx, 14f, 0xFF333333.toInt()).apply {
            text = "Аккаунт $username найден, но почта не привязана. Без подтверждённой почты продолжить вход нельзя."
            gravity = Gravity.CENTER
        })
        val email = field("Ваша почта, например name@mail.ru", false)
        val msg = message()
        val btn = primaryButton("Отправить письмо для подтверждения")
        c.addView(email); c.addView(msg); c.addView(btn)
        c.addView(linkText("Выйти") { logoutAndBack() })
        content.addView(c)
        btn.setOnClickListener {
            val e = email.text.toString().trim()
            if (!android.util.Patterns.EMAIL_ADDRESS.matcher(e).matches()) {
                showMsg(msg, "Введите корректную почту")
            } else if (!busy) {
                hideKeyboard()
                setBusy(true, btn, "Отправить письмо для подтверждения")
                val payload = JSONObject().put("email", e).toString()
                api.io.execute {
                    var r: SiteApi.Resp? = null
                    try { r = api.send("POST", "/api/add-email", payload) } catch (ex: Exception) { }
                    activity.runOnUiThread {
                        if (destroyed) return@runOnUiThread
                        setBusy(false, btn, "Отправить письмо для подтверждения")
                        if (r == null) { showMsg(msg, "Нет связи с сервером"); return@runOnUiThread }
                        val o = parse(r.body)
                        if (o == null || r.code !in 200..299 || !o.optBoolean("success", false)) {
                            showMsg(msg, (if (o != null) api.str(o, "error") else "").ifEmpty { "Ошибка ${r.code}" })
                            return@runOnUiThread
                        }
                        if (!o.optBoolean("verificationPending", false)) {
                            showMsg(msg, "Сервер не подтвердил ожидание проверки почты")
                            return@runOnUiThread
                        }
                        showEmailSent(api.str(o, "email").ifEmpty { e })
                    }
                }
            }
        }
    }

    private fun showEmailSent(email: String) {
        reset("mode_other")
        val c = card()
        c.addView(title("Подтвердите почту"))
        c.addView(UiUtil.text(ctx, 14f, 0xFF333333.toInt()).apply {
            text = "Письмо отправлено на $email. Откройте ссылку из письма, чтобы создать сессию и перейти на сайт."
            gravity = Gravity.CENTER
        })
        c.addView(linkText("Вернуться ко входу") { logoutAndBack() })
        content.addView(c)
    }

    private fun showVerification(error: String, masked: String) {
        reset("mode_other")
        val c = card()
        c.addView(title("Нужно подтвердить почту"))
        c.addView(UiUtil.text(ctx, 14f, 0xFFD02020.toInt()).apply {
            text = error + "\nСохранённый адрес: " + (if (masked.isEmpty()) "***" else masked)
            gravity = Gravity.CENTER
        })
        val status = UiUtil.text(ctx, 13f, 0xFF555555.toInt()).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, 0)
        }
        val resend = primaryButton("Отправить письмо снова")
        val change = primaryButton("Сменить через старую почту")
        c.addView(resend); c.addView(change); c.addView(status)
        c.addView(linkText("Назад") { showLoginForm("") })
        content.addView(c)
        val call = { path: String, btn: Button, label: String, okText: (JSONObject) -> String ->
            if (!busy) {
                setBusy(true, btn, label)
                status.text = ""
                api.io.execute {
                    var r: SiteApi.Resp? = null
                    try { r = api.send("POST", path, "{}") } catch (e: Exception) { }
                    activity.runOnUiThread {
                        if (destroyed) return@runOnUiThread
                        setBusy(false, btn, label)
                        val o = if (r != null) parse(r.body) else null
                        status.text = when {
                            r == null -> "Нет связи с сервером"
                            o == null -> "Сервер вернул некорректный ответ"
                            r.code !in 200..299 || !o.optBoolean("success", false) ->
                                api.str(o, "error").ifEmpty { "Ошибка ${r.code}" }
                            else -> okText(o)
                        }
                    }
                }
            }
        }
        resend.setOnClickListener {
            call("/api/resend-verification", resend, "Отправить письмо снова") { o ->
                api.str(o, "message").ifEmpty { "Письмо отправлено!" }
            }
        }
        change.setOnClickListener {
            call("/api/request-email-change", change, "Сменить через старую почту") { o ->
                api.str(o, "message") + ". Откройте её, чтобы указать новую почту."
            }
        }
    }

    private fun hideKeyboard() {
        try {
            val imm = ctx.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(overlay.windowToken, 0)
        } catch (e: Exception) { }
    }
}
