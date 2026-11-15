package ru.fortport.client

import org.json.JSONArray
import org.json.JSONObject

/** Значок: свой (GET /api/users/badges, equipped) или из каталога (GET /api/purchases/badges, price, owned). */
class BadgeItem(
    val id: Long, val name: String, val imagePath: String, val flavour: String,
    val price: Int, val owned: Boolean, val equipped: Boolean
)

/**
 * Значки профиля (настройки → «Профиль»), по settings.js сайта и логу:
 *  • GET  /api/users/badges      → {badges:[{id, name, imagePath, flavourText, equipped}]} — купленные;
 *  • GET  /api/purchases/badges  → {items:[{id, name, flavourText, imagePath, price, owned}]} — каталог;
 *  • POST /api/users/badges/{id} → {success, equipped, badgeId} — надеть / снять (переключатель);
 *  • POST /api/purchases/badges/{id} → {success, error?} — купить.
 * Картинка значка: /ui/assets/badges/badge_{imagePath}.webp. Методы блокирующие — не из UI-потока.
 */
object Badges {

    fun imageUrl(imagePath: String) = "/ui/assets/badges/badge_${java.net.URLEncoder.encode(imagePath, "UTF-8")}.webp"

    class Result(val ok: Boolean, val error: String = "", val authLost: Boolean = false, val equipped: Boolean = false, val badgeId: Long = 0L)

    /** Свои значки, затем непокупленные из каталога (как на сайте); null — не удалось загрузить. */
    fun load(api: SiteApi): List<BadgeItem>? = try {
        val rOwn = api.get("/api/users/badges")
        val rCat = api.get("/api/purchases/badges")
        if (rOwn.code != 200 && rCat.code != 200) null else {
            val out = ArrayList<BadgeItem>()
            val seen = HashSet<Long>()
            if (rOwn.code == 200) {
                val a = JSONObject(rOwn.body).optJSONArray("badges") ?: JSONArray()
                for (i in 0 until a.length()) {
                    val o = a.optJSONObject(i) ?: continue
                    val id = o.optLong("id")
                    seen.add(id)
                    out.add(BadgeItem(id, api.str(o, "name"), api.str(o, "imagePath"), api.str(o, "flavourText"), 0, true, o.optBoolean("equipped")))
                }
            }
            if (rCat.code == 200) {
                val a = JSONObject(rCat.body).optJSONArray("items") ?: JSONArray()
                for (i in 0 until a.length()) {
                    val o = a.optJSONObject(i) ?: continue
                    val id = o.optLong("id")
                    if (o.optBoolean("owned") || id in seen) continue
                    out.add(BadgeItem(id, api.str(o, "name"), api.str(o, "imagePath"), api.str(o, "flavourText"), o.optInt("price"), false, false))
                }
            }
            out
        }
    } catch (e: Exception) { null }

    /** Надет ли сейчас какой-то значок (по списку [load]). */
    fun equipped(list: List<BadgeItem>?): BadgeItem? = list?.firstOrNull { it.equipped }

    fun toggle(api: SiteApi, id: Long): Result = call(api, "/api/users/badges/$id", "Не удалось изменить значок")

    fun buy(api: SiteApi, id: Long): Result = call(api, "/api/purchases/badges/$id", "Не удалось купить значок")

    private fun call(api: SiteApi, path: String, fallback: String): Result = try {
        val r = api.send("POST", path, null)
        val o = try { JSONObject(r.body) } catch (e: Exception) { null }
        when {
            r.code == 401 -> Result(false, "Войдите заново", authLost = true)
            r.code in 200..299 && o?.optBoolean("success", true) != false ->
                Result(true, equipped = o?.optBoolean("equipped") ?: false, badgeId = o?.optLong("badgeId") ?: 0L)
            else -> Result(false, o?.optString("error").orEmpty().ifBlank { fallback })
        }
    } catch (e: Exception) { Result(false, "Нет соединения") }
}
