package ru.fortport.client

import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import android.text.InputFilter
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import org.json.JSONArray
import org.json.JSONObject
import ru.fortport.client.UiUtil.dp
import java.io.ByteArrayOutputStream

/**
 * Окно нового поста (на сайте — модальное окно makePostCreator()/createPost() в post.js).
 * Поля: текст, заголовок (уходит в начале текста как {title:"…"}), анонимно, спойлер, 18+,
 * вложения (медиатека сайта или файл с устройства), опрос; на ленте — выбор «Свой профиль / порт».
 * Отправка: POST /api/posts (multipart).
 */
object PostComposer {

    private const val MAX_UPLOAD = 60L * 1024 * 1024
    private const val RECOMPRESS_OVER = 1_500_000

    /**
     * @param fixedCommunity порт, в который публикуем (страница порта); null — личный пост
     * @param allowChoice    показывать выбор «Новый пост в:» (основная лента)
     */
    fun show(
        host: PostHost,
        pickFile: (done: (Uri) -> Unit) -> Unit,
        fixedCommunity: Long?,
        allowChoice: Boolean,
        onPosted: () -> Unit
    ) {
        val act = host.activity
        val repo = host.repo

        val attachments = ArrayList<Attachment>()
        var poll: JSONObject? = null
        var pollTitle = ""
        var community: Long? = fixedCommunity
        var communityName = "Свой профиль"
        var ports: List<Pair<Long, String>> = emptyList()
        var sending = false

        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(act, 4), dp(act, 4), dp(act, 4), dp(act, 4))
        }
        val scroll = ScrollView(act).apply { addView(box) }
        val lblInk = AeroUi.labelColor(act)

        // Куда публикуем (Aero DropDown; на своей странице и в порте элемент скрыт).
        if (allowChoice) {
            repo.pool.execute {
                val list = repo.loadMyPorts()
                act.runOnUiThread { ports = list }
            }
            val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
            row.addView(TextView(act).apply { text = "Выложить в:"; textSize = 15f; setTextColor(lblInk) })
            lateinit var dd: TextView
            dd = AeroUi.dropdown(
                act,
                items = { listOf("Мой профиль") + ports.map { p -> p.second } },
                selected = { if (community == null) 0 else ports.indexOfFirst { it.first == community } + 1 },
                onSelect = { which ->
                    if (which == 0) { community = null; communityName = "Мой профиль" }
                    else { community = ports[which - 1].first; communityName = ports[which - 1].second }
                    dd.text = communityName
                }
            )
            communityName = "Мой профиль"
            dd.text = communityName
            row.addView(AeroUi.dropdownView(dd), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(act, 10) })
            box.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(act, 10) })
        }

        val cbTitle = AeroUi.checkBox(act, "Заголовок")
        val cbAnon = AeroUi.checkBox(act, "Анонимно")
        val cbSpoiler = AeroUi.checkBox(act, "Спойлер")
        val cbNsfw = AeroUi.checkBox(act, "18+")
        val row1 = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        row1.addView(cbTitle, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row1.addView(cbAnon, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val row2 = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        row2.addView(cbSpoiler, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row2.addView(cbNsfw, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        box.addView(row1)
        box.addView(row2)

        // Поля растягиваются вниз по мере ввода, пока не упрутся в ограничение по символам.
        val etSpoiler = AeroUi.input(EditText(act).apply {
            hint = "Текст спойлера"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            filters = arrayOf(InputFilter.LengthFilter(200))
            gravity = android.view.Gravity.TOP
            visibility = View.GONE
        })
        val etTitle = AeroUi.input(EditText(act).apply {
            hint = "Заголовок"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            filters = arrayOf(InputFilter.LengthFilter(100))
            gravity = android.view.Gravity.TOP
            visibility = View.GONE
        })
        val etText = AeroUi.input(EditText(act).apply {
            hint = "Текст поста"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 5
            gravity = android.view.Gravity.TOP
        })
        cbTitle.setOnCheckedChangeListener { _, on -> etTitle.visibility = if (on) View.VISIBLE else View.GONE }
        cbSpoiler.setOnCheckedChangeListener { _, on -> etSpoiler.visibility = if (on) View.VISIBLE else View.GONE }
        box.addView(etTitle, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(act, 10) })
        box.addView(etSpoiler, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(act, 8) })
        box.addView(etText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(act, 8) })

        // Вложения и опрос.
        val files = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            background = AeroUi.fieldBackground(act)
            setPadding(dp(act, 8), dp(act, 4), dp(act, 8), dp(act, 8))
            visibility = View.GONE
        }
        box.addView(files, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(act, 8) })

        fun kindOf(a: Attachment): String = when {
            a.libPath != null -> if (a.isGif) "gif" else (a.libType ?: "image")
            a.mime.equals("image/gif", true) -> "gif"
            a.mime.startsWith("image/") -> "image"
            a.mime.startsWith("video/") -> "video"
            a.mime.startsWith("audio/") -> "audio"
            else -> ""
        }

        lateinit var redrawFiles: () -> Unit
        fun chip(label: String, onRemove: () -> Unit): View = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            background = UiUtil.rect(UiUtil.softBg(act), UiUtil.softStroke(act), 1, 6, act)
            setPadding(dp(act, 10), dp(act, 4), dp(act, 4), dp(act, 4))
            addView(
                UiUtil.text(act, 14f, UiUtil.ink(act), maxLines = 1).apply { text = label },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
            addView(TextView(act).apply {
                text = "✕"; textSize = 18f; setTextColor(0xFFCC3344.toInt())
                setPadding(dp(act, 12), dp(act, 4), dp(act, 12), dp(act, 4))
                setOnClickListener { onRemove() }
            })
        }
        redrawFiles = {
            files.removeAllViews()
            attachments.forEachIndexed { i, a ->
                val icon = when (kindOf(a)) { "video" -> "🎬 "; "audio" -> "🎵 "; "gif" -> "GIF "; else -> "🖼 " }
                files.addView(
                    chip(icon + a.name) { attachments.removeAt(i); redrawFiles() },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                        .apply { topMargin = dp(act, 6) }
                )
            }
            if (poll != null) {
                files.addView(
                    chip("📊 Опрос: $pollTitle") { poll = null; redrawFiles() },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                        .apply { topMargin = dp(act, 6) }
                )
            }
            files.visibility = if (files.childCount > 0) View.VISIBLE else View.GONE
        }

        fun describe(uri: Uri): Attachment {
            var name = "file"
            try {
                act.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) name = c.getString(0) ?: name
                }
            } catch (_: Exception) {}
            return Attachment(uri, name, act.contentResolver.getType(uri).orEmpty())
        }

        /** Правила сайта: до 10 изображений или 10 видео (без смешивания с другими), до 3 аудио. */
        fun tryAdd(a: Attachment) {
            val k = kindOf(a)
            if (k.isEmpty()) { Toast.makeText(act, "Можно прикрепить фото, видео, аудио или GIF", Toast.LENGTH_SHORT).show(); return }
            val kinds = attachments.map { x -> kindOf(x) } + k
            val images = kinds.count { x -> x == "image" || x == "gif" }
            val videos = kinds.count { x -> x == "video" }
            val audios = kinds.count { x -> x == "audio" }
            val err = when {
                videos > 0 && (images > 0 || audios > 0) -> "Нельзя смешивать видео с изображениями или аудио"
                videos > 10 -> "Максимум 10 видео"
                images > 10 -> "Максимум 10 изображений"
                audios > 3 -> "Максимум 3 аудиофайла"
                else -> null
            }
            if (err != null) { Toast.makeText(act, err, Toast.LENGTH_SHORT).show(); return }
            attachments.add(a)
            redrawFiles()
        }

        val btnRow = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        val btnAdd = AeroButton.style(TextView(act).apply {
            text = "+ Медиа"; textSize = 16f; gravity = android.view.Gravity.CENTER
            setPadding(dp(act, 8), dp(act, 11), dp(act, 8), dp(act, 11))
            setOnClickListener {
                MediaLibraryPicker.show(host, pickFile, { a -> tryAdd(a) }, { uri -> describe(uri) }) {
                    showPollDialog(act, pollTitle.ifBlank { null }) { t, json -> pollTitle = t; poll = json; redrawFiles() }
                }
            }
        })
        val send = AeroButton.style(TextView(act).apply {
            text = "Публикация"; textSize = 16f; gravity = android.view.Gravity.CENTER
            setPadding(dp(act, 8), dp(act, 11), dp(act, 8), dp(act, 11))
        })
        btnRow.addView(btnAdd, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(act, 6) })
        btnRow.addView(send, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(act, 6) })
        box.addView(btnRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(act, 12) })

        val root = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            background = AeroUi.panelBackground(act)
            setPadding(dp(act, 14), dp(act, 14), dp(act, 14), dp(act, 14))
        }
        root.addView(TextView(act).apply {
            text = "Новый пост"; textSize = 22f; gravity = android.view.Gravity.CENTER; setTextColor(AeroUi.titleColor(act))
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(act, 10) })
        root.addView(scroll, LinearLayout.LayoutParams(-1, -2))
        val dialog = AlertDialog.Builder(act).setView(root).create()
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        dialog.show()

        send.setOnClickListener {
            if (sending) return@setOnClickListener
            val raw = etText.text.toString().trim()
            val title = if (cbTitle.isChecked) etTitle.text.toString().trim() else ""
            val content = if (title.isNotEmpty()) "{title:\"$title\"} $raw" else raw
            if (content.isBlank() && attachments.isEmpty() && poll == null) {
                Toast.makeText(act, "Напишите что-нибудь или прикрепите файл", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            sending = true
            send.isEnabled = false
            send.text = "Отправка..."
            val snapshot = ArrayList(attachments)
            val pollJson = poll?.toString()
            val spoiler = cbSpoiler.isChecked
            val spoilerText = etSpoiler.text.toString().trim()
            val anon = cbAnon.isChecked
            val nsfw = cbNsfw.isChecked
            val comm = community
            repo.pool.execute {
                var err: String? = null
                val existing = ArrayList<Pair<String, String>>()
                val uploads = ArrayList<PostsRepo.UploadFile>()
                for (a in snapshot) {
                    if (a.libPath != null) { existing.add(a.libPath to kindOf(a)); continue }
                    val up = readUpload(host, a)
                    if (up == null) { err = "Не удалось прочитать файл: ${a.name}"; break }
                    if (up.bytes.size > MAX_UPLOAD) { err = "Файл слишком большой: ${a.name}"; break }
                    uploads.add(up)
                }
                val errMsg: String? = err
                val result = if (errMsg == null) {
                    repo.createPost(PostsRepo.NewPost(content, comm, anon, spoiler, spoilerText, nsfw, pollJson, existing, uploads))
                } else null
                act.runOnUiThread {
                    if (act.isFinishing || act.isDestroyed) return@runOnUiThread
                    sending = false
                    send.isEnabled = true
                    send.text = "Публикация"
                    when {
                        errMsg != null -> Toast.makeText(act, errMsg, Toast.LENGTH_LONG).show()
                        result == null -> {}
                        result.authLost -> { dialog.dismiss(); host.authLost() }
                        !result.ok -> Toast.makeText(act, result.error.ifBlank { "Не удалось создать пост" }, Toast.LENGTH_LONG).show()
                        else -> { dialog.dismiss(); Toast.makeText(act, "Пост опубликован", Toast.LENGTH_SHORT).show(); onPosted() }
                    }
                }
            }
        }
    }

    /** Файл с устройства → байты; большие фото (не GIF) ужимаются до 2048px, как compressImage() на сайте. */
    private fun readUpload(host: PostHost, a: Attachment): PostsRepo.UploadFile? {
        val uri = a.uri ?: return null
        val bytes = try {
            host.activity.contentResolver.openInputStream(uri)?.use { s -> s.readBytes() }
        } catch (e: Exception) { null } ?: return null
        val isPhoto = a.mime.startsWith("image/") && !a.mime.equals("image/gif", true)
        if (isPhoto && bytes.size > RECOMPRESS_OVER) {
            val bmp: Bitmap? = try { host.api.decodeSampled(bytes, 2048) } catch (e: Exception) { null }
            if (bmp != null) {
                val out = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
                val base = a.name.substringBeforeLast('.', a.name)
                return PostsRepo.UploadFile(out.toByteArray(), "$base.jpg", "image/jpeg")
            }
        }
        return PostsRepo.UploadFile(bytes, a.name, a.mime)
    }

    /** Создание опроса (как addMakePoll() на сайте): вопрос, варианты, «несколько ответов», срок. */
    private fun showPollDialog(
        act: androidx.appcompat.app.AppCompatActivity,
        @Suppress("UNUSED_PARAMETER") currentTitle: String?,
        onDone: (title: String, json: JSONObject) -> Unit
    ) {
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(act, 16), dp(act, 8), dp(act, 16), dp(act, 8))
        }
        val etTitle = EditText(act).apply { hint = "Вопрос (Яблоки или кирпичи...)" }
        box.addView(etTitle)
        val choices = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        fun addChoice() {
            val n = choices.childCount + 1
            choices.addView(EditText(act).apply { hint = "Вариант $n" })
        }
        addChoice(); addChoice()
        box.addView(choices)
        box.addView(Button(act).apply {
            text = "+ Добавить вариант"
            setOnClickListener {
                if (choices.childCount >= 10) Toast.makeText(act, "Максимум 10 вариантов", Toast.LENGTH_SHORT).show()
                else addChoice()
            }
        })
        val multi = CheckBox(act).apply { text = "Выбрать несколько"; textSize = 15f }
        box.addView(multi)
        val spans = listOf("Без срока" to 0L, "1 час" to 3600L, "1 день" to 86400L, "3 дня" to 259200L, "7 дней" to 604800L)
        var span = 0L
        val tvSpan = TextView(act).apply {
            textSize = 15f
            setTextColor(if (UiUtil.isNight(act)) android.graphics.Color.WHITE else UiUtil.TEXT)
            text = "Срок действия: без срока ▾"
            setPadding(0, dp(act, 10), 0, dp(act, 10))
        }
        tvSpan.setOnClickListener {
            AlertDialog.Builder(act)
                .setTitle("Срок действия")
                .setItems(spans.map { s -> s.first }.toTypedArray()) { _, which ->
                    span = spans[which].second
                    tvSpan.text = "Срок действия: ${spans[which].first.lowercase()} ▾"
                }
                .show()
        }
        box.addView(tvSpan)

        val d = AlertDialog.Builder(act)
            .setTitle("Создать опрос")
            .setView(ScrollView(act).apply { addView(box) })
            .setPositiveButton("Создать опрос", null)
            .setNegativeButton("Отмена", null)
            .create()
        d.show()
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val title = etTitle.text.toString().trim()
            if (title.isEmpty()) { Toast.makeText(act, "Введите вопрос", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            val texts = ArrayList<String>()
            var empty = false
            for (i in 0 until choices.childCount) {
                val t = (choices.getChildAt(i) as EditText).text.toString().trim()
                if (t.isEmpty()) empty = true else texts.add(t)
            }
            if (empty) { Toast.makeText(act, "Заполните все варианты или оставьте только нужные", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            if (texts.size < 2) { Toast.makeText(act, "Минимум 2 варианта", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            val arr = JSONArray()
            texts.forEach { t -> arr.put(JSONObject().put("text", t)) }
            val json = JSONObject()
                .put("title", title)
                .put("choices", arr)
                .put("multiple_choice", multi.isChecked)
                .put("expires_at", if (span > 0) System.currentTimeMillis() / 1000L + span else JSONObject.NULL)
            d.dismiss()
            onDone(title, json)
        }
    }
}
