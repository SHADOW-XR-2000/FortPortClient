package ru.fortport.client

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import java.io.File
import android.util.Log
import okhttp3.MultipartBody
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Тестовый режим разработчика: «сервер» внутри приложения. SiteApi отдаёт сюда все запросы,
 * пока включён тестовый режим, поэтому нативные экраны работают без сети.
 *
 * Данные (виртуальный аккаунт, друзья, порты, лента, настройки) лежат в SharedPreferences
 * «demo_db» одним JSON-документом и переживают перезапуск; сбросить можно долгим нажатием
 * на кнопку тестового режима на экране входа.
 *
 * Формы ответов повторяют то, что разбирают контроллеры приложения (см. PostParser,
 * FriendsListController, PortController и т.д.). Чего здесь нет — отвечает 404 с пояснением.
 * Не реализовано: чаты (список всегда пуст), музыка (пусто), награды, загрузка картинок
 * (запрос принимается, но файл не сохраняется).
 */
class DemoBackend(context: Context) {

    companion object {
        const val ME = 900000001L
        private const val TAG = "DemoBackend"
        private const val KEY = "db"
        private const val PAGE = 15
        private val ISO = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.000'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    }

    private val prefs = context.applicationContext.getSharedPreferences("demo_db", Context.MODE_PRIVATE)
    private var db: JSONObject = load()
    private val mediaDir = File(context.applicationContext.filesDir, "demo_media").apply { mkdirs() }

    init { ensureMedia() }

    // ------------------------------------------------------------------ медиатека (фото/GIF/видео/аудио)

    /** Вид файла по mime: (ключ списка в медиатеке, тип во вложении поста). */
    private fun kindOf(mime: String, name: String): Pair<String, String> {
        val n = name.lowercase(Locale.ROOT)
        return when {
            mime.equals("image/gif", true) || n.endsWith(".gif") -> "gifs" to "gif"
            mime.startsWith("image/") -> "photos" to "image"
            mime.startsWith("video/") -> "videos" to "video"
            mime.startsWith("audio/") -> "audios" to "audio"
            n.endsWith(".mp4") || n.endsWith(".webm") || n.endsWith(".mkv") -> "videos" to "video"
            n.endsWith(".mp3") || n.endsWith(".ogg") || n.endsWith(".wav") || n.endsWith(".m4a") -> "audios" to "audio"
            else -> "photos" to "image"
        }
    }

    private fun mediaList(): JSONArray = db.optJSONArray("media") ?: JSONArray().also { db.put("media", it) }

    /** Сохраняет файл в память приложения и заносит его в медиатеку «аккаунта». */
    private fun addMedia(bytes: ByteArray, name: String, mime: String, artist: String = ""): JSONObject {
        val (kind, type) = kindOf(mime, name)
        val id = nextId()
        val ext = name.substringAfterLast('.', "").takeIf { it.length in 1..5 } ?: "bin"
        val f = File(mediaDir, "$id.$ext")
        f.writeBytes(bytes)
        val item = obj(
            "id" to id, "kind" to kind, "type" to type, "file_path" to "file://" + f.absolutePath,
            "name" to name, "artist_name" to artist, "user_id" to ME
        )
        mediaList().put(item)
        return item
    }

    private fun findMedia(id: Long): JSONObject? =
        mediaList().let { a -> (0 until a.length()).map { a.getJSONObject(it) }.firstOrNull { it.optLong("id") == id } }

    /** Первый запуск: несколько сгенерированных картинок, чтобы библиотеку было что открывать. */
    private fun ensureMedia() {
        if (db.has("media")) return
        db.put("media", JSONArray())
        val palette = listOf(
            0xFF3D7BFF to 0xFF00E5FF, 0xFFFF7A59 to 0xFFFFD166, 0xFF7B2FF7 to 0xFFF107A3,
            0xFF11998E to 0xFF38EF7D, 0xFFFC466B to 0xFF3F5EFB, 0xFF232526 to 0xFF9AA5B1
        )
        palette.forEachIndexed { i, (a, b) ->
            try {
                val bmp = Bitmap.createBitmap(480, 480, Bitmap.Config.ARGB_8888)
                val c = Canvas(bmp)
                c.drawRect(0f, 0f, 480f, 480f, Paint().apply { shader = LinearGradient(0f, 0f, 480f, 480f, a.toInt(), b.toInt(), Shader.TileMode.CLAMP) })
                c.drawText("", 0f, 0f, Paint())
                val out = java.io.ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.PNG, 90, out)
                addMedia(out.toByteArray(), "Пример ${i + 1}.png", "image/png")
            } catch (e: Exception) { Log.w(TAG, "sample media", e) }
        }
        persist()
    }

    // ------------------------------------------------------------------ хранилище

    private fun load(): JSONObject = try {
        prefs.getString(KEY, null)?.let { JSONObject(it) } ?: seed().also { persist(it) }
    } catch (e: Exception) { seed().also { persist(it) } }

    private fun persist(o: JSONObject = db) { prefs.edit().putString(KEY, o.toString()).apply() }

    @Synchronized fun reset() { mediaDir.listFiles()?.forEach { it.delete() }; db = seed(); persist(); ensureMedia() }

    private fun obj(vararg p: Pair<String, Any?>) = JSONObject().also { o -> p.forEach { (k, v) -> o.put(k, v) } }
    private fun arr(vararg v: Any?) = JSONArray().also { a -> v.forEach { a.put(it) } }
    private fun iso(ms: Long) = ISO.format(java.util.Date(ms))

