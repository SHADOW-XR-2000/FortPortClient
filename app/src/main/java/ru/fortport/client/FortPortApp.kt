package ru.fortport.client

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class FortPortApp : Application() {

    companion object {
        const val NOTIFICATION_CHANNEL_ID = "fortport_default"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.app_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Уведомления ФортПорт"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }
}
