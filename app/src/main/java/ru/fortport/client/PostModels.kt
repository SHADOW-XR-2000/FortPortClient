package ru.fortport.client

import org.json.JSONArray
import org.json.JSONObject

/** Награда на посте (GET /api/posts/{id}/award → awards[]). */
class PostAward(val awardId: Long, val name: String, val image: String, val giverId: Long?)

/** Награда из каталога (GET /api/awards → awards[]). */
class AwardInfo(
    val id: Long,
    val name: String,
    val flavour: String,
    val image: String,
    val price: Int,
    val available: Boolean
)

class AwardCatalog(val awards: List<AwardInfo>, val dailyAvailable: Boolean, val dailyAwardId: Long?)

class PollChoice(val id: Long, val text: String, var votes: Int, var userVoted: Boolean)

class Poll(
    val id: Long,
    val title: String,
    val multiChoice: Boolean,
    /** unix-секунды или null. */
    val expiresAt: Long?,
    var totalVotes: Int,
    var choices: List<PollChoice>
) {
    val hasVoted: Boolean get() = choices.any { it.userVoted }
}

class PostComment(
    val id: Long,
    val userId: Long,
    val username: String,
    val profilePicture: String,
    val role: UserRole,
    val content: String,
    /** id родительского комментария; null — комментарий верхнего уровня. */
    val reference: Long?,
    val isSpoiler: Boolean,
    val attachment: String,
    val attachmentType: String,
    val createdAt: Long,
    var likeCount: Int,
    var likedByMe: Boolean
)

class UserRole(val isOwner: Boolean, val isDeveloper: Boolean, val isModerator: Boolean, val displayRole: String) {
    /** Подпись бейджа роли (как у role-badges.js на сайте — текстовая замена значка). */
    val badge: String
        get() = when {
            isOwner -> "Владелец"
            isDeveloper -> "Разработчик"
            isModerator -> "Модератор"
            else -> ""
        }
}

class Post(
    val id: Long,
    val userId: Long?,
    val username: String,
    val role: UserRole,
    var isPinned: Boolean,
    val content: String,
    val files: List<String>,
    val fileTypes: List<String>,
    val fileIds: List<Long>,
    val fileNames: List<String>,
    /** id порта или null для личного поста. */
    val communityId: Long?,
    val isAnonymous: Boolean,
    /** unix-миллисекунды. */
    val createdAt: Long,
    var likeCount: Int,
    var dislikeCount: Int,
    var likedByMe: Boolean,
    var dislikedByMe: Boolean,
    val spoiler: Boolean,
    val nsfw: Boolean,
    val spoilerPreview: String,
    var poll: Poll?,
    val comments: List<PostComment>,
    /** Число комментариев: в ленте порта/пользователя сервер присылает только число, а не массив. */
    val commentCount: Int
) {
    val score: Int get() = likeCount - dislikeCount

    /** Состояние, которое в списке нужно подгружать отдельно (не приходит с постом). */
    var awards: List<PostAward>? = null

    /** Спойлер/18+ раскрыт пользователем. */
    var revealed = false
    val isCovered: Boolean get() = (spoiler || nsfw) && !revealed
}

class MediaItem(val path: String, val type: String, val id: Long?, val name: String)

object PostParser {

    private fun str(o: JSONObject, k: String) = if (o.isNull(k)) "" else o.optString(k, "")

    private fun longOrNull(o: JSONObject, k: String): Long? =
        if (o.isNull(k)) null else o.optLong(k, 0L).takeIf { it > 0L }

    private fun idLong(v: Any?): Long? = when (v) {
        is Number -> v.toLong()
        is String -> v.toLongOrNull()
        else -> null
    }

    private fun idList(a: JSONArray?): List<Long> {
        if (a == null) return emptyList()
        val r = ArrayList<Long>(a.length())
        for (i in 0 until a.length()) idLong(a.opt(i))?.let { r.add(it) }
        return r
    }

    private fun strList(a: JSONArray?, size: Int): List<String> {
        val r = ArrayList<String>(size)
        for (i in 0 until size) r.add(if (a == null || a.isNull(i)) "" else a.optString(i, ""))
        return r
    }

    private fun role(o: JSONObject) = UserRole(
        o.optBoolean("isOwner", false), o.optBoolean("isDeveloper", false),
        o.optBoolean("isModerator", false), str(o, "displayRole")
    )