    private fun user(id: Long, name: String, status: String, dev: Boolean = false) = obj(
        "id" to id, "username" to name, "profilePicture" to "", "status" to status,
        "isAdmin" to false, "isOwner" to false, "isDeveloper" to dev, "isModerator" to false,
        "displayRole" to (if (dev) "Тестовый режим" else null), "createdAt" to iso(System.currentTimeMillis() - 86_400_000L * 30)
    )

    private fun bioOf(city: String, work: String, about: String) = obj(
        "profileBackground" to null, "dateOfBirth" to "", "homeCountry" to city, "education" to "",
        "workplace" to work, "hobbies" to "", "fandoms" to "", "religion" to "", "description" to about
    )

    private fun post(id: Long, uid: Long, comm: Long?, text: String, ageH: Long, vararg extra: Pair<String, Any?>): JSONObject {
        val ms = System.currentTimeMillis() - ageH * 3_600_000L - id * 1_000L
        val o = obj(
            "id" to id, "userId" to uid, "community" to (comm?.toString() ?: ""), "content" to text, "createdMs" to ms,
            "likes" to JSONArray(), "dislikes" to JSONArray(), "isPinned" to false, "spoiler" to false, "nsfw" to false,
            "spoilerPreview" to "", "isAnonymous" to false, "comments" to JSONArray()
        )
        extra.forEach { (k, v) -> o.put(k, v) }
        return o
    }

    private fun comment(id: Long, uid: Long, text: String, ageMin: Long, ref: Long? = null) = obj(
        "id" to id, "userId" to uid, "content" to text, "reference" to ref, "isSpoiler" to false,
        "attachment" to "", "attachmentType" to "", "createdMs" to System.currentTimeMillis() - ageMin * 60_000L,
        "likes" to JSONArray()
    )

    private fun seed(): JSONObject {
        val users = JSONArray()
            .put(user(ME, "Dev_Tester", "Тестовый аккаунт", dev = false))
            .put(user(2, "Жека_Тест", "Кидаюсь кирпичами"))
            .put(user(3, "Кирпич", ""))
            .put(user(4, "Рокетпак", "Лечу на солнце"))
            .put(user(5, "Грувстрит_Фан", "Здесь был я"))
        val bio = obj(
            ME.toString() to bioOf("Виртуальный город", "Тестирую приложение", "Это виртуальный аккаунт тестового режима: данные лежат только на этом устройстве."),
            "2" to bioOf("Деревня", "Офис ФортПорта", "Мой любимый снаряд — кирпич."),
            "3" to bioOf("", "", ""), "4" to bioOf("На солнце", "Летать на рокетпаке", ""), "5" to bioOf("", "", "")
        )
        val seq = obj(
            "showPostsFeed" to 0, "showPostsProfile" to 0, "showInfoProfile" to 0, "anonymousPage" to 0,
            "showFriendsList" to 1, "showCommunitiesList" to 0, "showAudios" to 0, "showPhotos" to 0,
            "allowMessagesFrom" to 0, "showNSFW" to 0
        )
        val cust = obj(
            "customBackground" to null, "notificationSound" to 1, "chatMessageSound" to 1,
            "postSentSound" to 1, "friendOnlineSound" to 1, "friendRequestSound" to 1
        )
        val communities = JSONArray()
            .put(obj(
                "id" to 100, "username" to "Тестовый порт", "profilePicture" to "", "type" to "port", "status" to "Только для проверки",
                "description" to "Порт для проверки ленты порта.", "rules" to "1. Не бросаться кирпичами.", "owner" to 2,
                "moderators" to arr(2), "subscribers" to arr(2, ME), "createdAt" to iso(System.currentTimeMillis() - 86_400_000L * 20), "profileBackground" to null
            ))
            .put(obj(
                "id" to 101, "username" to "Сообщество кирпичей", "profilePicture" to "", "type" to "community", "status" to "Писать может каждый",
                "description" to "Здесь можно всё, что не запрещено.", "rules" to "", "owner" to 3,
                "moderators" to arr(3), "subscribers" to arr(3, 4), "createdAt" to iso(System.currentTimeMillis() - 86_400_000L * 10), "profileBackground" to null
            ))
        val posts = JSONArray()
            .put(post(1001, 2, null, "{title:\"Добро пожаловать в тестовый режим\"}\nВсе данные здесь виртуальные и лежат на устройстве. Лайкайте, комментируйте, меняйте настройки.", 1,
                "isPinned" to false,
                "comments" to JSONArray().put(comment(5001, 3, "Первый!", 50)).put(comment(5002, 4, "Кирпич в студию", 40, 5001))))
            .put(post(1002, 3, 100, "Пост в порту: проверка ленты порта и подписки.", 2))
            .put(post(1003, 4, 101, "Пост в сообществе, писать сюда может кто угодно.", 3))
            .put(post(1004, 5, null, "Длинный текст для проверки переноса строк. ".repeat(12).trim(), 5))
            .put(post(1005, 2, null, "Опрос: яблоки или кирпичи?", 6, "poll" to obj(
                "id" to 1, "title" to "Что съесть?", "multiChoice" to 0, "expiresAt" to null, "totalVotes" to 3,
                "choices" to JSONArray()
                    .put(obj("id" to 11, "text" to "Яблоки", "votes" to 1, "userVoted" to 0))
                    .put(obj("id" to 12, "text" to "Кирпичи", "votes" to 2, "userVoted" to 0)))))
            .put(post(1006, 3, null, "Спойлер: кирпич оказался настоящим.", 8, "spoiler" to true, "spoilerPreview" to "Осторожно, спойлер"))
            .put(post(1007, 4, null, "Контент 18+ скрыт по умолчанию (настройка «Показывать контент 18+»).", 9, "nsfw" to true))
            .put(post(1008, ME, null, "Мой собственный пост: проверка вкладки «Профиль».", 12))
            .put(post(1009, 5, 100, "Ещё один пост в тестовом порту.", 20))
            .put(post(1010, 2, null, "Пост номер десять.", 26))
            .put(post(1011, 3, null, "Пост номер одиннадцать.", 30))
            .put(post(1012, 4, null, "Пост номер двенадцать.", 40))
            .put(post(1013, 5, null, "Пост номер тринадцать.", 50))
            .put(post(1014, 2, null, "Пост номер четырнадцать.", 60))
            .put(post(1015, 3, null, "Пост номер пятнадцать (для проверки подгрузки страниц).", 70))
            .put(post(1016, 4, null, "Пост номер шестнадцать, на второй странице.", 80))
        return obj(
            "users" to users, "bio" to bio, "seq" to seq, "cust" to cust, "communities" to communities, "posts" to posts,
            "friends" to arr(2, 3), "pending" to arr(4), "outgoing" to JSONArray(), "nextId" to 6000
        )
    }

