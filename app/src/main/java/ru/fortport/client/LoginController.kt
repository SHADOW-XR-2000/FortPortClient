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
 * «подтвердите почту», регистрация, восстановление пароля (почта → код → новый пароль)
 * и плашки сохранённых аккаунтов для автозаполнения.
 */
class LoginController(
    private val activity: AppCompatActivity,
    private val api: SiteApi,
    root: ViewGroup,
    /** Вход выполнен: данные пользователя из ответа сервера. */
    private val onLoggedIn: (JSONObject) -> Unit,
    /** Открыть веб-страницу сайта (больше не используется формами регистрации/сброса — они нативные). */
