package ru.fortport.client

import android.graphics.Outline
import android.graphics.drawable.ColorDrawable
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
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Нативные страницы порта вместо веб-страниц /new_community и /community/settings (концепции 1–3, тема aero):
 *  - создание порта (portId == 0): аватар, название, тип, описание, правила, «Создать»;
 *  - настройка порта: вкладки «Общие» (аватар, название, статус, тип, фон, описание, правила,
 *    «Удалить порт» с подтверждением, «Сохранить») и «Участники» (модераторы и подписчики с меню действий).
 *
 * Чтение (проверено по логу сайта):
 *  - GET /api/community/{id}/settings → id, username, type (page|community), created_at (unix сек),
 *    profile_picture, description, rules, status, owner_id, profile_background,
 *    moderators[{id, username, profile_picture, …}], subscribers[…те же поля…]. Владелец есть в обоих списках.
 *
 * Запись: в логах её нет (в присланных логах только открытие страниц) — адреса, методы и формы тел собраны
 * в [Endpoints] и НЕ ПРОВЕРЕНЫ. Когда будут логи «Создать» / «Сохранить» / «Удалить порт» / действий
 * в «Участниках», достаточно поправить [Endpoints] (и при необходимости [buildForm] / [saveFields]).
 *
 * Предположения о правах (сервер всё равно проверяет их сам): меню участников видно владельцу и модераторам;
 * назначать и снимать модераторов и удалять модераторов из порта может только владелец; «Удалить порт» — только владелец;
 * у владельца и у самого пользователя меню нет.
 */