    // ------------------------------------------------------------------ вход

    private fun ok(o: Any) = SiteApi.Resp(200, o.toString())
    private fun success() = ok(obj("success" to true))
    private fun fail(code: Int, msg: String) = SiteApi.Resp(code, obj("success" to false, "error" to msg).toString())

    private fun users(): JSONArray = db.getJSONArray("users")
    private fun findUser(id: Long): JSONObject? = (0 until users().length()).map { users().getJSONObject(it) }.firstOrNull { it.optLong("id") == id }
    private fun community(id: Long): JSONObject? = db.getJSONArray("communities").let { a -> (0 until a.length()).map { a.getJSONObject(it) }.firstOrNull { it.optLong("id") == id } }
    private fun ids(a: JSONArray): MutableList<Long> = (0 until a.length()).map { a.optLong(it) }.toMutableList()
    private fun toArr(l: Collection<Long>) = JSONArray().also { a -> l.forEach { a.put(it) } }
    private fun nextId(): Long { val n = db.optLong("nextId", 6000); db.put("nextId", n + 1); return n }
    private fun allPosts(): List<JSONObject> = db.getJSONArray("posts").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
    private fun findPost(id: Long) = allPosts().firstOrNull { it.optLong("id") == id }

    /** Пользователь для bootstrap/входа. */
    fun meJson(): JSONObject = JSONObject(findUser(ME).toString())

    // ------------------------------------------------------------------ представления

    private fun userView(id: Long, withRelations: Boolean = false): JSONObject? {
        val u = findUser(id) ?: return null
        val o = JSONObject(u.toString())
        if (withRelations) {
            if (id == ME) { o.put("friends", db.getJSONArray("friends")); o.put("pending", db.getJSONArray("pending")) }
            else { o.put("friends", JSONArray()); o.put("pending", if (ids(db.getJSONArray("outgoing")).contains(id)) arr(ME) else JSONArray()) }
        }
        return o
    }

    private fun commentView(c: JSONObject): JSONObject {
        val o = JSONObject(c.toString())
        val u = findUser(c.optLong("userId"))
        o.put("username", u?.optString("username") ?: "Удалён"); o.put("profilePicture", u?.optString("profilePicture") ?: "")
        o.put("isOwner", false); o.put("isDeveloper", u?.optBoolean("isDeveloper") ?: false); o.put("isModerator", false)
        o.put("displayRole", u?.opt("displayRole")); o.put("createdAt", iso(c.optLong("createdMs")))
        o.put("likeCount", c.getJSONArray("likes").length()); o.remove("createdMs")
        return o
    }

    private fun postView(p: JSONObject): JSONObject {
        val o = JSONObject(p.toString())
        val u = findUser(p.optLong("userId"))
        o.put("username", u?.optString("username") ?: "Удалён")
        o.put("isOwner", false); o.put("isDeveloper", u?.optBoolean("isDeveloper") ?: false); o.put("isModerator", false)
        o.put("displayRole", u?.opt("displayRole"))
        if (!p.has("files")) { o.put("files", JSONArray()); o.put("fileTypes", JSONArray()); o.put("fileIds", JSONArray()); o.put("fileNames", JSONArray()) }
        o.put("createdAt", iso(p.optLong("createdMs")))
        o.put("likeCount", p.getJSONArray("likes").length()); o.put("dislikeCount", p.getJSONArray("dislikes").length())
        val cs = JSONArray(); val src = p.getJSONArray("comments")
        for (i in 0 until src.length()) cs.put(commentView(src.getJSONObject(i)))
        o.put("comments", cs); o.remove("createdMs")
        return o
    }

