package ru.fortport.client

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Outline
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Base64
import android.util.Log
import android.util.LruCache
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.webkit.CookieManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Call
import okio.BufferedSink
import okio.source
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Нативный экран диалога вместо веб-версии чата.
 *
 * Работает с тем же API, что и сайт (scripts/pages/chats.js):
 *  - GET  /api/chats/messages/{chatId}                 — последние 60 сообщений;
 *  - GET  /api/chats/messages/{chatId}/before/{id}     — подгрузка старых;
 *  - GET  /api/chats/{chatId}/wait                     — long-poll новых событий
 *                                                         (new_message / read_all, 204 — повторить);
 *  - POST /api/chats/messages/{chatId}                 — multipart: messageText, referenceId, files;
 *  - PUT  /api/chats/messages/message                  — правка {messageId,newText,deleteFiles};
 *  - DELETE /api/chats/messages/message                — удаление {messageId};
 *  - POST /api/users/chats/read/{chatId}               — пометить прочитанным.
 *
 * Тексты шифруются как на сайте (crypto-utils.js): AES-GCM, ключ = SHA-256(chatId),
 * формат base64(iv[12] + ciphertext+tag). Авторизация — куки WebView + X-Device-Id.
 */
class ChatActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PAYLOAD = "payload"
        private const val TAG = "ChatActivity"
        private const val SITE_ORIGIN = "https://fortport.ru"
        private const val SITE_HOST = "fortport.ru"
        private const val PAGE_SIZE = 60
        private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    private class Msg(
        val id: Long,
        val userId: Long,
        val username: String,
        var text: String,
        val filePaths: List<String>,
        val fileTypes: List<String>,
        var isRead: Boolean,
        val createdAt: Date?,
        val referenceId: Long?
    )

    private sealed class Row {
        class DateRow(val text: String) : Row()
        class MsgRow(val msg: Msg) : Row()
    }

    // --- данные диалога ---
    private var chatId = ""
    private var myId = 0L
    private var deviceId = ""
    private var userAgent = ""
    private var partnerName = ""
    private var partnerAvatar: String? = null

    private val messages = ArrayList<Msg>()
    private var rows: List<Row> = emptyList()
    private var hasMore = true
    private var loadingMore = false
    @Volatile private var active = true
    @Volatile private var pollCall: Call? = null

    private var replyTo: Msg? = null
    private var editing: Msg? = null
    private val pendingFiles = ArrayList<Uri>()

    // --- сеть ---
    private val io = Executors.newFixedThreadPool(2)
    private val imgPool = Executors.newFixedThreadPool(3)
    private val pollThread = Executors.newSingleThreadExecutor()
    private lateinit var client: OkHttpClient
    private lateinit var pollClient: OkHttpClient
    private val imgCache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 8L).toInt().coerceAtLeast(4 * 1024 * 1024)
    ) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    // --- вью ---
    private lateinit var list: RecyclerView
    private lateinit var layoutManager: LinearLayoutManager
    private lateinit var adapter: ChatAdapter
    private lateinit var input: EditText
    private lateinit var sendBtn: TextView
    private lateinit var loadingBar: ProgressBar
    private lateinit var replyBar: View
    private lateinit var replyText: TextView
    private lateinit var attachInfo: TextView

    private val pickFiles = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (!uris.isNullOrEmpty()) {
            pendingFiles.addAll(uris)
            updateAttachInfo()
        }
    }

    private val utcFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val dayFormat = SimpleDateFormat("d MMMM", Locale("ru"))
    private val dayKeyFormat = SimpleDateFormat("yyyyMMdd", Locale.US)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!parsePayload()) {
            finish()
            return
        }
        setContentView(R.layout.activity_chat)
        buildClients()

        list = findViewById(R.id.chatList)
        input = findViewById(R.id.chatInput)
        sendBtn = findViewById(R.id.chatSend)
        loadingBar = findViewById(R.id.chatLoading)
        replyBar = findViewById(R.id.chatReplyBar)
        replyText = findViewById(R.id.chatReplyText)
        attachInfo = findViewById(R.id.chatAttachInfo)

        findViewById<TextView>(R.id.chatName).text = partnerName
        findViewById<TextView>(R.id.chatBack).setOnClickListener { finish() }
        val avatar = findViewById<ImageView>(R.id.chatAvatar)
        roundOutline(avatar, 10f)
        // Сразу показываем штатный ассет, чтобы нативный placeholder не мигал
        // и аватар не оставался пустым во время загрузки/при сетевой ошибке.
        setDefaultAvatar(avatar)
        partnerAvatar?.let { loadImage(avatar, absUrl(it), 200) }

        layoutManager = LinearLayoutManager(this).apply { stackFromEnd = false }
        adapter = ChatAdapter()
        list.layoutManager = layoutManager
        list.adapter = adapter
        list.itemAnimator = null
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dy < 0 && layoutManager.findFirstVisibleItemPosition() <= 2) loadMore()
            }
        })

        sendBtn.setOnClickListener { send() }
        findViewById<View>(R.id.chatAttach).setOnClickListener { pickFiles.launch("*/*") }
        findViewById<View>(R.id.chatReplyClose).setOnClickListener {
            if (editing != null) cancelEdit() else setReply(null)
        }
        attachInfo.setOnClickListener {
            pendingFiles.clear()
            updateAttachInfo()
        }

        loadInitial()
    }

    override fun onDestroy() {
        active = false
        pollCall?.cancel()
        io.shutdownNow()
        imgPool.shutdownNow()
        pollThread.shutdownNow()
        super.onDestroy()
    }

    private fun parsePayload(): Boolean {
        return try {
            val o = JSONObject(intent.getStringExtra(EXTRA_PAYLOAD) ?: return false)
            chatId = o.optString("chatId", "")
            myId = o.optString("myId", "0").toLongOrNull() ?: 0L
            deviceId = o.optString("deviceId", "")
            userAgent = o.optString("ua", "")
            partnerName = o.optString("name", "")
            partnerAvatar = o.optString("avatar", "").takeIf { it.isNotBlank() && it != "null" }
            // chatId используется в пути запроса и как источник ключа шифрования — только цифры и «_».
            chatId.isNotEmpty() && chatId.all { it.isDigit() || it == '_' }
        } catch (e: Exception) {
            Log.w(TAG, "Некорректные данные чата", e)
            false
        }
    }

    // ------------------------------------------------------------------ сеть

    private fun buildClients() {
        val base = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val req = chain.request()
                if (req.url.host != SITE_HOST) return@addInterceptor chain.proceed(req)
                val b = req.newBuilder()
                if (userAgent.isNotBlank()) b.header("User-Agent", userAgent)
                if (deviceId.isNotBlank()) b.header("X-Device-Id", deviceId)
                CookieManager.getInstance().getCookie(SITE_ORIGIN)?.let { b.header("Cookie", it) }
                val resp = chain.proceed(b.build())
                // Сервер может обновить сессионные куки — отдаём их обратно в WebView.
                val set = resp.headers("Set-Cookie")
                if (set.isNotEmpty()) {
                    set.forEach { CookieManager.getInstance().setCookie(SITE_ORIGIN, it) }
                    CookieManager.getInstance().flush()
                }
                resp
            }
            .build()
        client = base
        pollClient = base.newBuilder().readTimeout(75, TimeUnit.SECONDS).build()
    }

    private class Resp(val code: Int, val body: String)

    private fun call(req: Request, c: OkHttpClient = client): Resp {
        c.newCall(req).execute().use { r -> return Resp(r.code, r.body?.string().orEmpty()) }
    }

    private fun get(path: String) = call(Request.Builder().url(SITE_ORIGIN + path).get().build())

    private fun main(block: () -> Unit) {
        runOnUiThread { if (!isFinishing && !isDestroyed) block() }
    }

    private fun onAuthLost() = main {
        Toast.makeText(this, "Сессия недействительна. Войдите заново.", Toast.LENGTH_LONG).show()
        finish()
    }

    // ------------------------------------------------------------- шифрование

    private fun key(): SecretKeySpec =
        SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(chatId.toByteArray(Charsets.UTF_8)), "AES")

    private fun encrypt(text: String): String {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(), GCMParameterSpec(128, iv))
        val ct = cipher.doFinal(text.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv + ct, Base64.NO_WRAP)
    }

    /** Как decryptMessageIfNeeded() на сайте; при неудаче показываем исходный текст. */
    private fun decrypt(raw: String): String {
        if (raw.length <= 20) return raw
        return try {
            val data = Base64.decode(raw, Base64.DEFAULT)
            if (data.size <= 12) return raw
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data.copyOfRange(0, 12)))
            String(cipher.doFinal(data, 12, data.size - 12), Charsets.UTF_8)
        } catch (e: Exception) {
            raw
        }
    }

    // ------------------------------------------------------------ разбор JSON

    private fun str(o: JSONObject, k: String) = if (o.isNull(k)) "" else o.optString(k, "")

    private fun parseMsg(o: JSONObject): Msg {
        val paths = ArrayList<String>()
        val types = ArrayList<String>()
        o.optJSONArray("filePaths")?.let { a ->
            val t = o.optJSONArray("fileTypes") ?: JSONArray()
            for (i in 0 until a.length()) {
                paths.add(a.optString(i, ""))
                types.add(t.optString(i, "image").ifBlank { "image" })
            }
        }
        return Msg(
            id = o.optLong("id"),
            userId = o.optLong("userId"),
            username = str(o, "username"),
            text = decrypt(str(o, "messageText")),
            filePaths = paths,
            fileTypes = types,
            isRead = o.optBoolean("isRead", false) || o.optInt("isRead", 0) == 1,
            createdAt = try { utcFormat.parse(str(o, "createdAt")) } catch (e: Exception) { null },
            referenceId = if (o.isNull("referenceId")) null else o.optLong("referenceId").takeIf { it > 0 }
        )
    }

    private fun parseMessages(json: String): List<Msg> {
        val arr = JSONObject(json).optJSONArray("messages") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(::parseMsg) }
    }

    // ----------------------------------------------------------------- загрузка

    private fun loadInitial() {
        loadingBar.visibility = View.VISIBLE
        io.execute {
            try {
                val r = get("/api/chats/messages/$chatId")
                if (r.code == 401 || r.code == 403) { onAuthLost(); return@execute }
                if (r.code !in 200..299) throw IOException("HTTP ${r.code}")
                val page = parseMessages(r.body)
                main {
                    messages.clear()
                    messages.addAll(page.sortedBy { it.id })
                    hasMore = page.size >= PAGE_SIZE
                    rebuildRows()
                    scrollToBottom()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Не удалось загрузить сообщения", e)
                main { Toast.makeText(this, "Не удалось загрузить сообщения", Toast.LENGTH_SHORT).show() }
            } finally {
                main { loadingBar.visibility = View.GONE }
            }
            markRead()
            startPolling()
        }
    }

    private fun loadMore() {
        if (loadingMore || !hasMore || messages.isEmpty()) return
        loadingMore = true
        val oldest = messages.first().id
        io.execute {
            try {
                val r = get("/api/chats/messages/$chatId/before/$oldest")
                if (r.code !in 200..299) throw IOException("HTTP ${r.code}")
                val page = parseMessages(r.body)
                main {
                    val oldRows = rows.size
                    val first = layoutManager.findFirstVisibleItemPosition()
                    val top = list.getChildAt(0)?.top ?: 0
                    val known = messages.map { it.id }.toHashSet()
                    messages.addAll(page.filter { it.id !in known })
                    messages.sortBy { it.id }
                    hasMore = page.size >= PAGE_SIZE
                    rebuildRows()
                    val added = rows.size - oldRows
                    if (first >= 0) layoutManager.scrollToPositionWithOffset(first + added, top)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Не удалось подгрузить старые сообщения", e)
            } finally {
                main { loadingMore = false }
            }
        }
    }

    /** Подтягивает последние сообщения и вливает в список (после отправки/правки/удаления). */
    private fun refreshLatest(scroll: Boolean) {
        io.execute {
            try {
                val r = get("/api/chats/messages/$chatId")
                if (r.code !in 200..299) return@execute
                val page = parseMessages(r.body)
                main {
                    val oldestFresh = page.minOfOrNull { it.id } ?: 0L
                    val freshIds = page.map { it.id }.toHashSet()
                    // Удалённые сообщения: в диапазоне свежей страницы, но отсутствующие в ней.
                    messages.removeAll { it.id >= oldestFresh && it.id !in freshIds }
                    mergeIn(page)
                    if (scroll) scrollToBottom()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Не удалось обновить сообщения", e)
            }
        }
    }

    private fun mergeIn(page: List<Msg>) {
        val byId = messages.associateBy { it.id }.toMutableMap()
        for (m in page) {
            val old = byId[m.id]
            if (old != null) {
                old.text = m.text
                old.isRead = m.isRead
            } else {
                messages.add(m)
            }
        }
        messages.sortBy { it.id }
        rebuildRows()
    }

    private fun markRead() {
        try {
            call(Request.Builder().url("$SITE_ORIGIN/api/users/chats/read/$chatId")
                .post(ByteArray(0).toRequestBody(null)).build())
        } catch (e: Exception) {
            Log.w(TAG, "Не удалось пометить прочитанным", e)
        }
    }

    private fun startPolling() {
        pollThread.execute {
            var failures = 0
            while (active) {
                try {
                    val call = pollClient.newCall(
                        Request.Builder().url("$SITE_ORIGIN/api/chats/$chatId/wait").get().build()
                    )
                    pollCall = call
                    call.execute().use { r ->
                        if (r.code == 401 || r.code == 403) { onAuthLost(); active = false; return@use }
                        if (r.code == 204) { failures = 0; return@use }
                        if (r.code !in 200..299) throw IOException("HTTP ${r.code}")
                        failures = 0
                        val o = JSONObject(r.body?.string().orEmpty())
                        when (o.optString("type")) {
                            "new_message" -> o.optJSONObject("message")?.let { handleIncoming(parseMsg(it)) }
                            "read_all" -> main {
                                messages.forEach { if (it.userId == myId) it.isRead = true }
                                rebuildRows()
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (!active) break
                    failures++
                    Log.w(TAG, "Ошибка long-poll", e)
                    try { Thread.sleep(minOf(10_000L, 2_000L * failures)) } catch (ie: InterruptedException) { break }
                }
            }
        }
    }

    private fun handleIncoming(m: Msg) {
        main {
            if (messages.any { it.id == m.id }) return@main
            val nearBottom = layoutManager.findLastVisibleItemPosition() >= rows.size - 2
            mergeIn(listOf(m))
            if (nearBottom || m.userId == myId) scrollToBottom()
        }
        if (m.userId != myId) io.execute { markRead() }
    }

    // -------------------------------------------------------------- отправка

    private fun send() {
        val text = input.text.toString().trim()
        val edit = editing
        if (edit != null) {
            if (text.isEmpty()) return
            sendBtn.isEnabled = false
            io.execute {
                try {
                    // Как на сайте: при правке newText уходит как есть.
                    val body = JSONObject()
                        .put("messageId", edit.id).put("newText", text).put("deleteFiles", JSONArray())
                        .toString().toRequestBody(JSON_TYPE)
                    val r = call(Request.Builder().url("$SITE_ORIGIN/api/chats/messages/message").put(body).build())
                    if (r.code !in 200..299) throw IOException("HTTP ${r.code}")
                    main { cancelEdit() }
                    refreshLatest(false)
                } catch (e: Exception) {
                    Log.w(TAG, "Не удалось изменить сообщение", e)
                    main { Toast.makeText(this, "Не удалось изменить сообщение", Toast.LENGTH_SHORT).show() }
                } finally {
                    main { sendBtn.isEnabled = true }
                }
            }
            return
        }
        if (text.isEmpty() && pendingFiles.isEmpty()) return
        val ref = replyTo?.id
        val files = pendingFiles.toList()
        sendBtn.isEnabled = false
        io.execute {
            try {
                val mp = MultipartBody.Builder().setType(MultipartBody.FORM)
                if (text.isNotEmpty()) mp.addFormDataPart("messageText", encrypt(text))
                if (ref != null) mp.addFormDataPart("referenceId", ref.toString())
                for (uri in files) {
                    val mime = contentResolver.getType(uri) ?: "application/octet-stream"
                    mp.addFormDataPart("files", fileName(uri), UriBody(uri, mime))
                }
                val r = call(
                    Request.Builder().url("$SITE_ORIGIN/api/chats/messages/$chatId").post(mp.build()).build()
                )
                if (r.code !in 200..299) throw IOException("HTTP ${r.code}")
                main {
                    input.setText("")
                    pendingFiles.clear()
                    updateAttachInfo()
                    setReply(null)
                }
                refreshLatest(true)
            } catch (e: Exception) {
                Log.w(TAG, "Не удалось отправить сообщение", e)
                main { Toast.makeText(this, "Не удалось отправить сообщение", Toast.LENGTH_SHORT).show() }
            } finally {
                main { sendBtn.isEnabled = true }
            }
        }
    }

    private inner class UriBody(private val uri: Uri, private val mime: String) : RequestBody() {
        override fun contentType() = mime.toMediaTypeOrNull()
        override fun writeTo(sink: BufferedSink) {
            contentResolver.openInputStream(uri)?.use { sink.writeAll(it.source()) }
                ?: throw IOException("Не удалось открыть файл")
        }
    }

    private fun fileName(uri: Uri): String {
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.let { return it }
            }
        } catch (e: Exception) { /* имя по умолчанию */ }
        return uri.lastPathSegment ?: "file"
    }

    private fun updateAttachInfo() {
        if (pendingFiles.isEmpty()) {
            attachInfo.visibility = View.GONE
        } else {
            attachInfo.text = "📎 Вложений: ${pendingFiles.size} (нажмите, чтобы убрать)"
            attachInfo.visibility = View.VISIBLE
        }
    }

    private fun setReply(m: Msg?) {
        replyTo = m
        if (m == null) {
            replyBar.visibility = View.GONE
        } else {
            replyText.text = "↩ ${m.username}: ${snippet(m)}"
            replyBar.visibility = View.VISIBLE
        }
    }

    private fun startEdit(m: Msg) {
        editing = m
        setReply(null)
        replyText.text = "Редактирование сообщения"
        replyBar.visibility = View.VISIBLE
        input.setText(m.text)
        input.setSelection(input.text.length)
        input.requestFocus()
    }

    private fun cancelEdit() {
        editing = null
        input.setText("")
        replyBar.visibility = if (replyTo != null) View.VISIBLE else View.GONE
        replyTo?.let { replyText.text = "↩ ${it.username}: ${snippet(it)}" }
    }

    private fun deleteMessage(m: Msg) {
        AlertDialog.Builder(this)
            .setMessage("Удалить сообщение?")
            .setPositiveButton("Удалить") { _, _ ->
                io.execute {
                    try {
                        val body = JSONObject().put("messageId", m.id).toString().toRequestBody(JSON_TYPE)
                        val r = call(
                            Request.Builder().url("$SITE_ORIGIN/api/chats/messages/message").delete(body).build()
                        )
                        if (r.code !in 200..299) throw IOException("HTTP ${r.code}")
                        main {
                            messages.removeAll { it.id == m.id }
                            rebuildRows()
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Не удалось удалить сообщение", e)
                        main { Toast.makeText(this, "Не удалось удалить сообщение", Toast.LENGTH_SHORT).show() }
                    }
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun showMessageMenu(m: Msg) {
        val mine = m.userId == myId
        val labels = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()
        labels.add("Ответить"); actions.add { cancelEditSilently(); setReply(m); input.requestFocus() }
        if (m.text.isNotBlank()) {
            labels.add("Копировать")
            actions.add {
                (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("message", m.text))
            }
        }
        if (mine && m.text.isNotBlank()) { labels.add("Изменить"); actions.add { startEdit(m) } }
        if (mine) { labels.add("Удалить"); actions.add { deleteMessage(m) } }
        AlertDialog.Builder(this)
            .setItems(labels.toTypedArray()) { _, which -> actions[which]() }
            .show()
    }

    private fun cancelEditSilently() {
        if (editing != null) { editing = null; input.setText("") }
    }

    // -------------------------------------------------------------- отрисовка

    private fun snippet(m: Msg): String {
        if (m.text.isNotBlank()) return m.text
        return when (m.fileTypes.firstOrNull()) {
            "video" -> "[Видео]"
            "audio" -> "[Аудио]"
            "gif" -> "[GIF]"
            null -> ""
            else -> "[Фото]"
        }
    }

    private fun rebuildRows() {
        val out = ArrayList<Row>(messages.size + 8)
        var lastKey: String? = null
        for (m in messages) {
            val d = m.createdAt
            if (d != null) {
                val key = dayKeyFormat.format(d)
                if (key != lastKey) {
                    out.add(Row.DateRow(dayFormat.format(d)))
                    lastKey = key
                }
            }
            out.add(Row.MsgRow(m))
        }
        rows = out
        adapter.notifyDataSetChanged()
    }

    private fun scrollToBottom() {
        if (rows.isNotEmpty()) list.scrollToPosition(rows.size - 1)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun roundOutline(v: View, radiusDp: Float) {
        val r = radiusDp * resources.displayMetrics.density
        v.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, r)
            }
        }
        v.clipToOutline = true
    }

    private fun absUrl(path: String): String =
        if (path.startsWith("http")) path else SITE_ORIGIN + (if (path.startsWith("/")) path else "/$path")

    private fun isSiteUrl(url: String): Boolean = try {
        val u = Uri.parse(url)
        u.scheme == "https" && (u.host == SITE_HOST || u.host?.endsWith(".$SITE_HOST") == true)
    } catch (e: Exception) { false }

    /** Простейшая загрузка картинок с кэшем в памяти (куки/X-Device-Id добавляет interceptor). */
    private fun setDefaultAvatar(iv: ImageView) {
        try {
            assets.open("ui/default-avatar.jpg").use { stream ->
                val bitmap = BitmapFactory.decodeStream(stream)
                if (bitmap != null) iv.setImageBitmap(bitmap)
                else iv.setImageResource(R.drawable.avatar_placeholder)
            }
        } catch (e: Exception) {
            // Резерв на случай, если ассет отсутствует в сборке.
            iv.setImageResource(R.drawable.avatar_placeholder)
            Log.w(TAG, "Не удалось открыть ui/default-avatar.jpg", e)
        }
    }

    private fun loadImage(iv: ImageView, url: String, maxSide: Int) {
        if (!isSiteUrl(url)) return
        iv.tag = url
        imgCache.get(url)?.let { iv.setImageBitmap(it); return }
        setDefaultAvatar(iv)
        imgPool.execute {
            try {
                val bytes = client.newCall(Request.Builder().url(url).get().build()).execute().use { r ->
                    if (!r.isSuccessful) return@execute
                    r.body?.bytes() ?: return@execute
                }
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= maxSide && bounds.outHeight / (sample * 2) >= maxSide) sample *= 2
                val bmp = BitmapFactory.decodeByteArray(
                    bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }
                ) ?: return@execute
                imgCache.put(url, bmp)
                main { if (iv.tag == url) iv.setImageBitmap(bmp) }
            } catch (e: Exception) {
                Log.w(TAG, "Не удалось загрузить картинку $url", e)
            }
        }
    }

    private fun openPhoto(m: Msg, index: Int) {
        val items = JSONArray()
        var selected = 0
        m.filePaths.forEachIndexed { i, p ->
            val t = m.fileTypes.getOrNull(i) ?: "image"
            if (t == "image" || t == "gif") {
                if (i == index) selected = items.length()
                val mediaId = Regex("^/photo/(\\d+)").find(p)?.groupValues?.get(1)?.toLongOrNull()
                items.put(JSONObject().put("url", absUrl(p)).put("type", t).put("mediaId", mediaId ?: JSONObject.NULL))
            }
        }
        if (items.length() == 0) return
        startActivity(
            Intent(this, PhotoViewerActivity::class.java).putExtra(
                PhotoViewerActivity.EXTRA_PAYLOAD,
                JSONObject().put("items", items).put("index", selected)
                    .put("deviceId", deviceId).put("ua", userAgent).toString()
            )
        )
    }

    private fun openExternal(url: String) {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (e: Exception) {
            Toast.makeText(this, "Не удалось открыть файл", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------------------------------------------------------------- адаптер

    private inner class ChatAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = rows.size
        override fun getItemViewType(position: Int) = if (rows[position] is Row.DateRow) 0 else 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inf = LayoutInflater.from(parent.context)
            return if (viewType == 0) DateHolder(inf.inflate(R.layout.item_chat_date, parent, false))
            else MsgHolder(inf.inflate(R.layout.item_chat_message, parent, false))
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = rows[position]) {
                is Row.DateRow -> (holder as DateHolder).text.text = row.text
                is Row.MsgRow -> (holder as MsgHolder).bind(row.msg)
            }
        }
    }

    private inner class DateHolder(v: View) : RecyclerView.ViewHolder(v) {
        val text: TextView = v.findViewById(R.id.dateText)
    }

    private inner class MsgHolder(val root: View) : RecyclerView.ViewHolder(root) {
        private val bubble: LinearLayout = root.findViewById(R.id.msgBubble)
        private val reply: TextView = root.findViewById(R.id.msgReply)
        private val attachments: LinearLayout = root.findViewById(R.id.msgAttachments)
        private val text: TextView = root.findViewById(R.id.msgText)
        private val meta: TextView = root.findViewById(R.id.msgMeta)

        fun bind(m: Msg) {
            val mine = m.userId == myId
            // Для сообщений с цитатой даём пузырю больше ширины: длинный исходный
            // текст занимает меньше строк и не превращает пузырь в узкую высокую колонку.
            val hasReply = m.referenceId != null
            val oppositeSidePadding = if (hasReply) dp(12) else dp(56)
            root.setPadding(
                if (mine) oppositeSidePadding else dp(8),
                root.paddingTop,
                if (mine) dp(8) else oppositeSidePadding,
                root.paddingBottom
            )
            val bubbleParams = bubble.layoutParams as FrameLayout.LayoutParams
            // Ширина цитаты не должна зависеть от длины текста ответа: для ответов
            // растягиваем весь пузырь на доступную ширину родителя.
            bubbleParams.width = if (hasReply) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT
            bubbleParams.gravity = if (mine) Gravity.END else Gravity.START
            bubble.layoutParams = bubbleParams
            bubble.setBackgroundResource(if (mine) R.drawable.bg_bubble_mine else R.drawable.bg_bubble_theirs)

            val ref = m.referenceId?.let { id -> messages.firstOrNull { it.id == id } }
            if (ref != null) {
                reply.text = "${ref.username}\n${snippet(ref)}"
                reply.visibility = View.VISIBLE
            } else {
                reply.visibility = View.GONE
            }

            attachments.removeAllViews()
            m.filePaths.forEachIndexed { i, path ->
                val type = m.fileTypes.getOrNull(i) ?: "image"
                val url = absUrl(path)
                if (!isSiteUrl(url)) return@forEachIndexed
                if (type == "image" || type == "gif") {
                    val iv = ImageView(root.context).apply {
                        layoutParams = LinearLayout.LayoutParams(dp(220), dp(165)).apply { bottomMargin = dp(6) }
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        setBackgroundColor(0x33000000)
                        roundOutline(this, 12f)
                        setOnClickListener { openPhoto(m, i) }
                        setOnLongClickListener { showMessageMenu(m); true }
                    }
                    attachments.addView(iv)
                    loadImage(iv, url, 600)
                } else {
                    val label = if (type == "video") "▶ Видео" else "♪ Аудио"
                    attachments.addView(TextView(root.context).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply { bottomMargin = dp(6) }
                        this.text = "$label (открыть)"
                        setTextColor(0xFF9CC7E8.toInt())
                        textSize = 15f
                        setPadding(dp(4), dp(8), dp(4), dp(8))
                        setOnClickListener { openExternal(url) }
                        setOnLongClickListener { showMessageMenu(m); true }
                    })
                }
            }

            if (m.text.isBlank()) {
                text.visibility = View.GONE
            } else {
                text.visibility = View.VISIBLE
                text.text = m.text
            }
            val time = m.createdAt?.let { timeFormat.format(it) }.orEmpty()
            meta.text = if (mine) "$time  ${if (m.isRead) "✓✓" else "✓"}" else time

            bubble.setOnLongClickListener { showMessageMenu(m); true }
            text.setOnLongClickListener { showMessageMenu(m); true }
        }
    }
}
