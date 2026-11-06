package ru.fortport.client

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Обновление приложения через GitHub Releases репозитория [REPO].
 *
 * Как это работает:
 *  - CI (.github/workflows/release.yml) на каждую сборку создаёт релиз с тегом `build-N`
 *    (N = номер запуска = versionCode) и прикладывает к нему подписанный APK;
 *  - приложение спрашивает `releases/latest`, берёт N из тега и сравнивает с установленным versionCode;
 *  - если новее — показывает окно с описанием, скачивает APK (с прогрессом и проверкой SHA-256,
 *    если GitHub отдаёт digest) и передаёт его системному установщику.
 *
 * Поверх установленного приложения Android ставит только APK с ТОЙ ЖЕ подписью — поэтому
 * релизы должны подписываться одним и тем же ключом (см. README, «Обновления»).
 *
 * Отдельный OkHttp-клиент: запросы к GitHub не должны нести куки и заголовки сессии fortport.ru.
 */
object UpdateManager {

    const val REPO = "SHADOW-XR-2000/FortPortClient"
    private const val TAG = "Update"
    private const val API_LATEST = "https://api.github.com/repos/$REPO/releases/latest"
    private const val PREFS = "updates"
    private const val CHECK_INTERVAL_MS = 6L * 60 * 60 * 1000
    private const val APK_MIME = "application/vnd.android.package-archive"
    private const val NOTES_LIMIT = 900

    class Release(
        val build: Long,
        val title: String,
        val notes: String,
        val apkUrl: String,
        val size: Long,
        val sha256: String?
    )

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    @Volatile private var checking = false
    @Volatile private var downloadCall: Call? = null