    /** Текстовые страницы сайта (/support, /about, /privacy, /terms) для тестового режима — той же разметкой, что и на сайте. */
    private fun infoPageHtml(path: String): String? {
        val title = when (path) {
            "/support" -> "Поддержка"; "/about" -> "О нас"; "/privacy" -> "Политика конфиденциальности"; "/terms" -> "Пользовательское соглашение"
            else -> return null
        }
        return "<html><body><div id=\"utility-bar\"><a href=\"/\">Домой</a></div><img src=\"/logo.png\"><h1>$title</h1>" +
            "<p><b>Тестовый режим:</b> это пример текста страницы, настоящий берётся с сайта.</p><hr>" +
            "<h2>1. Раздел</h2><p>1.1 Абзац с <a href=\"mailto:test@example.com\">ссылкой</a>.</p>" +
            "<h3>Подраздел:</h3><ul><li>Первый пункт</li><li>Второй пункт</li></ul><hr><p>Контакты: <a href=\"tel:+70000000000\">+7 000 000-00-00</a></p>" +
            "<footer>Подвал</footer></body></html>"
    }

    private fun canManagePort(c: JSONObject) = c.optLong("owner") == ME || ids(c.getJSONArray("moderators")).contains(ME)

    /** Ответ GET /api/community/{id}/settings: участники — объекты, время — unix-секунды. */
    private fun portSettingsView(c: JSONObject): JSONObject {
        fun member(id: Long) = obj(
            "id" to id, "username" to (findUser(id)?.optString("username") ?: "Удалён"),
            "profile_picture" to (findUser(id)?.optString("profilePicture") ?: ""),
            "isOwner" to false, "isDeveloper" to false, "isModerator" to false
        )
        fun members(a: JSONArray) = JSONArray().also { out -> ids(a).forEach { out.put(member(it)) } }
        return obj(
            "id" to c.optLong("id"), "username" to c.optString("username"), "type" to c.optString("type"),
            "created_at" to System.currentTimeMillis() / 1000, "profile_picture" to c.optString("profilePicture"),
            "description" to c.optString("description"), "rules" to c.optString("rules"), "status" to c.optString("status"),
            "owner_id" to c.optLong("owner"), "profile_background" to c.opt("profileBackground"),
            "moderators" to members(c.getJSONArray("moderators")), "subscribers" to members(c.getJSONArray("subscribers"))
        )
    }

    private fun communityView(c: JSONObject) = JSONObject(c.toString()).also {
        it.put("subscriberCount", c.getJSONArray("subscribers").length()); it.put("subCount", c.getJSONArray("subscribers").length())
    }

    private fun friendLite(id: Long) = findUser(id)?.let { u -> obj("id" to id, "username" to u.optString("username"), "profilePicture" to "", "status" to u.optString("status")) }

    // ------------------------------------------------------------------ маршрутизация

    @Synchronized
    fun handle(method: String, path: String, json: String?): SiteApi.Resp {
        return try {
            route(method.uppercase(Locale.ROOT), path, json?.let { try { JSONObject(it) } catch (e: Exception) { null } } ?: JSONObject())
                .also { if (method.uppercase(Locale.ROOT) != "GET") persist() }
        } catch (e: Exception) {
            Log.w(TAG, "$method $path", e)
            fail(500, "Тестовый режим: ошибка обработки запроса")
        }
    }

    private fun query(path: String): Map<String, String> =
        path.substringAfter('?', "").split('&').filter { it.contains('=') }.associate {
            URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8")
        }

