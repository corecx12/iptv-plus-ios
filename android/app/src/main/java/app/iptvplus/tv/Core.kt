package app.iptvplus.tv

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.zip.GZIPInputStream

const val USER_AGENT = "VLC/3.0.20 LibVLC/3.0.20"

fun JSONObject.s(key: String): String {
    if (!has(key) || isNull(key)) return ""
    return opt(key)?.toString() ?: ""
}

// ---------- Modelle ----------

data class Account(val server: String, val username: String, val password: String) {
    companion object {
        fun normalize(input: String): String {
            var x = input.trim().trimEnd('/')
            if (x.isNotEmpty() && !x.startsWith("http://", true) && !x.startsWith("https://", true)) x = "http://$x"
            for (suf in listOf("/player_api.php", "/get.php")) if (x.endsWith(suf)) x = x.removeSuffix(suf)
            return x
        }
    }
}

enum class Kind(val path: String, val catAction: String, val listAction: String) {
    LIVE("live", "get_live_categories", "get_live_streams"),
    MOVIE("movie", "get_vod_categories", "get_vod_streams"),
    SERIES("series", "get_series_categories", "get_series")
}

data class Category(val id: String, val name: String)

data class Item(
    val id: String, val name: String, val icon: String, val categoryId: String,
    val ext: String, val num: Int, val epgId: String, val year: String, val rating: String,
    val added: Long = 0,
    /** alle Kategorien des Titels (category_ids), kommagetrennt – ein Film kann in mehreren stehen */
    val categoryIds: String = ""
) {
    /** Haupt- und Zusatzkategorien */
    fun cats(): List<String> =
        if (categoryIds.isEmpty()) listOf(categoryId) else (categoryIds.split(',') + categoryId).filter { it.isNotEmpty() }.distinct()

    companion object {
        /** nur diese Felder werden beim Lesen großer Listen behalten */
        val FIELDS = setOf("series_id", "stream_id", "name", "cover", "stream_icon", "category_id", "container_extension",
            "num", "epg_channel_id", "year", "releaseDate", "rating", "added", "last_modified", "category_ids")

        fun from(m: Map<String, String>, k: Kind): Item {
            fun g(key: String) = m[key] ?: ""
            val y = g("year")
            return Item(
                id = if (k == Kind.SERIES) g("series_id") else g("stream_id"),
                name = g("name"),
                icon = if (k == Kind.SERIES) g("cover") else g("stream_icon"),
                categoryId = g("category_id"),
                ext = g("container_extension"),
                num = g("num").toIntOrNull() ?: 0,
                epgId = g("epg_channel_id").lowercase().trim(),
                year = if (y.isNotEmpty()) y else g("releaseDate").take(4),
                rating = g("rating"),
                added = g("added").toLongOrNull() ?: g("last_modified").toLongOrNull() ?: 0L,
                categoryIds = g("category_ids")
            )
        }

        fun from(o: JSONObject, k: Kind): Item {
            val y = o.s("year")
            return Item(
                id = if (k == Kind.SERIES) o.s("series_id") else o.s("stream_id"),
                name = o.s("name"),
                icon = if (k == Kind.SERIES) o.s("cover") else o.s("stream_icon"),
                categoryId = o.s("category_id"),
                ext = o.s("container_extension"),
                num = o.s("num").toIntOrNull() ?: 0,
                epgId = o.s("epg_channel_id").lowercase().trim(),
                year = if (y.isNotEmpty()) y else o.s("releaseDate").take(4),
                rating = o.s("rating"),
                added = o.s("added").toLongOrNull() ?: o.s("last_modified").toLongOrNull() ?: 0L,
                categoryIds = o.optJSONArray("category_ids")?.let { a -> (0 until a.length()).joinToString(",") { a.opt(it).toString() } } ?: ""
            )
        }
    }
}

class ApiException(msg: String) : Exception(msg)

// ---------- Xtream-API ----------

