package ru.fortport.client

import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject

/**
 * Пополнение CD в стиле Aero (по логу оплаты и payment-widget.js сайта):
 *  • GET  /api/payments/options → {enabled, options:[{id, units, label, amountKopecks, formatted}]};
 *  • POST /api/payments/create {optionId} → {paymentId, confirmationUrl, units, formatted, status};
 *  • GET  /api/payments/history?limit=20[&beforeId=] → {items:[{type, createdAt, amountUnits, formatted, comment}], nextBeforeId}.
 * Ссылку оплаты (YooMoney) окно не открывает само, а отдаёт в [onPay]: MainActivity показывает её в изолированном слое.
 */
class PaymentDialog(
    private val activity: AppCompatActivity,
    private val api: SiteApi,
    private val onPay: (String) -> Unit
) {
    private val d = activity.resources.displayMetrics.density
    private fun dp(v: Int) = (v * d + 0.5f).toInt()
    private val dlg = AeroDialog(activity, "Пополнение CD")
    private val label = AeroUi.labelColor(activity)

    private class Option(val id: Int, val label: String, val price: String)

    fun show() {
        dlg.show()
        showOptions()
    }

    private fun status(text: String, error: Boolean = false) = UiUtil.text(activity, 15f, if (error) 0xFFD9534F.toInt() else label).apply {
        this.text = text; gravity = Gravity.CENTER; setPadding(0, dp(8), 0, dp(8))
    }

    private fun ui(block: () -> Unit) = activity.runOnUiThread {
        if (!activity.isFinishing && !activity.isDestroyed && dlg.dialog.isShowing) block()
    }

    private fun showOptions() {
        dlg.setTitle("Пополнение CD")
        dlg.body.removeAllViews(); dlg.footer.removeAllViews()
        val box = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        dlg.body.addView(UiUtil.text(activity, 15f, label).apply { text = "Выберите сумму пополнения"; gravity = Gravity.CENTER }, UiUtil.lp(UiUtil.MATCH, UiUtil.WRAP))
        dlg.body.addView(box, UiUtil.lp(UiUtil.MATCH, UiUtil.WRAP).apply { topMargin = dp(8) })
        box.addView(status("Загружаем доступные суммы…"))
        dlg.footer.addView(dlg.button("История операций") { showHistory() }, UiUtil.lp(UiUtil.MATCH, UiUtil.WRAP))
        dlg.footer.addView(dlg.button("Закрыть") { dlg.dismiss() }, UiUtil.lp(UiUtil.MATCH, UiUtil.WRAP).apply { topMargin = dp(6) })

        api.io.execute {
            var opts: List<Option>? = null; var enabled = true
            try {
                val r = api.get("/api/payments/options")
                if (r.code == 200) {
                    val o = JSONObject(r.body)
                    enabled = o.optBoolean("enabled", true)
                    val a = o.optJSONArray("options")
                    opts = (0 until (a?.length() ?: 0)).mapNotNull { i ->
                        a?.optJSONObject(i)?.let { Option(it.optInt("id"), api.str(it, "label"), api.str(it, "formatted")) }
                    }
                }
            } catch (e: Exception) { opts = null }
            ui {
                box.removeAllViews()
                val list = opts
                when {
                    list == null -> box.addView(status("Не удалось загрузить суммы", true))
                    !enabled || list.isEmpty() -> box.addView(status("Платежи временно недоступны. Попробуйте позже.", true))
                    else -> list.forEach { opt -> box.addView(optionRow(opt, box), UiUtil.lp(UiUtil.MATCH, UiUtil.WRAP).apply { topMargin = dp(6) }) }
                }
            }
        }
    }

    private fun optionRow(opt: Option, box: LinearLayout): View = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = AeroUi.fieldBackground(activity)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        isClickable = true; isFocusable = true
        addView(UiUtil.text(activity, 18f, AeroUi.titleColor(activity), bold = true).apply { text = opt.label }, UiUtil.lp(0, UiUtil.WRAP, 1f))
        addView(UiUtil.text(activity, 17f, label).apply { text = opt.price })
        setOnClickListener { pay(opt, box) }
    }

    private var busy = false
    private fun pay(opt: Option, box: LinearLayout) {
        if (busy) return
        busy = true
        for (i in 0 until box.childCount) box.getChildAt(i).alpha = 0.5f
        val st = status("Создаём платёж…")
        box.addView(st)
        api.io.execute {
            var url = ""; var err = "Не удалось создать платёж"
            try {
                val r = api.send("POST", "/api/payments/create", JSONObject().put("optionId", opt.id).toString())
                val o = JSONObject(r.body.ifBlank { "{}" })
                if (r.code == 200) url = api.str(o, "confirmationUrl")
                if (url.isBlank()) err = api.str(o, "error").ifBlank { "Платёжная система не вернула ссылку на оплату" }
            } catch (e: Exception) { /* сообщение по умолчанию */ }
            activity.runOnUiThread {
                busy = false
                if (url.startsWith("https://")) { dlg.dismiss(); onPay(url) }
                else if (!activity.isFinishing && !activity.isDestroyed && dlg.dialog.isShowing) {
                    for (i in 0 until box.childCount) box.getChildAt(i).alpha = 1f
                    st.text = err; st.setTextColor(0xFFD9534F.toInt())
                }
            }
        }
    }

    private fun typeLabel(t: String) = when (t) {
        "topup" -> "Пополнение"; "refund" -> "Возврат"; "purchase" -> "Покупка"
        "adjustment" -> "Корректировка"; "chargeback" -> "Отмена платежа"; else -> t
    }

    private fun showHistory() {
        dlg.setTitle("История операций")
        dlg.body.removeAllViews(); dlg.footer.removeAllViews()
        val list = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val st = status("Загружаем историю…")
        dlg.body.addView(list, UiUtil.lp(UiUtil.MATCH, UiUtil.WRAP))
        dlg.body.addView(st, UiUtil.lp(UiUtil.MATCH, UiUtil.WRAP))
        var beforeId: Long? = null
        var loading = false
        lateinit var more: TextView
        fun load() {
            if (loading) return
            loading = true; more.visibility = View.GONE
            val first = list.childCount == 0
            if (first) { st.text = "Загружаем историю…"; st.visibility = View.VISIBLE }
            api.io.execute {
                var items: List<JSONObject>? = null; var next: Long? = null
                try {
                    val r = api.get("/api/payments/history?limit=20" + (beforeId?.let { "&beforeId=$it" } ?: ""))
                    if (r.code == 200) {
                        val o = JSONObject(r.body)
                        val a = o.optJSONArray("items")
                        items = (0 until (a?.length() ?: 0)).mapNotNull { a?.optJSONObject(it) }
                        if (!o.isNull("nextBeforeId")) next = o.optLong("nextBeforeId")
                    }
                } catch (e: Exception) { items = null }
                ui {
                    loading = false
                    val got = items
                    if (got == null) { st.text = "Не удалось загрузить историю"; st.visibility = View.VISIBLE; return@ui }
                    got.forEach { list.addView(historyRow(it), UiUtil.lp(UiUtil.MATCH, UiUtil.WRAP).apply { topMargin = dp(6) }) }
                    beforeId = next
                    if (list.childCount == 0) { st.text = "Операций пока нет"; st.visibility = View.VISIBLE } else st.visibility = View.GONE
                    more.visibility = if (next != null) View.VISIBLE else View.GONE
                }
            }
        }
        more = dlg.button("Показать ещё") { load() }.apply { visibility = View.GONE }
        dlg.footer.addView(more, UiUtil.lp(UiUtil.MATCH, UiUtil.WRAP))
        dlg.footer.addView(dlg.button("Назад") { showOptions() }, UiUtil.lp(UiUtil.MATCH, UiUtil.WRAP).apply { topMargin = dp(6) })
        load()
    }

    private fun historyRow(o: JSONObject): View = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        background = AeroUi.fieldBackground(activity)
        setPadding(dp(12), dp(8), dp(12), dp(8))
        val credit = o.optLong("amountUnits") >= 0
        val top = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val info = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        info.addView(UiUtil.text(activity, 16f, AeroUi.titleColor(activity), bold = true).apply { text = typeLabel(api.str(o, "type")) })
        info.addView(UiUtil.text(activity, 13f, label).apply { text = formatDate(api.str(o, "createdAt")) })
        top.addView(info, UiUtil.lp(0, UiUtil.WRAP, 1f))
        top.addView(UiUtil.text(activity, 16f, if (credit) 0xFF3C9A4B.toInt() else 0xFFD9534F.toInt(), bold = true).apply { text = api.str(o, "formatted") })
        addView(top, UiUtil.lp(UiUtil.MATCH, UiUtil.WRAP))
        val c = api.str(o, "comment")
        if (c.isNotBlank()) addView(UiUtil.text(activity, 13f, label).apply { text = c }, UiUtil.lp(UiUtil.MATCH, UiUtil.WRAP))
    }

    private fun formatDate(iso: String): String = try {
        val t = java.time.OffsetDateTime.parse(iso).atZoneSameInstant(java.time.ZoneId.systemDefault())
        java.time.format.DateTimeFormatter.ofPattern("d.MM.yyyy HH:mm").format(t)
    } catch (e: Exception) { iso }
}