    private fun route(method: String, full: String, body: JSONObject): SiteApi.Resp {
        val p = full.substringBefore('?')
        val q = query(full)
        fun m(re: String) = Regex(re).matchEntire(p)?.groupValues
        val write = method == "PUT" || method == "POST" || method == "PATCH"

        infoPageHtml(p)?.let { if (method == "GET") return SiteApi.Resp(200, it) }
        if (p == "/api/session/bootstrap") return ok(obj(
            "authenticated" to true, "user" to meJson(),
            "mascot" to obj("bricked" to 0, "handHolding" to true, "pettingRecord" to 0), "announcements" to JSONArray()
        ))
        if (p == "/api/logout") return success()
        if (p == "/api/payments/balance") return ok(obj("balanceUnits" to 1234, "formatted" to "1234 CD"))
        if (p == "/api/mascot/data/feed") return ok(obj("totalUsers" to users().length(), "totalPosts" to allPosts().size, "totalCommunities" to db.getJSONArray("communities").length()))
        if (p == "/api/users/mascot/news") return ok(obj("news" to JSONArray()))

        // --- настройки профиля
        m("/api/users/(bio|seq|cust)/(\\d+)")?.let { g ->
            val id = g[2].toLong()
            if (g[1] == "bio") {
                val b = db.getJSONObject("bio").optJSONObject(g[2]) ?: return fail(404, "Пользователь не найден")
                val u = findUser(id) ?: return fail(404, "Пользователь не найден")
                if (write) {
                    if (id != ME) return fail(403, "Нельзя менять чужой профиль")
                    body.keys().forEach { k -> if (k == "username") u.put("username", body.optString(k)) else if (k == "status") u.put("status", body.optString(k)); if (k != "username") b.put(k, body.opt(k)) }
                    return success()
                }
                return ok(JSONObject(b.toString()).also { it.put("id", id); it.put("username", u.optString("username")); it.put("profilePicture", u.optString("profilePicture")); it.put("status", u.optString("status")) })
            }
            val target = db.getJSONObject(g[1])
            if (id != ME) return fail(403, "Нет доступа")
            if (write) { body.keys().forEach { k -> target.put(k, body.opt(k)) }; return success() }
            return ok(target)
        }

        // --- уведомления и чаты (пока пусто)
        m("/api/users/notifications/(\\d+)/count")?.let { return ok(obj("count" to 0)) }
        m("/api/users/notifications/(\\d+)")?.let { return ok(JSONArray()) }
        if (p.startsWith("/api/users/notifications/") && write) return success()
        if (p == "/api/users/chats/unread") return ok(obj("unreadCount" to 0))
        if (p == "/api/users/chats/wait") return ok(obj())
        m("/api/users/chats/(\\d+)")?.let { return ok(obj("favouriteChats" to JSONArray(), "userChats" to JSONArray())) }
        m("/api/users/(photos|gifs|videos|audios)")?.let { g ->
            val kind = g[1]
            val out = JSONArray()
            mediaList().let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
                .filter { it.optString("kind") == kind }.reversed().forEach { out.put(it) }
            return ok(obj(kind to out))
        }

        // --- пользователи
        if (p == "/api/users" && method == "GET") return ok(JSONArray().also { a -> (0 until users().length()).forEach { a.put(users().getJSONObject(it)) } })
        if (p == "/api/users/feedlist") return ok(obj("feeds" to JSONArray()
            .put(feed("Лента - новое", "feed", "new")).put(feed("Друзья", "friends", "new"))
            .put(feed("Порты", "ports", "new")).put(feed("Лента - горячее", "feed", "hot"))))
        if (p == "/api/users/search") {
            val s = q["q"].orEmpty().lowercase(Locale.ROOT)
            return ok(JSONArray().also { a -> (0 until users().length()).map { users().getJSONObject(it) }.filter { it.optLong("id") != ME && it.optString("username").lowercase(Locale.ROOT).contains(s) }.forEach { a.put(it) } })
        }
        if (p == "/api/users/mute") return success()
        m("/api/users/(\\d+)/commIds")?.let { return ok(comms { c -> c.getJSONArray("subscribers").let { s -> ids(s).contains(ME) } }) }
        m("/api/users/(\\d+)/friends")?.let { g -> return ok(JSONArray().also { a -> if (g[1].toLong() == ME) ids(db.getJSONArray("friends")).forEach { a.put(obj("id" to it)) } }) }
        m("/api/users/(\\d+)/communities")?.let { g -> val id = g[1].toLong(); return ok(comms { c -> ids(c.getJSONArray("subscribers")).contains(id) }) }
        m("/api/users/(\\d+)/pinned-post")?.let { return ok(obj()) }
        m("/api/users/(\\d+)")?.let { g -> return userView(g[1].toLong(), true)?.let { ok(it) } ?: fail(404, "Пользователь не найден") }
        m("/api/user/communities/(\\d+)")?.let { g ->
            val id = g[1].toLong()
            return ok(obj("communities" to comms { c -> ids(c.getJSONArray("subscribers")).contains(id) }))
        }

        // --- друзья
        m("/api/friends/(\\d+)")?.let { g ->
            val id = g[1].toLong()
            val fr = JSONArray(); val pend = JSONArray()
            if (id == ME) {
                ids(db.getJSONArray("friends")).forEach { friendLite(it)?.let { u -> fr.put(u) } }
                ids(db.getJSONArray("pending")).forEach { friendLite(it)?.let { u -> pend.put(u) } }
            }
            return ok(obj("friends" to fr, "pending" to pend, "subscribersCount" to 0))
        }
        if (p.startsWith("/api/friends/") && write) {
            val friends = ids(db.getJSONArray("friends")); val pending = ids(db.getJSONArray("pending")); val out = ids(db.getJSONArray("outgoing"))
            when (p.substringAfterLast('/')) {
                "request" -> body.optLong("toUserId").let { if (it > 0 && it !in friends && it !in out) out.add(it) }
                "accept" -> body.optLong("requesterUserId").let { pending.remove(it); if (it > 0 && it !in friends) friends.add(it) }
                "reject" -> pending.remove(body.optLong("requesterUserId"))
                "remove" -> friends.remove(body.optLong("friendId"))
                "cancel" -> out.remove(body.optLong("toUserId"))
                else -> return fail(404, "Тестовый режим: $p не реализовано")
            }
            db.put("friends", toArr(friends)); db.put("pending", toArr(pending)); db.put("outgoing", toArr(out))
            return success()
        }
        if (p == "/api/chats/create") return fail(501, "Чаты недоступны в тестовом режиме")

        // --- порты
        if (p == "/api/communities/search") {
            val s = q["q"].orEmpty().lowercase(Locale.ROOT)
            return ok(comms { c -> c.optString("username").lowercase(Locale.ROOT).contains(s) })
        }
        // --- настройка порта (страницы создания/настройки; формы тел совпадают с PortSettingsController)
        m("/api/community/(\\d+)/settings")?.let { g ->
            val c = community(g[1].toLong()) ?: return fail(404, "Порт не найден")
            if (!canManagePort(c)) return fail(403, "Нет прав на настройку порта")
            return ok(portSettingsView(c))
        }
        if (method == "DELETE") m("/api/communities/(\\d+)")?.let { g ->
            val c = community(g[1].toLong()) ?: return fail(404, "Порт не найден")
            if (c.optLong("owner") != ME) return fail(403, "Удалить порт может только владелец")
            val a = db.getJSONArray("communities")
            for (i in 0 until a.length()) if (a.getJSONObject(i).optLong("id") == c.optLong("id")) { a.remove(i); break }
            return success()
        }
        m("/api/community/(\\d+)/moderators")?.let { g ->
            val c = community(g[1].toLong()) ?: return fail(404, "Порт не найден")
            if (c.optLong("owner") != ME) return fail(403, "Назначать модераторов может только владелец")
            val uid = body.optLong("userId", 0L)
            if (findUser(uid) == null) return fail(404, "Пользователь не найден")
            val mods = ids(c.getJSONArray("moderators")); if (!mods.contains(uid)) mods.add(uid)
            c.put("moderators", toArr(mods)); return success()
        }
        m("/api/community/(\\d+)/moderators/(\\d+)")?.let { g ->
            val c = community(g[1].toLong()) ?: return fail(404, "Порт не найден")
            if (c.optLong("owner") != ME) return fail(403, "Снимать модераторов может только владелец")
            val uid = g[2].toLong()
            if (uid == c.optLong("owner")) return fail(400, "Хозяина порта нельзя снять")
            val mods = ids(c.getJSONArray("moderators")); mods.remove(uid)
            c.put("moderators", toArr(mods)); return success()
        }
        m("/api/community/(\\d+)/subscribers/(\\d+)")?.let { g ->
            val c = community(g[1].toLong()) ?: return fail(404, "Порт не найден")
            if (!canManagePort(c)) return fail(403, "Нет прав")
            val uid = g[2].toLong()
            if (uid == c.optLong("owner")) return fail(400, "Хозяина порта нельзя удалить")
            val subs = ids(c.getJSONArray("subscribers")); subs.remove(uid)
            val mods = ids(c.getJSONArray("moderators")); mods.remove(uid)
            c.put("subscribers", toArr(subs)); c.put("moderators", toArr(mods)); return success()
        }
        m("/api/communities/(\\d+)")?.let { g -> return community(g[1].toLong())?.let { ok(communityView(it)) } ?: fail(404, "Порт не найден") }
        m("/api/communities/(\\d+)/(join|leave)")?.let { g ->
            val c = community(g[1].toLong()) ?: return fail(404, "Порт не найден")
            val subs = ids(c.getJSONArray("subscribers")); subs.remove(ME); if (g[2] == "join") subs.add(ME)
            c.put("subscribers", toArr(subs)); return success()
        }

        // --- посты
        if (p == "/api/feed" && method == "POST") return feed(q, body)
        if (p == "/api/awards") return ok(obj("awards" to JSONArray(), "daily" to obj("available" to false)))
        m("/api/posts/(\\d+)")?.let { g ->
            val post = findPost(g[1].toLong()) ?: return fail(404, "Пост не найден")
            if (method == "DELETE") { removePost(post.optLong("id")); return success() }
            return ok(postView(post))
        }
        m("/api/posts/(\\d+)/(like|dislike)")?.let { g ->
            val post = findPost(g[1].toLong()) ?: return fail(404, "Пост не найден")
            val likes = ids(post.getJSONArray("likes")); val dis = ids(post.getJSONArray("dislikes"))
            if (g[2] == "like") { if (!likes.remove(ME)) { likes.add(ME); dis.remove(ME) } }
            else { if (!dis.remove(ME)) { dis.add(ME); likes.remove(ME) } }
            post.put("likes", toArr(likes)); post.put("dislikes", toArr(dis))
            return ok(obj("success" to true, "liked" to likes.contains(ME), "disliked" to dis.contains(ME), "likeCount" to likes.size, "dislikeCount" to dis.size))
        }
        m("/api/posts/(\\d+)/view")?.let { return success() }
        m("/api/posts/(\\d+)/award")?.let { return if (write) fail(501, "Награды недоступны в тестовом режиме") else ok(obj("awards" to JSONArray())) }
        m("/api/posts/(\\d+)/comments/(\\d+)/like")?.let { g ->
            val c = findComment(g[1].toLong(), g[2].toLong()) ?: return fail(404, "Комментарий не найден")
            val l = ids(c.getJSONArray("likes")); if (!l.remove(ME)) l.add(ME); c.put("likes", toArr(l)); return success()
        }
        m("/api/posts/(\\d+)/comments/(\\d+)")?.let { g ->
            val post = findPost(g[1].toLong()) ?: return fail(404, "Пост не найден")
            if (method == "DELETE") {
                val src = post.getJSONArray("comments"); val keep = JSONArray()
                for (i in 0 until src.length()) if (src.getJSONObject(i).optLong("id") != g[2].toLong()) keep.put(src.getJSONObject(i))
                post.put("comments", keep); return success()
            }
        }
        m("/api/posts/pin/(\\d+)")?.let { g -> findPost(g[1].toLong())?.put("isPinned", method == "POST"); return success() }
        if (p == "/api/posts/flag") return success()
        if (p == "/api/polls/vote") {
            val chosen = ids(body.optJSONArray("choiceIds") ?: JSONArray())
            for (post in allPosts()) {
                val poll = post.optJSONObject("poll") ?: continue
                val ch = poll.getJSONArray("choices")
                if ((0 until ch.length()).none { ch.getJSONObject(it).optLong("id") in chosen || (method == "DELETE" && ch.getJSONObject(it).optInt("userVoted") == 1) }) continue
                var total = 0
                for (i in 0 until ch.length()) {
                    val c = ch.getJSONObject(i); val mine = c.optInt("userVoted") == 1
                    if (method == "DELETE") { if (mine) { c.put("votes", c.optInt("votes") - 1); c.put("userVoted", 0) } }
                    else if (c.optLong("id") in chosen && !mine) { c.put("votes", c.optInt("votes") + 1); c.put("userVoted", 1) }
                    total += c.optInt("votes")
                }
                poll.put("totalVotes", total)
            }
            return success()
        }
        return fail(404, "Тестовый режим: $method $p не реализовано")
    }

