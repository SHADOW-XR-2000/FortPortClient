package ru.fortport.client

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import android.util.Log

/**
 * Служба фонового воспроизведения: держит процесс живым, пока играет музыка, и показывает
 * уведомление (шторка + экран блокировки) с кнопками «назад / пауза / вперёд».
 * Сам звук ведёт PlaybackCore; служба только слушает его состояние.
 */
class MusicService : Service(), PlaybackCore.Listener {

    companion object {
        private const val TAG = "MusicService"
        private const val CHANNEL_ID = "music_playback"
        private const val NOTIF_ID = 4201
        private const val ACTION_PLAY_PAUSE = "ru.fortport.client.music.PLAY_PAUSE"
        private const val ACTION_NEXT = "ru.fortport.client.music.NEXT"
        private const val ACTION_PREV = "ru.fortport.client.music.PREV"
        private const val ACTION_STOP = "ru.fortport.client.music.STOP"
    }

    private var foregroundStarted = false
    private var largeIcon: Bitmap? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        PlaybackCore.init(this)
        PlaybackCore.addListener(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Музыка", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Управление воспроизведением"
                setShowBadge(false)
            }
        )
        largeIcon = try {
            assets.open("ui/big_disc.webp").use { s ->
                BitmapFactory.decodeStream(s, null, BitmapFactory.Options().apply { inSampleSize = 4 })
            }
        } catch (e: Exception) { null }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // После startForegroundService нужно сразу вызвать startForeground — иначе система убьёт приложение.
        if (!foregroundStarted) startForegroundCompat(buildNotification())
        when (intent?.action) {
            ACTION_PLAY_PAUSE -> PlaybackCore.togglePlay()
            ACTION_NEXT -> PlaybackCore.next()
            ACTION_PREV -> PlaybackCore.previous()
            ACTION_STOP -> PlaybackCore.stopAll()
        }
        refresh()
        return START_NOT_STICKY
    }

    override fun onPlaybackChanged() = refresh()

    override fun onDestroy() {
        PlaybackCore.removeListener(this)
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Приложение смахнули из «недавних»: если музыка не играет — службу не держим.
        if (!PlaybackCore.wantPlay) {
            PlaybackCore.stopAll()
            refresh()
        }
        super.onTaskRemoved(rootIntent)
    }

    private fun refresh() {
        val track = PlaybackCore.current
        if (track == null) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foregroundStarted = false
            stopSelf()
            return
        }
        val n = buildNotification()
        if (PlaybackCore.wantPlay) {
            if (foregroundStarted) notifyOnly(n) else startForegroundCompat(n)
        } else {
            // Пауза: уведомление остаётся, но его можно смахнуть (тогда служба останавливается).
            if (foregroundStarted) {
                stopForeground(STOP_FOREGROUND_DETACH)
                foregroundStarted = false
            }
            notifyOnly(n)
        }
    }

    private fun notifyOnly(n: Notification) {
        try { getSystemService(NotificationManager::class.java).notify(NOTIF_ID, n) } catch (e: Exception) { Log.w(TAG, "notify", e) }
    }

    private fun startForegroundCompat(n: Notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(NOTIF_ID, n)
            }
            foregroundStarted = true
        } catch (e: Exception) {
            Log.w(TAG, "startForeground не удался", e)
            notifyOnly(n)
        }
    }

    private fun servicePi(action: String, code: Int): PendingIntent =
        PendingIntent.getService(
            this, code,
            Intent(this, MusicService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun action(icon: Int, title: String, pi: PendingIntent) =
        Notification.Action.Builder(Icon.createWithResource(this, icon), title, pi).build()

    private fun buildNotification(): Notification {
        val track = PlaybackCore.current
        val playing = PlaybackCore.wantPlay
        val open = PendingIntent.getActivity(
            this, 0,
            (packageManager.getLaunchIntentForPackage(packageName) ?: Intent()).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val style = Notification.MediaStyle().setShowActionsInCompactView(0, 1, 2)
        PlaybackCore.mediaSessionToken?.let { style.setMediaSession(it) }
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_music_note)
            .setContentTitle(track?.name ?: "FortPort")
            .setContentText(track?.artist ?: "")
            .setLargeIcon(largeIcon)
            .setContentIntent(open)
            .setDeleteIntent(servicePi(ACTION_STOP, 4))
            .setOngoing(playing)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .addAction(action(R.drawable.ic_notif_prev, "Назад", servicePi(ACTION_PREV, 1)))
            .addAction(
                if (playing) action(R.drawable.ic_notif_pause, "Пауза", servicePi(ACTION_PLAY_PAUSE, 2))
                else action(R.drawable.ic_notif_play, "Воспроизвести", servicePi(ACTION_PLAY_PAUSE, 2))
            )
            .addAction(action(R.drawable.ic_notif_next, "Вперёд", servicePi(ACTION_NEXT, 3)))
            .addAction(action(R.drawable.ic_notif_close, "Выключить", servicePi(ACTION_STOP, 4)))
            .setStyle(style)
            .build()
    }
}