    // ------------------------------------------------------------------ настройки

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun autoCheckEnabled(ctx: Context) = prefs(ctx).getBoolean("auto_check", true)
    fun setAutoCheck(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("auto_check", on).apply()

    fun installedBuild(ctx: Context): Long =
        PackageInfoCompat.getLongVersionCode(ctx.packageManager.getPackageInfo(ctx.packageName, 0))

    fun installedName(ctx: Context): String =
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"

    /** Версия для экрана настроек: просто «0.6.4» (номер сборки — внутренний, см. versionCode). */
    fun versionLabel(ctx: Context) = installedName(ctx)

    // ------------------------------------------------------------------ проверка

    /** Автопроверка при запуске: не чаще раза в 6 часов, можно отключить в настройках. */
    fun autoCheck(activity: Activity) {
        if (!autoCheckEnabled(activity)) return
        if (System.currentTimeMillis() - prefs(activity).getLong("last_check", 0) < CHECK_INTERVAL_MS) return
        check(activity, manual = false)
    }

    /**
     * @param manual true — по кнопке: показывает и «уже последняя версия», и ошибки, игнорирует
     *   пропущенную версию. [message] — куда выводить эти короткие сообщения (по умолчанию Toast).
     */
    fun check(
        activity: Activity,
        manual: Boolean,
        message: (String, Boolean) -> Unit = { t, _ -> Toast.makeText(activity, t, Toast.LENGTH_LONG).show() }
    ) {
        if (checking) return
        checking = true
        Thread {
            var release: Release? = null
            var error: String? = null
            try {
                release = fetchLatest()
                prefs(activity).edit().putLong("last_check", System.currentTimeMillis()).apply()
            } catch (e: Exception) {
                Log.w(TAG, "check failed", e)
                error = if (e is IOException) "Нет связи с GitHub" else (e.message ?: "Ошибка проверки обновлений")
            }
            checking = false
            ui(activity) {
                val rel = release
                when {
                    error != null -> if (manual) message("Не удалось проверить обновления: $error", false)
                    rel == null || rel.build <= installedBuild(activity) ->
                        if (manual) message("Установлена последняя версия: ${versionLabel(activity)}", true)
                    !manual && prefs(activity).getLong("skipped_build", 0) == rel.build -> Unit
                    else -> showOffer(activity, rel, manual)
                }
            }
        }.start()
    }

    /** null — релизов нет (404) или в нём нет APK. */
    private fun fetchLatest(): Release? {
        val req = Request.Builder().url(API_LATEST)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "FortPortClient")
            .build()
        http.newCall(req).execute().use { resp ->
            if (resp.code == 404) return null
            if (!resp.isSuccessful) throw IOException("GitHub ответил ${resp.code}")
            val j = JSONObject(resp.body?.string().orEmpty())
            if (j.optBoolean("draft") || j.optBoolean("prerelease")) return null
            val build = Regex("(\\d+)$").find(j.optString("tag_name"))?.value?.toLongOrNull() ?: return null
            val assets = j.optJSONArray("assets") ?: return null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                val url = a.optString("browser_download_url")
                if (!a.optString("name").endsWith(".apk", true) || !url.startsWith("https://")) continue
                return Release(
                    build = build,
                    title = j.optString("name").ifBlank { j.optString("tag_name") },
                    notes = j.optString("body").trim(),
                    apkUrl = url,
                    size = a.optLong("size"),
                    sha256 = a.optString("digest").removePrefix("sha256:").takeIf { it.length == 64 }
                )
            }
        }
        return null
    }

    // ------------------------------------------------------------------ окна

    private fun showOffer(activity: Activity, rel: Release, manual: Boolean) {
        if (activity.isFinishing || activity.isDestroyed) return
        val text = buildString {
            // Если название версии то же (поменялась только сборка) — различаем по номеру сборки.
            val same = rel.title == installedName(activity)
            append("Новая версия: ${rel.title}" + if (same) " (сборка ${rel.build})" else "")
            append("\nУстановлена: ${installedName(activity)}" + if (same) " (сборка ${installedBuild(activity)})" else "")
            if (rel.size > 0) append("\nРазмер: %.1f МБ".format(rel.size / 1048576.0))
            if (rel.notes.isNotEmpty()) {
                append("\n\n")
                append(if (rel.notes.length > NOTES_LIMIT) rel.notes.take(NOTES_LIMIT) + "…" else rel.notes)
            }
        }
        val b = AlertDialog.Builder(activity)
            .setTitle("Доступно обновление")
            .setMessage(text)
            .setPositiveButton("Обновить") { _, _ -> startUpdate(activity, rel) }
            .setNegativeButton("Позже", null)
        if (!manual) b.setNeutralButton("Пропустить версию") { _, _ ->
            prefs(activity).edit().putLong("skipped_build", rel.build).apply()
        }
        b.show()
    }

    private fun startUpdate(activity: Activity, rel: Release) {
        if (!activity.packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(activity)
                .setTitle("Нужно разрешение")
                .setMessage("Чтобы установить обновление, разрешите приложению устанавливать программы. " +
                    "Включите переключатель на открывшемся экране, вернитесь назад и нажмите «Обновить» ещё раз.")
                .setPositiveButton("Открыть настройки") { _, _ ->
                    activity.startActivity(
                        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))
                    )
                }
                .setNegativeButton("Отмена", null)
                .show()
            return
        }
        val file = apkFile(activity, rel.build)
        if (file.exists() && isIntact(file, rel)) { install(activity, file); return }
        download(activity, rel)
    }

    private fun download(activity: Activity, rel: Release) {
        val bar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; isIndeterminate = rel.size <= 0
        }
        val label = TextView(activity).apply { text = "Подключение…"; gravity = Gravity.CENTER }
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val p = (24 * resources.displayMetrics.density).toInt()
            setPadding(p, p, p, p / 2)
            addView(bar, LinearLayout.LayoutParams(-1, -2))
            addView(label, LinearLayout.LayoutParams(-1, -2).apply { topMargin = p / 2 })
        }
        var cancelled = false
        val dialog = AlertDialog.Builder(activity)
            .setTitle("Загрузка обновления")
            .setView(box)
            .setNegativeButton("Отмена") { _, _ -> cancelled = true; downloadCall?.cancel() }
            .setCancelable(false)
            .show()

        Thread {
            val dir = updatesDir(activity)
            val part = File(dir, "update-${rel.build}.part")
            val target = apkFile(activity, rel.build)
            var error: String? = null
            try {
                dir.mkdirs()
                dir.listFiles()?.forEach { if (it != part) it.delete() } // старые загрузки не копим
                val call = http.newCall(Request.Builder().url(rel.apkUrl).header("User-Agent", "FortPortClient").build())
                downloadCall = call
                call.execute().use { resp ->
                    if (!resp.isSuccessful) throw IOException("Сервер ответил ${resp.code}")
                    val body = resp.body ?: throw IOException("Пустой ответ")
                    val total = body.contentLength().takeIf { it > 0 } ?: rel.size
                    var done = 0L
                    var lastUi = 0L
                    body.byteStream().use { input ->
                        part.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                done += n
                                val now = System.currentTimeMillis()
                                if (now - lastUi > 150) {
                                    lastUi = now
                                    ui(activity) {
                                        if (total > 0) {
                                            bar.isIndeterminate = false
                                            bar.progress = (done * 100 / total).toInt()
                                            label.text = "%.1f из %.1f МБ".format(done / 1048576.0, total / 1048576.0)
                                        } else label.text = "%.1f МБ".format(done / 1048576.0)
                                    }
                                }
                            }
                        }
                    }
                }
                if (!isIntact(part, rel)) throw IOException("Файл повреждён или не совпадает с релизом")
                target.delete()
                if (!part.renameTo(target)) throw IOException("Не удалось сохранить файл")
            } catch (e: Exception) {
                part.delete()
                if (!cancelled) {
                    Log.w(TAG, "download failed", e)
                    error = e.message ?: "Ошибка загрузки"
                }
            }
            downloadCall = null
            ui(activity) {
                runCatching { dialog.dismiss() }
                when {
                    cancelled -> Unit
                    error != null -> Toast.makeText(activity, "Не удалось скачать обновление: $error", Toast.LENGTH_LONG).show()
                    else -> install(activity, target)
                }
            }
        }.start()
    }

    /** Размер и (если GitHub его дал) SHA-256 должны совпасть с описанием релиза. */
    private fun isIntact(f: File, rel: Release): Boolean {
        if (rel.size > 0 && f.length() != rel.size) return false
        val want = rel.sha256 ?: return f.length() > 0
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { s ->
            val buf = ByteArray(64 * 1024)
            while (true) { val n = s.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }.equals(want, ignoreCase = true)
    }

    private fun install(activity: Activity, file: File) {
        try {
            val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", file)
            activity.startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, APK_MIME)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
        } catch (e: Exception) {
            Log.w(TAG, "install failed", e)
            Toast.makeText(activity, "Не удалось запустить установку: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // ------------------------------------------------------------------ файлы

    private fun updatesDir(ctx: Context) = File(ctx.cacheDir, "updates")
    private fun apkFile(ctx: Context, build: Long) = File(updatesDir(ctx), "FortPortClient-$build.apk")

    /** После успешного обновления скачанный APK больше не нужен. Вызывать при запуске. */
    fun cleanup(ctx: Context) {
        val current = installedBuild(ctx)
        updatesDir(ctx).listFiles()?.forEach { f ->
            val n = Regex("(\\d+)\\.(apk|part)$").find(f.name)?.groupValues?.get(1)?.toLongOrNull()
            if (n == null || n <= current) f.delete()
        }
    }

    private fun ui(activity: Activity, block: () -> Unit) {
        activity.runOnUiThread { if (!activity.isFinishing && !activity.isDestroyed) block() }
    }
}