    private fun feed(name: String, type: String, sort: String) = obj(
        "settingName" to name, "filterType" to type, "sortType" to sort, "timerange" to null, "showNsfw" to 0, "showAnonymous" to 1, "showMuted" to 1
    )

    private fun comms(filter: (JSONObject) -> Boolean): JSONArray = JSONArray().also { out ->
        db.getJSONArray("communities").let { a -> (0 until a.length()).map { a.getJSONObject(it) }.filter(filter).forEach { out.put(communityView(it)) } }
    }

    private fun findComment(postId: Long, commentId: Long): JSONObject? {
        val a = findPost(postId)?.getJSONArray("comments") ?: return null
        return (0 until a.length()).map { a.getJSONObject(it) }.firstOrNull { it.optLong("id") == commentId }
    }

    private fun removePost(id: Long) {
        val keep = JSONArray(); val a = db.getJSONArray("posts")
        for (i in 0 until a.length()) if (a.getJSONObject(i).optLong("id") != id) keep.put(a.getJSONObject(i))
        db.put("posts", keep)
    }

    /** POST /api/feed: фильтры из query и тела (communityIds/userIds), пагинация before/offset. */
    private fun feed(q: Map<String, String>, body: JSONObject): SiteApi.Resp {
        var list = allPosts()
        val users = ids(body.optJSONArray("userIds") ?: JSONArray())
        val comms = ids(body.optJSONArray("communityIds") ?: JSONArray())
        val own = q["userId"]?.toLongOrNull(); val comm = q["communityId"]?.toLongOrNull()
        if (own != null) list = list.filter { it.optLong("userId") == own && !it.optBoolean("isAnonymous") }
        else if (comm != null) list = list.filter { it.optString("community") == comm.toString() }
        else {
            when (q["filter"]) {
                "friends" -> list = list.filter { it.optLong("userId") in users || it.optLong("userId") == ME }
                "ports" -> list = list.filter { it.optString("community").toLongOrNull() in comms }
                "mixed" -> list = list.filter { it.optLong("userId") in users || it.optString("community").toLongOrNull() in comms }
            }
            if (q["shownsfw"] != "1" && db.getJSONObject("seq").optInt("showNSFW") != 1) list = list.filter { !it.optBoolean("nsfw") }
            if (q["showanon"] == "0") list = list.filter { !it.optBoolean("isAnonymous") }
        }
        val sortNew = (q["sort"] ?: "new") == "new"
        val page: List<JSONObject>
        if (sortNew) {
            val before = q["before"]?.toLongOrNull()
            page = list.sortedByDescending { it.optLong("createdMs") }
                .filter { before == null || it.optLong("createdMs") / 1000 < before }.take(PAGE)
        } else {
            val offset = q["offset"]?.toIntOrNull() ?: 0
            page = list.sortedByDescending { it.getJSONArray("likes").length() - it.getJSONArray("dislikes").length() }.drop(offset).take(PAGE)
        }
        return ok(obj("posts" to JSONArray().also { a -> page.forEach { a.put(postView(it)) } }))
    }

