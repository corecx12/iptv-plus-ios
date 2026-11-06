package app.iptvplus.tv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Ein angefangener Film oder eine angefangene Folge (für „Weiterschauen“) */
data class Resume(
    val kind: Kind,
    val id: String,
    val title: String,
    val icon: String,
    val ext: String,
    val pos: Long,
    val len: Long,
    val ts: Long,
    val seriesId: String = "",
    val seriesName: String = "",
    val seriesIcon: String = "",
    val season: String = "",
    val episode: String = ""
) {
    val fraction: Float get() = if (len > 0) (pos.toFloat() / len).coerceIn(0f, 1f) else 0f
    val remainingMin: Long get() = ((len - pos) / 60000).coerceAtLeast(1)

    fun toJson(): JSONObject = JSONObject()
        .put("k", kind.name).put("id", id).put("t", title).put("i", icon).put("x", ext)
        .put("p", pos).put("l", len).put("ts", ts)
        .put("sid", seriesId).put("sn", seriesName).put("si", seriesIcon).put("se", season).put("ep", episode)

    companion object {
        fun from(o: JSONObject) = Resume(
            Kind.valueOf(o.optString("k", "MOVIE")), o.optString("id"), o.optString("t"), o.optString("i"), o.optString("x"),
            o.optLong("p"), o.optLong("l"), o.optLong("ts"),
            o.optString("sid"), o.optString("sn"), o.optString("si"), o.optString("se"), o.optString("ep")
        )
    }
}

/** Merkt sich pro Konto, wo man bei Filmen und Folgen aufgehört hat */
class ResumeStore(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("iptvplus_resume", Context.MODE_PRIVATE)
    var accountKey: String = ""

    private fun key() = "list:$accountKey"

    private fun load(): MutableList<Resume> = try {
        val a = JSONArray(prefs.getString(key(), "[]"))
        (0 until a.length()).map { Resume.from(a.getJSONObject(it)) }.toMutableList()
    } catch (e: Exception) { mutableListOf() }

    private fun save(list: List<Resume>) {
        val a = JSONArray()
        for (r in list.sortedByDescending { it.ts }.take(40)) a.put(r.toJson())
        prefs.edit().putString(key(), a.toString()).apply()
    }

    /** neueste zuerst */
    fun all(): List<Resume> = load().sortedByDescending { it.ts }

    fun get(kind: Kind, id: String): Resume? = load().firstOrNull { it.kind == kind && it.id == id }

    /** zuletzt gesehene Folge einer Serie */
    fun lastOfSeries(seriesId: String): Resume? = load().filter { it.seriesId == seriesId }.maxByOrNull { it.ts }

    /** Position speichern; unter 1 Minute oder fast am Ende wird nichts gemerkt */
    fun put(r: Resume) {
        val list = load()
        list.removeAll { it.kind == r.kind && it.id == r.id }
        // pro Serie nur die aktuelle Folge in „Weiterschauen“
        if (r.seriesId.isNotEmpty()) list.removeAll { it.seriesId == r.seriesId }
        if (r.len > 0 && r.pos > 60_000 && r.fraction < 0.95f) list.add(r)
        save(list)
    }

    fun remove(kind: Kind, id: String) {
        val list = load()
        if (list.removeAll { it.kind == kind && it.id == id }) save(list)
    }
}