class PortSettingsController(
    private val activity: AppCompatActivity,
    private val api: SiteApi,
    private val host: FrameLayout,
    /** Выбор картинки с устройства; результат приходит в переданный колбэк. */
    private val pickImage: ((Uri) -> Unit) -> Unit,
    /** Заголовок верхней панели («Новый порт» / «Настройка порта»). */
    private val onTitle: (String) -> Unit,
    /** Порт создан: id нового порта (0 — сервер не вернул id). */
    private val onCreated: (Long) -> Unit,
    /** Настройки сохранены: ленту порта нужно перечитать. */
    private val onSaved: (Long) -> Unit,
    /** Порт удалён. */
    private val onDeleted: (Long) -> Unit,
    private val onAuthLost: () -> Unit
) {
    private companion object {
        const val TAG = "PortSettings"
        const val MAX_UPLOAD = 20L * 1024 * 1024
        const val TOPBAR_DP = 70
        /** Тип порта на сервере → подпись. */
        val TYPES = listOf("community" to "Сообщество", "page" to "Страница")
    }

    /** Адреса записи. НЕ ПРОВЕРЕНЫ (в логах только GET) — поправить по логам действий. */
    private class Ep(val method: String, val path: String)
    private object Endpoints {
        val create = Ep("POST", "/api/communities")
        fun settings(id: Long) = "/api/community/$id/settings"
        fun save(id: Long) = Ep("PUT", "/api/community/$id/settings")
        fun delete(id: Long) = Ep("DELETE", "/api/communities/$id")
        fun addModerator(id: Long) = Ep("POST", "/api/community/$id/moderators")
        fun removeModerator(id: Long, userId: Long) = Ep("DELETE", "/api/community/$id/moderators/$userId")
        fun removeSubscriber(id: Long, userId: Long) = Ep("DELETE", "/api/community/$id/subscribers/$userId")
        /** Имена файловых полей multipart. */
        const val AVATAR_FIELD = "profile_picture"
        const val BACKGROUND_FIELD = "profile_background"
    }

    private enum class Tab { GENERAL, MEMBERS }
    private enum class Img { AVATAR, BACKGROUND }

    private class Member(val id: Long, val name: String, val avatar: String)
    private class UploadException(msg: String) : Exception(msg)

    private val d = activity.resources.displayMetrics.density
    private fun dp(v: Int) = (v * d + 0.5f).toInt()

    // --- состояние (правки живут, пока страницу не покинули; между вкладками не теряются)
    private var portId = 0L
    private var fresh = true // нужно начать с чистого листа (первое открытие или после ухода со страницы)
    private var started = false
    private var loaded = false
    private var saving = false
    private var generation = 0
    private var tab = Tab.GENERAL

    private var name = ""
    private var status = ""
    private var type = "community"
    private var description = ""
    private var rules = ""
    private var avatarPath = ""
    private var backgroundPath = ""
    private var ownerId = 0L
    private val moderators = ArrayList<Member>()
    private val subscribers = ArrayList<Member>()
    private val pendingImg = HashMap<Img, Uri>()

    val isCreate: Boolean get() = portId == 0L

    // --- виды
    private val scroll = ScrollView(activity)
    private val content = LinearLayout(activity)
    private val pill = TextView(activity)
    private var primaryButton: TextView? = null
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

    /** Какой порт открыть (0 — создание). Состояние сбрасывается, если порт другой или страницу покидали ([discard]). */
    fun setPort(id: Long) {
        if (!fresh && id == portId) return
        portId = id
        fresh = false
        generation++
        loaded = false; saving = false
        tab = Tab.GENERAL
        name = ""; status = ""; type = "community"; description = ""; rules = ""
        avatarPath = ""; backgroundPath = ""; ownerId = 0L
        moderators.clear(); subscribers.clear(); pendingImg.clear()
        content.removeAllViews()
        primaryButton = null
    }

    /** Пользователь ушёл со страницы: при следующем открытии форма будет пустой. */
    fun discard() {
        fresh = true
        generation++
        hideKeyboard()
    }

    fun start(userId: String, deviceId: String, userAgent: String) {
        api.setSession(userId, deviceId, userAgent)
        started = true
        onTitle(if (isCreate) "Новый порт" else "Настройка порта")
        when {
            loaded -> render()
            isCreate -> { loaded = true; render() }
            else -> load()
        }
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

    private class Loaded(
        val name: String, val status: String, val type: String, val description: String, val rules: String,
        val avatar: String, val background: String, val owner: Long,
        val moderators: List<Member>, val subscribers: List<Member>
    )

    private fun members(a: JSONArray?): List<Member> {
        if (a == null) return emptyList()
        val out = ArrayList<Member>(a.length())
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val id = o.optLong("id", 0L)
            if (id <= 0) continue
            out.add(Member(id, api.str(o, "username").ifBlank { "Без имени" }, api.str(o, "profile_picture")))
        }
        return out
    }

    private fun parse(o: JSONObject) = Loaded(
        name = api.str(o, "username"), status = api.str(o, "status"),
        type = api.str(o, "type").ifBlank { "community" },
        description = api.str(o, "description"), rules = api.str(o, "rules"),
        avatar = api.str(o, "profile_picture"), background = api.str(o, "profile_background"),
        owner = o.optLong("owner_id", 0L),
        moderators = members(o.optJSONArray("moderators")), subscribers = members(o.optJSONArray("subscribers"))
    )

    private class Fetch(val data: Loaded?, val auth: Boolean, val error: String?)

    /** Читает настройки (из io-потока). */
    private fun fetch(): Fetch {
        when (api.ensureUserId()) {
            1 -> return Fetch(null, true, null)
            2 -> return Fetch(null, false, "Не удалось загрузить настройки порта.")
        }
        val r = api.get(Endpoints.settings(portId))
        return when {
            r.code == 401 -> Fetch(null, true, null)
            r.code == 403 -> Fetch(null, false, "Нет прав на настройку этого порта.")
            r.code == 404 -> Fetch(null, false, "Порт не найден.")
            r.code != 200 -> Fetch(null, false, "Не удалось загрузить настройки порта.")
            else -> Fetch(parse(JSONObject(r.body)), false, null)
        }
    }

    private fun load() {
        val gen = ++generation
        showMessage("Загрузка…", retry = false)
        api.io.execute {
            val res = try { fetch() } catch (e: Exception) {
                Log.w(TAG, "Не удалось загрузить настройки порта", e)
                Fetch(null, false, "Нет связи с сервером.")
            }
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed || gen != generation || !started) return@runOnUiThread
                val data = res.data
                when {
                    res.auth -> onAuthLost()
                    data != null -> {
                        applyAll(data)
                        loaded = true
                        render()
                    }
                    else -> showMessage(res.error ?: "Не удалось загрузить настройки порта.", retry = true)
                }
            }
        }
    }

    private fun applyAll(l: Loaded) {
        name = l.name; status = l.status; type = l.type; description = l.description; rules = l.rules
        avatarPath = l.avatar; backgroundPath = l.background
        applyMembers(l)
    }

    private fun applyMembers(l: Loaded) {
        ownerId = l.owner
        moderators.clear(); moderators.addAll(l.moderators)
        subscribers.clear(); subscribers.addAll(l.subscribers)
    }

    private fun showMessage(text: String, retry: Boolean) {
        content.removeAllViews()
        primaryButton = null
        content.addView(UiUtil.text(activity, 16f, AeroUi.labelColor(activity)).apply {
            this.text = text
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(48), dp(24), dp(16))
        }, UiUtil.lp(UiUtil.MATCH, UiUtil.WRAP))
        if (retry) content.addView(button("Повторить") { load() }, UiUtil.lp(UiUtil.MATCH, dp(52)).apply {
            setMargins(dp(24), dp(8), dp(24), 0)
        })
    }

    // ------------------------------------------------------------------ отрисовка

    private fun render() {
        hideKeyboard()
        content.removeAllViews()
        primaryButton = null
        if (!isCreate) content.addView(tabBar(), UiUtil.lp(UiUtil.MATCH, UiUtil.WRAP))
        val bubble = bubble()
        if (isCreate || tab == Tab.GENERAL) fillGeneral(bubble) else fillMembers(bubble)
        content.addView(bubble, UiUtil.lp(UiUtil.MATCH, UiUtil.WRAP).apply { setMargins(dp(10), dp(12), dp(10), 0) })
        scroll.scrollTo(0, 0)
    }

    /** Вторичная панель «Общие / Участники» (как во вкладках настроек). */
    private fun tabBar(): View {
        val night = UiUtil.isNight(activity)
        val row = LinearLayout(activity)
        row.orientation = LinearLayout.HORIZONTAL
        row.background = AeroBar.skin(activity, false)
        row.setPadding(dp(10), dp(10), dp(10), dp(10))
        listOf(Tab.GENERAL to "Общие", Tab.MEMBERS to "Участники").forEach { (t, title) ->
            val active = t == tab
            val tv = UiUtil.text(activity, 15f, AeroButton.textColor(activity), bold = active, maxLines = 1).apply {
                text = title
                gravity = Gravity.CENTER
                if (active) {
                    background = UiUtil.rect(if (night) 0xFF235A96.toInt() else 0xFFBEE6FD.toInt(), 0xFF3C7FB1.toInt(), 2, 4, activity)
                } else AeroButton.style(this)
                setPadding(dp(4), dp(12), dp(4), dp(12))
                setOnClickListener { if (tab != t) { tab = t; render() } }
            }
            row.addView(tv, UiUtil.lp(0, UiUtil.WRAP, 1f).apply { setMargins(dp(4), 0, dp(4), 0) })
        }
        return row
    }

    private fun bubble(): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        background = AeroUi.panelBackground(activity)
        setPadding(dp(14), dp(14), dp(14), dp(16))
    }

    private fun LinearLayout.add(v: View, top: Int = 0) {
        addView(v, UiUtil.lp(UiUtil.MATCH, UiUtil.WRAP).apply { topMargin = dp(top) })
    }

    // ---- вкладка «Общие» / создание

    private fun fillGeneral(b: LinearLayout) {
        // Блок 1.2 / 2.3: аватар слева; справа — название и (при создании) тип или (при настройке) статус.
        val head = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        head.addView(imageCell(Img.AVATAR, "Аватар"), UiUtil.lp(dp(112), dp(112)))
        val right = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        right.add(input("Название", name, false) { name = it })
        if (isCreate) right.add(typeRow(), 10) else right.add(input("Статус", status, false) { status = it }, 10)
        head.addView(right, UiUtil.lp(0, UiUtil.WRAP, 1f).apply { leftMargin = dp(12) })
        b.add(head)
        if (!isCreate) b.add(typeRow(), 12)
        b.add(typeHint(), 6)

        if (!isCreate) b.add(imageCell(Img.BACKGROUND, "Фон"), 14)

        b.add(input("Описание", description, true, lines = 6) { description = it }, 14)
        b.add(input("Правила", rules, true, lines = 5) { rules = it }, 12)

        if (isCreate) {
            b.add(button("Создать") { save() }.also { primaryButton = it }, 16)
        } else {
            val del = button("Удалить порт") { confirmDelete() }.apply {
                setTextColor(if (UiUtil.isNight(activity)) 0xFFFF8A80.toInt() else 0xFFB3261E.toInt())
            }
            // Удалять порт может только владелец; остальным (модераторам) кнопка не нужна.
            if (isOwner()) b.add(del, 16)
            b.add(button("Сохранить") { save() }.also { primaryButton = it }, if (isOwner()) 10 else 16)
        }
    }

    private fun typeName() = TYPES.firstOrNull { it.first == type }?.second ?: type

    private fun typeRow(): View {
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(UiUtil.text(activity, 15f, AeroUi.labelColor(activity)).apply { text = "Тип:" }, UiUtil.lp(UiUtil.WRAP, UiUtil.WRAP))
        var ref: TextView? = null
        val names = TYPES.map { it.second }
        val btn = AeroUi.dropdown(activity, { names }, { TYPES.indexOfFirst { it.first == type }.coerceAtLeast(0) }) { i ->
            type = TYPES[i].first
            ref?.text = typeName()
            typeHintView?.text = typeHintText()
        }
        ref = btn
        btn.text = typeName()
        row.addView(AeroUi.dropdownView(btn), UiUtil.lp(0, UiUtil.WRAP, 1f).apply { leftMargin = dp(10) })
        return row
    }

    private var typeHintView: TextView? = null
    private fun typeHintText() = if (type == "page") "Страница: писать в порт могут только владелец и модераторы."
        else "Сообщество: писать в порт может каждый подписчик."
    private fun typeHint(): TextView = UiUtil.text(activity, 12f, AeroUi.labelColor(activity)).apply {
        text = typeHintText()
        alpha = 0.8f
        typeHintView = this
    }

    // ---- вкладка «Участники»

    private fun me() = api.userId.toLongOrNull() ?: 0L
    private fun isOwner() = ownerId != 0L && ownerId == me()
    private fun canManage() = isOwner() || moderators.any { it.id == me() }

    private fun fillMembers(b: LinearLayout) {
        b.add(sectionTitle("Модераторы"))
        if (moderators.isEmpty()) b.add(emptyNote("Модераторов нет"), 10)
        moderators.forEach { m -> b.add(memberRow(m, moderatorSection = true), 10) }

        b.add(sectionTitle("Подписчики"), 22)
        if (subscribers.isEmpty()) b.add(emptyNote("Подписчиков нет"), 10)
        // Модератор тоже считается подписчиком — остаётся и в этом списке.
        subscribers.forEach { m -> b.add(memberRow(m, moderatorSection = false), 10) }
    }

    private fun sectionTitle(text: String): TextView = UiUtil.text(activity, 22f, AeroUi.titleColor(activity), bold = true).apply {
        this.text = text
        gravity = Gravity.CENTER
    }

    private fun emptyNote(text: String): TextView = UiUtil.text(activity, 14f, AeroUi.labelColor(activity)).apply {
        this.text = text
        gravity = Gravity.CENTER
        alpha = 0.7f
    }

    private fun memberRow(m: Member, moderatorSection: Boolean): View {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = AeroUi.fieldBackground(activity)
            setPadding(dp(8), dp(8), dp(6), dp(8))
        }
        val av = ImageView(activity).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) { outline.setRoundRect(0, 0, view.width, view.height, dp(6).toFloat()) }
            }
        }
        api.loadAvatar(av, m.avatar)
        row.addView(av, UiUtil.lp(dp(52), dp(52)))

        val texts = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(UiUtil.text(activity, 17f, AeroUi.labelColor(activity), maxLines = 1).apply { text = m.name })
        val caption = when {
            m.id == ownerId -> "Хозяин порта"
            !moderatorSection && moderators.any { it.id == m.id } -> "Модератор"
            else -> ""
        }
        if (caption.isNotEmpty()) texts.addView(UiUtil.text(activity, 12f, AeroUi.labelColor(activity)).apply { text = caption; alpha = 0.75f })
        row.addView(texts, UiUtil.lp(0, UiUtil.WRAP, 1f).apply { leftMargin = dp(12) })

        val actions = actionsFor(m, moderatorSection)
        if (actions.isNotEmpty()) {
            val menu = UiUtil.text(activity, 24f, AeroUi.labelColor(activity), bold = true).apply {
                text = "☰"
                gravity = Gravity.CENTER
                isClickable = true
                setOnClickListener { showMenu(this, actions) }
            }
            row.addView(menu, UiUtil.lp(dp(48), dp(48)))
        }
        return row
    }

    /** Действия меню плашки (подписи и обработчики). Пусто — меню не показывается. */
    private fun actionsFor(m: Member, moderatorSection: Boolean): List<Pair<String, () -> Unit>> {
        if (!canManage() || m.id == ownerId || m.id == me()) return emptyList()
        val isMod = moderators.any { it.id == m.id }
        val out = ArrayList<Pair<String, () -> Unit>>()
        if (moderatorSection) {
            if (isOwner()) out.add("Убрать из модераторов" to { confirmMember("Убрать из модераторов?", "«${m.name}» перестанет быть модератором порта.", "Убрать") { removeModerator(m) } })
        } else {
            if (!isMod && isOwner()) out.add("Назначить модератором" to { confirmMember("Назначить модератором?", "«${m.name}» станет модератором порта.", "Назначить") { addModerator(m) } })
            if (!isMod || isOwner()) out.add("Удалить из порта" to { confirmMember("Удалить пользователя?", "«${m.name}» будет удалён из подписчиков порта.", "Удалить") { removeSubscriber(m) } })
        }
        return out
    }

    private fun showMenu(anchor: View, actions: List<Pair<String, () -> Unit>>) {
        val col = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = AeroUi.panelBackground(activity)
            minimumWidth = dp(210)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        var pw: PopupWindow? = null
        actions.forEach { (label, act) ->
            col.addView(UiUtil.text(activity, 15f, AeroUi.labelColor(activity)).apply {
                text = label
                setPadding(dp(14), dp(12), dp(14), dp(12))
                setOnClickListener { pw?.dismiss(); act() }
            })
        }
        val popup = PopupWindow(col, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            elevation = dp(10).toFloat()
            setBackgroundDrawable(ColorDrawable(0))
        }
        pw = popup
        popup.showAsDropDown(anchor, 0, 0, Gravity.END)
    }

    private fun confirmMember(title: String, message: String, positive: String, run: () -> Unit) {
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(positive) { _, _ -> run() }
            .setNegativeButton("Отмена", null)
            .show()
    }

    // ------------------------------------------------------------------ элементы формы

    private fun input(hint: String, value: String, multiline: Boolean, lines: Int = 3, onChange: (String) -> Unit): EditText =
        AeroUi.input(EditText(activity)).apply {
            this.hint = hint
            setText(value)
            textSize = 16f
            if (multiline) {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                minLines = lines
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

    private fun button(text: String, onClick: () -> Unit): TextView = UiUtil.text(activity, 18f, AeroButton.textColor(activity), bold = true).let { tv ->
        tv.text = text
        tv.gravity = Gravity.CENTER
        AeroButton.style(tv)
        tv.setPadding(dp(8), dp(14), dp(8), dp(14))
        tv.setOnClickListener { if (tv.isEnabled) onClick() }
        tv
    }

    private fun imageCell(kind: Img, label: String): FrameLayout {
        val cell = FrameLayout(activity)
        cell.background = AeroUi.fieldBackground(activity)
        cell.clipToOutline = true
        cell.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) { outline.setRoundRect(0, 0, view.width, view.height, dp(8).toFloat()) }
        }
        val iv = ImageView(activity).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        cell.addView(iv, FrameLayout.LayoutParams(UiUtil.MATCH, UiUtil.MATCH))

        val pending = pendingImg[kind]
        val path = if (kind == Img.AVATAR) avatarPath else backgroundPath
        var empty = false
        when {
            pending != null -> iv.setImageURI(pending)
            kind == Img.AVATAR -> api.loadAvatar(iv, path)
            path.isBlank() -> empty = true
            else -> api.loadImage(iv, path, 800)
        }
        if (kind == Img.BACKGROUND && empty) {
            cell.addView(UiUtil.text(activity, 17f, AeroUi.labelColor(activity)).apply {
                text = "Фон [изображение]"
                gravity = Gravity.CENTER
            }, FrameLayout.LayoutParams(UiUtil.MATCH, UiUtil.MATCH))
        } else {
            cell.addView(UiUtil.text(activity, 12f, UiUtil.WHITE).apply {
                text = label
                gravity = Gravity.CENTER
                setBackgroundColor(0x99000000.toInt())
                setPadding(dp(4), dp(3), dp(4), dp(3))
            }, FrameLayout.LayoutParams(UiUtil.MATCH, UiUtil.WRAP, Gravity.BOTTOM))
        }
        cell.setOnClickListener {
            pickImage { uri ->
                if (activity.isFinishing || activity.isDestroyed) return@pickImage
                pendingImg[kind] = uri
                if (started) rerenderKeepScroll()
            }
        }
        if (kind == Img.BACKGROUND) cell.layoutParams = LinearLayout.LayoutParams(UiUtil.MATCH, dp(110))
        return cell
    }

    private fun rerenderKeepScroll() { val y = scroll.scrollY; render(); scroll.post { scroll.scrollTo(0, y) } }

    // ------------------------------------------------------------------ сохранение / создание

    /** Текстовые поля запроса сохранения/создания (форма не проверена по логам). */
    private fun saveFields(): Map<String, String> {
        val f = LinkedHashMap<String, String>()
        f["userId"] = api.userId
        f["username"] = name.trim()
        f["type"] = type
        f["description"] = description
        f["rules"] = rules
        if (!isCreate) f["status"] = status
        return f
    }

    private fun buildForm(fields: Map<String, String>, files: List<Pair<String, Uri>>): MultipartBody {
        val b = MultipartBody.Builder().setType(MultipartBody.FORM)
        fields.forEach { (k, v) -> b.addFormDataPart(k, v) }
        val cr = activity.contentResolver
        for ((field, uri) in files) {
            val bytes = cr.openInputStream(uri)?.use { it.readBytes() } ?: throw UploadException("Не удалось прочитать файл")
            if (bytes.size > MAX_UPLOAD) throw UploadException("Файл слишком большой (максимум 20 МБ)")
            val mime = cr.getType(uri) ?: "image/jpeg"
            val ext = when (mime) { "image/png" -> "png"; "image/gif" -> "gif"; "image/webp" -> "webp"; else -> "jpg" }
            b.addFormDataPart(field, "upload.$ext", bytes.toRequestBody(mime.toMediaType()))
        }
        return b.build()
    }

    /** Текст ошибки из ответа сервера: код не 2xx или `success:false`; null — всё хорошо. */
    private fun failure(r: SiteApi.Resp, base: String): String? {
        val o = try { JSONObject(r.body) } catch (e: Exception) { null }
        val msg = o?.let { it.optString("error", it.optString("message", "")) }.orEmpty()
        if (r.code !in 200..299) return if (msg.isNotBlank()) "$base: $msg" else "$base (код ${r.code})"
        if (o != null && o.has("success") && !o.optBoolean("success", true)) return if (msg.isNotBlank()) "$base: $msg" else base
        return null
    }

    private fun parseId(body: String): Long = try {
        val o = JSONObject(body)
        listOf("id", "communityId", "community_id").map { o.optLong(it, 0L) }.firstOrNull { it > 0 }
            ?: o.optJSONObject("community")?.optLong("id", 0L)?.takeIf { it > 0 } ?: 0L
    } catch (e: Exception) { 0L }

    private fun save() {
        if (saving || !loaded) return
        if (name.isBlank()) { toast("Введите название порта", false); return }
        val creating = isCreate
        val id = portId
        val fields = saveFields()
        val files = ArrayList<Pair<String, Uri>>()
        pendingImg[Img.AVATAR]?.let { files.add(Endpoints.AVATAR_FIELD to it) }
        if (!creating) pendingImg[Img.BACKGROUND]?.let { files.add(Endpoints.BACKGROUND_FIELD to it) }

        saving = true
        primaryButton?.apply { isEnabled = false; text = if (creating) "Создание…" else "Сохранение…"; alpha = 0.7f }
        val gen = generation
        api.io.execute {
            var err: String? = null
            var auth = false
            var newId = 0L
            var reloaded: Loaded? = null
            try {
                if (api.ensureUserId() == 1) auth = true
                else {
                    val body = buildForm(fields + ("userId" to api.userId), files)
                    val ep = if (creating) Endpoints.create else Endpoints.save(id)
                    val r = api.sendMultipart(ep.method, ep.path, body)
                    if (r.code == 401) auth = true
                    else {
                        err = failure(r, if (creating) "Не удалось создать порт" else "Не удалось сохранить")
                        if (err == null && creating) newId = parseId(r.body)
                    }
                    if (err == null && !auth && !creating) {
                        // Пути картинок после загрузки берём с сервера (ошибка здесь не критична).
                        try { api.get(Endpoints.settings(id)).takeIf { it.code == 200 }?.let { reloaded = parse(JSONObject(it.body)) } }
                        catch (e: Exception) { Log.w(TAG, "refresh after save", e) }
                    }
                }
            } catch (e: UploadException) {
                err = e.message
            } catch (e: Exception) {
                Log.w(TAG, "Сохранение порта", e)
                err = "Нет связи с сервером"
            }
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                saving = false
                primaryButton?.apply { isEnabled = true; text = if (creating) "Создать" else "Сохранить"; alpha = 1f }
                if (gen != generation || !started) return@runOnUiThread
                when {
                    auth -> onAuthLost()
                    err != null -> toast(err!!, false)
                    creating -> { fresh0(); onCreated(newId) }
                    else -> {
                        // В тестовом режиме файл не сохраняется — оставляем выбранную картинку на экране.
                        if (!api.isDemo) pendingImg.clear()
                        reloaded?.let { applyAll(it) }
                        toast("Порт сохранён", true)
                        onSaved(id)
                        render()
                    }
                }
            }
        }
    }

    /** После успешного создания форма больше не нужна. */
    private fun fresh0() { fresh = true }

    // ------------------------------------------------------------------ удаление порта

    private fun confirmDelete() {
        if (saving) return
        AlertDialog.Builder(activity)
            .setTitle("Удалить порт?")
            .setMessage("Порт «${name.ifBlank { "без названия" }}» будет удалён вместе с подписками. Это действие нельзя отменить.")
            .setPositiveButton("Удалить") { _, _ -> deletePort() }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun deletePort() {
        val id = portId
        val gen = generation
        saving = true
        primaryButton?.apply { isEnabled = false; alpha = 0.7f }
        api.io.execute {
            var err: String? = null
            var auth = false
            try {
                if (api.ensureUserId() == 1) auth = true
                else {
                    val ep = Endpoints.delete(id)
                    val r = api.send(ep.method, ep.path, null)
                    if (r.code == 401) auth = true else err = failure(r, "Не удалось удалить порт")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Удаление порта", e)
                err = "Нет связи с сервером"
            }
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                saving = false
                primaryButton?.apply { isEnabled = true; alpha = 1f }
                if (gen != generation || !started) return@runOnUiThread
                when {
                    auth -> onAuthLost()
                    err != null -> toast(err!!, false)
                    else -> { fresh0(); onDeleted(id) }
                }
            }
        }
    }

    // ------------------------------------------------------------------ участники: запросы

    private fun addModerator(m: Member) = memberRequest(Endpoints.addModerator(portId), """{"userId":${m.id}}""",
        "Не удалось назначить модератором", "«${m.name}» назначен модератором")

    private fun removeModerator(m: Member) = memberRequest(Endpoints.removeModerator(portId, m.id), null,
        "Не удалось убрать из модераторов", "«${m.name}» больше не модератор")

    private fun removeSubscriber(m: Member) = memberRequest(Endpoints.removeSubscriber(portId, m.id), null,
        "Не удалось удалить пользователя", "«${m.name}» удалён из порта")

    private fun memberRequest(ep: Ep, json: String?, errBase: String, okMsg: String) {
        if (saving) return
        saving = true
        val gen = generation
        api.io.execute {
            var err: String? = null
            var auth = false
            var reloaded: Loaded? = null
            try {
                if (api.ensureUserId() == 1) auth = true
                else {
                    val r = api.send(ep.method, ep.path, json)
                    if (r.code == 401) auth = true
                    else {
                        err = failure(r, errBase)
                        if (err == null) {
                            try { api.get(Endpoints.settings(portId)).takeIf { it.code == 200 }?.let { reloaded = parse(JSONObject(it.body)) } }
                            catch (e: Exception) { Log.w(TAG, "refresh members", e) }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Действие с участником", e)
                err = "Нет связи с сервером"
            }
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                saving = false
                if (gen != generation || !started) return@runOnUiThread
                when {
                    auth -> onAuthLost()
                    err != null -> toast(err!!, false)
                    else -> {
                        // Обновляем только списки участников: несохранённые правки формы не трогаем.
                        reloaded?.let { applyMembers(it) }
                        toast(okMsg, true)
                        if (tab == Tab.MEMBERS) rerenderKeepScroll()
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ плашка

    /** Плашка с результатом: страница не закрывается и пользователя никуда не переносит. */
    private fun toast(text: String, good: Boolean) {
        hideTask?.let { pill.removeCallbacks(it) }
        pill.animate().cancel()
        pill.text = text
        pill.background = UiUtil.rect(if (good) 0xFF1B8F5A.toInt() else 0xFFC0392B.toInt(), 0xFF111111.toInt(), 2, 22, activity)
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