    // ------------------------------------------------------------------ multipart (новые посты, комментарии, картинки)

    private fun fields(b: MultipartBody): Map<String, String> {
        val out = HashMap<String, String>()
        for (part in b.parts) {
            val name = Regex("name=\"([^\"]*)\"").find(part.headers?.get("Content-Disposition").orEmpty())?.groupValues?.get(1) ?: continue
            val ct = part.body.contentType()
            if (ct != null && ct.type != "text") continue // файлы пропускаем
            out[name] = Buffer().also { part.body.writeTo(it) }.readUtf8()
        }
        return out
    }

    @Synchronized
    fun handleMultipart(method: String, path: String, body: MultipartBody): SiteApi.Resp {
        return try {
        val f = fields(body)
        val p = path.substringBefore('?')
        val res = when {
            p == "/api/posts" -> {
                val attached = attachments(body, f, "files", "existingFiles")
                val id = nextId()
                val post = post(id, ME, f["community"]?.toLongOrNull(), f["content"].orEmpty(), 0,
                    "isAnonymous" to (f["isAnonymous"] == "true"), "spoiler" to (f["isSpoiler"] == "1"),
                    "spoilerPreview" to f["spoilerPreview"].orEmpty(), "nsfw" to (f["isNsfw"] == "1"))
                post.put("createdMs", System.currentTimeMillis())
                f["poll"]?.let { raw -> pollFrom(raw)?.let { post.put("poll", it) } }
                if (attached.isNotEmpty()) {
                    post.put("files", JSONArray().also { a -> attached.forEach { a.put(it.optString("file_path")) } })
                    post.put("fileTypes", JSONArray().also { a -> attached.forEach { a.put(it.optString("type")) } })
                    post.put("fileIds", JSONArray().also { a -> attached.forEach { a.put(it.optLong("id")) } })
                    post.put("fileNames", JSONArray().also { a -> attached.forEach { a.put(it.optString("name")) } })
                }
                db.getJSONArray("posts").put(post)
                ok(obj("success" to true, "id" to id))
            }
            Regex("/api/posts/(\\d+)/comments").matches(p) -> {
                val post = findPost(Regex("/api/posts/(\\d+)/comments").matchEntire(p)!!.groupValues[1].toLong()) ?: return fail(404, "Пост не найден")
                val att = attachments(body, f, "attachment", "existingAttachment").firstOrNull()
                val c = comment(nextId(), ME, f["content"].orEmpty(), 0, f["refId"]?.toLongOrNull())
                if (att != null) {
                    c.put("attachment", att.optString("file_path"))
                    c.put("attachmentType", if (att.optString("type") == "gif") "image" else att.optString("type"))
                }
                c.put("createdMs", System.currentTimeMillis())
                post.getJSONArray("comments").put(c)
                ok(obj("success" to true, "id" to c.optLong("id")))
            }
            p == "/api/communities" && method == "POST" -> {
                val name = f["username"].orEmpty().trim()
                if (name.isEmpty()) return fail(400, "Введите название порта")
                val id = nextId()
                db.getJSONArray("communities").put(obj(
                    "id" to id, "username" to name, "profilePicture" to "", "type" to (f["type"] ?: "community"), "status" to "",
                    "description" to f["description"].orEmpty(), "rules" to f["rules"].orEmpty(), "owner" to ME,
                    "moderators" to arr(ME), "subscribers" to arr(ME), "createdAt" to iso(System.currentTimeMillis()), "profileBackground" to null
                ))
                ok(obj("success" to true, "id" to id))
            }
            Regex("/api/community/(\\d+)/settings").matches(p) && method == "PUT" -> {
                val c = community(Regex("/api/community/(\\d+)/settings").matchEntire(p)!!.groupValues[1].toLong()) ?: return fail(404, "Порт не найден")
                if (!canManagePort(c)) return fail(403, "Нет прав на настройку порта")
                val name = f["username"].orEmpty().trim()
                if (name.isEmpty()) return fail(400, "Введите название порта")
                c.put("username", name)
                f["type"]?.let { c.put("type", it) }
                f["status"]?.let { c.put("status", it) }
                f["description"]?.let { c.put("description", it) }
                f["rules"]?.let { c.put("rules", it) }
                success() // файлы (аватар/фон) принимаем, но не сохраняем
            }
            p.startsWith("/api/users/") -> success() // загрузка аватара/фона: принимаем, файл не сохраняем
            else -> fail(404, "Тестовый режим: $method $p не реализовано")
        }
        persist(); res
    } catch (e: Exception) {
        Log.w(TAG, "multipart $path", e); fail(500, "Тестовый режим: ошибка обработки запроса")
    }
    }

