package ru.fortport.client

import android.content.Intent
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import ru.fortport.client.UiUtil.dp

/** Что нужно карточке поста от экрана, на котором она лежит. */
interface PostHost {
    val activity: androidx.appcompat.app.AppCompatActivity
    val api: SiteApi
    val repo: PostsRepo
    /** Тап по «Смотреть комментарии». */
    fun openPost(post: Post)
    fun openProfile(userId: Long)
    fun openCommunity(communityId: Long)
    /** Пост удалён/скрыт — убрать из списка. */
    fun removePost(post: Post)
    /** Список надо перечитать (закреп, скрытый пользователь и т.п.). */
    fun reloadList()
    /** Баланс CD-Coins мог измениться (награда). */
    fun balanceChanged()
    fun authLost()
}

/** Меню «▼», окно наград, жалоба — нативные замены модалок сайта (display-post.js). */
object PostDialogs {

    private val FLAG_TYPES = listOf(
        "spam" to "Спам",
        "unmarked_nsfw" to "Нет цензуры на контенте 18+",
        "harassment" to "Разжигание конфликта",
        "hatespeech" to "Язык вражды",
        "illegal" to "Нелегальный контент"
    )

    fun postLink(p: Post) = "${SiteApi.ORIGIN}/post?id=${p.id}"

    // ------------------------------------------------------------------ меню «▼»

