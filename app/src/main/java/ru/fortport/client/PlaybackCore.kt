package ru.fortport.client

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.MediaPlayer
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.webkit.CookieManager
import android.widget.Toast
import androidx.core.content.ContextCompat

class MusicTrack(
    val id: Long,
    val path: String,
    val name: String,
    val artist: String,
    val genre: String,
    val uploadedBy: Long,
    val inLibrary: Boolean
)

/**
 * Единый плеер приложения (живёт на уровне процесса, а не экрана): MediaPlayer, очередь, аудиофокус
 * и MediaSession. Экран «Музыка» и уведомление (MusicService) — только слушатели и пульты управления,
 * поэтому музыка не прерывается при смене вкладки, сворачивании или пересоздании Activity.
 * Всё вызывается из главного потока.
 */
object PlaybackCore {
    private const val TAG = "PlaybackCore"

    interface Listener { fun onPlaybackChanged() }

    private lateinit var app: Context
    private var inited = false

    private val listeners = ArrayList<Listener>()

    var playlist: List<MusicTrack> = emptyList(); private set
    var playlistOwner: String = ""; private set
    var current: MusicTrack? = null; private set
    var shuffleOn = false; private set
    var repeatOn = false; private set
    /** Намерение «играть» (пауза/ошибка/конец списка сбрасывают). */
    var wantPlay = false; private set
    var prepared = false; private set

    private var queue: List<MusicTrack> = emptyList()
    private var player: MediaPlayer? = null
    private var session: MediaSession? = null
    private var userAgent = ""
    private var deviceId = ""

    private lateinit var audioManager: AudioManager
    private var focusRequest: AudioFocusRequest? = null
    private var resumeAfterFocus = false
    private var noisyRegistered = false

    fun init(context: Context) {
        if (inited) return
        inited = true
        app = context.applicationContext
        audioManager = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    fun configure(userAgent: String, deviceId: String) {
        if (userAgent.isNotBlank()) this.userAgent = userAgent
        if (deviceId.isNotBlank()) this.deviceId = deviceId
    }

    fun addListener(l: Listener) { if (!listeners.contains(l)) listeners.add(l) }
    fun removeListener(l: Listener) { listeners.remove(l) }

    private fun changed() {
        updateSession()
        ArrayList(listeners).forEach { it.onPlaybackChanged() }
    }

    val mediaSessionToken: MediaSession.Token? get() = session?.sessionToken

    // ------------------------------------------------------------------ состояние

    fun position(): Int = try { if (prepared) player?.currentPosition ?: 0 else 0 } catch (e: IllegalStateException) { 0 }
    fun duration(): Int = try { if (prepared) player?.duration ?: 0 else 0 } catch (e: IllegalStateException) { 0 }

    // ------------------------------------------------------------------ список и порядок

    /** Библиотека, которую открыл экран, обновилась: если играет она же — подхватываем новый список. */
    fun updateLibrary(owner: String, list: List<MusicTrack>) {
        if (current != null && owner != playlistOwner) return
        playlist = list
        playlistOwner = owner
        if (shuffleOn) rebuildQueue()
        changed()
    }

    private fun order(): List<MusicTrack> = if (shuffleOn) queue else playlist

    private fun posOf(): Int {
        val c = current ?: return -1
        return order().indexOfFirst { it.id == c.id }
    }

    /** Текущий трек первым, остальные в случайном порядке (как на сайте). */
    private fun rebuildQueue() {
        val c = current
        queue = if (c != null && playlist.any { it.id == c.id }) {
            listOf(playlist.first { it.id == c.id }) + playlist.filter { it.id != c.id }.shuffled()
        } else playlist.shuffled()
    }

    fun toggleShuffle() {
        shuffleOn = !shuffleOn
        if (shuffleOn) rebuildQueue() else queue = emptyList()
        changed()
    }

    fun toggleRepeat() {
        repeatOn = !repeatOn
        changed()
    }

    // ------------------------------------------------------------------ управление

    /** Запуск трека из списка [list] библиотеки [owner]. */
    fun play(track: MusicTrack, owner: String, list: List<MusicTrack>) {
        playlist = list
        playlistOwner = owner
        current = track
        if (shuffleOn) rebuildQueue()
        startTrack(track)
    }

    /** Кнопка Play без выбранного трека: начинаем с первого в списке. */
    fun playFirst(owner: String, list: List<MusicTrack>) {
        if (current != null) { togglePlay(); return }
        playlist = list
        playlistOwner = owner
        if (shuffleOn) rebuildQueue()
        order().firstOrNull()?.let { play(it, owner, list) }
    }

    fun togglePlay() {
        if (current == null) return
        if (wantPlay) pause() else resume()
    }

    fun next() {
        val o = order()
        val n = posOf() + 1
        if (n in o.indices) startTrack(o[n].also { current = it })
    }

    fun previous() {
        val p = player
        if (p != null && prepared && position() > 3000) {
            p.seekTo(0)
            changed()
            return
        }
        val n = posOf() - 1
        if (n >= 0) startTrack(order()[n].also { current = it })
    }

    fun seekTo(ms: Int) {
        val p = player ?: return
        if (!prepared) return
        try { p.seekTo(ms.coerceIn(0, duration())) } catch (e: IllegalStateException) { return }
        changed()
    }

    fun resume() {
        val p = player
        if (p == null || !prepared) {
            current?.let { startTrack(it) }
            return
        }
        if (!requestFocus()) return
        wantPlay = true
        try { p.start() } catch (e: IllegalStateException) { return }
        registerNoisy()
        ensureService()
        changed()
    }

    fun pause() {
        wantPlay = false
        resumeAfterFocus = false
        try { player?.takeIf { prepared && it.isPlaying }?.pause() } catch (e: IllegalStateException) { /* уже остановлен */ }
        unregisterNoisy()
        changed()
    }

    /** Полная остановка: убираем уведомление и плеер. */
    fun stopAll() {
        wantPlay = false
        resumeAfterFocus = false
        releasePlayer()
        abandonFocus()
        unregisterNoisy()
        current = null
        session?.isActive = false
        changed()
    }

    /** Трек удалён пользователем из библиотеки. */
    fun onTrackRemoved(id: Long) {
        if (current?.id == id) stopAll()
    }

    // ------------------------------------------------------------------ воспроизведение

    private fun startTrack(a: MusicTrack) {
        releasePlayer()
        current = a
        wantPlay = true
        prepared = false
        ensureSession()
        val mp = MediaPlayer()
        player = mp
        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            mp.setWakeMode(app, PowerManager.PARTIAL_WAKE_LOCK)
            val headers = HashMap<String, String>()
            if (userAgent.isNotBlank()) headers["User-Agent"] = userAgent
            if (deviceId.isNotBlank()) headers["X-Device-Id"] = deviceId
            CookieManager.getInstance().getCookie(SiteApi.ORIGIN)?.let { headers["Cookie"] = it }
            val url = if (a.path.startsWith("http")) a.path else SiteApi.ORIGIN + (if (a.path.startsWith("/")) "" else "/") + a.path
            mp.setDataSource(app, Uri.parse(url.replace(" ", "%20")), headers)
            mp.setOnPreparedListener {
                if (player !== mp) return@setOnPreparedListener
                prepared = true
                if (wantPlay && requestFocus()) {
                    mp.start()
                    registerNoisy()
                } else wantPlay = false
                changed()
            }
            mp.setOnCompletionListener {
                if (player !== mp) return@setOnCompletionListener
                onCompleted(mp)
            }
            mp.setOnErrorListener { _, what, extra ->
                if (player === mp) {
                    Log.w(TAG, "Ошибка плеера what=$what extra=$extra")
                    Toast.makeText(app, "Не удалось воспроизвести трек", Toast.LENGTH_SHORT).show()
                    wantPlay = false
                    prepared = false
                    changed()
                }
                true
            }
            mp.prepareAsync()
        } catch (e: Exception) {
            Log.w(TAG, "Не удалось запустить трек", e)
            Toast.makeText(app, "Не удалось воспроизвести трек", Toast.LENGTH_SHORT).show()
            wantPlay = false
        }
        ensureService()
        changed()
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
            // Конец списка: останавливаемся в начале трека.
            wantPlay = false
            try { mp.seekTo(0) } catch (e: IllegalStateException) {}
            abandonFocus()
            unregisterNoisy()
            changed()
        }
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