    /**
     * Вложения запроса: загруженные файлы (поле [fileField]) сохраняются и попадают в медиатеку,
     * файлы из медиатеки приходят в [existingField] как {path:"/photo/ID", type} (или массив таких).
     */
    private fun attachments(b: MultipartBody, f: Map<String, String>, fileField: String, existingField: String): List<JSONObject> {
        val out = ArrayList<JSONObject>()
        for (part in b.parts) {
            val cd = part.headers?.get("Content-Disposition").orEmpty()
            val name = Regex("name=\"([^\"]*)\"").find(cd)?.groupValues?.get(1) ?: continue
            val fileName = Regex("filename=\"([^\"]*)\"").find(cd)?.groupValues?.get(1) ?: continue
            if (name != fileField) continue
            val bytes = Buffer().also { part.body.writeTo(it) }.readByteArray()
            out.add(addMedia(bytes, fileName.ifBlank { "file" }, part.body.contentType()?.toString().orEmpty().substringBefore(';')))
        }
        f[existingField]?.let { raw ->
            try {
                val arr = if (raw.trimStart().startsWith("[")) JSONArray(raw) else JSONArray().put(JSONObject(raw))
                for (i in 0 until arr.length()) {
                    val id = Regex("(\\d+)$").find(arr.optJSONObject(i)?.optString("path").orEmpty())?.groupValues?.get(1)?.toLongOrNull() ?: continue
                    findMedia(id)?.let { out.add(it) }
                }
            } catch (e: Exception) { Log.w(TAG, "existing media", e) }
        }
        return out
    }

    private fun pollFrom(raw: String): JSONObject? = try {
        val o = JSONObject(raw)
        val src = o.optJSONArray("choices") ?: o.optJSONArray("options") ?: JSONArray()
        val ch = JSONArray()
        for (i in 0 until src.length()) {
            val v = src.opt(i)
            val text = if (v is JSONObject) v.optString("text") else v.toString()
            if (text.isNotBlank()) ch.put(obj("id" to nextId(), "text" to text, "votes" to 0, "userVoted" to 0))
        }
        obj("id" to nextId(), "title" to o.optString("title"), "multiChoice" to (if (o.optBoolean("multiChoice") || o.optInt("multiChoice") == 1) 1 else 0),
            "expiresAt" to null, "totalVotes" to 0, "choices" to ch)
    } catch (e: Exception) { null }
}