    fun showPostMenu(host: PostHost, anchor: View, p: Post) {
        val ctx = host.activity
        val mine = p.userId != null && p.userId == host.repo.me
        val pm = PopupMenu(ctx, anchor)
        pm.menu.add(0, 1, 0, "Поделиться")
        if (!mine && p.userId != null && !p.isAnonymous) pm.menu.add(0, 2, 1, "Скрыть пользователя")
        if (!mine) pm.menu.add(0, 3, 2, "Пожаловаться")
        if (mine && !p.isAnonymous) pm.menu.add(0, 4, 3, if (p.isPinned) "Открепить публикацию" else "Закрепить публикацию")
        if (mine) pm.menu.add(0, 5, 4, "Удалить")
        pm.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> share(host, p)
                2 -> confirmMute(host, p)
                3 -> showFlag(host, p)
                4 -> togglePin(host, p)
                5 -> confirmDelete(host, p)
            }
            true
        }
        pm.show()
    }

    private fun share(host: PostHost, p: Post) {
        val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, postLink(p))
        host.activity.startActivity(Intent.createChooser(i, "Поделиться постом"))
    }

    private fun toast(host: PostHost, s: String) = Toast.makeText(host.activity, s, Toast.LENGTH_SHORT).show()

    private fun confirmMute(host: PostHost, p: Post) {
        AlertDialog.Builder(host.activity)
            .setMessage("Скрыть пользователя ${p.username}? Его посты пропадут из вашей ленты.")
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Скрыть") { _, _ ->
                val uid = p.userId ?: return@setPositiveButton
                host.repo.pool.execute {
                    val r = host.repo.muteUser(uid)
                    host.activity.runOnUiThread {
                        if (r.authLost) host.authLost()
                        else if (r.ok) host.reloadList() else toast(host, r.error.ifBlank { "Не удалось скрыть" })
                    }
                }
            }.show()
    }

    private fun togglePin(host: PostHost, p: Post) {
        host.repo.pool.execute {
            val r = host.repo.pin(p.id, !p.isPinned)
            host.activity.runOnUiThread {
                if (r.ok) host.reloadList() else toast(host, r.error.ifBlank { "Не удалось изменить закрепление" })
            }
        }
    }

    private fun confirmDelete(host: PostHost, p: Post) {
        AlertDialog.Builder(host.activity)
            .setMessage("Удалить публикацию?")
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Удалить") { _, _ ->
                host.repo.pool.execute {
                    val r = host.repo.deletePost(p.id)
                    host.activity.runOnUiThread {
                        if (r.ok) host.removePost(p) else toast(host, "Не удалось удалить")
                    }
                }
            }.show()
    }

    // ------------------------------------------------------------------ жалоба

    private fun showFlag(host: PostHost, p: Post) {
        val ctx = host.activity
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 20), dp(ctx, 8), dp(ctx, 20), 0)
        }
        box.addView(TextView(ctx).apply { text = "Вы уверены, что этот пост нарушает правила?" })
        val spinner = Spinner(ctx)
        spinner.adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, FLAG_TYPES.map { it.second })
        box.addView(spinner)
        val notes = EditText(ctx).apply { hint = "Примечания (необязательно)"; maxLines = 4 }
        box.addView(notes)
        AlertDialog.Builder(ctx)
            .setTitle("Пожаловаться на пост")
            .setView(box)
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Отправить") { _, _ ->
                val type = FLAG_TYPES[spinner.selectedItemPosition].first
                val text = notes.text.toString().trim()
                host.repo.pool.execute {
                    val r = host.repo.flag(p.id, type, text)
                    host.activity.runOnUiThread {
                        toast(host, if (r.ok) "Жалоба отправлена" else r.error.ifBlank { "Не удалось отправить жалобу" })
                    }
                }
            }.show()
    }

    // ------------------------------------------------------------------ награды

    /** Окно выбора награды (GET /api/awards), затем подтверждение. [onGiven] — награда выдана. */
    fun showAwardPicker(host: PostHost, p: Post, onGiven: () -> Unit) {
        val ctx = host.activity
        val loading = AlertDialog.Builder(ctx).setMessage("Загрузка наград...").setCancelable(true).show()
        host.repo.pool.execute {
            val cat = host.repo.loadAwardCatalog()
            ctx.runOnUiThread {
                if (ctx.isFinishing || ctx.isDestroyed) return@runOnUiThread
                loading.dismiss()
                if (cat == null) { toast(host, "Не удалось загрузить награды"); return@runOnUiThread }
                buildPicker(host, p, cat, onGiven)
            }
        }
    }

    private fun buildPicker(host: PostHost, p: Post, cat: AwardCatalog, onGiven: () -> Unit) {
        val ctx = host.activity
        val list = cat.awards.filter { it.available }
            .sortedBy { if (cat.dailyAvailable && it.id == cat.dailyAwardId) 0 else 1 } // бесплатная — первой
        if (list.isEmpty()) { toast(host, "Нет доступных наград"); return }
        val grid = GridLayout(ctx).apply { columnCount = 3; setPadding(dp(ctx, 8), dp(ctx, 8), dp(ctx, 8), dp(ctx, 8)) }
        val dialog = AlertDialog.Builder(ctx).setTitle("Наградить").setView(ScrollView(ctx).apply { addView(grid) })
            .setNegativeButton("Закрыть", null).create()
        list.forEach { a ->
            val daily = cat.dailyAvailable && a.id == cat.dailyAwardId
            val cell = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(ctx, 4), dp(ctx, 6), dp(ctx, 4), dp(ctx, 6))
                background = UiUtil.rect(if (daily) 0xFFE6FFF3.toInt() else 0xFFF6F8FA.toInt(), 0xFFCCCFE0.toInt(), 1, 8, ctx)
            }
            val iv = ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
            cell.addView(iv, LinearLayout.LayoutParams(dp(ctx, 64), dp(ctx, 64)))
            host.api.loadImage(iv, awardImagePath(a.image, big = false), 256)
            cell.addView(UiUtil.text(ctx, 12f, UiUtil.TEXT, maxLines = 2).apply { text = a.name; gravity = Gravity.CENTER })
            cell.addView(UiUtil.text(ctx, 11f, if (daily) 0xFF0A8F54.toInt() else UiUtil.SUB, bold = daily).apply {
                text = if (daily) "Бесплатно сегодня" else "${a.price} CD"; gravity = Gravity.CENTER
            })
            val lp = GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED, 1f), GridLayout.spec(GridLayout.UNDEFINED, 1f))
            lp.width = 0
            lp.setMargins(dp(ctx, 4), dp(ctx, 4), dp(ctx, 4), dp(ctx, 4))
            cell.layoutParams = lp
            cell.setOnClickListener { confirmAward(host, p, a, daily) { dialog.dismiss(); onGiven() } }
            grid.addView(cell)
        }
        dialog.show()
    }

    private fun confirmAward(host: PostHost, p: Post, a: AwardInfo, daily: Boolean, onGiven: () -> Unit) {
        val ctx = host.activity
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(ctx, 20), dp(ctx, 12), dp(ctx, 20), 0)
        }
        val iv = ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        box.addView(iv, LinearLayout.LayoutParams(dp(ctx, 140), dp(ctx, 140)))
        host.api.loadImage(iv, awardImagePath(a.image, big = true), 512)
        box.addView(UiUtil.text(ctx, 18f, UiUtil.TEXT, bold = true).apply { text = a.name; gravity = Gravity.CENTER })
        if (a.flavour.isNotBlank()) box.addView(UiUtil.text(ctx, 14f, UiUtil.SUB).apply { text = a.flavour; gravity = Gravity.CENTER })
        box.addView(UiUtil.text(ctx, 15f, if (daily) 0xFF0A8F54.toInt() else UiUtil.TEXT, bold = true).apply {
            text = if (daily) "Бесплатно сегодня" else "${a.price} CD"; gravity = Gravity.CENTER
            setPadding(0, dp(ctx, 8), 0, 0)
        })
        val anon = CheckBox(ctx).apply { text = "Анонимно" }
        box.addView(anon)
        val status = UiUtil.text(ctx, 13f, 0xFFC62828.toInt())
        box.addView(status)

        val d = AlertDialog.Builder(ctx).setView(box)
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Наградить", null).create()
        d.show()
        // Кнопка не закрывает окно сама: при ошибке (например, не хватает CD) остаёмся и показываем причину.
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { btn ->
            btn.isEnabled = false
            status.text = ""
            host.repo.pool.execute {
                val r = host.repo.giveAward(p.id, a.id, anon.isChecked)
                ctx.runOnUiThread {
                    if (ctx.isFinishing || ctx.isDestroyed) return@runOnUiThread
                    if (r.ok) {
                        d.dismiss(); host.balanceChanged(); onGiven()
                    } else {
                        btn.isEnabled = true
                        if (r.authLost) { d.dismiss(); host.authLost() }
                        else status.text = r.error.ifBlank { "Не удалось отправить награду" }
                    }
                }
            }
        }
    }

    /** Путь картинки награды на сайте: /ui/assets/awards/award_{имя}[_big].webp. */
    fun awardImagePath(name: String, big: Boolean): String =
        "/ui/assets/awards/award_${java.net.URLEncoder.encode(name, "UTF-8")}${if (big) "_big" else ""}.webp"

    /** Окно «кто подарил» при тапе на награду в посте. */
    fun showAwardInfo(host: PostHost, group: List<PostAward>) {
        val ctx = host.activity
        val first = group.firstOrNull() ?: return
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(ctx, 20), dp(ctx, 12), dp(ctx, 20), 0)
        }
        val iv = ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        box.addView(iv, LinearLayout.LayoutParams(dp(ctx, 140), dp(ctx, 140)))
        host.api.loadImage(iv, awardImagePath(first.image, big = true), 512)
        box.addView(UiUtil.text(ctx, 18f, UiUtil.TEXT, bold = true).apply { text = first.name; gravity = Gravity.CENTER })
        val names = group.map { g ->
            g.giverId?.let { host.repo.users[it]?.name?.ifBlank { null } } ?: "Аноним"
        }
        box.addView(UiUtil.text(ctx, 14f, UiUtil.SUB).apply {
            text = "Подарено: " + names.joinToString(", "); gravity = Gravity.CENTER
            setPadding(0, dp(ctx, 8), 0, 0)
        })
        AlertDialog.Builder(ctx).setView(box).setPositiveButton("Закрыть", null).show()
    }
}