class XtreamApi(val acc: Account) {
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    fun apiUrl(params: Map<String, String>): String {
        val q = StringBuilder("username=${enc(acc.username)}&password=${enc(acc.password)}")
        for ((k, v) in params) q.append("&").append(enc(k)).append("=").append(enc(v))
        return "${acc.server}/player_api.php?$q"
    }

    fun open(url: String, timeoutMs: Int = 60000): InputStream {
        val c = URL(url).openConnection() as HttpURLConnection
        c.setRequestProperty("User-Agent", USER_AGENT)
        c.connectTimeout = 20000
        c.readTimeout = timeoutMs
        c.instanceFollowRedirects = true
        val code = c.responseCode
        if (code != 200) {
            c.disconnect()
            throw ApiException(if (code == 513 || code == 511) "Login abgelehnt (HTTP $code) – Benutzername/Passwort prüfen." else "Server antwortet mit HTTP $code")
        }
        val bis = BufferedInputStream(c.inputStream, 256 * 1024)
        bis.mark(4)
        val b1 = bis.read(); val b2 = bis.read()
        bis.reset()
        return if (b1 == 0x1f && b2 == 0x8b) GZIPInputStream(bis, 256 * 1024) else bis
    }

    fun text(params: Map<String, String>): String = open(apiUrl(params)).use { it.readBytes().toString(Charsets.UTF_8) }

    fun userInfo(): JSONObject {
        val t = text(emptyMap())
        val o = try { JSONObject(t) } catch (e: Exception) { throw ApiException("Unerwartete Antwort vom Server") }
        val u = o.optJSONObject("user_info") ?: throw ApiException("Login abgelehnt – Benutzername/Passwort prüfen.")
        if (u.s("auth") != "1") throw ApiException("Login abgelehnt – Benutzername/Passwort prüfen.")
        return u
    }

    private fun array(params: Map<String, String>): JSONArray {
        val t = text(params).trim()
        return if (t.startsWith("[")) JSONArray(t) else JSONArray()
    }

    fun categories(k: Kind): List<Category> {
        val a = array(mapOf("action" to k.catAction))
        return (0 until a.length()).map { val o = a.getJSONObject(it); Category(o.s("category_id"), o.s("category_name")) }
    }

    /**
     * Liste direkt aus dem Datenstrom lesen (nicht erst als einen riesigen Text in den Speicher).
     * Große Anbieter liefern zehntausende Filme – so bleibt der Speicherbedarf auf dem Fire TV klein.
     */
    fun items(k: Kind, category: String? = null): List<Item> {
        val p = mutableMapOf("action" to k.listAction)
        if (!category.isNullOrEmpty()) p["category_id"] = category
        open(apiUrl(p)).use { ins ->
            val r = android.util.JsonReader(java.io.InputStreamReader(ins, Charsets.UTF_8))
            r.isLenient = true
            if (r.peek() != android.util.JsonToken.BEGIN_ARRAY) return emptyList()
            val out = ArrayList<Item>()
            r.beginArray()
            while (r.hasNext()) {
                if (r.peek() != android.util.JsonToken.BEGIN_OBJECT) { r.skipValue(); continue }
                r.beginObject()
                val m = HashMap<String, String>(16)
                while (r.hasNext()) {
                    val name = r.nextName()
                    if (name !in Item.FIELDS) { r.skipValue(); continue }
                    when (r.peek()) {
                        android.util.JsonToken.STRING, android.util.JsonToken.NUMBER -> m[name] = r.nextString()
                        android.util.JsonToken.BOOLEAN -> m[name] = r.nextBoolean().toString()
                        android.util.JsonToken.NULL -> r.nextNull()
                        android.util.JsonToken.BEGIN_ARRAY -> {
                            // category_ids: [12, 34]
                            val ids = ArrayList<String>(4)
                            r.beginArray()
                            while (r.hasNext()) {
                                when (r.peek()) {
                                    android.util.JsonToken.STRING, android.util.JsonToken.NUMBER -> ids.add(r.nextString())
                                    else -> r.skipValue()
                                }
                            }
                            r.endArray()
                            m[name] = ids.joinToString(",")
                        }
                        else -> r.skipValue()
                    }
                }
                r.endObject()
                out.add(Item.from(m, k))
            }
            r.endArray()
            out.trimToSize()
            return out
        }
    }

