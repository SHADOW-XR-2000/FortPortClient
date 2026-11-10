package ru.fortport.client

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Пользовательские скины маскота: импорт из zip, список, удаление.
 *
 * Формат zip (корень может быть обёрнут в одну папку — это допустимо):
 *   skin.json            {"name": "Название для переключателя", "author": "..."}   — обязателен
 *   phrases/<категория>.json   массивы строк (см. assets/ui/mascot/skins/README.md)  — любые категории
 *   video/mascot-<эмоция>.webm прозрачные WebM: peek, lookout, wave, killed, stand, think, blush, yell
 *
 * Скину не нужно содержать всё: чего нет — берётся у маскота по умолчанию.
 * Установленный скин лежит в files/mascots/<безопасное имя>/ (skin.json, phrases/, video/).
 */
object MascotSkins {
    const val DEFAULT = "Жека"

    private const val MAX_ENTRIES = 300
    private const val MAX_TOTAL = 80L * 1024 * 1024
    private const val MAX_JSON = 1L * 1024 * 1024
    private const val MAX_VIDEO = 30L * 1024 * 1024
    private val VIDEOS = setOf("peek", "lookout", "wave", "killed", "stand", "think", "blush", "yell")
    private val CATEGORY_RE = Regex("[A-Za-z0-9_\\-]{1,60}\\.json")

    class SkinException(message: String) : Exception(message)

    /** Итог импорта: что реально попало в скин (чтобы было видно, если что-то не распознано). */
    class Result(
        val name: String, val categories: List<String>, val unknown: List<String>, val videos: Int,
        /** Файлы архива, не попавшие в скин: .json вне phrases/ и webm с неизвестным именем. */
        val skipped: List<String> = emptyList()
    )

    private const val PREFS = "settings_ui"
    private const val REV_KEY = "mascot_skin_rev"

    /** Счётчик установок: меняется при каждом импорте, чтобы маскот перечитал реплики даже у скина с тем же названием. */
    fun revision(ctx: Context): Int = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(REV_KEY, 0)

