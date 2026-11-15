package ru.fortport.client

import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/**
 * Окна настройки лент в стиле Aero (концепты «Настройка ленты», «Новая лента», «Выбрать порт»).
 *
 *  1. Список лент: плашки «радио + название + ⋯». Тап по плашке выбирает ленту; ⋯ открывает меню «Изменить» / «Удалить».
 *     Внизу — «Создать новую ленту».
 *  2. Редактор: название; фильтр (Лента, Порты, Друзья, Микс); сортировка (Новое, Старое, Горячее, Качественное, Популярное,
 *     Спорное); период (для «Популярное» и «Спорное», как на сайте); чекбоксы «Показывать 18+», «Показывать спойлеры»,
 *     «Показать скрытых пользователей», «Анонимные посты». Для «Микс» — кнопки выбора портов и друзей.
 *  3. Выбор портов/друзей: плашки «чекбокс + аватар + название», кнопка «Готово».
 *
 * Запросы (по feed.js сайта и логу создания): POST/PUT/DELETE /api/users/feedlist, GET /api/users/{me}/commIds и /friends.
 * Смешанная лента хранится строкой filterType = "PORTS:[1,2] USERS:[3,4]".
 */
class FeedSettings(
    private val activity: AppCompatActivity,
    private val api: SiteApi,
    private val repo: PostsRepo,
    /** Лента, активная сейчас (радио в списке). */
    private val active: () -> FeedTab,
    private val onSelect: (FeedTab) -> Unit,
    /** Список лент на сервере изменился (создали, поменяли, удалили) — уже перечитанный. */
    private val onTabsChanged: (List<FeedTab>) -> Unit
) {
    companion object {
        val FILTERS = listOf("feed" to "Лента", "ports" to "Порты", "friends" to "Друзья", "mixed" to "Микс")
        val SORTS = listOf(
            "new" to "Новое", "old" to "Старое", "hot" to "Горячее",
            "quality" to "Качественное", "popular" to "Популярное", "controversial" to "Спорное"
        )
        val PERIODS = listOf(
            "24h" to "24 часа", "3d" to "3 дня", "7d" to "7 дней", "30d" to "30 дней",
            "180d" to "180 дней", "1y" to "1 год", "3y" to "3 года", "10y" to "10 лет"
        )
        private const val CUSTOM = "custom"

        fun filterKey(t: FeedTab) = if (t.filterType.startsWith("PORTS:[")) "mixed" else t.filterType
        fun needsPeriod(sort: String) = sort == "popular" || sort == "controversial"

        /** «Лента · Новое · 7 дней» — подпись под названием в списке. */
        fun describe(t: FeedTab): String {
            val parts = ArrayList<String>()
            val fk = filterKey(t)
            parts.add(FILTERS.firstOrNull { it.first == fk }?.second ?: t.filterType)
            parts.add(SORTS.firstOrNull { it.first == t.sortType }?.second ?: t.sortType)
            if (needsPeriod(t.sortType)) {
                parts.add(
                    when {
                        t.rangeJson != null -> "свой диапазон"
                        !t.timerange.isNullOrBlank() -> PERIODS.firstOrNull { it.first == t.timerange }?.second ?: t.timerange
                        else -> ""
                    }
                )
            }
            return parts.filter { it.isNotBlank() }.joinToString(" · ")
        }

        /** Идентификаторы из «PORTS:[1,2] USERS:[3]». */
        fun parseMixed(filterType: String, tag: String): LinkedHashSet<Long> {
            val out = LinkedHashSet<Long>()
            Regex("$tag:\\[([^\\]]*)\\]").find(filterType)?.groupValues?.get(1)?.split(',')
                ?.mapNotNull { it.trim().toLongOrNull() }?.forEach { out.add(it) }
            return out
        }

        fun serializeMixed(ports: Collection<Long>, users: Collection<Long>) =
            "PORTS:[${ports.joinToString(",")}] USERS:[${users.joinToString(",")}]"
    }

    private val d = activity.resources.displayMetrics.density
    private fun dp(v: Int) = (v * d + 0.5f).toInt()

    private var tabs: List<FeedTab> = emptyList()
    private var listDialog: AeroDialog? = null

    fun show(current: List<FeedTab>) {
        tabs = current
        showList()
    }

    private fun toast(s: String) = Toast.makeText(activity, s, Toast.LENGTH_SHORT).show()

    private fun ui(r: () -> Unit) = activity.runOnUiThread { if (!activity.isFinishing && !activity.isDestroyed) r() }

    // ------------------------------------------------------------------ список

    private fun showList() {
        val dlg = AeroDialog(activity, "Настройка ленты")
        listDialog = dlg
        dlg.onDismiss = { if (listDialog === dlg) listDialog = null }
        fillList(dlg)
        dlg.footer.addView(dlg.button("Создать новую ленту") { dlg.dismiss(); showEditor(null) }, UiUtil.lp(UiUtil.MATCH, UiUtil.WRAP))
        dlg.show()
    }

    private fun isActive(t: FeedTab): Boolean {
        val a = active()
        return if (a.id != 0L) t.id == a.id else t.name == a.name
    }

    private fun fillList(dlg: AeroDialog) {
        dlg.body.removeAllViews()
        if (tabs.isEmpty()) {
            dlg.body.addView(UiUtil.text(activity, 15f, AeroUi.labelColor(activity)).apply {
                text = "Лент пока нет. Создайте первую."
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(16), dp(8), dp(8))
            }, UiUtil.lp(UiUtil.MATCH, UiUtil.WRAP))
            return
        }
        tabs.forEach { t -> dlg.body.addView(plate(dlg, t), UiUtil.lp(UiUtil.MATCH, UiUtil.WRAP).apply { topMargin = dp(8) }) }
    }

    private fun plate(dlg: AeroDialog, t: FeedTab): View {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = AeroUi.fieldBackground(activity)
            setPadding(dp(12), dp(8), dp(4), dp(8))
            setOnClickListener { dlg.dismiss(); onSelect(t) }
        }
        row.addView(ImageView(activity).apply { setImageDrawable(AeroUi.radioDrawable(activity, isActive(t))) }, UiUtil.lp(dp(26), dp(26)))
        val col = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        col.addView(UiUtil.text(activity, 17f, AeroUi.titleColor(activity), bold = true).apply {
            text = t.name.ifBlank { "Без названия" }; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        })
        col.addView(UiUtil.text(activity, 12f, AeroUi.labelColor(activity)).apply {
            text = describe(t); alpha = 0.8f; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        })
        row.addView(col, UiUtil.lp(0, UiUtil.WRAP, 1f).apply { marginStart = dp(10) })
        val dots = UiUtil.text(activity, 26f, AeroUi.titleColor(activity), bold = true).apply {
            text = "⋯"; gravity = Gravity.CENTER
            setPadding(dp(12), 0, dp(12), dp(4))
        }
        dots.setOnClickListener { anchor ->
            AeroUi.popupMenu(activity, anchor, listOf(
                "Изменить" to { dlg.dismiss(); showEditor(t) },
                "Удалить" to { confirmDelete(t) }
            ))
        }
        row.addView(dots, UiUtil.lp(UiUtil.WRAP, dp(40)))
        return row
    }

    private fun confirmDelete(t: FeedTab) {
        AlertDialog.Builder(activity)
            .setMessage("Удалить ленту «${t.name}»?")
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Удалить") { _, _ ->
                repo.pool.execute {
                    val r = repo.deleteFeed(t.id)
                    ui {
                        if (r.authLost) toast("Войдите заново")
                        else if (r.ok) refreshTabs { listDialog?.let { fillList(it) } }
                        else toast(r.error.ifBlank { "Не удалось удалить" })
                    }
                }
            }.show()
    }

    /** Перечитывает ленты с сервера, сообщает контроллеру и вызывает [then] в UI-потоке. */
    private fun refreshTabs(then: () -> Unit) {
        repo.pool.execute {
            val fresh = try { repo.loadFeedTabs() } catch (e: Exception) { null }
            ui {
                if (fresh != null) { tabs = fresh; onTabsChanged(fresh) }
                then()
            }
        }
    }

    // ------------------------------------------------------------------ редактор

    /** Выпадающий список «Подпись: значение». */
    private fun labeled(prefix: String, options: () -> List<Pair<String, String>>, current: () -> String, onPick: (String) -> Unit): View {
        var ref: TextView? = null
        fun label() = "$prefix: ${options().firstOrNull { it.first == current() }?.second.orEmpty()}"
        val btn = AeroUi.dropdown(activity, { options().map { it.second } }, { options().indexOfFirst { it.first == current() }.coerceAtLeast(0) }) { i ->
            onPick(options()[i].first)
            ref?.text = label()
        }
        ref = btn
        btn.text = label()
        btn.textSize = 16f
        return AeroUi.dropdownView(btn)
    }

    private fun showEditor(existing: FeedTab?) {
        val dlg = AeroDialog(activity, if (existing == null) "Новая лента" else "Изменить ленту")
        // Закрыли без сохранения — возвращаемся к списку (как onClosed на сайте).
        var finished = false
        dlg.onDismiss = { if (!finished) showList() }

        var filter = existing?.let { filterKey(it) } ?: "feed"
        if (FILTERS.none { it.first == filter }) filter = "feed"
        var sort = existing?.sortType ?: "new"
        if (SORTS.none { it.first == sort }) sort = "new"
        var period = existing?.timerange?.takeIf { t -> PERIODS.any { it.first == t } } ?: "24h"
        var rangeJson = existing?.rangeJson
        var nsfw = existing?.showNsfw ?: false
        var spoiler = existing?.showSpoiler ?: true
        var muted = existing?.showMuted ?: true
        var anon = existing?.showAnonymous ?: true
        val ports = existing?.let { parseMixed(it.filterType, "PORTS") } ?: LinkedHashSet()
        val users = existing?.let { parseMixed(it.filterType, "USERS") } ?: LinkedHashSet()

        val body = dlg.body
        fun add(v: View, top: Int = 0) = body.addView(v, UiUtil.lp(UiUtil.MATCH, UiUtil.WRAP).apply { topMargin = dp(top) })

        // 1. Название
        val name = AeroUi.input(EditText(activity)).apply {
            hint = "Название"; textSize = 16f
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
            setText(existing?.name.orEmpty())
        }
        add(name)

        // 2. Фильтр + кнопки выбора для «Микс»
        val mixedBlock = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val summary = UiUtil.text(activity, 13f, AeroUi.labelColor(activity))
        fun refreshSummary() {
            val parts = ArrayList<String>()
            if (ports.isNotEmpty()) parts.add("Порты: ${ports.size}")
            if (users.isNotEmpty()) parts.add("Друзья: ${users.size}")
            summary.text = if (parts.isEmpty()) "Ничего не выбрано" else parts.joinToString(" · ")
        }
        val periodView = labeled("Период", {
            (if (rangeJson != null) listOf(CUSTOM to "Свой диапазон") else emptyList()) + PERIODS
        }, { if (rangeJson != null) CUSTOM else period }) { v ->
            if (v != CUSTOM) { rangeJson = null; period = v }
        }
        fun refreshVisibility() {
            mixedBlock.visibility = if (filter == "mixed") View.VISIBLE else View.GONE
            periodView.visibility = if (needsPeriod(sort)) View.VISIBLE else View.GONE
        }
        add(labeled("Фильтр", { FILTERS }, { filter }) { filter = it; refreshVisibility() }, 8)

        val pickRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        pickRow.addView(dlg.button("Выбрать порты") {
            pickEntities("ports", ports) { ports.clear(); ports.addAll(it); refreshSummary() }
        }.apply { textSize = 14f }, UiUtil.lp(0, UiUtil.WRAP, 1f))
        pickRow.addView(dlg.button("Выбрать друзей") {
            pickEntities("friends", users) { users.clear(); users.addAll(it); refreshSummary() }
        }.apply { textSize = 14f }, UiUtil.lp(0, UiUtil.WRAP, 1f).apply { marginStart = dp(8) })
        mixedBlock.addView(pickRow, UiUtil.lp(UiUtil.MATCH, UiUtil.WRAP))
        mixedBlock.addView(summary, UiUtil.lp(UiUtil.MATCH, UiUtil.WRAP).apply { topMargin = dp(6) })
        add(mixedBlock, 8)
        refreshSummary()

        // 3. Сортировка + период
        add(labeled("Сортировка", { SORTS }, { sort }) { sort = it; refreshVisibility() }, 8)
        add(periodView, 8)
        refreshVisibility()

        // 4. 18+
        fun check(label: String, value: Boolean, onChange: (Boolean) -> Unit): CheckBox =
            AeroUi.checkBox(activity, label).apply {
                textSize = 16f
                isChecked = value
                setOnCheckedChangeListener { _, c -> onChange(c) }
            }
        add(check("Показывать 18+", nsfw) { nsfw = it }, 10)
        body.addView(AeroUi.separator(activity), UiUtil.lp(UiUtil.MATCH, dp(1)).apply { topMargin = dp(8) })
        // 5–7
        add(check("Показывать спойлеры", spoiler) { spoiler = it }, 8)
        add(check("Показать скрытых пользователей", muted) { muted = it }, 6)
        add(check("Анонимные посты", anon) { anon = it }, 6)

        var saving = false
        val save = dlg.button(if (existing == null) "Создать" else "Сохранить") {}
        save.setOnClickListener {
            if (saving) return@setOnClickListener
            val title = name.text.toString().trim()
            if (title.isEmpty()) { toast("Введите название"); return@setOnClickListener }
            if (filter == "mixed" && ports.isEmpty() && users.isEmpty()) { toast("Выберите хотя бы один порт или друга"); return@setOnClickListener }
            val timed = needsPeriod(sort)
            val tab = FeedTab(
                name = title,
                filterType = if (filter == "mixed") serializeMixed(ports, users) else filter,
                sortType = sort,
                timerange = if (timed && rangeJson == null) period else null,
                showNsfw = nsfw, showAnonymous = anon, showMuted = muted,
                id = existing?.id ?: 0L, showSpoiler = spoiler,
                rangeJson = if (timed) rangeJson else null
            )
            saving = true
            save.isEnabled = false; save.alpha = 0.7f
            repo.pool.execute {
                val r = repo.saveFeed(existing?.id, tab)
                ui {
                    saving = false
                    save.isEnabled = true; save.alpha = 1f
                    when {
                        r.authLost -> toast("Войдите заново")
                        !r.ok -> toast(r.error.ifBlank { "Не удалось сохранить" })
                        else -> {
                            finished = true
                            dlg.dismiss()
                            refreshTabs { showList() }
                        }
                    }
                }
            }
        }
        dlg.footer.addView(save, UiUtil.lp(UiUtil.MATCH, UiUtil.WRAP))
        dlg.show()
    }

    // ------------------------------------------------------------------ выбор портов / друзей

    private fun pickEntities(kind: String, selected: Set<Long>, onDone: (Set<Long>) -> Unit) {
        val isPorts = kind == "ports"
        val dlg = AeroDialog(activity, if (isPorts) "Выбрать порт" else "Выбрать друзей")
        val picked = LinkedHashSet(selected)
        val info = UiUtil.text(activity, 15f, AeroUi.labelColor(activity)).apply {
            text = "Загрузка…"; gravity = Gravity.CENTER; setPadding(dp(8), dp(16), dp(8), dp(16))
        }
        dlg.body.addView(info, UiUtil.lp(UiUtil.MATCH, UiUtil.WRAP))
        dlg.footer.addView(dlg.button("Готово") { dlg.dismiss(); onDone(picked) }, UiUtil.lp(UiUtil.MATCH, UiUtil.WRAP))
        dlg.show()
        repo.pool.execute {
            val items = repo.loadEntities(kind)
            ui {
                dlg.body.removeAllViews()
                when {
                    items == null -> dlg.body.addView(info.apply { text = "Не удалось загрузить список" }, UiUtil.lp(UiUtil.MATCH, UiUtil.WRAP))
                    items.isEmpty() -> dlg.body.addView(info.apply { text = if (isPorts) "У вас нет портов" else "У вас нет друзей" }, UiUtil.lp(UiUtil.MATCH, UiUtil.WRAP))
                    else -> items.forEach { e -> dlg.body.addView(entityPlate(e, picked), UiUtil.lp(UiUtil.MATCH, UiUtil.WRAP).apply { topMargin = dp(8) }) }
                }
            }
        }
    }

    private fun entityPlate(e: EntityLite, picked: MutableSet<Long>): View {
        val box = AeroUi.checkBox(activity, "").apply {
            isChecked = e.id in picked
            isClickable = false; isFocusable = false
            setPadding(0, 0, 0, 0)
        }
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = AeroUi.fieldBackground(activity)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setOnClickListener {
                box.isChecked = !box.isChecked
                if (box.isChecked) picked.add(e.id) else picked.remove(e.id)
            }
        }
        row.addView(box, UiUtil.lp(UiUtil.WRAP, UiUtil.WRAP))
        val avatar = ImageView(activity).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = UiUtil.rect(Color.WHITE, 0xFF6C96C0.toInt(), 1, 6, activity)
            setPadding(dp(1), dp(1), dp(1), dp(1))
            clipToOutline = true
        }
        row.addView(avatar, UiUtil.lp(dp(46), dp(46)).apply { marginStart = dp(12) })
        api.loadAvatar(avatar, e.avatar)
        row.addView(UiUtil.text(activity, 17f, AeroUi.titleColor(activity)).apply {
            text = e.name.ifBlank { e.id.toString() }; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        }, UiUtil.lp(0, UiUtil.WRAP, 1f).apply { marginStart = dp(12) })
        return row
    }
}