    fun info(action: String, key: String, id: String): JSONObject {
        val t = text(mapOf("action" to action, key to id)).trim()
        return if (t.startsWith("{")) JSONObject(t) else JSONObject()
    }

    fun streamUrl(k: Kind, id: String, ext: String): String {
        val e = if (ext.isEmpty()) (if (k == Kind.LIVE) "ts" else "mp4") else ext
        val u = enc(acc.username).replace("+", "%20")
        val p = enc(acc.password).replace("+", "%20")
        return "${acc.server}/${k.path}/$u/$p/$id.$e"
    }

    val xmltvUrl: String get() = "${acc.server}/xmltv.php?username=${enc(acc.username)}&password=${enc(acc.password)}"
}

// ---------- Deutsch-Erkennung & Suche ----------

object Lang {
    val reGerman = Regex("(^|[^a-z])(de|ger|deu)([^a-z]|$)|deutsch|germany|österreich|schweiz", RegexOption.IGNORE_CASE)
    /** „DE: Titel“, „[DE] Titel“ oder „Titel (DE)“ */
    val reTitleDE = Regex("^\\s*[\\[|(]?\\s*(de|ger|deu)\\s*[\\]|):\\-]|[\\[(|]\\s*(de|ger|deu)\\s*[\\])|]", RegexOption.IGNORE_CASE)
    /** „EN: Titel“, „[FR] Titel“ oder „Titel (EN)“ */
    val reTitleForeign = Regex("^\\s*[\\[|(]?\\s*(en|eng|uk|us|fr|it|es|tr|pl|nl|ar)\\s*[\\]|):\\-]|[\\[(|]\\s*(en|eng|fr|it|es|tr|pl|nl|ar)\\s*[\\])|]", RegexOption.IGNORE_CASE)
    val reForeignCat = Regex("(^|[^a-z])(uk|gb|us|usa|fr|it|es|nl|be|pl|tr|pt|ar|yu|exyu|ex-yu|al|ro|gr|ru|ua|in|pk|af|se|dk|no|fi|hu|cz|sk|hr|rs|ba|bg|ca|au|br|mx|latino|arab|arabic|turkey|turk|france|italy|italia|spain|espana|poland|polska|portugal|netherlands|nederland|kurd|persian|iran|india|africa|asia)([^a-z]|$)", RegexOption.IGNORE_CASE)
    /** fremdsprachige Film-/Serienkategorien – ohne Kürzel, die auch deutsche Wörter sind („in“, „es“, „no“ …) */
    val reForeignVodCat = Regex("(^|[^a-z])(en|eng|english|uk|gb|us|usa|fr|french|nl|pl|tr|pt|yu|exyu|ex-yu|ro|gr|ru|ua|pk|dk|fi|hu|cz|sk|hr|rs|bg|au|br|mx|latino|arab|arabic|turkey|turk|turkish|france|italy|italia|italian|spain|espana|spanish|poland|polska|portugal|netherlands|nederland|kurd|persian|iran|india|hindi|africa|asia|albania)([^a-z]|$)", RegexOption.IGNORE_CASE)
    private val reQPrefix = Regex("^\\s*[\\[|(]?\\s*[a-z]{2,3}\\s*[\\]|):\\-]\\s*")
    private val reNonAlnum = Regex("[^a-z0-9]+")
    private val reMarks = Regex("\\p{M}+")

    fun normQ(s: String): String = reNonAlnum.replace(fold(s), "")

    /** klein, Umlaute ausgeschrieben, Akzente weg (é → e), Sprach-Vorsilbe „DE:“ weg */
    fun fold(s: String): String {
        var x = s.lowercase().replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss")
        x = reMarks.replace(java.text.Normalizer.normalize(x, java.text.Normalizer.Form.NFD), "")
        return reQPrefix.replace(x, "")
    }