    private fun bumpRevision(ctx: Context) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        p.edit().putInt(REV_KEY, p.getInt(REV_KEY, 0) + 1).apply()
    }

    /** Категории, которые приложение действительно использует. Остальные файлы фраз игнорируются. */
    val KNOWN_CATEGORIES = setOf(
        "greetings", "poke", "random", "own_profile", "profile", "port", "fortport",
        "hh_feed", "hh_communities", "hh_community", "hh_community_settings", "hh_profile",
        "hh_own_profile", "hh_settings", "hh_new_community", "hh_whatis_port"
    )

    fun root(ctx: Context) = File(ctx.filesDir, "mascots")

    /** Папка скина: имя очищается от символов, недопустимых в путях. */
    fun safe(name: String) = name.trim().replace(Regex("[^\\p{L}\\p{N} _\\-]"), "_").take(40).trim().ifEmpty { "skin" }

    fun dir(ctx: Context, name: String) = File(root(ctx), safe(name))

    /** Названия установленных скинов (из skin.json), по алфавиту. */
    fun installed(ctx: Context): List<String> =
        (root(ctx).listFiles { f -> f.isDirectory && !f.name.startsWith(".") } ?: emptyArray())
            .mapNotNull { readMeta(it)?.optString("name")?.takeIf { n -> n.isNotBlank() } }
            .distinct().sortedBy { it.lowercase() }

    private fun readMeta(dir: File): JSONObject? = try { JSONObject(File(dir, "skin.json").readText()) } catch (e: Exception) { null }

    fun delete(ctx: Context, name: String): Boolean {
        if (name == DEFAULT) return false
        val ok = dir(ctx, name).deleteRecursively()
        bumpRevision(ctx)
        return ok
    }

    /** Путь видео скина для WebView (/skins/…), либо null — играть стандартное из assets. */
    fun videoPath(ctx: Context, skin: String, video: String): String? {
        if (skin == DEFAULT) return null
        val s = safe(skin)
        if (!File(root(ctx), "$s/video/mascot-$video.webm").isFile) return null
        return "/skins/${Uri.encode(s)}/video/mascot-$video.webm"
    }

    /** Устанавливает скин из zip; возвращает его название. При ошибке бросает [SkinException] с понятным текстом. */
    fun install(ctx: Context, uri: Uri): Result {
        val rootDir = root(ctx).apply { mkdirs() }
        val tmp = File(rootDir, ".tmp_${System.currentTimeMillis()}")
        try {
            val input = ctx.contentResolver.openInputStream(uri) ?: throw SkinException("Не удалось открыть файл")
            val skipped = ArrayList<String>()
            input.use { extract(it, tmp, skipped) }
            val meta = try { JSONObject(File(tmp, "skin.json").readText()) } catch (e: Exception) {
                throw SkinException("В архиве нет корректного skin.json")
            }
            val name = meta.optString("name").trim()
            if (name.isEmpty()) throw SkinException("В skin.json не указано название (name)")
            if (name.length > 40) throw SkinException("Название маскота длиннее 40 символов")
            if (safe(name).equals(safe(DEFAULT), true) || name.equals("default", true)) {
                throw SkinException("Это имя занято основным маскотом")
            }
            val phrases = File(tmp, "phrases").listFiles()?.toList().orEmpty()
            val videos = File(tmp, "video").listFiles()?.toList().orEmpty()
            if (phrases.isEmpty() && videos.isEmpty()) throw SkinException("В архиве нет ни фраз (папка phrases/), ни видео (папка video/)")
            for (f in phrases) validatePhrases(f)
            File(tmp, "skin.json").writeText(JSONObject().put("name", name).put("author", meta.optString("author")).toString(2))
            val target = File(rootDir, safe(name))
            if (target.exists() && !target.deleteRecursively()) throw SkinException("Не удалось заменить прежнюю версию")
            if (!tmp.renameTo(target)) throw SkinException("Не удалось сохранить маскота")
            val cats = phrases.map { it.name.removeSuffix(".json") }.sorted()
            bumpRevision(ctx)
            return Result(name, cats.filter { it in KNOWN_CATEGORIES }, cats.filter { it !in KNOWN_CATEGORIES }, videos.size, skipped)
        } finally {
            tmp.deleteRecursively()
        }
    }

    private fun validatePhrases(f: File) {
        try {
            val a = JSONArray(f.readText())
            for (i in 0 until a.length()) a.getString(i)
        } catch (e: Exception) {
            throw SkinException("${f.name}: ожидается JSON-массив строк")
        }
    }

    /**
     * Распаковывает только нужное и только по фиксированным путям (zip-slip невозможен: из имени записи берётся
     * лишь последний компонент): skin.json, phrases/<категория>.json, video/mascot-*.webm.
     */
    private fun extract(input: InputStream, out: File, skipped: MutableList<String>) {
        out.mkdirs()
        var total = 0L
        var entries = 0
        var metaDepth = Int.MAX_VALUE
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (e.isDirectory) continue
                if (++entries > MAX_ENTRIES) throw SkinException("В архиве слишком много файлов")
                val parts = e.name.replace('\\', '/').split('/').filter { it.isNotEmpty() }
                if (parts.isEmpty() || parts.first() == "__MACOSX") continue
                val base = parts.last()
                if (base.startsWith(".")) continue
                val parent = parts.getOrNull(parts.size - 2)?.lowercase()
                val dest: File; val limit: Long
                when {
                    base.equals("skin.json", true) -> {
                        if (parts.size > metaDepth || parts.size > 2) continue // берём самый верхний skin.json
                        metaDepth = parts.size
                        dest = File(out, "skin.json"); limit = MAX_JSON
                    }
                    base.endsWith(".json", true) && parent == "phrases" -> {
                        if (!CATEGORY_RE.matches(base)) throw SkinException("Недопустимое имя файла фраз: $base")
                        dest = File(out, "phrases/${base.lowercase()}"); limit = MAX_JSON
                    }
                    base.endsWith(".webm", true) -> {
                        val n = base.substring(0, base.length - 5).lowercase().removePrefix("mascot-")
                        if (n !in VIDEOS) { skipped.add(e.name); continue }
                        dest = File(out, "video/mascot-$n.webm"); limit = MAX_VIDEO
                    }
                    else -> {
                        // .json вне phrases/ — частая ошибка в структуре архива: сообщаем, а не молчим
                        if (base.endsWith(".json", true)) skipped.add(e.name)
                        continue
                    }
                }
                dest.parentFile?.mkdirs()
                dest.outputStream().use { o ->
                    val buf = ByteArray(16 * 1024)
                    var size = 0L
                    while (true) {
                        val n = zip.read(buf)
                        if (n < 0) break
                        size += n; total += n
                        if (size > limit) throw SkinException("Файл ${base} слишком большой")
                        if (total > MAX_TOTAL) throw SkinException("Архив слишком большой")
                        o.write(buf, 0, n)
                    }
                }
            }
        }
    }
}
