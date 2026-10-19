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
    private val emptyView: View,
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
    private val onAuthLost: () -> Unit
) {
    companion object {
        private const val TAG = "Music"
        private const val TICK_MS = 250L
        private const val INACTIVE_ALPHA = 0.45f
    }

    private class Audio(
        val id: Long,
        val path: String,
        val name: String,
        val artist: String,
        val genre: String,
        val uploadedBy: Long
    )

    private val ui = Handler(Looper.getMainLooper())
    private val transfers = Executors.newSingleThreadExecutor()

    private var audios: List<Audio> = emptyList()
    private var queue: List<Audio> = emptyList()
    private var current: Audio? = null

    private var shuffleOn = false
    private var repeatOn = false

    private var player: MediaPlayer? = null
    private var prepared = false
    private var wantPlay = false

    private var started = false      // вкладка на экране
    private var loadedOnce = false
    private var bgCheckPending = false
    private var generation = 0
    private var destroyed = false

    private val audioManager = activity.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var focusRequest: AudioFocusRequest? = null
    private var resumeAfterFocus = false
    private var noisyRegistered = false

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
            if (started && player?.isPlaying == true) ui.postDelayed(this, TICK_MS)
        }
    }

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) pausePlayback()
        }
    }

    init {
        recycler.layoutManager = LinearLayoutManager(activity)
        recycler.adapter = adapter
        recycler.itemAnimator = null
        val d = activity.resources.displayMetrics.density
        // Список начинается под плашкой плеера — индикатор обновления рисуем под ней.
        swipe.setProgressViewOffset(false, (252 * d).toInt(), (314 * d).toInt())
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

        titleView.text = "Нет аудио"
        artistView.text = "—"
        seek.listener = object : GradientSeekBar.Listener {
            override fun onScrub(fraction: Float) {
                val dur = player?.takeIf { prepared }?.duration ?: return
                timeCurrent.text = fmt((fraction * dur).toInt())
            }

            override fun onSeekFinished(fraction: Float) {
                val p = player?.takeIf { prepared } ?: return
                p.seekTo((fraction * p.duration).toInt())
                updateProgress()
            }
        }
        btnPlay.setOnClickListener { togglePlay() }
        btnNext.setOnClickListener { playNext() }
        btnPrev.setOnClickListener { playPrevious() }
        btnShuffle.setOnClickListener { toggleShuffle() }
        btnRepeat.setOnClickListener { repeatOn = !repeatOn; updateButtons() }
        updateButtons()
    }

    // ------------------------------------------------------------------ жизненный цикл

    fun start(userId: String, deviceId: String, userAgent: String) {
        api.setSession(userId, deviceId, userAgent)
        destroyed = false
        if (!started) {
            started = true
            bgCheckPending = true
            if (!loadedOnce) swipe.isRefreshing = true
            syncDisc()
            ui.removeCallbacks(tick)
            ui.post(tick)
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
        wantPlay = false
        releasePlayer()
        abandonFocus()
        unregisterNoisy()
        discAnim.cancel()
        transfers.shutdownNow()
    }

    /** Кнопка «загрузить аудио с устройства» в шапке раздела. */
    fun pickFromDevice() {
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
        api.io.execute {
            var ok = false
            var auth = false
            var list: List<Audio> = emptyList()
            try {
                when (api.ensureUserId()) {
                    1 -> auth = true
                    2 -> {}
                    else -> {
                        val r = api.get("/api/users/audios?userId=${api.userId}")
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
            if (ok && bgCheckPending) {
                bgCheckPending = false
                try { api.refreshBackground() } catch (e: Exception) { Log.w(TAG, "Не удалось обновить фон", e) }
            }
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed || gen != generation || !started) return@runOnUiThread
                swipe.isRefreshing = false
                when {
                    auth -> onAuthLost()
                    ok -> { loadedOnce = true; applyList(list) }
                    !silent && !loadedOnce -> Toast.makeText(activity, "Не удалось загрузить музыку", Toast.LENGTH_SHORT).show()
                    !silent -> Toast.makeText(activity, "Не удалось обновить список", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun parse(arr: JSONArray?): List<Audio> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optLong("id", 0L)
            val path = api.str(o, "file_path")
            if (id <= 0 || path.isBlank()) return@mapNotNull null
            Audio(
                id = id,
                path = path,
                name = api.str(o, "name").ifBlank { "Без названия" },
                artist = api.str(o, "artist_name").ifBlank { "Неизвестно" },
                genre = api.str(o, "genre"),
                uploadedBy = o.optLong("uploaded_by", 0L)
            )
        }
    }

    private fun applyList(list: List<Audio>) {
        audios = list
        val cur = current
        if (cur != null) {
            val fresh = list.firstOrNull { it.id == cur.id }
            if (fresh == null) {
                stopAndClear()
            } else {
                current = fresh
                titleView.text = fresh.name
                artistView.text = fresh.artist
            }
        }
        if (shuffleOn) rebuildQueue()
        emptyView.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        adapter.notifyDataSetChanged()
    }

    // ------------------------------------------------------------------ порядок воспроизведения

    private fun order(): List<Audio> = if (shuffleOn) queue else audios

    private fun posOf(): Int {
        val c = current ?: return -1
        return order().indexOfFirst { it.id == c.id }
    }

    /** Текущий трек первым, остальные в случайном порядке (как на сайте). */
    private fun rebuildQueue() {
        val c = current
        queue = if (c != null && audios.any { it.id == c.id }) {
            listOf(audios.first { it.id == c.id }) + audios.filter { it.id != c.id }.shuffled()
        } else audios.shuffled()
    }

    private fun toggleShuffle() {
        shuffleOn = !shuffleOn
        if (shuffleOn) rebuildQueue() else queue = emptyList()
        updateButtons()
    }

    private fun playNext() {
        val o = order()
        val n = posOf() + 1
        if (n in o.indices) startTrack(o[n])
    }

    private fun playPrevious() {
        val p = player
        if (p != null && prepared && p.currentPosition > 3000) {
            p.seekTo(0)
            updateProgress()
            return
        }
        val n = posOf() - 1
        if (n >= 0) startTrack(order()[n])
    }

    private fun onRowClicked(a: Audio) {
        if (current?.id == a.id && player != null) { togglePlay(); return }
        current = a
        if (shuffleOn) rebuildQueue()
        startTrack(a)
    }

    private fun togglePlay() {
        if (current == null) {
            if (shuffleOn) rebuildQueue()
            order().firstOrNull()?.let { startTrack(it) }
            return
        }
        if (wantPlay) pausePlayback() else resumePlayback()
    }

    // ------------------------------------------------------------------ плеер

    private fun startTrack(a: Audio) {
        releasePlayer()
        current = a
        wantPlay = true
        prepared = false
        titleView.text = a.name
        artistView.text = a.artist
        seek.seekEnabled = false
        seek.progress = 0f
        timeCurrent.text = fmt(0)
        timeTotal.text = fmt(0)
        val mp = MediaPlayer()
        player = mp
        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            mp.setWakeMode(activity.applicationContext, PowerManager.PARTIAL_WAKE_LOCK)
            val headers = HashMap<String, String>()
            if (api.userAgent.isNotBlank()) headers["User-Agent"] = api.userAgent
            if (api.deviceId.isNotBlank()) headers["X-Device-Id"] = api.deviceId
            android.webkit.CookieManager.getInstance().getCookie(SiteApi.ORIGIN)?.let { headers["Cookie"] = it }
            mp.setDataSource(activity.applicationContext, Uri.parse(api.absUrl(a.path.replace(" ", "%20"))), headers)
            mp.setOnPreparedListener {
                if (player !== mp) return@setOnPreparedListener
                prepared = true
                seek.seekEnabled = true
                timeTotal.text = fmt(mp.duration)
                if (wantPlay && requestFocus()) {
                    mp.start()
                    registerNoisy()
                    ui.removeCallbacks(tick)
                    ui.post(tick)
                } else wantPlay = false
                refreshUi()
            }
            mp.setOnCompletionListener {
                if (player !== mp) return@setOnCompletionListener
                onCompleted(mp)
            }
            mp.setOnErrorListener { _, what, extra ->
                if (player === mp) {
                    Log.w(TAG, "Ошибка плеера what=$what extra=$extra")
                    Toast.makeText(activity, "Не удалось воспроизвести трек", Toast.LENGTH_SHORT).show()
                    wantPlay = false
                    prepared = false
                    seek.seekEnabled = false
                    refreshUi()
                }
                true
            }
            mp.prepareAsync()
        } catch (e: Exception) {
            Log.w(TAG, "Не удалось запустить трек", e)
            Toast.makeText(activity, "Не удалось воспроизвести трек", Toast.LENGTH_SHORT).show()
            wantPlay = false
        }
        refreshUi()
    }

    private fun onCompleted(mp: MediaPlayer) {
        if (repeatOn) {
            mp.seekTo(0)
            mp.start()
            return
        }
        val o = order()
        val n = posOf() + 1
        if (n in o.indices) {
            startTrack(o[n])
        } else {
            // Конец списка: останавливаемся на начале трека.
            wantPlay = false
            seek.progress = 0f
            timeCurrent.text = fmt(0)
            abandonFocus()
            unregisterNoisy()
            refreshUi()
        }
    }

    private fun resumePlayback() {
        val p = player
        if (p == null || !prepared) {
            current?.let { startTrack(it) }
            return
        }
        if (!requestFocus()) return
        wantPlay = true
        p.start()
        registerNoisy()
        ui.removeCallbacks(tick)
        ui.post(tick)
        refreshUi()
    }

    private fun pausePlayback() {
        wantPlay = false
        resumeAfterFocus = false
        try { player?.takeIf { prepared && it.isPlaying }?.pause() } catch (e: IllegalStateException) { /* уже остановлен */ }
        unregisterNoisy()
        refreshUi()
    }

    private fun stopAndClear() {
        wantPlay = false
        releasePlayer()
        abandonFocus()
        unregisterNoisy()
        current = null
        titleView.text = "Нет аудио"
        artistView.text = "—"
        seek.seekEnabled = false
        seek.progress = 0f
        timeCurrent.text = fmt(0)
        timeTotal.text = fmt(0)
        refreshUi()
    }

    private fun releasePlayer() {
        val p = player
        player = null
        prepared = false
        if (p != null) {
            try { p.setOnPreparedListener(null); p.setOnCompletionListener(null); p.setOnErrorListener(null) } catch (e: Exception) {}
            try { p.release() } catch (e: Exception) {}
        }
    }

    // ------------------------------------------------------------------ аудиофокус

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> pausePlayback()
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                if (wantPlay) {
                    val keep = true
                    pausePlayback()
                    resumeAfterFocus = keep
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> player?.setVolume(0.3f, 0.3f)
            AudioManager.AUDIOFOCUS_GAIN -> {
                player?.setVolume(1f, 1f)
                if (resumeAfterFocus) { resumeAfterFocus = false; resumePlayback() }
            }
        }
    }

    private fun requestFocus(): Boolean {
        val req = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setOnAudioFocusChangeListener(focusListener)
            .build().also { focusRequest = it }
        return audioManager.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonFocus() {
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
    }

    private fun registerNoisy() {
        if (noisyRegistered) return
        try {
            activity.registerReceiver(noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
            noisyRegistered = true
        } catch (e: Exception) { Log.w(TAG, "noisy receiver", e) }
    }

    private fun unregisterNoisy() {
        if (!noisyRegistered) return
        try { activity.unregisterReceiver(noisyReceiver) } catch (e: Exception) {}
        noisyRegistered = false
    }

    // ------------------------------------------------------------------ интерфейс плеера

    private fun refreshUi() {
        btnPlay.setImageResource(if (wantPlay) R.drawable.audio_pause else R.drawable.audio_play)
        btnPlay.contentDescription = if (wantPlay) "Пауза" else "Воспроизведение"
        syncDisc()
        updateButtons()
        adapter.notifyDataSetChanged()
    }

    private fun updateButtons() {
        btnShuffle.alpha = if (shuffleOn) 1f else INACTIVE_ALPHA
        btnRepeat.alpha = if (repeatOn) 1f else INACTIVE_ALPHA
        btnShuffle.isSelected = shuffleOn
        btnRepeat.isSelected = repeatOn
    }

    /** Диск вращается, пока играет музыка и вкладка на экране (как spinDisc на сайте). */
    private fun syncDisc() {
        val spin = wantPlay && started
        if (spin) {
            if (!discAnim.isStarted) discAnim.start()
            else if (discAnim.isPaused) discAnim.resume()
        } else if (discAnim.isRunning) {
            discAnim.pause()
        }
    }

    private fun updateProgress() {
        val p = player ?: return
        if (!prepared || seek.isDragging) return
        try {
            val dur = p.duration
            val pos = p.currentPosition
            if (dur > 0) seek.progress = pos.toFloat() / dur
            timeCurrent.text = fmt(pos)
        } catch (e: IllegalStateException) { /* плеер освобождён */ }
    }

    private fun fmt(ms: Int): String {
        val s = (ms / 1000).coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) String.format(java.util.Locale.US, "%d:%02d:%02d", h, m, sec) else String.format(java.util.Locale.US, "%d:%02d", m, sec)
    }

    // ------------------------------------------------------------------ действия над треком

    private fun showMenu(anchor: View, a: Audio) {
        val own = a.uploadedBy.toString() == api.userId
        val pm = PopupMenu(activity, anchor)
        pm.menu.add(0, 1, 0, "Скачать")
        if (own) pm.menu.add(0, 2, 1, "Редактировать")
        pm.menu.add(0, 3, 2, "Удалить")
        pm.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> download(a)
                2 -> showEditDialog(a)
                3 -> confirmDelete(a)
            }
            true
        }
        pm.show()
    }

    private fun confirmDelete(a: Audio) {
        AlertDialog.Builder(activity)
            .setTitle("Удалить трек?")
            .setMessage("«${a.name}» будет удалён из вашей библиотеки.")
            .setPositiveButton("Удалить") { _, _ -> deleteTrack(a) }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun deleteTrack(a: Audio) {
        api.io.execute {
            val ok = try { api.send("DELETE", "/api/users/audios/${a.id}", null).code in 200..299 } catch (e: Exception) { false }
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                if (!ok) {
                    Toast.makeText(activity, "Не удалось удалить трек", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                if (current?.id == a.id) stopAndClear()
                applyList(audios.filter { it.id != a.id })
                load(silent = true)
            }
        }
    }

    private fun showEditDialog(a: Audio) {
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

    private fun saveMeta(a: Audio, name: String, artist: String, genre: String) {
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

    private fun download(a: Audio) {
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

        fun bind(a: Audio, position: Int) {
            index.text = (position + 1).toString()
            name.text = a.name
            artist.text = a.artist
            itemView.setBackgroundResource(
                if (current?.id == a.id) R.drawable.bg_audio_row_active else R.drawable.bg_chat_card
            )
            itemView.setOnClickListener { onRowClicked(a) }
            menu.setOnClickListener { showMenu(menu, a) }
        }
    }
}