    // ------------------------------------------------------------------ аудиофокус и наушники

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> pause()
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                if (wantPlay) {
                    pause()
                    resumeAfterFocus = true
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> player?.setVolume(0.3f, 0.3f)
            AudioManager.AUDIOFOCUS_GAIN -> {
                player?.setVolume(1f, 1f)
                if (resumeAfterFocus) { resumeAfterFocus = false; resume() }
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

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) pause()
        }
    }

    private fun registerNoisy() {
        if (noisyRegistered) return
        try {
            app.registerReceiver(noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
            noisyRegistered = true
        } catch (e: Exception) { Log.w(TAG, "noisy receiver", e) }
    }

    private fun unregisterNoisy() {
        if (!noisyRegistered) return
        try { app.unregisterReceiver(noisyReceiver) } catch (e: Exception) {}
        noisyRegistered = false
    }

    // ------------------------------------------------------------------ сессия (гарнитура, шторка, экран блокировки)

    private fun ensureSession() {
        if (session != null) { session?.isActive = true; return }
        @Suppress("DEPRECATION")
        val s = MediaSession(app, "FortPortMusic").apply {
            setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() { if (current != null) resume() }
                override fun onPause() { pause() }
                override fun onSkipToNext() { next() }
                override fun onSkipToPrevious() { previous() }
                override fun onSeekTo(pos: Long) { seekTo(pos.toInt()) }
                override fun onStop() { stopAll() }
            })
            isActive = true
        }
        session = s
    }

    private fun updateSession() {
        val s = session ?: return
        val c = current
        if (c == null) {
            s.setPlaybackState(PlaybackState.Builder().setState(PlaybackState.STATE_STOPPED, 0, 0f).build())
            return
        }
        s.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, c.name)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, c.artist)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, duration().toLong())
                .build()
        )
        val state = when {
            wantPlay && !prepared -> PlaybackState.STATE_BUFFERING
            wantPlay -> PlaybackState.STATE_PLAYING
            else -> PlaybackState.STATE_PAUSED
        }
        val actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
            PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or
            PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_STOP
        s.setPlaybackState(
            PlaybackState.Builder()
                .setActions(actions)
                .setState(state, position().toLong(), if (state == PlaybackState.STATE_PLAYING) 1f else 0f, SystemClock.elapsedRealtime())
                .build()
        )
    }

    private fun ensureService() {
        try {
            ContextCompat.startForegroundService(app, Intent(app, MusicService::class.java))
        } catch (e: Exception) {
            // Например, запуск из фона без разрешения: музыка всё равно играет, просто без уведомления.
            Log.w(TAG, "Не удалось запустить службу воспроизведения", e)
        }
    }
}
