package ru.fortport.client

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject

/**
 * «Поделиться» ссылкой (профиль, пост) в стиле Aero — как openShareModal() сайта (templates.js):
 *  • отправка ссылки в чат с другим профилем: GET /api/users/chats/{me} → {favouriteChats, userChats}, затем
 *    POST /api/chats/messages/{chatId} {messageText: <зашифровано>} (шифрование — [ChatCrypto]);
 *  • копирование ссылки;
 *  • отправка в другое приложение через системное окно «Поделиться» (вместо кнопок соцсетей сайта).
 */
object ShareDialog {

    private class Chat(val id: String, val name: String, val avatar: String, val time: String)

    fun show(activity: AppCompatActivity, api: SiteApi, link: String, title: String = "Поделиться") {
        if (link.isBlank()) return
        val d = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * d + 0.5f).toInt()
        val label = AeroUi.labelColor(activity)

        val dlg = AeroDialog(activity, title)
        val body = dlg.body
        body.addView(UiUtil.text(activity, 14f, label, bold = true).apply { text = "Отправить в чат" }, UiUtil.lp(UiUtil.MATCH, UiUtil.WRAP))
        val chatsBox = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val status = UiUtil.text(activity, 14f, label).apply {
            text = "Загрузка чатов…"; gravity = Gravity.CENTER; setPadding(dp(8), dp(12), dp(8), dp(12))
        }
        chatsBox.addView(status, UiUtil.lp(UiUtil.MATCH, UiUtil.WRAP))
        body.addView(chatsBox, UiUtil.lp(UiUtil.MATCH, UiUtil.WRAP).apply { topMargin = dp(4) })

        // Ссылка + «Скопировать»
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val field = AeroUi.input(EditText(activity)).apply {
            setText(link); textSize = 14f; setSingleLine(true)
            isFocusable = false; isCursorVisible = false
        }
        row.addView(field, UiUtil.lp(0, UiUtil.WRAP, 1f))
        row.addView(smallButton(activity, "Скопировать") {
            val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            cm?.setPrimaryClip(ClipData.newPlainText("Ссылка", link))
            Toast.makeText(activity, "Ссылка скопирована", Toast.LENGTH_SHORT).show()
        }, UiUtil.lp(UiUtil.WRAP, dp(42)).apply { marginStart = dp(8) })
        body.addView(row, UiUtil.lp(UiUtil.MATCH, UiUtil.WRAP).apply { topMargin = dp(12) })

        dlg.footer.addView(dlg.button("В другое приложение") {
            val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, link)
            activity.startActivity(Intent.createChooser(i, title))
        }, UiUtil.lp(UiUtil.MATCH, UiUtil.WRAP))
        dlg.show()

        api.io.execute {
            var chats: List<Chat>? = null
            try {
                if (api.ensureUserId() == 0) {
                    val r = api.get("/api/users/chats/${api.userId}")
                    if (r.code == 200) chats = parseChats(api, JSONObject(r.body))
                }
            } catch (e: Exception) { /* список чатов не критичен: ссылку можно скопировать */ }
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed || !dlg.dialog.isShowing) return@runOnUiThread
                chatsBox.removeAllViews()
                val list = chats
                if (list == null) { chatsBox.addView(status.apply { text = "Не удалось загрузить чаты" }, UiUtil.lp(UiUtil.MATCH, UiUtil.WRAP)); return@runOnUiThread }
                if (list.isEmpty()) { chatsBox.addView(status.apply { text = "Нет чатов" }, UiUtil.lp(UiUtil.MATCH, UiUtil.WRAP)); return@runOnUiThread }
                list.forEach { c -> chatsBox.addView(chatRow(activity, api, c, link), UiUtil.lp(UiUtil.MATCH, UiUtil.WRAP).apply { topMargin = dp(6) }) }
            }
        }
    }

    private fun parseChats(api: SiteApi, o: JSONObject): List<Chat> {
        val out = ArrayList<Chat>()
        fun take(a: JSONArray?) {
            if (a == null) return
            for (i in 0 until a.length()) a.optJSONObject(i)?.let { c ->
                val id = api.str(c, "chatId")
                if (id.isNotBlank()) out.add(Chat(id, api.str(c, "username"), api.str(c, "profilePicture"), api.str(c, "lastMessageTime")))
            }
        }
        take(o.optJSONArray("favouriteChats")); take(o.optJSONArray("userChats"))
        // Свежие переписки выше (время в ISO-8601 — сравнивается как строка).
        return out.sortedByDescending { it.time }
    }

    private fun chatRow(activity: AppCompatActivity, api: SiteApi, c: Chat, link: String): View {
        val d = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * d + 0.5f).toInt()
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = AeroUi.fieldBackground(activity)
            setPadding(dp(10), dp(6), dp(8), dp(6))
        }
        val avatar = ImageView(activity).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = UiUtil.rect(Color.WHITE, 0xFF6C96C0.toInt(), 1, 6, activity)
            setPadding(dp(1), dp(1), dp(1), dp(1))
            clipToOutline = true
        }
        row.addView(avatar, UiUtil.lp(dp(40), dp(40)))
        api.loadAvatar(avatar, c.avatar)
        row.addView(UiUtil.text(activity, 16f, AeroUi.titleColor(activity)).apply {
            text = c.name.ifBlank { "Чат" }; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        }, UiUtil.lp(0, UiUtil.WRAP, 1f).apply { marginStart = dp(10) })
        var sending = false
        var sent = false
        val btn = smallButton(activity, "Поделиться") {}
        btn.setOnClickListener {
            if (sending || sent) return@setOnClickListener
            sending = true
            btn.text = "Отправка…"; btn.alpha = 0.7f
            api.io.execute {
                val ok = try {
                    val body = JSONObject().put("messageText", ChatCrypto.encrypt(c.id, link)).toString()
                    api.send("POST", "/api/chats/messages/${java.net.URLEncoder.encode(c.id, "UTF-8")}", body).code in 200..299
                } catch (e: Exception) { false }
                activity.runOnUiThread {
                    sending = false
                    sent = ok
                    btn.text = if (ok) "Отправлено ✓" else "Ошибка, повторить"
                    btn.alpha = 1f
                }
            }
        }
        row.addView(btn, UiUtil.lp(UiUtil.WRAP, dp(36)).apply { marginStart = dp(8) })
        return row
    }

    private fun smallButton(activity: AppCompatActivity, label: String, click: () -> Unit): TextView {
        val d = activity.resources.displayMetrics.density
        return UiUtil.text(activity, 14f, AeroButton.textColor(activity), bold = true).apply {
            text = label; gravity = Gravity.CENTER
            AeroButton.style(this)
            setPadding((12 * d).toInt(), 0, (12 * d).toInt(), 0)
            setOnClickListener { click() }
        }
    }
}
