package ru.fortport.client

import android.content.Context
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
 * Запись вкладки «Профиль» — по логу сайта: POST /api/users/update/bio, multipart: profileBackground и profilePicture
 * (файл или пустое поле, если не менялись), username, description, status, date_of_birth («гггг-мм-дд»), home_country,
 * education, workplace, hobbies, fandoms, religion → {success, updated[], profilePicture, profileBackground}.
 * Запись «Безопасности» и «Кастомизации» в логах пока не снималась — адреса и методы собраны в [Ep] и НЕ ПРОВЕРЕНЫ.
 *
 * Значок профиля: кнопка между картинками и именем открывает окно выбора ([Badges]).
 */
class SettingsController(
    private val activity: AppCompatActivity,
    private val api: SiteApi,
    private val host: FrameLayout,
    /** Выбор картинки с устройства; результат приходит в переданный колбэк. */
    private val pickImage: ((Uri) -> Unit) -> Unit,
    /** Выбор zip-файла (скин маскота); тип проверяется по содержимому. */
    private val pickZip: ((Uri) -> Unit) -> Unit,
    private val onAuthLost: () -> Unit
) {
    private companion object {
        const val TAG = "Settings"
        const val MAX_UPLOAD = 20L * 1024 * 1024
        const val DEFAULT_MASCOT = MascotSkins.DEFAULT
        const val TOPBAR_DP = 70
        val SEC_KEYS = listOf(
            "showPostsFeed", "showPostsProfile", "showInfoProfile", "anonymousPage", "showFriendsList",
            "showCommunitiesList", "showAudios", "showPhotos", "allowMessagesFrom", "showNSFW"
        )
        val SND_KEYS = listOf("notificationSound", "chatMessageSound", "postSentSound", "friendOnlineSound", "friendRequestSound")
        /** Поля формы «Профиль» на проводе (snake_case, порядок как в логе сайта). */
        val BIO_WIRE = listOf(
            "username" to "username", "description" to "description", "status" to "status", "dateOfBirth" to "date_of_birth",
            "homeCountry" to "home_country", "education" to "education", "workplace" to "workplace",
            "hobbies" to "hobbies", "fandoms" to "fandoms", "religion" to "religion"
        )
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
        fun seq(id: String) = Ep("PUT", "/api/users/seq/$id")
        fun cust(id: String) = Ep("PUT", "/api/users/cust/$id")
        val feedBg = Ep("POST", "/api/users/custom-background")
        const val FILE_FIELD = "file"
    }

    private enum class Tab { PROFILE, SECURITY, CUSTOM, ABOUT }
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
    /** Надетый значок (GET /api/users/badges); null — без значка. */
    private var badge: BadgeItem? = null
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
            var eqBadge: BadgeItem? = null
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
                            // Надетый значок для кнопки в «Профиле»; ошибка здесь не мешает показу страницы.
                            try {
                                val rbd = api.get("/api/users/badges")
                                if (rbd.code == 200) {
                                    val a = JSONObject(rbd.body).optJSONArray("badges")
                                    if (a != null) for (i in 0 until a.length()) {
                                        val o = a.optJSONObject(i) ?: continue
                                        if (o.optBoolean("equipped")) {
                                            eqBadge = BadgeItem(o.optLong("id"), api.str(o, "name"), api.str(o, "imagePath"), api.str(o, "flavourText"), 0, true, true)
                                        }
                                    }
                                }
                            } catch (e: Exception) { Log.w(TAG, "badges", e) }
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
                        badge = eqBadge
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
        val t = UiUtil.text(activity, 16f, AeroUi.labelColor(activity)).apply {
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
            Tab.ABOUT -> fillAbout(bubble)
        }
        content.addView(bubble, UiUtil.lp(UiUtil.MATCH, UiUtil.WRAP).apply { setMargins(dp(14), dp(16), dp(14), 0) })
        if (tab == Tab.ABOUT) { saveButton = null; content.addView(View(activity), UiUtil.lp(UiUtil.MATCH, dp(24))); scroll.scrollTo(0, 0); return }
        val save = button("Сохранить") { save() }
        saveButton = save
        content.addView(save, UiUtil.lp(UiUtil.MATCH, dp(56)).apply { setMargins(dp(14), dp(16), dp(14), 0) })
        scroll.scrollTo(0, 0)
    }

    private fun tabBar(): View {
        // Вкладки Aero: активная — стеклянная кнопка, остальные — «поля».
        val row = LinearLayout(activity)
        row.orientation = LinearLayout.HORIZONTAL
        row.setPadding(dp(10), dp(12), dp(10), 0)
        // Четыре вкладки не помещаются в ряд: карусель с горизонтальной прокруткой (активная вкладка остаётся в поле зрения).
        val hs = android.widget.HorizontalScrollView(activity).apply { isHorizontalScrollBarEnabled = false; addView(row) }
        listOf(Tab.PROFILE to "Профиль", Tab.SECURITY to "Безопасность", Tab.CUSTOM to "Кастомизация", Tab.ABOUT to "О проекте").forEach { (t, title) ->
            val active = t == tab
            val tv = UiUtil.text(activity, 14f, if (active) AeroButton.textColor(activity) else AeroUi.labelColor(activity), bold = active, maxLines = 1).apply {
                text = title
                gravity = Gravity.CENTER
                if (active) AeroButton.style(this) else background = AeroUi.fieldBackground(activity)
                setPadding(dp(4), dp(12), dp(4), dp(12))
                setOnClickListener { if (tab != t) { tab = t; render() } }
            }
            tv.setPadding(dp(16), dp(12), dp(16), dp(12))
            row.addView(tv, UiUtil.lp(UiUtil.WRAP, UiUtil.WRAP).apply { setMargins(dp(4), 0, dp(4), 0) })
            if (active) hs.post { hs.scrollTo((tv.left - dp(24)).coerceAtLeast(0), 0) }
        }
        return hs
    }

    // ---- вкладка «О проекте»

    private fun assetBitmap(path: String): android.graphics.Bitmap? = try {
        activity.assets.open(path).use { android.graphics.BitmapFactory.decodeStream(it) }
    } catch (e: Exception) { null }

    /** Плашка «иконка + название»; иконка берётся из assets/ui/icons/[icon], без файла — [fallback]-эмодзи. */
    private fun linkPlate(icon: String, fallback: String, label: String, url: String): View = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = AeroUi.fieldBackground(activity)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        isClickable = true; isFocusable = true
        val bmp = assetBitmap("ui/icons/$icon")
        if (bmp != null) addView(ImageView(activity).apply { setImageBitmap(bmp) }, UiUtil.lp(dp(32), dp(32)))
        else addView(UiUtil.text(activity, 24f, AeroUi.titleColor(activity)).apply { text = fallback; gravity = Gravity.CENTER }, UiUtil.lp(dp(32), dp(32)))
        addView(UiUtil.text(activity, 17f, AeroUi.titleColor(activity), bold = true, maxLines = 1).apply { text = label },
            UiUtil.lp(0, UiUtil.WRAP, 1f).apply { leftMargin = dp(12) })
        setOnClickListener {
            val uri = android.net.Uri.parse(url)
            try {
                // Страницы fortport.ru открываются нативно (MainActivity разбирает ссылку), остальное — во внешнем приложении.
                if (SiteLinks.isSite(uri)) activity.startActivity(
                    android.content.Intent(android.content.Intent.ACTION_VIEW, uri, activity, MainActivity::class.java)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
                ) else activity.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, uri))
            } catch (e: Exception) { toast("Не удалось открыть ссылку", false) }
        }
    }

    private fun fillAbout(b: LinearLayout) {
        val splash = assetBitmap("styles/" + if (UiUtil.isNight(activity)) "shadowxr_darktheme.png" else "shadowxr_daytheme.png")
        b.add(title("О проекте"))
        b.add(UiUtil.text(activity, 15f, AeroUi.labelColor(activity)).apply {
            text = "Данный проект не является официальным мобильным клиентом для fortport, и создан только в экспериментальных целях. Код приложения распространяется в открытом виде."
        }, 14)
        b.add(UiUtil.text(activity, 15f, AeroUi.labelColor(activity)).apply { text = "Если возникли вопросы, можете связаться ниже." }, 12)
        b.add(linkPlate("project_icon.png", "👤", "FortPort", "https://fortport.ru/profile?id=1785517307920"), 14)
        b.add(linkPlate("youtube_icon.png", "▶", "YouTube", "https://youtube.com/@SHADOW_X-RAY"), 8)
        b.add(linkPlate("github_icon.png", "🐙", "GitHub", "https://github.com/SHADOW-XR-2000/FortPortClient"), 8)
        val ver = try { activity.packageManager.getPackageInfo(activity.packageName, 0).versionName } catch (e: Exception) { null }
        b.add(UiUtil.text(activity, 13f, AeroUi.labelColor(activity)).apply {
            text = (if (ver != null) "Версия $ver\n" else "") + "2026"; gravity = Gravity.CENTER; alpha = 0.8f
        }, 16)
        if (splash != null) b.add(ImageView(activity).apply {
            setImageBitmap(splash); adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_CENTER
        }, 16)
    }

    private fun bubble(): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        background = AeroUi.panelBackground(activity)
        setPadding(dp(16), dp(14), dp(16), dp(18))
    }

    private fun title(text: String): TextView = UiUtil.text(activity, 26f, AeroUi.titleColor(activity), bold = true).apply {
        this.text = text
        gravity = Gravity.CENTER
    }

    private fun caption(text: String): TextView = UiUtil.text(activity, 13f, AeroUi.labelColor(activity)).apply {
        this.text = text
        alpha = 0.85f
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

        // Значок: кнопка по ширине как поле имени, открывает окно со значками.
        b.add(badgeButton(), 12)

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
        b.addView(AeroUi.separator(activity), UiUtil.lp(UiUtil.MATCH, dp(1)).apply { setMargins(0, dp(18), 0, dp(14)) })

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
            else AlertDialog.Builder(activity)
                .setTitle("Удалить маскота?")
                .setMessage("«$chosen» будет удалён с этого устройства.")
                .setPositiveButton("Удалить") { _, _ ->
                    val gone = chosen
                    if (MascotSkins.delete(activity, gone)) {
                        if (mascotSelected() == gone) prefs.edit().putString("mascot_selected", DEFAULT_MASCOT).apply()
                        toast("Маскот удалён", true)
                    } else toast("Не удалось удалить маскота", false)
                    rerenderKeepScroll()
                }
                .setNegativeButton("Отмена", null)
                .show()
        }.apply { textSize = 16f; setTextColor(if (UiUtil.isNight(activity)) 0xFFFF9A90.toInt() else 0xFFB3261E.toInt()) }
        val applyBtn = button("Применить") {
            prefs.edit().putString("mascot_selected", chosen).apply()
            toast("Маскот выбран: $chosen", true)
        }.apply { textSize = 16f }
        actions.addView(del, UiUtil.lp(0, dp(48), 1f))
        actions.addView(applyBtn, UiUtil.lp(0, dp(48), 1f).apply { leftMargin = dp(10) })
        dependent.add(actions); b.add(actions, 10)

        val importBtn = button("Импортировать") {
            pickZip { uri ->
                if (activity.isFinishing || activity.isDestroyed) return@pickZip
                toast("Импорт…", true)
                Thread {
                    val result: Pair<MascotSkins.Result?, String?> = try { MascotSkins.install(activity, uri) to null }
                    catch (e: MascotSkins.SkinException) { null to (e.message ?: "Неверный архив") }
                    catch (e: Exception) { Log.w(TAG, "mascot import", e); null to "Не удалось прочитать архив (нужен .zip)" }
                    activity.runOnUiThread {
                        if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                        val (res, err) = result
                        if (res != null) {
                            prefs.edit().putString("mascot_selected", res.name).apply() // сразу выбираем импортированного
                            val info = "«${res.name}»: фраз-категорий ${res.categories.size}, видео ${res.videos}" +
                                if (res.unknown.isNotEmpty()) ". Не распознаны: ${res.unknown.joinToString()}" else ""
                            toast(info, res.unknown.isEmpty() && (res.categories.isNotEmpty() || res.videos > 0))
                            rerenderKeepScroll()
                            if (res.skipped.isNotEmpty() || (res.categories.isEmpty() && res.unknown.isEmpty())) {
                                val msg = StringBuilder()
                                if (res.categories.isEmpty() && res.unknown.isEmpty()) msg.append("Файлы фраз не найдены: они должны лежать в папке phrases/ (например phrases/random.json).\n\n")
                                if (res.skipped.isNotEmpty()) msg.append("Пропущены (не на своём месте или неизвестное имя):\n").append(res.skipped.take(12).joinToString("\n"))
                                AlertDialog.Builder(activity).setTitle("Проверьте архив").setMessage(msg.toString().trim()).setPositiveButton("Понятно", null).show()
                            }
                        } else toast(err ?: "Ошибка импорта", false)
                    }
                }.start()
            }
        }.apply { textSize = 16f }
        dependent.add(importBtn); b.add(importBtn, 10)

        b.add(UiUtil.text(activity, 13f, AeroUi.labelColor(activity)).apply {
            text = "Внимание! Кастомного маскота видно только вам, это фича на уровне клиента!"
            gravity = Gravity.CENTER
        }, 18)
        applyEnabled(mascotEnabled())

        // --- блок «Обновления» (GitHub Releases, см. UpdateManager)
        b.addView(AeroUi.separator(activity), UiUtil.lp(UiUtil.MATCH, dp(1)).apply { setMargins(0, dp(18), 0, dp(14)) })
        b.add(title("Обновления").apply { textSize = 24f })
        b.add(UiUtil.text(activity, 14f, AeroUi.labelColor(activity)).apply {
            text = "Установлена версия ${UpdateManager.versionLabel(activity)}"
            gravity = Gravity.CENTER
        }, 8)
        b.add(toggle("Проверять при запуске:", UpdateManager.autoCheckEnabled(activity)) { UpdateManager.setAutoCheck(activity, it) }, 12)
        val checkBtn = button("Проверить обновления") {
            UpdateManager.check(activity, manual = true) { text, good -> toast(text, good) }
        }.apply { textSize = 16f }
        b.add(checkBtn, 10)
    }

    private fun setChildrenEnabled(v: View, on: Boolean) {
        if (v is ViewGroup) for (i in 0 until v.childCount) { v.getChildAt(i).isEnabled = on; setChildrenEnabled(v.getChildAt(i), on) }
    }

    private fun mascotEnabled() = prefs.getBoolean("mascot_enabled", true)
    private fun mascotHints() = prefs.getBoolean("mascot_hints", handHoldingServer)
    private fun mascotSelected() = prefs.getString("mascot_selected", DEFAULT_MASCOT) ?: DEFAULT_MASCOT
    private fun mascotNames(): List<String> = listOf(DEFAULT_MASCOT) + MascotSkins.installed(activity)

    /** Перерисовать вкладку, не теряя положения прокрутки. */
    private fun rerenderKeepScroll() { val y = scroll.scrollY; render(); scroll.post { scroll.scrollTo(0, y) } }

    private fun infoButton(): View {
        val icon = try { activity.assets.open("ui/icons/info.png").use { android.graphics.BitmapFactory.decodeStream(it) } } catch (e: Exception) { null }
        val v: View = if (icon != null) ImageView(activity).apply { setImageBitmap(icon); scaleType = ImageView.ScaleType.FIT_CENTER }
        else UiUtil.text(activity, 18f, AeroButton.textColor(activity), bold = true).apply {
            text = "i"; gravity = Gravity.CENTER
            AeroButton.style(this, 18f)
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

    /** Кнопка значка: иконка надетого значка (или заглушка) и его название; тап открывает окно выбора. */
    private fun badgeButton(): View {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = AeroUi.fieldBackground(activity)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            minimumHeight = dp(48)
            setOnClickListener { showBadgePicker() }
        }
        val icon = ImageView(activity).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        row.addView(icon, LinearLayout.LayoutParams(dp(34), dp(34)))
        val cur = badge
        api.loadImage(icon, if (cur != null) Badges.imageUrl(cur.imagePath) else "/ui/icons/badge_picker.webp", 128)
        row.addView(TextView(activity).apply {
            text = cur?.name ?: "Выбрать значок"
            textSize = 16f
            setTextColor(AeroUi.titleColor(activity))
        }, LinearLayout.LayoutParams(0, UiUtil.WRAP, 1f).apply { marginStart = dp(12) })
        row.addView(TextView(activity).apply { text = "›"; textSize = 22f; setTextColor(AeroUi.labelColor(activity)) })
        return row
    }

    /** Окно «Значки»: «Без значка», купленные (тап — надеть/снять) и каталог (тап — купить за CD). */
    private fun showBadgePicker() {
        val dlg = AeroDialog(activity, "Значки")
        val info = UiUtil.text(activity, 15f, AeroUi.labelColor(activity)).apply {
            text = "Загрузка…"; gravity = Gravity.CENTER; setPadding(dp(8), dp(16), dp(8), dp(16))
        }
        dlg.body.addView(info, UiUtil.lp(UiUtil.MATCH, UiUtil.WRAP))
        dlg.show()

        fun finish(equipped: BadgeItem?) {
            badge = equipped
            dlg.dismiss()
            if (started && tab == Tab.PROFILE) render()
        }
        fun fill(list: List<BadgeItem>) {
            dlg.body.removeAllViews()
            val current = list.firstOrNull { it.equipped }
            fun row(item: BadgeItem?, active: Boolean, click: () -> Unit): View {
                val r = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    background = AeroUi.fieldBackground(activity)
                    setPadding(dp(10), dp(8), dp(12), dp(8))
                    setOnClickListener { click() }
                }
                r.addView(ImageView(activity).apply { setImageDrawable(AeroUi.radioDrawable(activity, active)) }, LinearLayout.LayoutParams(dp(26), dp(26)))
                val ic = ImageView(activity).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
                r.addView(ic, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginStart = dp(10) })
                if (item != null) api.loadImage(ic, Badges.imageUrl(item.imagePath), 128)
                val col = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
                col.addView(UiUtil.text(activity, 16f, AeroUi.titleColor(activity), bold = true).apply { text = item?.name ?: "Без значка" })
                if (item != null && item.flavour.isNotBlank()) col.addView(UiUtil.text(activity, 12f, AeroUi.labelColor(activity)).apply { text = item.flavour; alpha = 0.85f })
                r.addView(col, LinearLayout.LayoutParams(0, UiUtil.WRAP, 1f).apply { marginStart = dp(10) })
                if (item != null && !item.owned) {
                    r.addView(UiUtil.text(activity, 14f, AeroUi.titleColor(activity), bold = true).apply { text = "${item.price} CD" })
                    r.alpha = 0.9f
                }
                return r
            }
            fun add(v: View) = dlg.body.addView(v, UiUtil.lp(UiUtil.MATCH, UiUtil.WRAP).apply { topMargin = dp(8) })
            add(row(null, current == null) {
                if (current == null) { dlg.dismiss(); return@row }
                toggle(dlg, current) { finish(null) }
            })
            list.forEach { b ->
                add(row(b, b.equipped) {
                    if (b.owned) toggle(dlg, b) { eq -> finish(if (eq) b else null) }
                    else AlertDialog.Builder(activity)
                        .setMessage("Купить значок «${b.name}» за ${b.price} CD?")
                        .setNegativeButton("Отмена", null)
                        .setPositiveButton("Купить") { _, _ ->
                            api.io.execute {
                                val r = Badges.buy(api, b.id)
                                val fresh = if (r.ok) Badges.load(api) else null
                                activity.runOnUiThread {
                                    if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                                    when {
                                        r.authLost -> onAuthLost()
                                        !r.ok -> toast(r.error, false)
                                        fresh != null -> { toast("Значок куплен", true); if (dlg.dialog.isShowing) fill(fresh) }
                                    }
                                }
                            }
                        }.show()
                })
            }
        }
        api.io.execute {
            val list = Badges.load(api)
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed || !dlg.dialog.isShowing) return@runOnUiThread
                if (list == null) info.text = "Не удалось загрузить значки" else fill(list)
            }
        }
    }

    /** Надеть/снять значок (сервер переключает); [done] получает итоговое состояние «надет». */
    private fun toggle(dlg: AeroDialog, item: BadgeItem, done: (Boolean) -> Unit) {
        api.io.execute {
            val r = Badges.toggle(api, item.id)
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                when {
                    r.authLost -> onAuthLost()
                    r.ok -> done(r.equipped)
                    else -> toast(r.error, false)
                }
            }
        }
    }

    private fun input(value: String, multiline: Boolean, onChange: (String) -> Unit): EditText = AeroUi.input(EditText(activity)).apply {
        setText(value)
        textSize = 16f
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

    /**
     * Дата рождения «дд.мм.гггг». Раньше любой введённый разделитель стирался, а точки ставились только при вводе
     * следующей цифры, из-за чего дату нельзя было набрать «как привыкли». Теперь:
     *  - клавиатура с цифрами, точкой и дефисом; «-», «/», «,» и пробел превращаются в точку;
     *  - после полного дня или месяца (две цифры) точка ставится сама, лишний разделитель подряд игнорируется;
     *  - при стирании ничего не подставляется, поэтому точки можно удалять;
     *  - при уходе из поля дата приводится к виду «дд.мм.гггг» (7.1.1990 → 07.01.1990, 07011990 → 07.01.1990).
     */
    private fun dateInput(value: String, onChange: (String) -> Unit): View {
        val et = AeroUi.input(EditText(activity)).apply {
            setText(value)
            textSize = 16f
            setPadding(dp(12), dp(10), dp(48), dp(10))
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
            hint = "дд.мм.гггг"
            filters = arrayOf(android.text.InputFilter.LengthFilter(10))
        }
        et.addTextChangedListener(object : TextWatcher {
            var busy = false
            var prevLen = et.text.length
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) { prevLen = s?.length ?: 0 }
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (busy || s == null) return
                busy = true
                try {
                    val grew = s.length > prevLen
                    // любые разделители → точка; посторонние символы убираем
                    val cleaned = s.toString().map { if (it in "-/,. ") '.' else it }.filter { it.isDigit() || it == '.' }
                        .joinToString("").replace(Regex("^\\.+"), "").replace(Regex("\\.{2,}"), ".")
                    var f = cleaned
                    // автоточка после полного дня/месяца — только при наборе в конец
                    if (grew && Regex("^(\\d{2}|\\d{1,2}\\.\\d{2})$").matches(f)) f += "."
                    if (f != s.toString()) { s.replace(0, s.length, f); et.setSelection(et.text.length) }
                } finally { busy = false }
                onChange(et.text.toString())
            }
        })
        et.setOnFocusChangeListener { _, focused ->
            if (!focused) {
                val n = normalizeDob(et.text.toString())
                if (n != et.text.toString()) et.setText(n)
                onChange(et.text.toString())
            }
        }
        // Иконка календаря у правого края поля: окно Aero с выбором дня, месяца и года (без ручного ввода).
        val icon = UiUtil.text(activity, 22f, AeroUi.titleColor(activity)).apply {
            text = "📅"; gravity = Gravity.CENTER
            isClickable = true; isFocusable = false
            setOnClickListener { hideKeyboard(); et.clearFocus(); showDatePicker(et.text.toString()) { et.setText(it) } }
        }
        return FrameLayout(activity).apply {
            addView(et, FrameLayout.LayoutParams(-1, -2))
            addView(icon, FrameLayout.LayoutParams(dp(44), -1, Gravity.END))
        }
    }

    private val MONTHS = listOf("Января", "Февраля", "Марта", "Апреля", "Мая", "Июня", "Июля", "Августа", "Сентября", "Октября", "Ноября", "Декабря")

    /** Окно выбора даты рождения: три выпадающих списка (день, месяц, год) и «ОК». [onPick] получает «дд.мм.гггг». */
    private fun showDatePicker(current: String, onPick: (String) -> Unit) {
        val now = Calendar.getInstance()
        val maxYear = now.get(Calendar.YEAR)
        val m = Regex("^(\\d{2})\\.(\\d{2})\\.(\\d{4})$").find(normalizeDob(current.trim()))
        var year = (m?.groupValues?.get(3)?.toInt() ?: 2000).coerceIn(1900, maxYear)
        var month = ((m?.groupValues?.get(2)?.toInt() ?: 1) - 1).coerceIn(0, 11)
        fun daysIn(): Int = Calendar.getInstance().apply { clear(); set(year, month, 1) }.getActualMaximum(Calendar.DAY_OF_MONTH)
        var day = (m?.groupValues?.get(1)?.toInt() ?: 1).coerceIn(1, daysIn())

        val dlg = AeroDialog(activity, "Дата рождения")
        val years = (maxYear downTo 1900).map { it.toString() }
        lateinit var dayBtn: TextView
        lateinit var monthBtn: TextView
        lateinit var yearBtn: TextView
        fun refresh() {
            if (day > daysIn()) day = daysIn()
            dayBtn.text = "%02d".format(day)
            monthBtn.text = MONTHS[month]
            yearBtn.text = year.toString()
        }
        // Смена месяца или года пересчитывает число дней (например, 31 → 28), refresh() обновляет подписи всех трёх кнопок.
        dayBtn = AeroUi.dropdown(activity, { (1..daysIn()).map { "%02d".format(it) } }, { day - 1 }) { i -> day = i + 1; refresh() }
        monthBtn = AeroUi.dropdown(activity, { MONTHS }, { month }) { i -> month = i; refresh() }
        yearBtn = AeroUi.dropdown(activity, { years }, { maxYear - year }) { i -> year = maxYear - i; refresh() }
        refresh()

        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(AeroUi.dropdownView(dayBtn), UiUtil.lp(0, UiUtil.WRAP, 0.8f))
        row.addView(AeroUi.dropdownView(monthBtn), UiUtil.lp(0, UiUtil.WRAP, 1.5f).apply { leftMargin = dp(6) })
        row.addView(AeroUi.dropdownView(yearBtn), UiUtil.lp(0, UiUtil.WRAP, 1.1f).apply { leftMargin = dp(6) })
        dlg.body.addView(row, UiUtil.lp(UiUtil.MATCH, UiUtil.WRAP).apply { topMargin = dp(4) })
        dlg.footer.addView(dlg.button("ОК") {
            val cal = Calendar.getInstance().apply { clear(); set(year, month, day) }
            if (cal.after(now)) { android.widget.Toast.makeText(activity, "Дата не может быть в будущем", android.widget.Toast.LENGTH_SHORT).show(); return@button }
            onPick("%02d.%02d.%04d".format(day, month + 1, year))
            dlg.dismiss()
        }, UiUtil.lp(UiUtil.MATCH, UiUtil.WRAP))
        dlg.show()
    }

    /** «7.1.1990», «07-01-1990», «07011990» → «07.01.1990»; нераспознанное возвращается как есть. */
    private fun normalizeDob(raw: String): String {
        val s = raw.trim().trimEnd('.')
        Regex("^(\\d{1,2})\\.(\\d{1,2})\\.(\\d{4})$").find(s)?.let {
            return "%02d.%02d.%s".format(it.groupValues[1].toInt(), it.groupValues[2].toInt(), it.groupValues[3])
        }
        Regex("^(\\d{2})(\\d{2})(\\d{4})$").find(s)?.let { return "${it.groupValues[1]}.${it.groupValues[2]}.${it.groupValues[3]}" }
        return raw
    }

    private fun toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit): LinearLayout {
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val tv = UiUtil.text(activity, 15f, AeroUi.labelColor(activity)).apply { text = label }
        val cb = AeroUi.checkBox(activity, "").apply {
            isChecked = checked
            setOnCheckedChangeListener { _, v -> onChange(v) }
        }
        row.addView(tv, UiUtil.lp(0, UiUtil.WRAP, 1f))
        row.addView(cb, UiUtil.lp(UiUtil.WRAP, UiUtil.WRAP))
        row.setOnClickListener { if (row.isEnabled) cb.toggle() }
        return row
    }

    /** Выпадающий список Aero (кнопка со стрелкой и всплывающий список). [radio] оставлен для совместимости вызовов. */
    @Suppress("UNUSED_PARAMETER")
    private fun dropdown(
        dialogTitle: String, options: List<String>, current: Int, radio: Boolean = true, onPick: (Int) -> Unit
    ): View {
        var cur = current
        var ref: TextView? = null
        fun label() = options.getOrNull(cur) ?: "Вариант $cur"
        val btn = AeroUi.dropdown(activity, { options }, { cur }) { i ->
            cur = i
            ref?.text = label()
            onPick(i)
        }
        ref = btn
        btn.text = label()
        btn.textSize = 16f
        return AeroUi.dropdownView(btn)
    }

    private fun button(text: String, onClick: () -> Unit): TextView = UiUtil.text(activity, 20f, AeroButton.textColor(activity), bold = true).apply {
        this.text = text
        gravity = Gravity.CENTER
        AeroButton.style(this)
        setOnClickListener { if (isEnabled) onClick() }
    }

    private fun imageCell(kind: Img, label: String): FrameLayout {
        val cell = FrameLayout(activity)
        cell.background = AeroUi.fieldBackground(activity)
        cell.clipToOutline = true
        cell.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) { outline.setRoundRect(0, 0, view.width, view.height, dp(5).toFloat()) }
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
        var dobServer = ""
        when (currentTab) {
            Tab.PROFILE -> {
                if (bio["username"].orEmpty().isBlank()) { toast("Имя не может быть пустым", false); return }
                val dob = dobToServer(bio["dateOfBirth"].orEmpty())
                if (dob == null) { toast("Проверьте дату рождения (дд.мм.гггг)", false); return }
                dobServer = dob
            }
            else -> {}
        }
        val secJson = if (currentTab == Tab.SECURITY) JSONObject().also { j -> SEC_KEYS.forEach { k -> j.put(k, sec[k] ?: 0) } } else null
        val custJson = if (currentTab == Tab.CUSTOM) JSONObject().also { j -> SND_KEYS.forEach { k -> j.put(k, snd[k] ?: 1) } } else null
        val uploads: List<Pair<Img, Ep>> = when (currentTab) {
            Tab.PROFILE -> emptyList() // аватар и фон уходят в той же форме, что и поля профиля
            Tab.CUSTOM -> listOf(Img.FEED_BG to Endpoints.feedBg)
            Tab.SECURITY, Tab.ABOUT -> emptyList()
        }.filter { pendingImg.containsKey(it.first) }
        val bioImages = if (currentTab == Tab.PROFILE) listOf(Img.AVATAR, Img.PROFILE_BG).filter { pendingImg.containsKey(it) } else emptyList()
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
                    val r = if (currentTab == Tab.PROFILE) saveBioForm(dobServer, pendingSnapshot[Img.AVATAR], pendingSnapshot[Img.PROFILE_BG])
                    else {
                        val (ep, json) = if (currentTab == Tab.SECURITY) Endpoints.seq(me) to secJson!! else Endpoints.cust(me) to custJson!!
                        api.send(ep.method, ep.path, json.toString()).let { it.code to it.body }
                    }
                    if (r.first == 401) auth = true
                    else if (r.first !in 200..299) err = describe(r.first, r.second, "Не удалось сохранить")
                    else if (currentTab == Tab.PROFILE && try { JSONObject(r.second).optBoolean("success", true) } catch (e: Exception) { true } == false) {
                        err = describe(r.first, r.second, "Не удалось сохранить")
                    }
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
                        if (!api.isDemo) { uploads.forEach { pendingImg.remove(it.first) }; bioImages.forEach { pendingImg.remove(it) } }
                        freshBio?.let { avatarPath = api.str(it, "profilePicture"); profileBgPath = api.str(it, "profileBackground") }
                        freshCust?.let { feedBgPath = api.str(it, "customBackground") }
                        toast("Настройки сохранены", true)
                        if ((uploads.isNotEmpty() || bioImages.isNotEmpty()) && tab == currentTab) render()
                    }
                }
            }
        }
    }

    private fun describe(code: Int, body: String, base: String): String {
        val msg = try { JSONObject(body).let { it.optString("error", it.optString("message", "")) } } catch (e: Exception) { "" }
        return if (msg.isNotBlank()) "$base: $msg" else "$base (код $code)"
    }

    /**
     * Сохранение вкладки «Профиль» — форма сайта (лог): POST /api/users/update/bio, multipart. Картинки — файлом в поле
     * profilePicture / profileBackground, если выбраны новые, иначе пустое поле (сервер оставляет прежние). Вызывать из io-потока.
     */
    private fun saveBioForm(dob: String, avatar: Uri?, background: Uri?): Pair<Int, String> {
        val cr = activity.contentResolver
        val mb = MultipartBody.Builder().setType(MultipartBody.FORM)
        for ((field, uri) in listOf("profileBackground" to background, "profilePicture" to avatar)) {
            if (uri == null) { mb.addFormDataPart(field, ""); continue }
            val bytes = cr.openInputStream(uri)?.use { it.readBytes() } ?: return 0 to ""
            if (bytes.size > MAX_UPLOAD) return 413 to "{\"error\":\"Файл слишком большой\"}"
            val mime = cr.getType(uri) ?: "image/jpeg"
            val ext = when (mime) { "image/png" -> "png"; "image/gif" -> "gif"; "image/webp" -> "webp"; else -> "jpg" }
            mb.addFormDataPart(field, "upload.$ext", bytes.toRequestBody(mime.toMediaType()))
        }
        for ((key, wire) in BIO_WIRE) mb.addFormDataPart(wire, if (key == "dateOfBirth") dob else bio[key].orEmpty())
        val r = api.sendMultipart("POST", "/api/users/update/bio", mb.build())
        return r.code to r.body
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
        val m = Regex("^(\\d{2})\\.(\\d{2})\\.(\\d{4})$").find(normalizeDob(s)) ?: return null
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
        pill.background = UiUtil.rect(if (good) 0xFF1B8F5A.toInt() else 0xFFC0392B.toInt(), 0x99FFFFFF.toInt(), 1, 22, activity)
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
