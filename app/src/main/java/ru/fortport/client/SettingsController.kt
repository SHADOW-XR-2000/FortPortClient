package ru.fortport.client

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Outline
import android.graphics.Typeface
import android.net.Uri
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.inputmethod.InputMethodManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.Calendar

/**
 * Нативные настройки профиля вместо веб-страницы /settings (концепции 1–3):
 * вкладки «Профиль», «Безопасность», «Кастомизация».
 *
 * Чтение (проверено по логу сайта):
 *  - GET /api/users/bio/{id}  — username, profilePicture, profileBackground, status, description,
 *                               dateOfBirth, homeCountry, education, workplace, hobbies, fandoms, religion;
 *  - GET /api/users/seq/{id}  — showPostsFeed, showPostsProfile, showInfoProfile, anonymousPage,
 *                               showFriendsList, showCommunitiesList, showAudios, showPhotos,
 *                               allowMessagesFrom, showNSFW;
 *  - GET /api/users/cust/{id} — customBackground и пять флагов звуков;
 *  - GET /api/session/bootstrap → mascot{handHolding,…}.
 *
 * Запись: в логе её нет — адреса и методы собраны в [Ep] и НЕ ПРОВЕРЕНЫ. Когда будет лог
 * нажатия «Сохранить» на сайте, достаточно поправить [Ep] (и при необходимости форму тела).
 */
