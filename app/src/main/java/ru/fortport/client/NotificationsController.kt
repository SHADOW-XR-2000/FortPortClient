package ru.fortport.client

import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import org.json.JSONArray
import org.json.JSONObject
import ru.fortport.client.UiUtil.dp
import java.util.Calendar

/**
 * Нативная вкладка «Уведомления» (на сайте — выпадающая панель из auth.js).
 *
 * API: GET /api/users/notifications/{me} — список; POST /api/users/notifications/read/{id} — прочитано (удаляет);
 * POST /api/users/notifications/{me}/read — прочитать все; заявки в друзья — POST /api/friends/accept|reject {requesterUserId}.
 * Кнопки по типу: комментарий/ответ — «Ответить» (пост с прокруткой к комментарию), лайк/награда — «Перейти»,
 * заявка в друзья — «Принять»/«Отклонить», принятая заявка — «Перейти» (профиль); везде «Прочитать».
 */
class NotificationsController(
    private val activity: AppCompatActivity,
    private val api: SiteApi,
    private val swipe: SwipeRefreshLayout,
    private val recycler: RecyclerView,
    private val onOpenPost: (postId: Long, commentId: Long) -> Unit,
    private val onOpenProfile: (Long) -> Unit,
    /** Сколько уведомлений осталось (для значка колокольчика). */
    private val onCount: (Int) -> Unit,
    private val onAuthLost: () -> Unit
) {
    companion object {
        private const val TAG = "Notifications"
    }

    private class Notif(
        val id: Long, val type: String, val sourceId: Long, val postId: Long?, val commentId: Long?,
        val requesterId: Long?, val name: String, val picture: String, val text: String,
        val preview: String, val createdAt: Long, val awardImage: String
    )

    private sealed class Row {
        class Header(val text: String) : Row()
        class Card(val n: Notif) : Row()
        class Empty(val text: String) : Row()
    }

    private val main = Handler(Looper.getMainLooper())
    private var items = ArrayList<Notif>()
    private var rows: List<Row> = emptyList()
    private var started = false
    private var loadedOnce = false
    private var generation = 0
    private val busy = HashSet<Long>()

    private val adapter = Adapter()

    init {
        recycler.layoutManager = LinearLayoutManager(activity)
        recycler.adapter = adapter
        recycler.itemAnimator = null
        val d = activity.resources.displayMetrics.density
        swipe.setProgressViewOffset(false, (70 * d).toInt(), (130 * d).toInt())
        swipe.setColorSchemeResources(R.color.fp_accent)
        swipe.setDistanceToTriggerSync((32 * d).toInt())
        swipe.setOnRefreshListener { load(false) }
    }

    // ------------------------------------------------------------------ жизненный цикл

    fun start(userId: String, deviceId: String, userAgent: String) {
        api.setSession(userId, deviceId, userAgent)
        if (started) return
        started = true
        // Уже показанный список остаётся на месте, свежие данные подгружаются поверх.
        load(loadedOnce)
    }

    fun stop() {
        started = false
        generation++
        swipe.isRefreshing = false
    }

    fun destroy() { generation++ }

    /** Кнопка «Обновить». */
    fun refresh() { swipe.isRefreshing = true; load(false) }

    private fun onUi(r: () -> Unit) {
        main.post { if (!activity.isDestroyed && !activity.isFinishing) r() }
    }

    private fun toast(s: String) = Toast.makeText(activity, s, Toast.LENGTH_SHORT).show()

    // ------------------------------------------------------------------ загрузка

    private fun optId(o: JSONObject, key: String): Long? =
        if (o.has(key) && !o.isNull(key)) o.optLong(key, 0L).takeIf { v -> v != 0L } else null

    private fun parse(a: JSONArray): ArrayList<Notif> {
        val out = ArrayList<Notif>(a.length())
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val id = o.optLong("id", 0L)
            if (id == 0L) continue
            // Текст может прийти кусками (textParts) — склеиваем.
            var text = api.str(o, "text")
            val parts = o.optJSONArray("textParts")
            if (parts != null && parts.length() > 0) {
                val sb = StringBuilder()
                for (j in 0 until parts.length()) parts.optJSONObject(j)?.let { pt -> sb.append(api.str(pt, "text")) }
                if (sb.isNotEmpty()) text = sb.toString()
            }
            out.add(
                Notif(
                    id = id,
                    type = api.str(o, "type"),
                    sourceId = o.optLong("sourceId", 0L),
                    postId = optId(o, "postId"),
                    commentId = optId(o, "commentId"),
                    requesterId = optId(o, "requesterId") ?: optId(o, "sourceId"),
                    name = api.str(o, "requesterName"),
                    picture = api.str(o, "requesterPicture"),
                    text = text,
                    preview = api.str(o, "preview"),
                    createdAt = PostParser.parseIso(api.str(o, "createdAt")),
                    awardImage = api.str(o, "awardImage")
                )
            )
        }
        return out
    }

    private fun load(silent: Boolean) {
        val gen = ++generation
        if (!silent && !loadedOnce) swipe.isRefreshing = true
        api.io.execute {
            var list: ArrayList<Notif>? = null
            var auth = false
            try {
                when (api.ensureUserId()) {
                    1 -> auth = true
                    2 -> {}
                    else -> {
                        val r = api.get("/api/users/notifications/${api.userId}")
                        if (r.code == 401) auth = true
                        else if (r.code == 200) list = parse(JSONArray(r.body.trim()))
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Не удалось загрузить уведомления", e)
            }
            onUi {
                if (gen != generation) return@onUi
                swipe.isRefreshing = false
                val l = list
                when {
                    auth -> onAuthLost()
                    l != null -> {
                        loadedOnce = true; items = l; rebuild(); onCount(items.size)
                    }
                    !silent || !loadedOnce -> {
                        if (rows.isEmpty()) {
                            rows = listOf(Row.Empty("Не удалось загрузить уведомления.\nПотяните вниз, чтобы повторить"))
                            adapter.notifyDataSetChanged()
                        } else toast("Не удалось обновить уведомления")
                    }
                }
            }
        }
    }

    private fun dateLabel(millis: Long): String {
        val now = Calendar.getInstance()
        val c = Calendar.getInstance().apply { timeInMillis = millis }
        fun day(cal: Calendar) = cal.get(Calendar.YEAR) * 1000 + cal.get(Calendar.DAY_OF_YEAR)
        val today = day(now)
        val d = day(c)
        val yest = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
        val week = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -7) }
        return when {
            d == today -> "Сегодня"
            d == day(yest) -> "Вчера"
            millis >= week.timeInMillis -> "На этой неделе"
            else -> java.text.SimpleDateFormat("d MMMM", java.util.Locale("ru")).format(java.util.Date(millis))
        }
    }

    private fun timeAgo(millis: Long): String {
        if (millis <= 0) return ""
        val diff = System.currentTimeMillis() - millis
        val mins = diff / 60000
        val hours = diff / 3600000
        val days = diff / 86400000
        return when {
            mins < 1 -> "Только что"
            mins < 60 -> "$mins мин назад"
            hours < 24 -> "$hours ч назад"
            days == 1L -> "Вчера"
            days < 7 -> "$days дн назад"
            else -> java.text.SimpleDateFormat("d MMM", java.util.Locale("ru")).format(java.util.Date(millis))
        }
    }

    private fun rebuild() {
        val out = ArrayList<Row>()
        if (items.isEmpty()) {
            out.add(Row.Empty("Пусто"))
        } else {
            var last = ""
            items.forEach { n ->
                val label = dateLabel(n.createdAt)
                if (label != last) { out.add(Row.Header(label)); last = label }
                out.add(Row.Card(n))
            }
        }
        rows = out
        adapter.notifyDataSetChanged()
    }

    // ------------------------------------------------------------------ действия

    private fun removeLocal(id: Long) {
        items.removeAll { n -> n.id == id }
        rebuild()
        onCount(items.size)
    }

    /** «Прочитано»: сервер удаляет уведомление. [then] вызывается после ответа (успех или нет). */
    private fun markRead(id: Long, then: ((Boolean) -> Unit)? = null) {
        api.io.execute {
            var ok = false
            try {
                val r = api.send("POST", "/api/users/notifications/read/$id", null)
                ok = r.code in 200..299
            } catch (e: Exception) {
                Log.w(TAG, "Не удалось отметить прочитанным", e)
            }
            onUi {
                if (ok) removeLocal(id) else toast("Не удалось отметить прочитанным")
                then?.invoke(ok)
            }
        }
    }

    /** Кнопка «Прочитать всё» в нижней панели. */
    fun markAll() {
        if (items.isEmpty()) return
        api.io.execute {
            var ok = false
            try {
                val r = api.send("POST", "/api/users/notifications/${api.userId}/read", null)
                ok = r.code in 200..299
            } catch (e: Exception) {
                Log.w(TAG, "Не удалось прочитать все", e)
            }
            onUi {
                if (ok) { items = ArrayList(); rebuild(); onCount(0) } else toast("Не удалось прочитать все уведомления")
            }
        }
    }

    private fun friendAction(n: Notif, accept: Boolean) {
        val rid = n.requesterId ?: n.sourceId
        if (!busy.add(n.id)) return
        api.io.execute {
            var ok = false
            var err = ""
            try {
                val r = api.send(
                    "POST", "/api/friends/${if (accept) "accept" else "reject"}",
                    JSONObject().put("requesterUserId", rid).toString()
                )
                val o = try { JSONObject(r.body) } catch (e: Exception) { null }
                ok = r.code in 200..299
                if (!ok && o != null) err = api.str(o, "error")
            } catch (e: Exception) {
                Log.w(TAG, "Заявка не обработана", e)
            }
            onUi {
                busy.remove(n.id)
                if (ok) markRead(n.id) else toast(err.ifBlank { "Не удалось обработать заявку" })
            }
        }
    }

    private fun openPostOf(n: Notif, withComment: Boolean) {
        val post = n.postId ?: n.sourceId
        if (post == 0L) { toast("Пост не найден"); return }
        markRead(n.id)
        onOpenPost(post, if (withComment) (n.commentId ?: 0L) else 0L)
    }

    // ------------------------------------------------------------------ вид карточки

    private fun button(ctx: android.content.Context, label: String, click: () -> Unit): TextView =
        TextView(ctx).apply {
            text = label; textSize = 14f; gravity = Gravity.CENTER
            setTextColor(UiUtil.TEXT)
            background = UiUtil.rect(Color.WHITE, UiUtil.BORDER, 2, 8, ctx)
            setPadding(dp(ctx, 8), dp(ctx, 8), dp(ctx, 8), dp(ctx, 8))
            setOnClickListener { click() }
        }

    private fun buildCard(ctx: android.content.Context, n: Notif): View {
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            // Плашка полупрозрачная: фон страницы просвечивает.
            background = UiUtil.rect(0xD9FFFFFF.toInt(), UiUtil.BORDER, 2, 8, ctx)
            setPadding(dp(ctx, 10), dp(ctx, 10), dp(ctx, 10), dp(ctx, 10))
        }
        val top = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val avatar = ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = UiUtil.rect(Color.WHITE, UiUtil.BORDER, 1, 4, ctx)
            setPadding(dp(ctx, 2), dp(ctx, 2), dp(ctx, 2), dp(ctx, 2))
            clipToOutline = true
        }
        top.addView(avatar, UiUtil.lp(dp(ctx, 64), dp(ctx, 64)))
        when (n.type) {
            "post_awarded" -> api.loadImage(avatar, "/ui/assets/awards/award_${n.awardImage}.webp", 256)
            "like_on_post", "like_on_comment" -> api.loadImage(avatar, "/ui/notifications/post_liked.webp", 256)
            "comment_on_post", "reply_to_comment", "friend_request", "friend_request_accepted" -> api.loadAvatar(avatar, n.picture)
            else -> api.loadAvatar(avatar, "")
        }
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        top.addView(col, UiUtil.lp(0, UiUtil.WRAP, 1f).apply { marginStart = dp(ctx, 10) })
        if (n.name.isNotBlank()) {
            col.addView(UiUtil.text(ctx, 15f, UiUtil.TEXT, bold = true).apply {
                text = n.name
                val rid = n.requesterId
                if (rid != null) setOnClickListener { onOpenProfile(rid) }
            })
        }
        if (n.text.isNotBlank()) col.addView(UiUtil.text(ctx, 14f, UiUtil.TEXT).apply { text = n.text })
        val ago = timeAgo(n.createdAt)
        if (ago.isNotBlank()) col.addView(UiUtil.text(ctx, 12f, UiUtil.SUB).apply { text = ago })
        card.addView(top, UiUtil.lp(UiUtil.MATCH, UiUtil.WRAP))

        if (n.preview.isNotBlank()) {
            card.addView(
                UiUtil.text(ctx, 14f, Color.WHITE).apply {
                    text = n.preview
                    maxLines = 4
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    background = UiUtil.rect(0xFF8A8A8A.toInt(), 0, 0, 10, ctx)
                    setPadding(dp(ctx, 12), dp(ctx, 8), dp(ctx, 12), dp(ctx, 8))
                },
                UiUtil.lp(UiUtil.MATCH, UiUtil.WRAP).apply { topMargin = dp(ctx, 8) }
            )
        }

        val actions = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        fun add(label: String, click: () -> Unit) {
            actions.addView(
                button(ctx, label, click),
                UiUtil.lp(0, UiUtil.WRAP, 1f).apply { setMargins(dp(ctx, 4), 0, dp(ctx, 4), 0) }
            )
        }
        val read = { markRead(n.id) }
        when (n.type) {
            "comment_on_post", "reply_to_comment" -> { add("Ответить") { openPostOf(n, true) }; add("Прочитать", read) }
            "post_awarded", "like_on_post", "like_on_comment" -> { add("Перейти") { openPostOf(n, false) }; add("Прочитать", read) }
            "friend_request" -> { add("Принять") { friendAction(n, true) }; add("Отклонить") { friendAction(n, false) } }
            "friend_request_accepted" -> {
                add("Перейти") {
                    val rid = n.requesterId ?: n.sourceId
                    markRead(n.id)
                    if (rid != 0L) onOpenProfile(rid)
                }
                add("Прочитать", read)
            }
            else -> add("Прочитать", read)
        }
        card.addView(actions, UiUtil.lp(UiUtil.MATCH, UiUtil.WRAP).apply { topMargin = dp(ctx, 10) })
        return card
    }

    // ------------------------------------------------------------------ адаптер

    private inner class Adapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = rows.size
        override fun getItemViewType(position: Int) = when (rows[position]) {
            is Row.Header -> 0
            is Row.Card -> 1
            is Row.Empty -> 2
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val ctx = parent.context
            val holderView: View = when (viewType) {
                0 -> UiUtil.text(ctx, 14f, Color.WHITE, bold = true).apply {
                    setShadowLayer(3f, 0f, 1f, 0xCC000000.toInt())
                    setPadding(dp(ctx, 6), dp(ctx, 4), dp(ctx, 6), dp(ctx, 6))
                }
                1 -> FrameHolderView(ctx)
                else -> UiUtil.text(ctx, 16f, Color.WHITE).apply {
                    gravity = Gravity.CENTER
                    setShadowLayer(3f, 0f, 1f, 0xCC000000.toInt())
                    setPadding(dp(ctx, 12), dp(ctx, 48), dp(ctx, 12), dp(ctx, 48))
                }
            }
            holderView.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { if (viewType == 1) setMargins(0, 0, 0, dp(ctx, 10)) }
            return object : RecyclerView.ViewHolder(holderView) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = rows[position]) {
                is Row.Header -> (holder.itemView as TextView).text = row.text
                is Row.Empty -> (holder.itemView as TextView).text = row.text
                is Row.Card -> (holder.itemView as FrameHolderView).show(buildCard(holder.itemView.context, row.n))
            }
        }
    }

    /** Контейнер, в который подставляется собранная карточка (карточки собираются при каждом показе). */
    private class FrameHolderView(ctx: android.content.Context) : android.widget.FrameLayout(ctx) {
        fun show(v: View) {
            removeAllViews()
            addView(v, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }
}
