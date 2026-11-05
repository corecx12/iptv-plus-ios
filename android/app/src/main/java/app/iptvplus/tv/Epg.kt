package app.iptvplus.tv

import android.util.Xml
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale

data class Prog(val s: Long, val e: Long, val t: String, val d: String)

class EpgChannel(val name: String, var icon: String, val streamId: String, val num: Int, var progs: List<Prog>) {
    fun current(now: Long = System.currentTimeMillis() / 1000): Prog? = progs.firstOrNull { it.s <= now && it.e > now }
    fun upcoming(now: Long = System.currentTimeMillis() / 1000): List<Prog> = progs.filter { it.e > now }
}

class EpgData(val updated: Long, val source: String, val server: String, val channels: List<EpgChannel>, val map: Map<String, Int>) {
    fun forStream(id: String): EpgChannel? = map[id]?.let { channels.getOrNull(it) }

    fun toJson(): String {
        val o = JSONObject()
        o.put("v", 2); o.put("updated", updated); o.put("source", source); o.put("server", server)
        val chs = JSONArray()
        for (c in channels) {
            val co = JSONObject()
            co.put("name", c.name); co.put("icon", c.icon); co.put("sid", c.streamId); co.put("num", c.num)
            val ps = JSONArray()
            for (p in c.progs) ps.put(JSONArray().put(p.s).put(p.e).put(p.t).put(p.d))
            co.put("p", ps)
            chs.put(co)
        }
        o.put("channels", chs)
        val m = JSONObject()
        for ((k, v) in map) m.put(k, v)
        o.put("map", m)
        return o.toString()
    }

    companion object {
        fun fromJson(t: String): EpgData? = try {
            val o = JSONObject(t)
            if (o.optInt("v") != 2) null else {
                val chs = o.getJSONArray("channels")
                val list = (0 until chs.length()).map { i ->
                    val c = chs.getJSONObject(i)
                    val ps = c.getJSONArray("p")
                    EpgChannel(c.s("name"), c.s("icon"), c.s("sid"), c.optInt("num"),
                        (0 until ps.length()).map { j -> val a = ps.getJSONArray(j); Prog(a.getLong(0), a.getLong(1), a.getString(2), a.getString(3)) })
                }
                val mo = o.getJSONObject("map")
                val map = HashMap<String, Int>()
                for (k in mo.keys()) map[k] = mo.getInt(k)
                EpgData(o.getLong("updated"), o.s("source"), o.s("server"), list, map)
            }
        } catch (e: Exception) { null }

        fun load(f: File): EpgData? = if (f.exists()) fromJson(f.readText()) else null
    }
}

/** XMLTV streamend lesen – auch sehr große Dateien */
object XmlTv {
    private val fmt1 = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US)
    private val fmt2 = SimpleDateFormat("yyyyMMddHHmmssZ", Locale.US)
    private val fmt3 = SimpleDateFormat("yyyyMMddHHmmss", Locale.US)

    fun time(s: String): Long {
        val x = s.trim()
        if (x.length < 14) return 0
        for (f in listOf(fmt1, fmt2)) try { return f.parse(x)!!.time / 1000 } catch (_: Exception) {}
        return try { fmt3.parse(x.take(14))!!.time / 1000 } catch (_: Exception) { 0 }
    }

    fun parse(
        input: InputStream, from: Long, to: Long,
        onChannel: ((String, String) -> Unit)?,
        wants: (String) -> Boolean,
        onProg: (String, Prog) -> Unit
    ) {
        val p = Xml.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        p.setInput(input, null)
        var chId = ""
        var inChannel = false
        var progCh = ""
        var inProg = false
        var start = ""; var stop = ""
        var title = ""; var titleDE = ""; var desc = ""; var descDE = ""
        try {
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) {
                    when (p.name) {
                        "channel" -> { inChannel = true; chId = (p.getAttributeValue(null, "id") ?: "").lowercase().trim() }
                        "display-name" -> if (inChannel && onChannel != null) onChannel(chId, p.nextText().trim())
                        "programme" -> {
                            progCh = (p.getAttributeValue(null, "channel") ?: "").lowercase().trim()
                            inProg = wants(progCh)
                            if (inProg) {
                                start = p.getAttributeValue(null, "start") ?: ""
                                stop = p.getAttributeValue(null, "stop") ?: ""
                                title = ""; titleDE = ""; desc = ""; descDE = ""
                            }
                        }
                        "title" -> if (inProg) {
                            val de = (p.getAttributeValue(null, "lang") ?: "").lowercase().startsWith("de")
                            val v = p.nextText().trim()
                            if (de) titleDE = v else if (title.isEmpty()) title = v
                        }
                        "desc" -> if (inProg) {
                            val de = (p.getAttributeValue(null, "lang") ?: "").lowercase().startsWith("de")
                            val v = p.nextText().trim()
                            if (de) descDE = v else if (desc.isEmpty()) desc = v
                        }
                    }
                } else if (ev == XmlPullParser.END_TAG) {
                    when (p.name) {
                        "channel" -> inChannel = false
                        "programme" -> {
                            if (inProg) {
                                val s = time(start)
                                var e = time(stop)
                                if (e == 0L) e = s + 1800
                                if (s > 0 && e >= from && s <= to) {
                                    var d = descDE.ifEmpty { desc }
                                    if (d.length > 400) d = d.take(400) + "…"
                                    onProg(progCh, Prog(s, e, titleDE.ifEmpty { title }, d))
                                }
                            }
                            inProg = false
                        }
                    }
                }
                ev = p.next()
            }
        } catch (e: Exception) {
            // abgeschnittene/fehlerhafte Datei: behalten, was gelesen wurde
        }
    }
}