class SettingsController(
    private val activity: AppCompatActivity,
    private val api: SiteApi,
    private val host: FrameLayout,
    /** Выбор картинки с устройства; результат приходит в переданный колбэк. */
    private val pickImage: ((Uri) -> Unit) -> Unit,
    private val onAuthLost: () -> Unit
) {
    private companion object {
        const val TAG = "Settings"
        const val MAX_UPLOAD = 20L * 1024 * 1024
        const val DEFAULT_MASCOT = "Жека"
        const val TOPBAR_DP = 70
        val TAB_BLUE = 0xFF1F78FF.toInt()
        val TAB_ACTIVE = 0xFFBEF1FC.toInt()
        val SAVE_TEAL = 0xFF12D9C0.toInt()
        val LINE = 0xFF111111.toInt()
        val SEC_KEYS = listOf(
            "showPostsFeed", "showPostsProfile", "showInfoProfile", "anonymousPage", "showFriendsList",
            "showCommunitiesList", "showAudios", "showPhotos", "allowMessagesFrom", "showNSFW"
        )
        val SND_KEYS = listOf("notificationSound", "chatMessageSound", "postSentSound", "friendOnlineSound", "friendRequestSound")
        val BIO_KEYS = listOf(
            "username", "status", "description", "dateOfBirth", "homeCountry", "education",
            "workplace", "hobbies", "fandoms", "religion"
        )
        val VISIBILITY = listOf("Кто угодно", "Друзья", "Только я")
        val WRITE = listOf("Кто угодно", "Друзья", "Никто")
    }

    /** Адреса сохранения. НЕ ПРОВЕРЕНЫ (в логе только GET) — поправить по логу «Сохранить». */
    private class Ep(val method: String, val path: String)
    private object Endpoints {
        fun bio(id: String) = Ep("PUT", "/api/users/bio/$id")
        fun seq(id: String) = Ep("PUT", "/api/users/seq/$id")
        fun cust(id: String) = Ep("PUT", "/api/users/cust/$id")
        val avatar = Ep("POST", "/api/users/avatar")
        val profileBg = Ep("POST", "/api/users/profile-background")
        val feedBg = Ep("POST", "/api/users/custom-background")
        const val FILE_FIELD = "file"
    }

    private enum class Tab { PROFILE, SECURITY, CUSTOM }
    private enum class Img { AVATAR, PROFILE_BG, FEED_BG }

    private val d = activity.resources.displayMetrics.density
    private fun dp(v: Int) = (v * d + 0.5f).toInt()
    private val prefs = activity.getSharedPreferences("settings_ui", Context.MODE_PRIVATE)

    // --- состояние (правки живут до перезагрузки, между вкладками не теряются)
    private var tab = Tab.PROFILE
    private var started = false
    private var loaded = false
    private var saving = false
    private var generation = 0
    private val bio = LinkedHashMap<String, String>()
    private val sec = LinkedHashMap<String, Int>()
    private val snd = LinkedHashMap<String, Int>()
    private var avatarPath = ""
    private var profileBgPath = ""
    private var feedBgPath = ""
    private var handHoldingServer = true
    private val pendingImg = HashMap<Img, Uri>()

    // --- виды
    private val scroll = ScrollView(activity)
    private val content = LinearLayout(activity)
    private val pill = TextView(activity)
    private var saveButton: TextView? = null
    private var hideTask: Runnable? = null

    init {
        scroll.isFillViewport = false
        scroll.clipToPadding = false
        scroll.setPadding(0, dp(TOPBAR_DP), 0, dp(28))
        content.orientation = LinearLayout.VERTICAL
        scroll.addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        host.addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        pill.textSize = 15f
        pill.setTextColor(UiUtil.WHITE)
        pill.setPadding(dp(18), dp(10), dp(18), dp(10))
        pill.visibility = View.GONE
        pill.elevation = dp(6).toFloat()
        host.addView(pill, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(36) })
    }

    // ------------------------------------------------------------------ жизненный цикл

    fun start(userId: String, deviceId: String, userAgent: String) {
        api.setSession(userId, deviceId, userAgent)
        started = true
        if (loaded) { render(); return }
        load()
    }

    fun stop() {
        started = false
        generation++
        hideKeyboard()
    }

    fun destroy() { stop() }

    private fun hideKeyboard() {
        val imm = activity.getSystemService(InputMethodManager::class.java)
        imm?.hideSoftInputFromWindow(host.windowToken, 0)
        activity.currentFocus?.clearFocus()
    }

    // ------------------------------------------------------------------ загрузка

    private fun load() {
        val gen = ++generation
        showMessage("Загрузка…", retry = false)
        api.io.execute {
            var ok = false
            var auth = false
            val newBio = LinkedHashMap<String, String>()
            val newSec = LinkedHashMap<String, Int>()
            val newSnd = LinkedHashMap<String, Int>()
            var avatar = ""; var pBg = ""; var fBg = ""; var hand = true
            try {
                when (api.ensureUserId()) {
                    1 -> auth = true
                    2 -> {}
                    else -> {
                        val id = api.userId
                        val rBio = api.get("/api/users/bio/$id")
                        val rSeq = api.get("/api/users/seq/$id")
                        val rCust = api.get("/api/users/cust/$id")
                        if (rBio.code == 401 || rSeq.code == 401 || rCust.code == 401) auth = true
                        else if (rBio.code == 200 && rSeq.code == 200 && rCust.code == 200) {
                            val b = JSONObject(rBio.body)
                            BIO_KEYS.forEach { k -> newBio[k] = api.str(b, k) }
                            avatar = api.str(b, "profilePicture")
                            pBg = api.str(b, "profileBackground")
                            val s = JSONObject(rSeq.body)
                            SEC_KEYS.forEach { k -> newSec[k] = s.optInt(k, 0) }
                            val c = JSONObject(rCust.body)
                            SND_KEYS.forEach { k -> newSnd[k] = c.optInt(k, 1) }
                            fBg = api.str(c, "customBackground")
                            // Подсказки маскота берём из bootstrap; их отсутствие не мешает показу страницы.
                            try {
                                val rb = api.get("/api/session/bootstrap")
                                if (rb.code == 200) {
                                    hand = JSONObject(rb.body).optJSONObject("mascot")?.optBoolean("handHolding", true) ?: true
                                }
                            } catch (e: Exception) { Log.w(TAG, "bootstrap", e) }
                            ok = true
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Не удалось загрузить настройки", e)
            }
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed || gen != generation || !started) return@runOnUiThread
                when {
                    auth -> onAuthLost()
                    ok -> {
                        loaded = true
                        bio.clear(); bio.putAll(newBio)
                        sec.clear(); sec.putAll(newSec)
                        snd.clear(); snd.putAll(newSnd)
                        avatarPath = avatar; profileBgPath = pBg; feedBgPath = fBg
                        handHoldingServer = hand
                        bio["dateOfBirth"] = dobToDisplay(bio["dateOfBirth"].orEmpty())
                        render()
                    }
                    else -> showMessage("Не удалось загрузить настройки.", retry = true)
                }
            }
        }
    }

    private fun showMessage(text: String, retry: Boolean) {
        content.removeAllViews()
        saveButton = null
        val t = UiUtil.text(activity, 16f, UiUtil.TEXT).apply {
            this.text = text
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(48), dp(24), dp(16))
        }
        content.addView(t, UiUtil.lp(UiUtil.MATCH, UiUtil.WRAP))
        if (retry) content.addView(button("Повторить") { load() }, UiUtil.lp(UiUtil.MATCH, dp(52)).apply {
            setMargins(dp(24), dp(8), dp(24), 0)
        })
    }

    // ------------------------------------------------------------------ отрисовка

    private fun render() {
        hideKeyboard()
        content.removeAllViews()
        content.addView(tabBar(), UiUtil.lp(UiUtil.MATCH, UiUtil.WRAP))
        val bubble = bubble()
        when (tab) {
            Tab.PROFILE -> fillProfile(bubble)
            Tab.SECURITY -> fillSecurity(bubble)
            Tab.CUSTOM -> fillCustom(bubble)
        }
        content.addView(bubble, UiUtil.lp(UiUtil.MATCH, UiUtil.WRAP).apply { setMargins(dp(14), dp(16), dp(14), 0) })
        val save = button("Сохранить") { save() }
        saveButton = save
        content.addView(save, UiUtil.lp(UiUtil.MATCH, dp(56)).apply { setMargins(dp(14), dp(16), dp(14), 0) })
        scroll.scrollTo(0, 0)
    }

    private fun tabBar(): View {
        val row = LinearLayout(activity)
        row.orientation = LinearLayout.HORIZONTAL
        row.setBackgroundColor(TAB_BLUE)
        row.setPadding(dp(10), dp(10), dp(10), dp(10))
        listOf(Tab.PROFILE to "Профиль", Tab.SECURITY to "Безопасность", Tab.CUSTOM to "Кастомизация").forEach { (t, title) ->
            val active = t == tab
            val tv = UiUtil.text(activity, 14f, UiUtil.TEXT, bold = active, maxLines = 1).apply {
                text = title
                gravity = Gravity.CENTER
                background = UiUtil.rect(if (active) TAB_ACTIVE else UiUtil.WHITE, if (active) UiUtil.BORDER else 0, if (active) 2 else 0, 6, activity)
                setPadding(dp(4), dp(14), dp(4), dp(14))
                setOnClickListener { if (tab != t) { tab = t; render() } }
            }
            row.addView(tv, UiUtil.lp(0, UiUtil.WRAP, 1f).apply { setMargins(dp(4), 0, dp(4), 0) })
        }
        return row
    }

    private fun bubble(): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        background = UiUtil.rect(UiUtil.WHITE, LINE, 3, 18, activity)
        setPadding(dp(16), dp(14), dp(16), dp(18))
    }

    private fun title(text: String): TextView = UiUtil.text(activity, 26f, LINE, bold = true).apply {
        this.text = text
        gravity = Gravity.CENTER
    }

    private fun caption(text: String): TextView = UiUtil.text(activity, 13f, UiUtil.SUB).apply {
        this.text = text
        setPadding(dp(2), dp(12), 0, dp(4))
    }

    private fun LinearLayout.add(v: View, top: Int = 0) {
        addView(v, UiUtil.lp(UiUtil.MATCH, UiUtil.WRAP).apply { topMargin = dp(top) })
    }

    // ---- вкладка «Профиль»

    private fun fillProfile(b: LinearLayout) {
        b.add(title("Профиль"))
        val cells = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        cells.addView(imageCell(Img.AVATAR, "Аватар"), UiUtil.lp(dp(120), dp(120)))
        cells.addView(imageCell(Img.PROFILE_BG, "Фон профиля"), UiUtil.lp(0, dp(120), 1f).apply { leftMargin = dp(12) })
        b.add(cells, 14)

        b.add(caption("Имя")); b.add(input(bio["username"].orEmpty(), false) { bio["username"] = it })
        b.add(caption("Статус")); b.add(input(bio["status"].orEmpty(), false) { bio["status"] = it })
        b.add(caption("Описание")); b.add(input(bio["description"].orEmpty(), true) { bio["description"] = it })
        b.add(caption("Дата рождения"))
        b.add(dateInput(bio["dateOfBirth"].orEmpty()) { bio["dateOfBirth"] = it })
        b.add(caption("Родной город")); b.add(input(bio["homeCountry"].orEmpty(), false) { bio["homeCountry"] = it })
        b.add(caption("Образование")); b.add(input(bio["education"].orEmpty(), false) { bio["education"] = it })
        b.add(caption("Ваша работа")); b.add(input(bio["workplace"].orEmpty(), false) { bio["workplace"] = it })
        b.add(caption("Хобби")); b.add(input(bio["hobbies"].orEmpty(), true) { bio["hobbies"] = it })
        b.add(caption("Фандомы")); b.add(input(bio["fandoms"].orEmpty(), true) { bio["fandoms"] = it })
        b.add(caption("Религия")); b.add(input(bio["religion"].orEmpty(), false) { bio["religion"] = it })
    }

    // ---- вкладка «Безопасность»

    private fun fillSecurity(b: LinearLayout) {
        b.add(title("Безопасность"))
        b.add(toggle("Показывать мои публикации в ленте:", sec["showPostsFeed"] == 1) { sec["showPostsFeed"] = if (it) 1 else 0 }, 12)
        b.add(toggle("Показывать контент 18+:", sec["showNSFW"] == 1) { sec["showNSFW"] = if (it) 1 else 0 }, 4)
        fun dd(label: String, key: String, options: List<String> = VISIBILITY) {
            b.add(caption(label))
            b.add(dropdown(label.trimEnd(':'), options, sec[key] ?: 0) { sec[key] = it })
        }
        dd("Кто может видеть мои публикации:", "showPostsProfile")
        dd("Кто может видеть информацию обо мне:", "showInfoProfile")
        dd("Кто может видеть моих друзей:", "showFriendsList")
        dd("Кто может видеть мои порты:", "showCommunitiesList")
        dd("Кто может видеть мою музыку:", "showAudios")
        dd("Кто может мне писать:", "allowMessagesFrom", WRITE)
        b.add(toggle("Анонимный профиль:", sec["anonymousPage"] == 1) { sec["anonymousPage"] = if (it) 1 else 0 }, 16)
    }

    // ---- вкладка «Кастомизация»

    private fun fillCustom(b: LinearLayout) {
        b.add(title("Кастомизация"))
        b.add(imageCell(Img.FEED_BG, "Фон ленты").also { it.minimumHeight = dp(110) }, 14)
        // Ячейка растягивается на всю ширину; высота — задана в imageCell.
        val sounds = listOf(
            "notificationSound" to "Звук уведомлений:", "chatMessageSound" to "Звук сообщений:",
            "postSentSound" to "Звук отправки публикации:", "friendOnlineSound" to "Звук при онлайне друзей:",
            "friendRequestSound" to "Звук заявки в друзья:"
        )
        sounds.forEachIndexed { i, (key, label) ->
            b.add(toggle(label, snd[key] == 1) { snd[key] = if (it) 1 else 0 }, if (i == 0) 12 else 4)
        }

        // разделитель между блоками
        b.addView(View(activity).apply { setBackgroundColor(LINE) }, UiUtil.lp(UiUtil.MATCH, dp(2)).apply { setMargins(0, dp(18), 0, dp(14)) })

        // --- блок «Маскот» (настройки клиентские: хранятся на устройстве)
        val head = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(View(activity), UiUtil.lp(dp(36), dp(1)))
        head.addView(title("Маскот").apply { textSize = 24f }, UiUtil.lp(0, UiUtil.WRAP, 1f))
        head.addView(infoButton(), UiUtil.lp(dp(36), dp(36)))
        b.add(head)

        val dependent = ArrayList<View>()
        fun applyEnabled(on: Boolean) = dependent.forEach { it.isEnabled = on; it.alpha = if (on) 1f else 0.4f; setChildrenEnabled(it, on) }

        b.add(toggle("Включить маскот:", mascotEnabled()) { prefs.edit().putBoolean("mascot_enabled", it).apply(); applyEnabled(it) }, 12)
        val hints = toggle("Подсказки о страницах:", mascotHints()) { prefs.edit().putBoolean("mascot_hints", it).apply() }
        dependent.add(hints); b.add(hints, 4)

        val pick = caption("Сменить маскота:")
        dependent.add(pick); b.add(pick)
        var chosen = mascotSelected()
        val names = mascotNames()
        val dd = dropdown("Сменить маскота", names, names.indexOf(chosen).coerceAtLeast(0), radio = true) { chosen = names[it] }
        dependent.add(dd); b.add(dd)

        val actions = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        val del = button("Удалить") {
            if (chosen == DEFAULT_MASCOT) toast("Основного маскота удалить нельзя", false)
            else toast("Удаление пользовательских маскотов появится вместе с импортом", false)
        }.apply { background = UiUtil.rect(0xFFFF8A80.toInt(), UiUtil.BORDER, 2, 12, activity); textSize = 16f }
        val applyBtn = button("Применить") {
            prefs.edit().putString("mascot_selected", chosen).apply()
            toast("Маскот выбран: $chosen", true)
        }.apply { textSize = 16f }
        actions.addView(del, UiUtil.lp(0, dp(48), 1f))
        actions.addView(applyBtn, UiUtil.lp(0, dp(48), 1f).apply { leftMargin = dp(10) })
        dependent.add(actions); b.add(actions, 10)

        val importBtn = button("Импортировать") { toast("Импорт маскота пока недоступен", false) }.apply { textSize = 16f }
        dependent.add(importBtn); b.add(importBtn, 10)

        b.add(UiUtil.text(activity, 13f, UiUtil.TEXT).apply {
            text = "Внимание! Кастомного маскота видно только вам, это фича на уровне клиента!"
            gravity = Gravity.CENTER
        }, 18)
        applyEnabled(mascotEnabled())
    }

    private fun setChildrenEnabled(v: View, on: Boolean) {
        if (v is ViewGroup) for (i in 0 until v.childCount) { v.getChildAt(i).isEnabled = on; setChildrenEnabled(v.getChildAt(i), on) }
    }

    private fun mascotEnabled() = prefs.getBoolean("mascot_enabled", true)
    private fun mascotHints() = prefs.getBoolean("mascot_hints", handHoldingServer)
    private fun mascotSelected() = prefs.getString("mascot_selected", DEFAULT_MASCOT) ?: DEFAULT_MASCOT
    private fun mascotNames(): List<String> = listOf(DEFAULT_MASCOT) // пользовательские появятся вместе с импортом

    private fun infoButton(): View {
        val icon = try { activity.assets.open("ui/icons/info.png").use { android.graphics.BitmapFactory.decodeStream(it) } } catch (e: Exception) { null }
        val v: View = if (icon != null) ImageView(activity).apply { setImageBitmap(icon); scaleType = ImageView.ScaleType.FIT_CENTER }
        else UiUtil.text(activity, 18f, LINE, bold = true).apply {
            text = "i"; gravity = Gravity.CENTER
            background = UiUtil.rect(UiUtil.WHITE, LINE, 2, 18, activity)
        }
        v.setOnClickListener {
            AlertDialog.Builder(activity)
                .setTitle("Маскот")
                .setMessage("Маскот рассказывает о новостях сайта и подсказывает, как пользоваться страницами. " +
                    "Если выключить его, подсказки и выбор маскота станут недоступны.")
                .setPositiveButton("Понятно", null)
                .show()
        }
        return v
    }

    // ------------------------------------------------------------------ элементы формы

    private fun input(value: String, multiline: Boolean, onChange: (String) -> Unit): EditText = EditText(activity).apply {
        setText(value)
        textSize = 16f
        setTextColor(UiUtil.TEXT)
        background = UiUtil.rect(UiUtil.WHITE, LINE, 2, 10, activity)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        if (multiline) {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 3
            gravity = Gravity.TOP or Gravity.START
        } else {
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
        }
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { onChange(s?.toString().orEmpty()) }
        })
    }

    /** Дата рождения вводится цифрами: точки подставляются сами (дд.мм.гггг). */
    private fun dateInput(value: String, onChange: (String) -> Unit): EditText {
        val et = input(value, false, onChange)
        et.inputType = InputType.TYPE_CLASS_NUMBER
        et.hint = "дд.мм.гггг"
        et.addTextChangedListener(object : TextWatcher {
            var busy = false
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (busy || s == null) return
                busy = true
                val digits = s.filter { it.isDigit() }.take(8)
                val sb = StringBuilder()
                digits.forEachIndexed { i, ch -> if (i == 2 || i == 4) sb.append('.'); sb.append(ch) }
                val f = sb.toString()
                if (f != s.toString()) s.replace(0, s.length, f)
                busy = false
                onChange(f)
            }
        })
        return et
    }

    private fun toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit): LinearLayout {
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val tv = UiUtil.text(activity, 15f, UiUtil.TEXT).apply { text = label }
        val cb = CheckBox(activity).apply {
            isChecked = checked
            buttonTintList = ColorStateList.valueOf(LINE)
            setOnCheckedChangeListener { _, v -> onChange(v) }
        }
        row.addView(tv, UiUtil.lp(0, UiUtil.WRAP, 1f))
        row.addView(cb, UiUtil.lp(UiUtil.WRAP, UiUtil.WRAP))
        row.setOnClickListener { if (row.isEnabled) cb.toggle() }
        return row
    }

    /** Выпадающий список (радио-выбор в диалоге). */
    private fun dropdown(
        dialogTitle: String, options: List<String>, current: Int, radio: Boolean = true, onPick: (Int) -> Unit
    ): TextView {
        var cur = current
        val tv = UiUtil.text(activity, 16f, UiUtil.TEXT)
        fun refresh() { tv.text = (options.getOrNull(cur) ?: "Вариант $cur") + "  ▾" }
        tv.background = UiUtil.rect(UiUtil.WHITE, LINE, 2, 10, activity)
        tv.setPadding(dp(12), dp(12), dp(12), dp(12))
        refresh()
        tv.setOnClickListener {
            if (!tv.isEnabled) return@setOnClickListener
            AlertDialog.Builder(activity)
                .setTitle(dialogTitle)
                .setSingleChoiceItems(options.toTypedArray(), cur) { dlg, which ->
                    cur = which; refresh(); onPick(which); dlg.dismiss()
                }
                .setNegativeButton("Отмена", null)
                .show()
        }
        return tv
    }

    private fun button(text: String, onClick: () -> Unit): TextView = UiUtil.text(activity, 20f, UiUtil.WHITE, bold = true).apply {
        this.text = text
        gravity = Gravity.CENTER
        background = UiUtil.rect(SAVE_TEAL, LINE, 2, 14, activity)
        setOnClickListener { if (isEnabled) onClick() }
    }

    private fun imageCell(kind: Img, label: String): FrameLayout {
        val cell = FrameLayout(activity)
        cell.background = UiUtil.rect(0xFFEEEEEE.toInt(), LINE, 2, 10, activity)
        cell.clipToOutline = true
        cell.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) { outline.setRoundRect(0, 0, view.width, view.height, dp(10).toFloat()) }
        }
        val iv = ImageView(activity).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        cell.addView(iv, FrameLayout.LayoutParams(UiUtil.MATCH, UiUtil.MATCH))
        val tag = UiUtil.text(activity, 12f, UiUtil.WHITE).apply {
            text = label
            gravity = Gravity.CENTER
            setBackgroundColor(0x99000000.toInt())
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        cell.addView(tag, FrameLayout.LayoutParams(UiUtil.MATCH, UiUtil.WRAP, Gravity.BOTTOM))

        val pending = pendingImg[kind]
        val path = when (kind) { Img.AVATAR -> avatarPath; Img.PROFILE_BG -> profileBgPath; Img.FEED_BG -> feedBgPath }
        when {
            pending != null -> iv.setImageURI(pending)
            kind == Img.AVATAR -> api.loadAvatar(iv, path)
            path.isBlank() -> iv.setImageBitmap(api.defaultBackgroundBitmap()) // не менялся — стандартный
            else -> api.loadImage(iv, path, 800) { ok -> if (!ok) iv.setImageBitmap(api.defaultBackgroundBitmap()) }
        }
        cell.setOnClickListener {
            pickImage { uri ->
                if (activity.isFinishing || activity.isDestroyed) return@pickImage
                pendingImg[kind] = uri
                iv.setImageURI(uri)
            }
        }
        if (kind == Img.FEED_BG) cell.layoutParams = LinearLayout.LayoutParams(UiUtil.MATCH, dp(110))
        return cell
    }

    // ------------------------------------------------------------------ сохранение

    private fun save() {
        if (saving || !loaded) return
        val me = api.userId
        val currentTab = tab
        var bioJson: JSONObject? = null
        when (currentTab) {
            Tab.PROFILE -> {
                if (bio["username"].orEmpty().isBlank()) { toast("Имя не может быть пустым", false); return }
                val dob = dobToServer(bio["dateOfBirth"].orEmpty())
                if (dob == null) { toast("Проверьте дату рождения (дд.мм.гггг)", false); return }
                bioJson = JSONObject().also { j ->
                    BIO_KEYS.forEach { k -> j.put(k, if (k == "dateOfBirth") dob else bio[k].orEmpty()) }
                }
            }
            else -> {}
        }
        val secJson = if (currentTab == Tab.SECURITY) JSONObject().also { j -> SEC_KEYS.forEach { k -> j.put(k, sec[k] ?: 0) } } else null
        val custJson = if (currentTab == Tab.CUSTOM) JSONObject().also { j -> SND_KEYS.forEach { k -> j.put(k, snd[k] ?: 1) } } else null
        val uploads: List<Pair<Img, Ep>> = when (currentTab) {
            Tab.PROFILE -> listOf(Img.AVATAR to Endpoints.avatar, Img.PROFILE_BG to Endpoints.profileBg)
            Tab.CUSTOM -> listOf(Img.FEED_BG to Endpoints.feedBg)
            Tab.SECURITY -> emptyList()
        }.filter { pendingImg.containsKey(it.first) }
        val pendingSnapshot = HashMap(pendingImg)

        saving = true
        saveButton?.apply { isEnabled = false; text = "Сохранение…"; alpha = 0.7f }
        val gen = generation
        api.io.execute {
            var err: String? = null
            var auth = false
            var freshBio: JSONObject? = null
            var freshCust: JSONObject? = null
            try {
                for ((kind, ep) in uploads) {
                    val r = upload(ep, pendingSnapshot[kind]!!)
                    if (r.first == 401) { auth = true; break }
                    if (r.first !in 200..299) { err = describe(r.first, r.second, "Не удалось загрузить картинку"); break }
                }
                if (err == null && !auth) {
                    val (ep, json) = when (currentTab) {
                        Tab.PROFILE -> Endpoints.bio(me) to bioJson!!
                        Tab.SECURITY -> Endpoints.seq(me) to secJson!!
                        Tab.CUSTOM -> Endpoints.cust(me) to custJson!!
                    }
                    val r = api.send(ep.method, ep.path, json.toString())
                    if (r.code == 401) auth = true
                    else if (r.code !in 200..299) err = describe(r.code, r.body, "Не удалось сохранить")
                }
                if (err == null && !auth) {
                    // Пути картинок после загрузки берём с сервера (ошибка здесь не критична).
                    try {
                        if (currentTab == Tab.PROFILE) api.get("/api/users/bio/$me").takeIf { it.code == 200 }?.let { freshBio = JSONObject(it.body) }
                        if (currentTab == Tab.CUSTOM) api.get("/api/users/cust/$me").takeIf { it.code == 200 }?.let { freshCust = JSONObject(it.body) }
                    } catch (e: Exception) { Log.w(TAG, "refresh after save", e) }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Сохранение настроек", e)
                err = "Нет связи с сервером"
            }
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                saving = false
                saveButton?.apply { isEnabled = true; text = "Сохранить"; alpha = 1f }
                if (gen != generation || !started) return@runOnUiThread
                when {
                    auth -> onAuthLost()
                    err != null -> toast(err!!, false)
                    else -> {
                        // В тестовом режиме файл не сохраняется — оставляем выбранную картинку на экране.
                        if (!api.isDemo) uploads.forEach { pendingImg.remove(it.first) }
                        freshBio?.let { avatarPath = api.str(it, "profilePicture"); profileBgPath = api.str(it, "profileBackground") }
                        freshCust?.let { feedBgPath = api.str(it, "customBackground") }
                        toast("Настройки сохранены", true)
                        if (uploads.isNotEmpty() && tab == currentTab) render()
                    }
                }
            }
        }
    }

    private fun describe(code: Int, body: String, base: String): String {
        val msg = try { JSONObject(body).let { it.optString("error", it.optString("message", "")) } } catch (e: Exception) { "" }
        return if (msg.isNotBlank()) "$base: $msg" else "$base (код $code)"
    }

    /** multipart-загрузка; (код, тело). Вызывать из io-потока. */
    private fun upload(ep: Ep, uri: Uri): Pair<Int, String> {
        val cr = activity.contentResolver
        val bytes = cr.openInputStream(uri)?.use { it.readBytes() } ?: return 0 to ""
        if (bytes.size > MAX_UPLOAD) return 413 to "{\"error\":\"Файл слишком большой\"}"
        val mime = cr.getType(uri) ?: "image/jpeg"
        val ext = when (mime) { "image/png" -> "png"; "image/gif" -> "gif"; "image/webp" -> "webp"; else -> "jpg" }
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("userId", api.userId)
            .addFormDataPart(Endpoints.FILE_FIELD, "upload.$ext", bytes.toRequestBody(mime.toMediaType()))
            .build()
        val r = api.sendMultipart(ep.method, ep.path, body)
        return r.code to r.body
    }

    // ------------------------------------------------------------------ даты

    /** Сервер → «дд.мм.гггг» (понимает ISO «гггг-мм-дд…» и уже готовый «дд.мм.гггг»). */
    private fun dobToDisplay(raw: String): String {
        val s = raw.trim()
        Regex("^(\\d{4})-(\\d{2})-(\\d{2})").find(s)?.let { return "${it.groupValues[3]}.${it.groupValues[2]}.${it.groupValues[1]}" }
        return s
    }

    /** «дд.мм.гггг» → «гггг-мм-дд»; "" — очистить; null — дата некорректна. */
    private fun dobToServer(display: String): String? {
        val s = display.trim()
        if (s.isEmpty()) return ""
        val m = Regex("^(\\d{2})\\.(\\d{2})\\.(\\d{4})$").find(s) ?: return null
        val day = m.groupValues[1].toInt(); val month = m.groupValues[2].toInt(); val year = m.groupValues[3].toInt()
        val now = Calendar.getInstance()
        if (year < 1900 || year > now.get(Calendar.YEAR) || month !in 1..12) return null
        val cal = Calendar.getInstance().apply { clear(); set(year, month - 1, 1) }
        if (day < 1 || day > cal.getActualMaximum(Calendar.DAY_OF_MONTH)) return null
        cal.set(year, month - 1, day)
        if (cal.after(now)) return null
        return "%04d-%02d-%02d".format(year, month, day)
    }

    // ------------------------------------------------------------------ плашка

    /** Плашка с результатом: страница не закрывается и пользователя никуда не переносит. */
    private fun toast(text: String, good: Boolean) {
        hideTask?.let { pill.removeCallbacks(it) }
        pill.animate().cancel()
        pill.text = text
        pill.background = UiUtil.rect(if (good) 0xFF1B8F5A.toInt() else 0xFFC0392B.toInt(), LINE, 2, 22, activity)
        pill.alpha = 0f
        pill.visibility = View.VISIBLE
        pill.animate().alpha(1f).setDuration(150).start()
        val task = Runnable {
            pill.animate().alpha(0f).setDuration(250).withEndAction { pill.visibility = View.GONE }.start()
        }
        hideTask = task
        pill.postDelayed(task, 2600)
    }
}
