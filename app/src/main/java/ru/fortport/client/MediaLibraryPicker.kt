package ru.fortport.client

import android.graphics.Color
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import org.json.JSONObject
import ru.fortport.client.UiUtil.dp

/** Элемент медиатеки пользователя (GET /api/users/photos|gifs|videos|audios). */
class LibItem(val id: Long, val filePath: String, val name: String, val artist: String)

/**
 * Выбор медиа для комментария (как «+» в блоке 3.7 на сайте): фото / GIF / видео / аудио из
 * своей медиатеки либо файл с устройства. Из медиатеки в комментарий уходит ссылка
 * (`existingAttachment` = {path: "/photo/{id}" | "/video/{id}" | "/audio/{id}", type: image|video|audio}),
 * файл не перезаливается.
 */
object MediaLibraryPicker {

    private class Kind(val label: String, val endpoint: String, val key: String, val pathPrefix: String, val type: String)

    private val KINDS = listOf(
        Kind("Фото", "photos", "photos", "/photo/", "image"),
        Kind("GIF", "gifs", "gifs", "/photo/", "image"),
        Kind("Видеозапись", "videos", "videos", "/video/", "video"),
        Kind("Аудио", "audios", "audios", "/audio/", "audio")
    )

    fun show(host: PostHost, pickFromDevice: (done: (Uri) -> Unit) -> Unit, onPicked: (Attachment) -> Unit,
             describeUri: (Uri) -> Attachment) {
        val labels = (KINDS.map { it.label + " из медиатеки" } + "Файл с устройства").toTypedArray()
        AlertDialog.Builder(host.activity)
            .setTitle("Прикрепить")
            .setItems(labels) { _, which ->
                if (which == KINDS.size) pickFromDevice { onPicked(describeUri(it)) }
                else loadAndShow(host, KINDS[which], onPicked)
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun loadAndShow(host: PostHost, kind: Kind, onPicked: (Attachment) -> Unit) {
        val ctx = host.activity
        val loading = AlertDialog.Builder(ctx).setMessage("Загрузка...").show()
        host.repo.pool.execute {
            val items = load(host, kind)
            ctx.runOnUiThread {
                if (ctx.isFinishing || ctx.isDestroyed) return@runOnUiThread
                loading.dismiss()
                when {
                    items == null -> Toast.makeText(ctx, "Ошибка загрузки", Toast.LENGTH_SHORT).show()
                    items.isEmpty() -> Toast.makeText(ctx, "Нет сохранённых файлов", Toast.LENGTH_SHORT).show()
                    else -> showItems(host, kind, items, onPicked)
                }
            }
        }
    }

    private fun load(host: PostHost, kind: Kind): List<LibItem>? = try {
        val r = host.api.get("/api/users/${kind.endpoint}?userId=${host.repo.me}")
        if (r.code != 200) null else {
            val a = JSONObject(r.body).optJSONArray(kind.key)
            val out = ArrayList<LibItem>()
            if (a != null) for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                out.add(
                    LibItem(
                        o.optLong("id"), host.api.str(o, "file_path"),
                        host.api.str(o, "name"), host.api.str(o, "artist_name")
                    )
                )
            }
            out
        }
    } catch (e: Exception) { null }

    private fun showItems(host: PostHost, kind: Kind, items: List<LibItem>, onPicked: (Attachment) -> Unit) {
        val ctx = host.activity
        val box: View
        var dialog: AlertDialog? = null
        val choose = { item: LibItem ->
            val label = when (kind.type) {
                "audio" -> item.name.ifBlank { "Аудио" }
                "video" -> "Видео #${item.id}"
                else -> if (kind.label == "GIF") "GIF #${item.id}" else "Фото #${item.id}"
            }
            onPicked(Attachment(null, label, kind.type, libPath = kind.pathPrefix + item.id, libType = kind.type, isGif = kind.label == "GIF"))
            dialog?.dismiss()
        }
        if (kind.type == "audio") {
            val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(ctx, 8), dp(ctx, 8), dp(ctx, 8), dp(ctx, 8)) }
            items.forEachIndexed { i, item ->
                val row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    background = UiUtil.rect(0xFFF2FAFF.toInt(), 0xFF8FDADB.toInt(), 1, 8, ctx)
                    setPadding(dp(ctx, 10), dp(ctx, 8), dp(ctx, 10), dp(ctx, 8))
                    setOnClickListener { choose(item) }
                }
                row.addView(UiUtil.text(ctx, 15f, UiUtil.TEXT, bold = true, maxLines = 1).apply { text = "${i + 1}. " + item.name.ifBlank { "Без названия" } })
                row.addView(UiUtil.text(ctx, 13f, UiUtil.SUB, maxLines = 1).apply { text = item.artist.ifBlank { "Неизвестно" } })
                col.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(ctx, 6) })
            }
            box = ScrollView(ctx).apply { addView(col) }
        } else {
            val grid = GridLayout(ctx).apply { columnCount = 3; setPadding(dp(ctx, 6), dp(ctx, 6), dp(ctx, 6), dp(ctx, 6)) }
            val size = (ctx.resources.displayMetrics.widthPixels - dp(ctx, 12 + 3 * 8 + 32)) / 3
            items.forEach { item ->
                val cell: View = if (kind.type == "video") {
                    TextView(ctx).apply {
                        text = "▶"; textSize = 28f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
                        setBackgroundColor(0xFF0B0F1A.toInt())
                    }
                } else {
                    ImageView(ctx).apply {
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        setBackgroundColor(0xFFE0E4EC.toInt())
                        host.api.loadImage(this, item.filePath, 256)
                    }
                }
                cell.setOnClickListener { choose(item) }
                val lp = GridLayout.LayoutParams().apply {
                    width = size; height = size
                    setMargins(dp(ctx, 4), dp(ctx, 4), dp(ctx, 4), dp(ctx, 4))
                }
                grid.addView(cell, lp)
            }
            box = ScrollView(ctx).apply { addView(grid) }
        }
        dialog = AlertDialog.Builder(ctx).setTitle(kind.label).setView(box)
            .setNegativeButton("Отмена", null).create()
        dialog.show()
    }
}