/** TV-Programm: Anbieter + Lücken mit öffentlichem deutschem EPG füllen */
object EpgBuilder {
    private const val FALLBACK = "https://epgshare01.online/epgshare01/epg_ripper_DE1.xml.gz"

    private class Cand(var name: String, var icon: String, val sid: String, val num: Int, var epgId: String, val key: String, var german: Boolean) {
        val progs = ArrayList<Prog>()
    }

    fun build(api: XtreamApi, cats: List<Category>, streams: List<Item>): EpgData {
        val de = HashSet<String>(); val foreign = HashSet<String>()
        for (c in cats) {
            if (Lang.reGerman.containsMatchIn(c.name)) de.add(c.id) else if (Lang.reForeignCat.containsMatchIn(c.name)) foreign.add(c.id)
        }
        val list = ArrayList<Cand>()
        val byEpg = HashMap<String, Cand>(); val byKey = HashMap<String, Cand>(); val streamMap = HashMap<String, Cand>()
        val sorted = streams.withIndex().sortedWith(compareBy({ if (de.contains(it.value.categoryId)) 0 else 1 }, { it.index })).map { it.value }
        for (s in sorted) {
            if (foreign.contains(s.categoryId)) continue
            val k = Canon.key(s.name)
            val german = de.contains(s.categoryId) || s.epgId.endsWith(".de")
            var cd: Cand? = if (s.epgId.isEmpty()) null else byEpg[s.epgId]
            if (cd == null && k.isNotEmpty()) cd = byKey[k]
            if (cd == null) {
                if (k.isEmpty()) continue
                cd = Cand(Canon.clean(s.name), s.icon, s.id, s.num, s.epgId, k, german)
                list.add(cd)
            }
            if (german) cd.german = true
            if (cd.epgId.isEmpty() && s.epgId.isNotEmpty()) cd.epgId = s.epgId
            if (cd.icon.isEmpty()) cd.icon = s.icon
            if (s.epgId.isNotEmpty() && byEpg[s.epgId] == null) byEpg[s.epgId] = cd
            if (k.isNotEmpty() && byKey[k] == null) byKey[k] = cd
            streamMap[s.id] = cd
        }
        val now = System.currentTimeMillis() / 1000
        val from = now - 3 * 3600; val to = now + 44 * 3600
        var nProv = 0; var nPub = 0

        // 1) Anbieter
        val idMap = HashMap<String, Cand>()
        for (c in list) if (c.epgId.isNotEmpty()) idMap[c.epgId] = c
        if (idMap.isNotEmpty()) {
            try {
                api.open(api.xmltvUrl, 300000).use { ins ->
                    XmlTv.parse(ins, from, to, null, { idMap.containsKey(it) }) { id, p -> idMap[id]?.progs?.add(p) }
                }
            } catch (_: Exception) {}
            nProv = list.count { it.progs.isNotEmpty() }
        }

        // 2) öffentliches deutsches Programm für Sender ohne Programm
        val missing = HashMap<String, Cand>()
        for (c in list) if (c.progs.isEmpty()) missing[c.key] = c
        val filled = HashSet<Cand>()
        if (missing.isNotEmpty()) {
            val pubMap = HashMap<String, Cand>()
            try {
                api.open(FALLBACK, 300000).use { ins ->
                    XmlTv.parse(ins, from, to, { id, name ->
                        if (!pubMap.containsKey(id)) {
                            val c = missing[Canon.key(name)]
                            if (c != null && !filled.contains(c)) { pubMap[id] = c; filled.add(c) }
                        }
                    }, { pubMap.containsKey(it) }) { id, p -> pubMap[id]?.progs?.add(p) }
                }
            } catch (_: Exception) {}
            nPub = filled.count { it.progs.isNotEmpty() }
        }

        val chosen = list.filter { it.progs.isNotEmpty() && (it.german || filled.contains(it)) }
            .sortedWith(compareBy({ if (it.num == 0) Int.MAX_VALUE else it.num }, { it.name.lowercase() }))
        val out = ArrayList<EpgChannel>(); val idx = HashMap<Cand, Int>()
        for (c in chosen) {
            val ps = c.progs.sortedBy { it.s }.distinctBy { it.s }
            idx[c] = out.size
            out.add(EpgChannel(c.name, c.icon, c.sid, c.num, ps))
        }
        val map = HashMap<String, Int>()
        for ((sid, c) in streamMap) idx[c]?.let { map[sid] = it }
        if (out.isEmpty()) throw ApiException("Keine Programmdaten für deutsche Sender gefunden")
        val src = if (nPub > 0) "Anbieter ($nProv) + epgshare01 ($nPub)" else if (nProv > 0) "Anbieter" else "epgshare01"
        return EpgData(now, src, api.acc.server, out, map)
    }
}
