package ru.fortport.client

import org.json.JSONArray
import org.json.JSONObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class UserLite(val id: Long, val name: String, val avatar: String, val role: UserRole)

class CommunityLite(val id: Long, val name: String, val avatar: String, val type: String)

/** Одна вкладка ленты (GET /api/users/feedlist → feeds[]). */
class FeedTab(
    val name: String,
    val filterType: String,
    val sortType: String,
    val timerange: String?,
    val showNsfw: Boolean,
    val showAnonymous: Boolean,
    val showMuted: Boolean
)

/** Что именно запрашиваем у POST /api/feed. */
sealed class FeedSource {
    class Tab(val tab: FeedTab) : FeedSource()
    class Community(val id: Long) : FeedSource()
    class User(val id: Long) : FeedSource()
}

class LikeResult(val liked: Boolean, val disliked: Boolean, val likeCount: Int, val dislikeCount: Int)

/** Результат запроса: ok либо текст ошибки; authLost — сервер ответил 401. */
class Outcome(val ok: Boolean, val error: String = "", val authLost: Boolean = false, val extra: JSONObject? = null)

/**
 * Все запросы нативных экранов с постами (лента, пост, профиль, порт).
 * Методы блокирующие: вызывать только не из UI-потока (SiteApi.io или pool).
 * Описание API — в справочнике проекта (api-feed.md).
 */
class PostsRepo(private val api: SiteApi) {

    companion object {
        const val PAGE_SIZE = 15
    }

    /** Отдельный пул для дозагрузки наград/портов, чтобы не стоять в очереди за ленточными запросами. */
    val pool: ExecutorService = Executors.newFixedThreadPool(3)

    @Volatile var users: Map<Long, UserLite> = emptyMap()
    /** Полный справочник GET /api/users уже загружен (а не только добавленные вручную). */
    @Volatile var usersLoaded = false
    private val communities = ConcurrentHashMap<Long, CommunityLite>()
    private val communityMisses = ConcurrentHashMap.newKeySet<Long>()

    val me: Long get() = api.userId.toLongOrNull() ?: 0L

    fun destroy() { pool.shutdownNow() }

    // ------------------------------------------------------------------ справочники