    /** Lautschrift, damit die genaue Schreibweise egal ist: Schrek = Shrek, Titanik = Titanic, Wikings = Vikings */
    fun sound(w: String): String {
        val o = StringBuilder(w.length)
        val n = w.length
        var i = 0
        while (i < n) {
            val c = w[i]
            if (!(c in 'a'..'z' || c in '0'..'9')) { i++; continue }
            val d = if (i + 1 < n) w[i + 1] else ' '
            val e = if (i + 2 < n) w[i + 2] else ' '
            var step = 2
            val r = when {
                c == 's' && d == 'c' && e == 'h' -> { step = 3; "sh" }
                c == 'c' && d == 'k' -> "k"
                c == 'p' && d == 'h' -> "f"
                c == 't' && d == 'h' -> "t"
                c == 'q' && d == 'u' -> "kv"
                c == 'd' && d == 't' -> "t"
                c == 'a' && d == 'e' -> "e"
                c == 'o' && d == 'e' -> "o"
                c == 'u' && d == 'e' -> "u"
                c == 'i' && d == 'e' -> "i"
                c == 'e' && (d == 'i' || d == 'y') -> "ai"
                else -> {
                    step = 1
                    when (c) { 'c' -> "k"; 'z' -> "s"; 'y' -> "i"; 'w' -> "v"; 'x' -> "ks"; else -> c.toString() }
                }
            }
            for (ch in r) {
                if (o.isNotEmpty() && o[o.length - 1] == ch && ch !in '0'..'9') continue
                o.append(ch)
            }
            i += step
        }
        return o.toString()
    }

    /** Suchschlüssel eines Titels (einmal pro Liste berechnet) */
    fun key(title: String): String = sound(fold(title))

    /** erlaubte Tippfehler je Wortlänge */
    private fun tol(len: Int) = when { len <= 3 -> 0; len <= 5 -> 1; len <= 8 -> 2; else -> 3 }

    /** kleinster Abstand von p zu irgendeinem Stück von t (Levenshtein mit freiem Anfang) */
    private fun near(p: String, t: String): Int {
        val m = p.length
        val col = IntArray(m + 1) { it }
        var best = m
        for (j in t.indices) {
            val tc = t[j]
            var diag = col[0]
            col[0] = 0
            for (i in 1..m) {
                val tmp = col[i]
                var v = diag + if (p[i - 1] == tc) 0 else 1
                if (col[i] + 1 < v) v = col[i] + 1
                if (col[i - 1] + 1 < v) v = col[i - 1] + 1
                col[i] = v
                diag = tmp
            }
            if (col[m] < best) best = col[m]
        }
        return best
    }

    class Query(text: String) {
        val whole: String
        val words: List<String>
        init {
            val f = fold(text)
            whole = sound(f)
            var w = f.split(reNonAlnum).map { sound(it) }.filter { it.isNotEmpty() }
            if (w.size > 1) w = w.filter { it.length >= 2 }
            words = if (w.isEmpty() && whole.isNotEmpty()) listOf(whole) else w
        }
        val isEmpty get() = whole.isEmpty()
    }

    /**
     * Wie gut passt der Titel? -1 = gar nicht, sonst kleiner = besser.
     * Wörter dürfen fehlen oder vertauscht sein („haus geldes“ → Haus des Geldes), Tippfehler sind erlaubt.
     */
    fun score(q: Query, t: String): Int {
        if (q.isEmpty) return -1
        if (t == q.whole) return 0
        if (t.startsWith(q.whole)) return 1
        if (t.contains(q.whole)) return 2
        var sum = 0
        var exact = true
        for (w in q.words) {
            if (t.contains(w)) continue
            exact = false
            val tl = tol(w.length)
            if (tl == 0) return wholeFuzzy(q, t)
            val d = near(w, t)
            if (d > tl) return wholeFuzzy(q, t)
            sum += d
        }
        return if (exact) 3 else 10 + sum
    }

