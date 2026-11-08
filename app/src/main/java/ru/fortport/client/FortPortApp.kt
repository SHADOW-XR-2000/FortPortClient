package ru.fortport.client

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import android.os.Process
import java.io.PrintWriter
import java.io.StringWriter
import kotlin.system.exitProcess

class FortPortApp : Application() {

    companion object {
        const val NOTIFICATION_CHANNEL_ID = "fortport_default"
    }

    override fun onCreate() {
        super.onCreate()
        // В процессе :crash (экран с причиной сбоя) ничего не настраиваем.
        if (isCrashProcess()) return
        installCrashReporter()
        createNotificationChannel()
    }

    private fun isCrashProcess(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && Application.getProcessName().orEmpty().endsWith(":crash")

    /**
     * Если приложение падает (в том числе при запуске), показываем причину отдельным экраном с кнопкой
     * «Скопировать» — так сбой можно разобрать без компьютера и logcat.
     */
    private fun installCrashReporter() {
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val sw = StringWriter()
                error.printStackTrace(PrintWriter(sw))
                val text = "Версия: ${runCatching { UpdateManager.versionLabel(this) }.getOrDefault("?")} " +
                    "(сборка ${runCatching { UpdateManager.installedBuild(this) }.getOrDefault(0L)}), " +
                    "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}\n" +
                    "Поток: ${thread.name}\n\n$sw"
                startActivity(
                    Intent(this, CrashActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        .putExtra("trace", text.take(60000))
                )
                Thread.sleep(400)
            } catch (e: Throwable) {
            } finally {
                Process.killProcess(Process.myPid())
                exitProcess(10)
            }
        }
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
