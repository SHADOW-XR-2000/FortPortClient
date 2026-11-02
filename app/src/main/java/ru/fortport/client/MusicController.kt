package ru.fortport.client

import android.animation.ObjectAnimator
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Нативная вкладка «Музыка» вместо веб-страницы /music (scripts/pages/audios.js).
 *
 * API:
 *  - GET    /api/users/audios?userId=…       — {audios[]} библиотека пользователя;
 *  - POST   /api/audios (multipart: audio, name, artistName) → {success, audio:{id}};
 *  - POST   /api/users/audios/{id}           — добавить загруженный трек в библиотеку;
 *  - DELETE /api/users/audios/{id}           — убрать трек из библиотеки;
 *  - PUT    /api/audios/{id} {name, artistName, genre} — правка своего трека;
 *  - файл трека — {file_path}, например /audios/123.mp3.
 *
 * Музыка продолжает играть при переходе на другие вкладки; управление — только на этой вкладке.
 */
class MusicController(
    private val activity: AppCompatActivity,
    private val api: SiteApi,
    private val swipe: SwipeRefreshLayout,
    private val recycler: RecyclerView,
    private val emptyView: TextView,
    private val bg: ImageView,
    private val disc: ImageView,
    private val titleView: TextView,
    private val artistView: TextView,
    private val timeCurrent: TextView,
    private val timeTotal: TextView,
    private val seek: GradientSeekBar,
    private val btnShuffle: ImageButton,
    private val btnPrev: ImageButton,
    private val btnPlay: ImageButton,
    private val btnNext: ImageButton,
    private val btnRepeat: ImageButton,
    private val onLibraryChanged: () -> Unit,
    private val onAuthLost: () -> Unit
) : PlaybackCore.Listener {
    companion object {
        private const val TAG = "Music"
        private const val TICK_MS = 250L
        private const val INACTIVE_ALPHA = 0.45f
        private const val REQUEST_NOTIFICATIONS = 4712
    }

    private val ui = Handler(Looper.getMainLooper())
    private val transfers = Executors.newSingleThreadExecutor()
    private val core = PlaybackCore

    private var audios: List<MusicTrack> = emptyList()

    /** Чья библиотека открыта: null — своя. */
    var viewedUserId: String? = null; private set
    var viewedName: String = ""; private set
    private var bioDone = false
    private var askedNotifications = false

    /** Своя ли библиотека (id совпадает с id пользователя). */
    val isOwnLibrary: Boolean get() = viewedUserId == null || viewedUserId == api.userId
    private fun ownerKey(): String = viewedUserId ?: api.userId

    private var started = false      // вкладка на экране
    private var loadedOnce = false
    private var bgCheckPending = false
    private var generation = 0
    private var destroyed = false

    private val discAnim = ObjectAnimator.ofFloat(disc, View.ROTATION, 0f, 360f).apply {
        duration = 4000L
        interpolator = LinearInterpolator()
        repeatCount = ObjectAnimator.INFINITE
    }

    private val adapter = Adapter()

    private val pickAudio = activity.registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (!uris.isNullOrEmpty()) uploadFiles(uris)
    }

    private val tick = object : Runnable {
        override fun run() {
            updateProgress()
            if (started && core.wantPlay && core.prepared) ui.postDelayed(this, TICK_MS)
        }
    }

    init {
        core.init(activity)
        recycler.layoutManager = LinearLayoutManager(activity)
        recycler.adapter = adapter
        recycler.itemAnimator = null
        val d = activity.resources.displayMetrics.density
        // Список начинается под плашкой плеера — индикатор обновления рисуем под ней.
        swipe.setProgressViewOffset(false, (260 * d).toInt(), (322 * d).toInt())
        swipe.setColorSchemeResources(R.color.fp_accent)
        swipe.setDistanceToTriggerSync((32 * d).toInt())
        swipe.setSize(SwipeRefreshLayout.LARGE)
        swipe.setOnRefreshListener { load(silent = false) }

        // Диск: картинка из ассетов (та же, что на сайте).
        api.io.execute {
            val bmp = try {
                activity.assets.open("ui/big_disc.webp").use { it.readBytes() }.let { api.decodeSampled(it, 900) }
            } catch (e: Exception) { null }
            if (bmp != null) activity.runOnUiThread { if (!destroyed) disc.setImageBitmap(bmp) }
        }

        seek.listener = object : GradientSeekBar.Listener {
            override fun onScrub(fraction: Float) {
                val dur = core.duration()
                if (dur > 0) timeCurrent.text = fmt((fraction * dur).toInt())
            }

            override fun onSeekFinished(fraction: Float) {
                val dur = core.duration()
                if (dur > 0) core.seekTo((fraction * dur).toInt())
                updateProgress()
            }
        }
        btnPlay.setOnClickListener { onPlayClicked() }
        btnNext.setOnClickListener { core.next() }
        btnPrev.setOnClickListener { core.previous() }
        btnShuffle.setOnClickListener { core.toggleShuffle() }
        btnRepeat.setOnClickListener { core.toggleRepeat() }
        core.addListener(this)
        syncFromCore()
    }

    // ------------------------------------------------------------------ чья библиотека

    /** null — своя. Возвращает true, если открытая библиотека сменилась. */
    fun setViewedUser(id: String?): Boolean {
        val n = id?.takeIf { it.isNotBlank() }
        if (n == viewedUserId) return false
        viewedUserId = n
        viewedName = ""
        bioDone = false
        loadedOnce = false
        generation++
        audios = emptyList()
        emptyView.visibility = View.GONE
        adapter.notifyDataSetChanged()
        swipe.isRefreshing = false
        if (n == null) bg.setImageBitmap(api.ownBackgroundBitmap())
        return true
    }

    // ------------------------------------------------------------------ жизненный цикл

    fun start(userId: String, deviceId: String, userAgent: String) {
        api.setSession(userId, deviceId, userAgent)
        core.configure(userAgent, deviceId)
        destroyed = false
        if (!started) {
            started = true
            bgCheckPending = true
            if (!loadedOnce) swipe.isRefreshing = true
            syncFromCore()
        }
        load(silent = true)
    }

    /** Вкладка ушла с экрана: опрос прекращаем, музыка продолжает играть. */
    fun stop() {
        started = false
        generation++
        swipe.isRefreshing = false
        ui.removeCallbacks(tick)
        syncDisc()
    }

    fun destroy() {
        stop()
        destroyed = true
        core.removeListener(this)
        discAnim.cancel()
        transfers.shutdownNow()
    }

    /** Кнопка «загрузить аудио с устройства» в шапке раздела (только в своей библиотеке). */
    fun pickFromDevice() {
        if (!isOwnLibrary) return
        try {
            pickAudio.launch("audio/*")
        } catch (e: Exception) {
            Log.w(TAG, "Не удалось открыть выбор файлов", e)
            Toast.makeText(activity, "Не удалось открыть выбор файлов", Toast.LENGTH_SHORT).show()
        }
    }

    // ------------------------------------------------------------------ загрузка списка

    private fun load(silent: Boolean) {
        val gen = ++generation
        val viewed = viewedUserId
        api.io.execute {
            var ok = false
            var auth = false
            var list: List<MusicTrack> = emptyList()
            var name: String? = null
            var otherBg: android.graphics.Bitmap? = null
            var bioLoaded = false
            try {
                when (api.ensureUserId()) {
                    1 -> auth = true
                    2 -> {}
                    else -> {
                        val target = viewed ?: api.userId
                        val r = api.get("/api/users/audios?userId=$target")
                        if (r.code == 401 || r.code == 403) auth = true
                        else if (r.code == 200) {
                            list = parse(JSONObject(r.body).optJSONArray("audios"))
                            ok = true
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Не удалось загрузить аудио", e)
            }
            if (ok) {
                val own = viewed == null || viewed == api.userId
                if (own) {
                    if (bgCheckPending) {
                        bgCheckPending = false
                        try { api.refreshBackground() } catch (e: Exception) { Log.w(TAG, "Не удалось обновить фон", e) }
                    }
                } else if (!bioDone) {
                    // Чужая библиотека: имя и фон хозяина страницы, как на сайте.
                    try {
                        val r = api.get("/api/users/bio/$viewed")
                        if (r.code == 200) {
                            val o = JSONObject(r.body)
                            name = api.str(o, "username")
                            val bgPath = api.str(o, "profileBackground")
                            if (bgPath.isNotBlank()) otherBg = api.downloadBitmap(bgPath, 1600)
                            bioLoaded = true
                        }
                    } catch (e: Exception) { Log.w(TAG, "Не удалось загрузить профиль владельца", e) }
                }
            }
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed || gen != generation || !started) return@runOnUiThread
                swipe.isRefreshing = false
                when {
                    auth -> onAuthLost()
                    ok -> {
                        loadedOnce = true
                        if (bioLoaded) {
                            bioDone = true
                            viewedName = name.orEmpty()
                            bg.setImageBitmap(otherBg ?: api.defaultBackgroundBitmap())
                        }
                        applyList(list)
                        onLibraryChanged()
                    }
                    !silent && !loadedOnce -> Toast.makeText(activity, "Не удалось загрузить музыку", Toast.LENGTH_SHORT).show()
                    !silent -> Toast.makeText(activity, "Не удалось обновить список", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun parse(arr: JSONArray?): List<MusicTrack> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optLong("id", 0L)
            val path = api.str(o, "file_path")
            if (id <= 0 || path.isBlank()) return@mapNotNull null
            MusicTrack(
                id = id,
                path = path,
                name = api.str(o, "name").ifBlank { "Без названия" },
                artist = api.str(o, "artist_name").ifBlank { "Неизвестно" },
                genre = api.str(o, "genre"),
                uploadedBy = o.optLong("uploaded_by", 0L),
                inLibrary = if (o.has("in_library") && !o.isNull("in_library")) o.optInt("in_library", 0) != 0 else isOwnLibrary
            )
        }
    }

    private fun applyList(list: List<MusicTrack>) {
        audios = list
        core.updateLibrary(ownerKey(), list)
        emptyView.text = if (isOwnLibrary) "В библиотеке пока нет аудио.\nНажмите кнопку загрузки вверху справа"
        else "В этой библиотеке пока нет аудио"
        emptyView.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        adapter.notifyDataSetChanged()
    }

    // ------------------------------------------------------------------ плеер

    private fun onPlayClicked() {
        if (core.current == null) {
            askNotificationPermission()
            core.playFirst(ownerKey(), audios)
        } else core.togglePlay()
    }

    private fun onRowClicked(a: MusicTrack) {
        if (core.current?.id == a.id) { core.togglePlay(); return }
        askNotificationPermission()
        core.play(a, ownerKey(), audios)
    }

    /** Android 13+: без этого разрешения уведомление с кнопками плеера не покажется. */
    private fun askNotificationPermission() {
        if (askedNotifications || Build.VERSION.SDK_INT < 33) return
        askedNotifications = true
        if (activity.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED) {
            androidx.core.app.ActivityCompat.requestPermissions(
                activity, arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS
            )
        }
    }

    override fun onPlaybackChanged() { syncFromCore() }

    private fun syncFromCore() {
        val c = core.current
        titleView.text = c?.name ?: "Нет аудио"
        artistView.text = c?.artist ?: "—"
        seek.seekEnabled = core.prepared
        timeTotal.text = fmt(core.duration())
        if (c == null) { seek.progress = 0f; timeCurrent.text = fmt(0) } else updateProgress()
        btnPlay.setImageResource(if (core.wantPlay) R.drawable.audio_pause else R.drawable.audio_play)
        btnPlay.contentDescription = if (core.wantPlay) "Пауза" else "Воспроизведение"
        btnShuffle.alpha = if (core.shuffleOn) 1f else INACTIVE_ALPHA
        btnRepeat.alpha = if (core.repeatOn) 1f else INACTIVE_ALPHA
        syncDisc()
        ui.removeCallbacks(tick)
        if (started && core.wantPlay && core.prepared) ui.post(tick)
        adapter.notifyDataSetChanged()
    }

    /** Диск вращается, пока играет музыка и вкладка на экране (как spinDisc на сайте). */
    private fun syncDisc() {
        val spin = core.wantPlay && started
        if (spin) {
            if (!discAnim.isStarted) discAnim.start()
            else if (discAnim.isPaused) discAnim.resume()
        } else if (discAnim.isRunning) {
            discAnim.pause()
        }
    }

    private fun updateProgress() {
        if (seek.isDragging) return
        val dur = core.duration()
        val pos = core.position()
        if (dur > 0) seek.progress = pos.toFloat() / dur
        timeCurrent.text = fmt(pos)
    }

    private fun fmt(ms: Int): String {
        val s = (ms / 1000).coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) String.format(java.util.Locale.US, "%d:%02d:%02d", h, m, sec)
        else String.format(java.util.Locale.US, "%d:%02d", m, sec)
    }

    // ------------------------------------------------------------------ действия над треком

    private fun showMenu(anchor: View, a: MusicTrack) {
        val mine = a.uploadedBy.toString() == api.userId
        val pm = PopupMenu(activity, anchor)
        pm.menu.add(0, 1, 0, "Скачать")
        // Как на сайте: править можно только свой трек в своей библиотеке; в чужой — добавить/убрать у себя.
        if (mine && isOwnLibrary) pm.menu.add(0, 2, 1, "Редактировать")
        if (isOwnLibrary) pm.menu.add(0, 3, 2, "Удалить")
        else if (a.inLibrary) pm.menu.add(0, 3, 2, "Убрать из моей библиотеки")
        else pm.menu.add(0, 4, 2, "Добавить в мою библиотеку")
        pm.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> download(a)
                2 -> showEditDialog(a)
                3 -> confirmDelete(a)
                4 -> addToLibrary(a)
            }
            true
        }
        pm.show()
    }

    private fun confirmDelete(a: MusicTrack) {
        AlertDialog.Builder(activity)
            .setTitle(if (isOwnLibrary) "Удалить трек?" else "Убрать трек?")
            .setMessage(if (isOwnLibrary) "«${a.name}» будет удалён из вашей библиотеки." else "«${a.name}» будет убран из вашей библиотеки.")
            .setPositiveButton(if (isOwnLibrary) "Удалить" else "Убрать") { _, _ -> deleteTrack(a) }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun deleteTrack(a: MusicTrack) {
        api.io.execute {
            val ok = try { api.send("DELETE", "/api/users/audios/${a.id}", null).code in 200..299 } catch (e: Exception) { false }
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                if (!ok) {
                    Toast.makeText(activity, "Не удалось удалить трек", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                if (isOwnLibrary) {
                    core.onTrackRemoved(a.id)
                    applyList(audios.filter { it.id != a.id })
                }
                load(silent = true)
            }
        }
    }

    private fun addToLibrary(a: MusicTrack) {
        api.io.execute {
            val ok = try { api.send("POST", "/api/users/audios/${a.id}", null).code in 200..299 } catch (e: Exception) { false }
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                Toast.makeText(activity, if (ok) "Добавлено в вашу библиотеку" else "Не удалось добавить трек", Toast.LENGTH_SHORT).show()
                if (ok) load(silent = true)
            }
        }
    }

    private fun showEditDialog(a: MusicTrack) {
        val d = activity.resources.displayMetrics.density
        fun field(hint: String, value: String) = EditText(activity).apply {
            this.hint = hint
            setText(value)
            setSingleLine(true)
        }
        val name = field("Название", a.name)
        val artist = field("Исполнитель", a.artist)
        val genre = field("Жанр", a.genre)
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * d).toInt()
            setPadding(pad, (8 * d).toInt(), pad, 0)
            addView(name); addView(artist); addView(genre)
        }
        AlertDialog.Builder(activity)
            .setTitle("Редактировать трек")
            .setView(box)
            .setPositiveButton("Сохранить", null)
            .setNegativeButton("Отмена", null)
            .create().also { dlg ->
                dlg.setOnShowListener {
                    dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val n = name.text.toString().trim()
                        if (n.isEmpty()) {
                            Toast.makeText(activity, "Название не может быть пустым", Toast.LENGTH_SHORT).show()
                            return@setOnClickListener
                        }
                        dlg.dismiss()
                        saveMeta(a, n, artist.text.toString().trim(), genre.text.toString().trim())
                    }
                }
            }.show()
    }

    private fun saveMeta(a: MusicTrack, name: String, artist: String, genre: String) {
        api.io.execute {
            var error: String? = "Не удалось сохранить"
            try {
                val body = JSONObject().put("name", name).put("artistName", artist).put("genre", genre).toString()
                val r = api.send("PUT", "/api/audios/${a.id}", body)
                val o = try { JSONObject(r.body) } catch (e: Exception) { JSONObject() }
                error = if (r.code in 200..299 && o.optBoolean("success", true)) null
                else o.optString("error", "").ifBlank { "Не удалось сохранить" }
            } catch (e: Exception) {
                Log.w(TAG, "Ошибка сохранения трека", e)
            }
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                if (error != null) Toast.makeText(activity, error, Toast.LENGTH_SHORT).show()
                else load(silent = true)
            }
        }
    }

    // ------------------------------------------------------------------ скачивание

    private fun download(a: MusicTrack) {
        Toast.makeText(activity, "Скачивание…", Toast.LENGTH_SHORT).show()
        transfers.execute {
            var result: String? = null
            try {
                val ext = a.path.substringAfterLast('.', "mp3").lowercase().takeIf { it.length in 2..5 } ?: "mp3"
                val fileName = a.name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifEmpty { "audio" } + "." + ext
                val mime = if (ext == "mp3") "audio/mpeg" else "audio/*"
                val req = Request.Builder().url(api.absUrl(a.path.replace(" ", "%20"))).get().build()
                api.client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
                    val body = resp.body ?: throw IllegalStateException("empty body")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val values = ContentValues().apply {
                            put(MediaStore.Audio.Media.DISPLAY_NAME, fileName)
                            put(MediaStore.Audio.Media.MIME_TYPE, mime)
                            put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/FortPort")
                            put(MediaStore.Audio.Media.IS_PENDING, 1)
                        }
                        val resolver = activity.contentResolver
                        val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
                            ?: throw IllegalStateException("insert failed")
                        try {
                            resolver.openOutputStream(uri)!!.use { out -> body.byteStream().use { it.copyTo(out) } }
                            val done = ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }
                            resolver.update(uri, done, null, null)
                        } catch (e: Exception) {
                            resolver.delete(uri, null, null)
                            throw e
                        }
                        result = "Сохранено в Музыка/FortPort: $fileName"
                    } else {
                        val dir = activity.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
                            ?: throw IllegalStateException("no storage")
                        dir.mkdirs()
                        val f = File(dir, fileName)
                        f.outputStream().use { out -> body.byteStream().use { it.copyTo(out) } }
                        result = "Сохранено: ${f.absolutePath}"
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Ошибка скачивания", e)
            }
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                Toast.makeText(activity, result ?: "Не удалось скачать трек", Toast.LENGTH_LONG).show()
            }
        }
    }

    // ------------------------------------------------------------------ загрузка с устройства

    private fun uploadFiles(uris: List<Uri>) {
        val total = uris.size
        Toast.makeText(activity, if (total == 1) "Загрузка аудио…" else "Загрузка аудио: $total", Toast.LENGTH_SHORT).show()
        swipe.isRefreshing = true
        transfers.execute {
            var okCount = 0
            var lastError: String? = null
            val upClient = api.client.newBuilder()
                .writeTimeout(180, TimeUnit.SECONDS)
                .readTimeout(180, TimeUnit.SECONDS)
                .build()
            for (uri in uris) {
                try {
                    uploadOne(upClient, uri)
                    okCount++
                } catch (e: Exception) {
                    Log.w(TAG, "Ошибка загрузки $uri", e)
                    lastError = e.message
                }
            }
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                swipe.isRefreshing = false
                val msg = when {
                    okCount == total -> if (total == 1) "Аудио загружено" else "Загружено треков: $okCount"
                    okCount > 0 -> "Загружено $okCount из $total"
                    else -> "Не удалось загрузить аудио" + (lastError?.let { ": $it" } ?: "")
                }
                Toast.makeText(activity, msg, Toast.LENGTH_LONG).show()
                if (okCount > 0) load(silent = true)
            }
        }
    }

    private fun uploadOne(client: okhttp3.OkHttpClient, uri: Uri) {
        val resolver = activity.contentResolver
        var display = "audio"
        var size = -1L
        resolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val si = c.getColumnIndex(OpenableColumns.SIZE)
                if (ni >= 0 && !c.isNull(ni)) display = c.getString(ni)
                if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
            }
        }
        val mime = resolver.getType(uri)?.takeIf { it.startsWith("audio/") } ?: "audio/mpeg"

        // Название/исполнитель/жанр — из тегов файла, иначе как на сайте: имя файла и «Неизвестно».
        var title = ""
        var artist = ""
        var genre = ""
        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(activity, uri)
            title = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE).orEmpty().trim()
            artist = (mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                ?: mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)).orEmpty().trim()
            genre = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE).orEmpty().trim()
        } catch (e: Exception) {
            Log.w(TAG, "Нет тегов у файла", e)
        } finally {
            try { mmr.release() } catch (e: Exception) {}
        }
        if (title.isEmpty()) title = display.substringBeforeLast('.', display)
        if (artist.isEmpty()) artist = "Неизвестно"

        val fileBody = object : RequestBody() {
            override fun contentType() = mime.toMediaTypeOrNull()
            override fun contentLength() = size
            override fun writeTo(sink: BufferedSink) {
                val input = resolver.openInputStream(uri) ?: throw IllegalStateException("Файл недоступен")
                input.use { sink.writeAll(it.source()) }
            }
        }
        val form = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("audio", display, fileBody)
            .addFormDataPart("name", title)
            .addFormDataPart("artistName", artist)
            .build()
        val resp = client.newCall(Request.Builder().url(SiteApi.ORIGIN + "/api/audios").post(form).build()).execute()
        val text = resp.use { r ->
            if (!r.isSuccessful) throw IllegalStateException("HTTP ${r.code}")
            r.body?.string().orEmpty()
        }
        val o = JSONObject(text)
        if (!o.optBoolean("success", false)) throw IllegalStateException(o.optString("error", "").ifBlank { "отказ сервера" })
        val id = o.optJSONObject("audio")?.optLong("id", 0L) ?: 0L
        if (id <= 0) throw IllegalStateException("нет id трека")
        val add = api.send("POST", "/api/users/audios/$id", null)
        if (add.code !in 200..299) throw IllegalStateException("не добавлен в библиотеку (${add.code})")
        if (genre.isNotEmpty()) {
            try {
                api.send("PUT", "/api/audios/$id", JSONObject().put("name", title).put("artistName", artist).put("genre", genre).toString())
            } catch (e: Exception) { Log.w(TAG, "Не удалось записать жанр", e) }
        }
    }

    // ------------------------------------------------------------------ список

    private inner class Adapter : RecyclerView.Adapter<RowHolder>() {
        override fun getItemCount() = audios.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            RowHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_audio_row, parent, false))

        override fun onBindViewHolder(holder: RowHolder, position: Int) = holder.bind(audios[position], position)
    }

    private inner class RowHolder(v: View) : RecyclerView.ViewHolder(v) {
        private val index = v.findViewById<TextView>(R.id.audioIndex)
        private val name = v.findViewById<TextView>(R.id.audioName)
        private val artist = v.findViewById<TextView>(R.id.audioArtist)
        private val menu = v.findViewById<TextView>(R.id.audioMenu)

        fun bind(a: MusicTrack, position: Int) {
            index.text = (position + 1).toString()
            name.text = a.name
            artist.text = a.artist
            itemView.setBackgroundResource(
                if (core.current?.id == a.id) R.drawable.bg_audio_row_active else R.drawable.bg_chat_card
            )
            itemView.setOnClickListener { onRowClicked(a) }
            menu.setOnClickListener { showMenu(menu, a) }
        }
    }
}