    /** GET /api/users — карта id → имя/аватар (в постах аватарок нет). Тяжёлый запрос: один раз на сессию экрана. */
    fun loadUsers(): Boolean {
        val r = api.get("/api/users")
        if (r.code != 200) return false
        val a = JSONArray(r.body)
        val m = HashMap<Long, UserLite>(a.length() * 2)
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val id = o.optLong("id")
            m[id] = UserLite(
                id, api.str(o, "username"), api.str(o, "profilePicture"),
                UserRole(o.optBoolean("isOwner"), o.optBoolean("isDeveloper"), o.optBoolean("isModerator"), api.str(o, "displayRole"))
            )
        }
        users = m
        usersLoaded = true
        return true
    }

    /** Добавляет пользователя в справочник (профиль открыт, а полный /api/users ещё не загружен). */
    fun putUser(u: UserLite) {
        if (users.containsKey(u.id)) return
        users = users + (u.id to u)
    }

    fun community(id: Long): CommunityLite? {
        communities[id]?.let { return it }
        if (communityMisses.contains(id)) return null
        return try {
            val r = api.get("/api/communities/$id")
            if (r.code != 200) { if (r.code == 404) communityMisses.add(id); return null }
            val o = JSONObject(r.body)
            CommunityLite(id, api.str(o, "username"), api.str(o, "profilePicture"), api.str(o, "type"))
                .also { communities[id] = it }
        } catch (e: Exception) { null }
    }

    fun putCommunity(c: CommunityLite) { communities[c.id] = c }

    fun cachedCommunity(id: Long): CommunityLite? = communities[id]

    // ------------------------------------------------------------------ вкладки ленты

    /** null — не удалось (сеть/сервер). Пустой список — у пользователя нет настроенных вкладок. */
    fun loadFeedTabs(): List<FeedTab>? {
        val r = api.get("/api/users/feedlist")
        if (r.code != 200) return null
        val a = JSONObject(r.body).optJSONArray("feeds") ?: return emptyList()
        val list = ArrayList<FeedTab>()
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            list.add(
                FeedTab(
                    name = api.str(o, "settingName"),
                    filterType = api.str(o, "filterType").ifBlank { "feed" },
                    sortType = api.str(o, "sortType").ifBlank { "new" },
                    timerange = if (o.opt("timerange") is String) o.optString("timerange") else null,
                    showNsfw = o.optInt("showNsfw", 0) == 1,
                    showAnonymous = o.optInt("showAnonymous", 1) != 0,
                    showMuted = o.optInt("showMuted", 1) != 0
                )
            )
        }
        return list
    }

    fun defaultTab() = FeedTab("Лента - новое", "feed", "new", null, false, true, true)

    // ------------------------------------------------------------------ посты

    /**
     * Страница постов. [cursor] — для sort=new это unix-секунды createdAt последнего поста
     * (`before`), для остальных сортировок — число уже загруженных постов (`offset`); null — первая страница.
     * На первой странице `before` НЕ передаём: сервер отвечает 400 «Invalid filter combination».
     */
    fun loadPosts(source: FeedSource, cursor: Long?): List<Post> {
        val q = StringBuilder()
        var body = "{}"
        val sortNew: Boolean
        when (source) {
            is FeedSource.Community -> {
                q.append("communityId=${source.id}&sort=new&filterinteracted=1"); sortNew = true
            }
            is FeedSource.User -> {
                q.append("userId=${source.id}&sort=new&filterinteracted=1"); sortNew = true
            }
            is FeedSource.Tab -> {
                val t = source.tab
                val ft = t.filterType
                val mixed = ft.startsWith("PORTS:[")
                q.append("filter=").append(
                    when {
                        mixed -> "mixed"
                        ft == "ports" -> "ports"
                        ft == "friends" -> "friends"
                        else -> "feed"
                    }
                )
                q.append("&sort=").append(URLEncoder.encode(t.sortType, "UTF-8"))
                if (t.showNsfw) q.append("&shownsfw=1")
                if (!t.showAnonymous) q.append("&showanon=0")
                val forced = t.sortType == "hot" || t.sortType == "quality"
                // На сайте «оставить просмотренные» для feed/friends (INTERACTED_FILTER_BY_TYPE = false).
                val keepsInteracted = ft == "feed" || ft == "friends"
                if (!forced && keepsInteracted) q.append("&filterinteracted=1")
                if (!t.showMuted) q.append("&filtermuted=0")
                if (!t.timerange.isNullOrBlank()) q.append("&time=").append(URLEncoder.encode(t.timerange, "UTF-8"))
                sortNew = t.sortType == "new"
                body = feedBody(t)
            }
        }
        if (cursor != null) q.append(if (sortNew) "&before=$cursor" else "&offset=$cursor")
        val r = api.send("POST", "/api/feed?$q", body)
        if (r.code == 401) throw AuthLostException()
        if (r.code != 200) throw IllegalStateException("feed ${r.code}")
        val arr = JSONObject(r.body).optJSONArray("posts") ?: JSONArray()
        val me = this.me
        val out = ArrayList<Post>(arr.length())
        for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { out.add(PostParser.post(it, me)) }
        return out
    }

    class AuthLostException : Exception()

    private fun feedBody(t: FeedTab): String {
        val o = JSONObject()
        val ft = t.filterType
        if (ft == "ports") {
            val r = api.get("/api/users/$me/commIds")
            if (r.code == 200) {
                val a = JSONArray(r.body); val ids = JSONArray()
                for (i in 0 until a.length()) a.optJSONObject(i)?.let { ids.put(it.optLong("id")) }
                o.put("communityIds", ids)
            }
        } else if (ft == "friends") {
            val r = api.get("/api/users/$me/friends")
            if (r.code == 200) {
                val a = JSONArray(r.body); val ids = JSONArray()
                for (i in 0 until a.length()) a.optJSONObject(i)?.let { ids.put(it.optLong("id")) }
                o.put("userIds", ids)
            }
        } else if (ft.startsWith("PORTS:[")) {
            // Смешанная лента: "PORTS:[1,2] USERS:[3,4]" (feed.js, parseMixedFilter).
            fun ids(tag: String): JSONArray {
                val a = JSONArray()
                Regex("$tag:\\[([^\\]]*)\\]").find(ft)?.groupValues?.get(1)?.split(',')
                    ?.mapNotNull { it.trim().toLongOrNull() }?.forEach { a.put(it) }
                return a
            }
            val ports = ids("PORTS"); val us = ids("USERS")
            if (ports.length() > 0) o.put("communityIds", ports)
            if (us.length() > 0) o.put("userIds", us)
        }
        return o.toString()
    }

    fun loadPost(id: Long): Post? {
        val r = api.get("/api/posts/$id")
        if (r.code != 200) return null
        return PostParser.post(JSONObject(r.body), me)
    }

    fun loadPinned(userId: Long): Post? {
        val r = api.get("/api/users/$userId/pinned-post")
        if (r.code != 200) return null
        val o = JSONObject(r.body).optJSONObject("post") ?: return null
        return PostParser.post(o, me)
    }

    // ------------------------------------------------------------------ действия

    private fun outcome(r: SiteApi.Resp): Outcome {
        if (r.code == 401) return Outcome(false, "Войдите заново", authLost = true)
        val o = try { JSONObject(r.body) } catch (e: Exception) { null }
        val ok = r.code in 200..299 && (o == null || !o.has("success") || o.optBoolean("success", true))
        return Outcome(ok, o?.optString("error").orEmpty(), extra = o)
    }

    private fun safe(block: () -> Outcome): Outcome = try { block() } catch (e: Exception) { Outcome(false, "Нет соединения") }

    fun like(postId: Long, dislike: Boolean): LikeResult? = try {
        val body = JSONObject().put("userId", me).toString()
        val r = api.send("POST", "/api/posts/$postId/${if (dislike) "dislike" else "like"}", body)
        val o = JSONObject(r.body)
        if (r.code == 200 && o.optBoolean("success")) {
            LikeResult(o.optBoolean("liked"), o.optBoolean("disliked"), o.optInt("likeCount"), o.optInt("dislikeCount"))
        } else null
    } catch (e: Exception) { null }

    fun loadAwards(postId: Long): List<PostAward>? = try {
        val r = api.get("/api/posts/$postId/award")
        if (r.code != 200) null else {
            val t = r.body.trim()
            val a = if (t.startsWith("[")) JSONArray(t) else JSONObject(t).optJSONArray("awards") ?: JSONArray()
            val out = ArrayList<PostAward>()
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                out.add(
                    PostAward(
                        o.optLong("awardId"), api.str(o, "awardName"), api.str(o, "awardImage"),
                        if (o.isNull("userId")) null else o.optLong("userId").takeIf { it > 0 }
                    )
                )
            }
            out
        }
    } catch (e: Exception) { null }

    fun loadAwardCatalog(): AwardCatalog? = try {
        val r = api.get("/api/awards")
        if (r.code != 200) null else {
            val o = JSONObject(r.body)
            val a = o.optJSONArray("awards") ?: JSONArray()
            val list = ArrayList<AwardInfo>()
            for (i in 0 until a.length()) {
                val x = a.optJSONObject(i) ?: continue
                // В ответе сервера опечатка: поле называется «avalible».
                list.add(
                    AwardInfo(
                        x.optLong("id"), api.str(x, "name"), api.str(x, "flavourText"), api.str(x, "imagePath"),
                        x.optInt("price", 0), x.optInt("avalible", x.optInt("available", 1)) != 0
                    )
                )
            }
            val d = o.optJSONObject("daily")
            AwardCatalog(list, d?.optBoolean("available", false) ?: false, d?.optLong("awardId", 0L)?.takeIf { it > 0 })
        }
    } catch (e: Exception) { null }

    fun giveAward(postId: Long, awardId: Long, anonymous: Boolean): Outcome = safe {
        val body = JSONObject().put("awardId", awardId).put("isAnonymous", anonymous).toString()
        outcome(api.send("POST", "/api/posts/$postId/award", body))
    }

    fun muteUser(userId: Long): Outcome = safe {
        outcome(api.send("POST", "/api/users/mute", JSONObject().put("targetUserId", userId).toString()))
    }

    fun pin(postId: Long, pin: Boolean): Outcome = safe {
        outcome(api.send(if (pin) "POST" else "DELETE", "/api/posts/pin/$postId", null))
    }

    fun deletePost(postId: Long): Outcome = safe { outcome(api.send("DELETE", "/api/posts/$postId", null)) }

    fun flag(postId: Long, type: String, notes: String): Outcome = safe {
        val b = JSONObject().put("postId", postId).put("flagType", type).put("notes", notes).toString()
        outcome(api.send("POST", "/api/posts/flag", b))
    }

    /** Просмотр поста (на сайте — «толчок» после нескольких секунд на экране). Ошибки игнорируем. */
    fun view(postId: Long) {
        try { api.send("POST", "/api/posts/$postId/view", "{}") } catch (_: Exception) {}
    }

    fun saveAudio(audioId: Long): Outcome = safe { outcome(api.send("POST", "/api/users/audios/$audioId", null)) }

    fun vote(choiceIds: List<Long>): Outcome = safe {
        val ids = JSONArray(); choiceIds.forEach { ids.put(it) }
        outcome(api.send("POST", "/api/polls/vote", JSONObject().put("choiceIds", ids).toString()))
    }

    /** DELETE с JSON-телом (отмена голоса). Не зависит от того, отправляет ли SiteApi.send тело для DELETE. */
    private fun deleteWithBody(path: String, json: String): SiteApi.Resp {
        val body = json.toRequestBody("application/json; charset=utf-8".toMediaType())
        val req = Request.Builder().url(SiteApi.ORIGIN + path).method("DELETE", body).build()
        api.client.newCall(req).execute().use { return SiteApi.Resp(it.code, it.body?.string().orEmpty()) }
    }

    fun unvote(choiceId: Long): Outcome = safe {
        outcome(deleteWithBody("/api/polls/vote", JSONObject().put("choiceId", choiceId).toString()))
    }

    /** «Да» комментарию: extra = {success, liked, likeCount}. */
    fun likeComment(postId: Long, commentId: Long): Outcome = safe {
        val body = JSONObject().put("userId", me).toString()
        outcome(api.send("POST", "/api/posts/$postId/comments/$commentId/like", body))
    }

    /**
     * Новый комментарий (multipart: userId, content, attachment?, refId? — id комментария, на который отвечаем).
     * После успеха сайт перечитывает пост целиком — так же делает и экран поста.
     */
    fun addComment(
        postId: Long, content: String, file: ByteArray?, fileName: String, mime: String, refId: Long?,
        existingPath: String? = null, existingType: String? = null
    ): Outcome = safe {
        val b = MultipartBody.Builder().setType(MultipartBody.FORM)
        b.addFormDataPart("userId", me.toString())
        b.addFormDataPart("content", content)
        if (file != null) {
            b.addFormDataPart("attachment", fileName, file.toRequestBody(mime.ifBlank { "application/octet-stream" }.toMediaType()))
        }
        if (existingPath != null && file == null) {
            // Файл из медиатеки: уходит ссылка, а не файл (как existingAttachment на сайте).
            b.addFormDataPart(
                "existingAttachment",
                JSONObject().put("path", existingPath).put("type", existingType ?: "image").toString()
            )
        }
        if (refId != null) b.addFormDataPart("refId", refId.toString())
        val req = Request.Builder().url(SiteApi.ORIGIN + "/api/posts/$postId/comments").post(b.build()).build()
        api.client.newCall(req).execute().use { outcome(SiteApi.Resp(it.code, it.body?.string().orEmpty())) }
    }

    class UploadFile(val bytes: ByteArray, val name: String, val mime: String)

    class NewPost(
        val content: String, val community: Long?, val anonymous: Boolean,
        val spoiler: Boolean, val spoilerPreview: String, val nsfw: Boolean,
        val pollJson: String?,
        /** Файлы из медиатеки: (путь, тип image|gif|video|audio). */
        val existing: List<Pair<String, String>>,
        val files: List<UploadFile>
    )

    /** Новый пост: POST /api/posts (multipart, как createPost() на сайте). */
    fun createPost(d: NewPost): Outcome = safe {
        val b = MultipartBody.Builder().setType(MultipartBody.FORM)
        b.addFormDataPart("content", d.content)
        b.addFormDataPart("userId", me.toString())
        b.addFormDataPart("community", d.community?.toString() ?: "")
        b.addFormDataPart("isAnonymous", d.anonymous.toString())
        if (d.spoiler) {
            b.addFormDataPart("isSpoiler", "1")
            b.addFormDataPart("spoilerPreview", d.spoilerPreview)
        }
        if (d.nsfw) b.addFormDataPart("isNsfw", "1")
        if (d.pollJson != null) b.addFormDataPart("poll", d.pollJson)
        if (d.existing.isNotEmpty()) {
            val arr = JSONArray()
            d.existing.forEach { e -> arr.put(JSONObject().put("path", e.first).put("type", e.second)) }
            b.addFormDataPart("existingFiles", arr.toString())
        }
        d.files.forEach { f ->
            b.addFormDataPart("files", f.name, f.bytes.toRequestBody(f.mime.ifBlank { "application/octet-stream" }.toMediaType()))
        }
        val req = Request.Builder().url(SiteApi.ORIGIN + "/api/posts").post(b.build()).build()
        api.client.newCall(req).execute().use { outcome(SiteApi.Resp(it.code, it.body?.string().orEmpty())) }
    }

    /** Порты, в которые можно написать (выбор «Новый пост в:»): [(id, название)]. */
    fun loadMyPorts(): List<Pair<Long, String>> = try {
        val r = api.get("/api/users/$me/commIds")
        val out = ArrayList<Pair<Long, String>>()
        if (r.code == 200) {
            val a = JSONArray(r.body)
            for (i in 0 until a.length()) a.optJSONObject(i)?.let { o -> out.add(o.optLong("id") to api.str(o, "username")) }
        }
        out
    } catch (e: Exception) { emptyList() }

    fun deleteComment(postId: Long, commentId: Long): Outcome = safe {
        outcome(api.send("DELETE", "/api/posts/$postId/comments/$commentId", null))
    }
}
