package ru.fortport.client

import android.net.Uri
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.style.ClickableSpan
import android.view.View

/**
 * Ссылки fortport.ru: распознавание внутренних адресов (пост, профиль, порт) и превращение URL в тексте в кликабельные.
 * Форматы: /post?id=N, /profile?id=N (/profile без id — свой), /community?id=N, а также /post/N, /profile/N, /community/N.
 */
object SiteLinks {

    enum class Kind { POST, PROFILE, PORT }
    class Target(val kind: Kind, val id: Long)

    private const val HOST = "fortport.ru"

    fun isSite(uri: Uri?): Boolean =
        uri != null && (uri.scheme == "https" || uri.scheme == "http") &&
            (uri.host.equals(HOST, true) || uri.host?.endsWith(".$HOST", true) == true)

    /** Внутренняя цель ссылки или null (внешняя ссылка / страница сайта без нативного экрана). */
    fun parse(url: String?): Target? = try {
        val uri = Uri.parse(url?.trim().orEmpty())
        if (!isSite(uri)) null else {
            val seg = uri.pathSegments.orEmpty()
            val kind = when (seg.firstOrNull()?.lowercase()) {
                "post" -> Kind.POST
                "profile", "user" -> Kind.PROFILE
                "community", "port" -> Kind.PORT
                else -> null
            }
            if (kind == null) null else {
                val id = (uri.getQueryParameter("id") ?: seg.getOrNull(1))?.toLongOrNull()
                when {
                    id != null && id > 0 -> Target(kind, id)
                    kind == Kind.PROFILE && seg.size == 1 -> Target(kind, 0L) // /profile без id — своя страница
                    else -> null
                }
            }
        }
    } catch (e: Exception) { null }

    private val URL_RE = Regex("""(?i)\b(?:https?://|www\.)[^\s<>"']+""")

    /** Текст, где URL — кликабельные. [onClick] получает полный адрес (https://…). */
    fun linkify(text: String, linkColor: Int, onClick: (String) -> Unit): CharSequence {
        val sp = SpannableString(text)
        for (m in URL_RE.findAll(text)) {
            // Хвостовая пунктуация не входит в адрес: «…/post?id=1.» или «(ссылка)».
            var end = m.range.last + 1
            while (end > m.range.first && text[end - 1] in ".,;:!?)»”") end--
            if (end <= m.range.first) continue
            val raw = text.substring(m.range.first, end)
            val full = if (raw.startsWith("www.", true)) "https://$raw" else raw
            sp.setSpan(object : ClickableSpan() {
                override fun onClick(widget: View) = onClick(full)
                override fun updateDrawState(ds: TextPaint) { ds.color = linkColor; ds.isUnderlineText = true }
            }, m.range.first, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return sp
    }
}
