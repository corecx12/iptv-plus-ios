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
    /** fast zu Ende geschaut */
    val done: Boolean get() = len > 0 && fraction >= 0.95f

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

/**
 * Merkt sich pro Konto, wo man bei Filmen und Folgen aufgehört hat.
 * - „Weiterschauen“-Liste: angefangene Titel, pro Serie nur die aktuelle Folge
 * - Verlauf: Position JEDES Films und JEDER Folge (auch ältere Folgen, auch fertig gesehen)
 */
class ResumeStore(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("iptvplus_resume", Context.MODE_PRIVATE)
    var accountKey: String = ""
        set(v) { field = v; loadedFor = null }

    private var loadedFor: String? = null
    private var list = mutableListOf<Resume>()
    private val hist = HashMap<String, Resume>()

    private fun k(kind: Kind, id: String) = "${kind.name}:$id"

    private fun read(key: String): MutableList<Resume> = try {
        val a = JSONArray(prefs.getString(key, "[]"))
        (0 until a.length()).map { Resume.from(a.getJSONObject(it)) }.toMutableList()
    } catch (e: Exception) { mutableListOf() }

    private fun write(key: String, items: Collection<Resume>, max: Int) {
        val a = JSONArray()
        for (r in items.sortedByDescending { it.ts }.take(max)) a.put(r.toJson())
        prefs.edit().putString(key, a.toString()).apply()
    }

    private fun ensure() {
        if (loadedFor == accountKey) return
        list = read("list:$accountKey")
        hist.clear()
        for (r in read("hist:$accountKey")) hist[k(r.kind, r.id)] = r
        // ältere Version: nur die Weiterschauen-Liste gab es
        for (r in list) if (!hist.containsKey(k(r.kind, r.id))) hist[k(r.kind, r.id)] = r
        loadedFor = accountKey
    }

    private fun saveList() = write("list:$accountKey", list, 40)
    private fun saveHist() {
        if (hist.size > 900) hist.values.sortedBy { it.ts }.take(hist.size - 900).forEach { hist.remove(k(it.kind, it.id)) }
        write("hist:$accountKey", hist.values, 900)
    }

    /** „Weiterschauen“, neueste zuerst */
    fun all(): List<Resume> { ensure(); return list.sortedByDescending { it.ts } }

    /** letzte Position eines Films oder einer Folge */
    fun get(kind: Kind, id: String): Resume? { ensure(); return hist[k(kind, id)] }

    /** zuletzt gesehene Folge einer Serie */
    fun lastOfSeries(seriesId: String): Resume? { ensure(); return hist.values.filter { it.seriesId == seriesId }.maxByOrNull { it.ts } }

    /** Position speichern (unter 30 Sekunden wird nichts gemerkt) */
    fun put(r: Resume) {
        ensure()
        list.removeAll { it.kind == r.kind && it.id == r.id }
        // pro Serie nur die aktuelle Folge in „Weiterschauen“
        if (r.seriesId.isNotEmpty()) list.removeAll { it.seriesId == r.seriesId }
        if (r.len > 0 && r.pos > 60_000 && !r.done) list.add(r)
        saveList()
        if (r.len > 0 && r.pos > 30_000) { hist[k(r.kind, r.id)] = r; saveHist() }
    }

    /** zu Ende geschaut: aus „Weiterschauen“ raus, im Verlauf als gesehen merken */
    fun markDone(kind: Kind, id: String) {
        ensure()
        if (list.removeAll { it.kind == kind && it.id == id }) saveList()
        hist[k(kind, id)]?.let { if (it.len > 0) { hist[k(kind, id)] = it.copy(pos = it.len, ts = System.currentTimeMillis()); saveHist() } }
    }

    fun remove(kind: Kind, id: String) {
        ensure()
        if (list.removeAll { it.kind == kind && it.id == id }) saveList()
    }
}

/** Ein Eintrag in „Meine Liste“ */
data class Fav(val kind: Kind, val id: String, val name: String, val icon: String, val ext: String, val ts: Long) {
    fun toItem() = Item(id, name, icon, "", ext, 0, "", "", "")
    fun toJson(): JSONObject = JSONObject().put("k", kind.name).put("id", id).put("n", name).put("i", icon).put("x", ext).put("ts", ts)

    companion object {
        fun from(o: JSONObject) = Fav(Kind.valueOf(o.optString("k", "MOVIE")), o.optString("id"), o.optString("n"), o.optString("i"), o.optString("x"), o.optLong("ts"))
    }
}

/** „Meine Liste“: gemerkte Filme, Serien und Sender pro Konto */
class FavStore(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("iptvplus_fav", Context.MODE_PRIVATE)
    var accountKey: String = ""
        set(v) { field = v; loadedFor = null }

    private var loadedFor: String? = null
    private val favs = LinkedHashMap<String, Fav>()

    private fun k(kind: Kind, id: String) = "${kind.name}:$id"

    private fun ensure() {
        if (loadedFor == accountKey) return
        favs.clear()
        try {
            val a = JSONArray(prefs.getString("fav:$accountKey", "[]"))
            for (i in 0 until a.length()) { val f = Fav.from(a.getJSONObject(i)); favs[k(f.kind, f.id)] = f }
        } catch (_: Exception) {}
        loadedFor = accountKey
    }

    private fun save() {
        val a = JSONArray()
        for (f in favs.values) a.put(f.toJson())
        prefs.edit().putString("fav:$accountKey", a.toString()).apply()
    }

    /** neueste zuerst */
    fun all(kind: Kind): List<Fav> { ensure(); return favs.values.filter { it.kind == kind }.sortedByDescending { it.ts } }

    fun isEmpty(): Boolean { ensure(); return favs.isEmpty() }

    fun contains(kind: Kind, id: String): Boolean { ensure(); return favs.containsKey(k(kind, id)) }

    /** hinzufügen oder entfernen; true = jetzt in der Liste */
    fun toggle(kind: Kind, it: Item): Boolean {
        ensure()
        val key = k(kind, it.id)
        val added = if (favs.remove(key) != null) false else {
            favs[key] = Fav(kind, it.id, it.name, it.icon, it.ext, System.currentTimeMillis()); true
        }
        save()
        return added
    }
}