    /** ISO-8601 «2026-10-23T18:08:30.000Z» → unix-миллисекунды (0, если не разобралось). */
    fun parseIso(s: String): Long = try {
        val f = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
        f.timeZone = java.util.TimeZone.getTimeZone("UTC")
        f.parse(s.take(19))?.time ?: 0L
    } catch (e: Exception) { 0L }

    fun poll(o: JSONObject?): Poll? {
        if (o == null) return null
        val ch = o.optJSONArray("choices") ?: JSONArray()
        val choices = ArrayList<PollChoice>()
        for (i in 0 until ch.length()) {
            val c = ch.optJSONObject(i) ?: continue
            choices.add(
                PollChoice(
                    c.optLong("id"), str(c, "text"), c.optInt("votes", 0),
                    c.optInt("userVoted", 0) == 1 || c.optBoolean("userVoted", false)
                )
            )
        }
        return Poll(
            o.optLong("id"), str(o, "title"), o.optInt("multiChoice", 0) == 1,
            longOrNull(o, "expiresAt"), o.optInt("totalVotes", 0), choices
        )
    }

    fun comment(o: JSONObject, me: Long): PostComment {
        val ref = o.opt("reference")
        val refId = idLong(ref)?.takeIf { it > 0L }
        return PostComment(
            id = o.optLong("id"),
            userId = o.optLong("userId"),
            username = str(o, "username"),
            profilePicture = str(o, "profilePicture"),
            role = role(o),
            content = str(o, "content"),
            reference = refId,
            isSpoiler = o.optBoolean("isSpoiler", false),
            attachment = str(o, "attachment"),
            attachmentType = str(o, "attachmentType"),
            createdAt = parseIso(str(o, "createdAt")),
            likeCount = o.optInt("likeCount", 0),
            likedByMe = idList(o.optJSONArray("likes")).contains(me)
        )
    }

    fun post(o: JSONObject, me: Long): Post {
        val files = strList(o.optJSONArray("files"), o.optJSONArray("files")?.length() ?: 0)
        val types = strList(o.optJSONArray("fileTypes"), files.size)
        val ids = idList(o.optJSONArray("fileIds"))
        // «comments» в ленте — массив; на всякий случай терпим и число.
        val comments = ArrayList<PostComment>()
        o.optJSONArray("comments")?.let { a ->
            for (i in 0 until a.length()) a.optJSONObject(i)?.let { comments.add(comment(it, me)) }
        }
        val comm = str(o, "community").toLongOrNull()?.takeIf { it > 0L }
        return Post(
            id = o.optLong("id"),
            userId = longOrNull(o, "userId"),
            username = str(o, "username"),
            role = role(o),
            isPinned = o.optBoolean("isPinned", false),
            content = str(o, "content"),
            files = files,
            fileTypes = types,
            fileIds = ids,
            fileNames = strList(o.optJSONArray("fileNames"), files.size),
            communityId = comm,
            isAnonymous = o.optBoolean("isAnonymous", false),
            createdAt = parseIso(str(o, "createdAt")),
            likeCount = o.optInt("likeCount", 0),
            dislikeCount = o.optInt("dislikeCount", 0),
            likedByMe = idList(o.optJSONArray("likes")).contains(me),
            dislikedByMe = idList(o.optJSONArray("dislikes")).contains(me),
            spoiler = o.optBoolean("spoiler", false),
            nsfw = o.optBoolean("nsfw", false),
            spoilerPreview = str(o, "spoilerPreview"),
            poll = poll(o.optJSONObject("poll")),
            comments = comments,
            commentCount = (o.opt("comments") as? Number)?.toInt() ?: comments.size
        )
    }

    /** Фото/видео/GIF поста (аудио показывается отдельными строками). */
    fun visualMedia(p: Post): List<MediaItem> {
        val r = ArrayList<MediaItem>()
        p.files.forEachIndexed { i, f ->
            val t = p.fileTypes.getOrNull(i).orEmpty().ifBlank { "image" }
            if (t == "image" || t == "gif" || t == "video") {
                r.add(MediaItem(f, t, p.fileIds.getOrNull(i), p.fileNames.getOrNull(i).orEmpty()))
            }
        }
        return r
    }

    fun audioItems(p: Post): List<MediaItem> {
        val r = ArrayList<MediaItem>()
        p.files.forEachIndexed { i, f ->
            if (p.fileTypes.getOrNull(i) == "audio") {
                r.add(MediaItem(f, "audio", p.fileIds.getOrNull(i), p.fileNames.getOrNull(i).orEmpty()))
            }
        }
        return r
    }
}