    private fun wholeFuzzy(q: Query, t: String): Int {
        if (q.words.size < 2) return -1
        val tl = tol(q.whole.length)
        if (tl == 0) return -1
        val d = near(q.whole, t)
        return if (d <= tl) 10 + d else -1
    }

    /** einfacher Treffer-Test (z. B. Filter innerhalb einer Kategorie) */
    fun matches(query: String, title: String): Boolean = score(Query(query), key(title)) >= 0
}

// ---------- Senderbezeichnung vergleichbar machen ----------

object Canon {
    private val rePrefix = Regex("^\\s*(\\[[^\\]]*\\]|\\|[^|]*\\||[a-z0-9]{2,8}\\s*[:|]\\s*)", RegexOption.IGNORE_CASE)
    private val reParen = Regex("\\([^)]*\\)|\\[[^\\]]*\\]")
    private val reTokens = Regex("\\b(hd|fhd|uhd|sd|4k|8k|hevc|h\\.?265|h\\.?264|raw|backup|ger|deu|de|germany|deutschland|50fps|60fps|vip|plus\\+?|multi|audio|fernsehen|television|\\d{3,4}p)\\b", RegexOption.IGNORE_CASE)
    private val reNonAlnum = Regex("[^a-z0-9]+")
    private val aliases = mapOf(
        "ard" to "daserste", "erstes" to "daserste", "daserstes" to "daserste",
        "rtlii" to "rtlzwei", "rtl2" to "rtlzwei",
        "pro7" to "prosieben", "pro7maxx" to "prosiebenmaxx", "pro7fun" to "prosiebenfun",
        "kabel1" to "kabeleins", "kabel1doku" to "kabeleinsdoku", "kabel1classics" to "kabeleinsclassics",
        "ntvnachrichten" to "ntv", "rtlnitro" to "nitro", "weltn24" to "welt", "n24" to "welt",
        "eurosport" to "eurosport1", "disney" to "disneychannel", "nickelodeon" to "nick",
        "bayerisches" to "br", "bayerischesfernsehen" to "br", "bralpha" to "ardalpha", "alpha" to "ardalpha",
        "hse24" to "hse", "hse24extra" to "hseextra", "servustvdeutschland" to "servustv", "servustvdetschland" to "servustv",
        "servustvtschland" to "servustv", "radiobrementv" to "radiobremen", "sixxtv" to "sixx", "voxtv" to "vox",
        "tele5tv" to "tele5", "toggo" to "toggoplus"
    )
    private val regional = setOf("", "nord", "sued", "ost", "west", "aachen", "bielefeld", "dortmund", "duesseldorf",
        "essen", "koeln", "muenster", "siegen", "wuppertal", "bonn", "duisburg", "sachsen", "sachsenanhalt", "thueringen",
        "berlin", "brandenburg", "hamburg", "niedersachsen", "schleswigholstein", "sh", "mv", "mecklenburgvorpommern", "bw",
        "rp", "badenwuerttemberg", "rheinlandpfalz", "hannover", "bayern", "franken", "schwaben", "muenchen", "oberbayern",
        "niederbayern", "unterfranken", "saarland", "hessen")
    private val regionalPrefixes = listOf("wdr", "ndr", "mdr", "rbb", "swr", "br", "hr", "sr")

    fun stripPrefix(s: String): String {
        var x = s
        repeat(3) {
            val t = rePrefix.replace(x, "")
            if (t == x) return x
            x = t
        }
        return x
    }

    fun key(name: String): String {
        var s = reParen.replace(stripPrefix(name), " ").lowercase()
        s = s.replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss")
        s = reTokens.replace(s, " ")
        var n = reNonAlnum.replace(s, "")
        aliases[n]?.let { n = it }
        for (p in regionalPrefixes) if (n.startsWith(p) && n.removePrefix(p) in regional) return p
        return n
    }

    fun clean(name: String): String = stripPrefix(name).trim().ifEmpty { name }
}
