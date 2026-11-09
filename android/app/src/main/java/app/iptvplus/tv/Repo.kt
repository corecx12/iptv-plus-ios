package app.iptvplus.tv

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Ein gespeichertes IPTV-Konto mit frei wählbarem Namen */
data class SavedAccount(val label: String, val account: Account) {
    fun toJson(): JSONObject = JSONObject().put("label", label).put("server", account.server).put("user", account.username).put("pass", account.password)
    companion object {
        fun from(o: JSONObject) = SavedAccount(o.optString("label"), Account(o.optString("server"), o.optString("user"), o.optString("pass")))
    }
}

/** Zugangsdaten, Zwischenspeicher und TV-Programm */
class Repo(private val ctx: Context) {
    private val prefs = ctx.getSharedPreferences("iptvplus", Context.MODE_PRIVATE)

    var accounts: MutableList<SavedAccount> = loadAccounts()
        private set
    var activeIndex: Int = prefs.getInt("activeIndex", 0).coerceIn(0, (accounts.size - 1).coerceAtLeast(0))
        private set

    val account: Account? get() = accounts.getOrNull(activeIndex)?.account
    var userInfo: JSONObject = JSONObject()
    var deOnly: Boolean
        get() = prefs.getBoolean("deOnly", true)
        set(v) { prefs.edit().putBoolean("deOnly", v).apply() }

    var epg: EpgData? = EpgData.load(epgFile())
    var epgLoading = false

    private val catCache = java.util.concurrent.ConcurrentHashMap<Kind, List<Category>>()
    private val listCache = java.util.concurrent.ConcurrentHashMap<String, List<Item>>()
    /** Anbieter, die die Gesamtliste verweigern – nicht bei jeder Suche erneut versuchen */
    private val noFullList = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<Kind, Boolean>())
    /** Suchschlüssel je Liste, damit nicht bei jedem Tastendruck alle Titel neu aufbereitet werden */
    private val keyCache = HashMap<Kind, Pair<List<Item>, Array<String>>>()
    private val infoCache = HashMap<String, JSONObject>()
    /** nur ein Ladevorgang je Art gleichzeitig (Hintergrund-Vorladen + Suche) */
    private val poolLocks = mapOf(Kind.MOVIE to Mutex(), Kind.SERIES to Mutex(), Kind.LIVE to Mutex())
    /** sichtbarer Ladestand für die Suche, z. B. „Serien: 12 / 80 Kategorien“ */
    val loadStatus = java.util.concurrent.ConcurrentHashMap<Kind, String>()

    val api: XtreamApi? get() = account?.let { XtreamApi(it) }

    private fun loadAccounts(): MutableList<SavedAccount> {
        // mehrere Konten (neu)
        val raw = prefs.getString("accounts", null)
        if (raw != null) {
            return try {
                val a = JSONArray(raw)
                (0 until a.length()).map { SavedAccount.from(a.getJSONObject(it)) }.toMutableList()
            } catch (e: Exception) { mutableListOf() }
        }
        // einzelnes Konto aus älterer Version übernehmen
        val s = prefs.getString("server", null)
        return if (s != null) mutableListOf(SavedAccount("Konto 1", Account(s, prefs.getString("user", "") ?: "", prefs.getString("pass", "") ?: "")))
        else mutableListOf()
    }

    private fun persistAccounts() {
        val a = JSONArray()
        for (sa in accounts) a.put(sa.toJson())
        prefs.edit().putString("accounts", a.toString()).putInt("activeIndex", activeIndex)
            .remove("server").remove("user").remove("pass").apply()
    }

    private fun epgFile() = File(ctx.cacheDir, "epg_cache.json")

    private fun resetCaches() { catCache.clear(); listCache.clear(); keyCache.clear(); noFullList.clear(); loadStatus.clear(); deletePoolFiles(); infoCache.clear(); epg = null; epgFile().delete() }

    /** Kennung des aktiven Kontos (für „Weiterschauen“ pro Konto) */
    val accountKey: String get() = account?.let { it.server + "|" + it.username } ?: ""

    /** Details zu Film oder Serie (Beschreibung, Hintergrundbild, Folgen), zwischengespeichert */
    suspend fun info(k: Kind, id: String): JSONObject = withContext(Dispatchers.IO) {
        val key = "${k.path}:$id"
        infoCache[key] ?: run {
            val a = api ?: return@withContext JSONObject()
            val o = if (k == Kind.SERIES) a.info("get_series_info", "series_id", id) else a.info("get_vod_info", "vod_id", id)
            if (infoCache.size > 300) infoCache.clear()
            infoCache[key] = o
            o
        }
    }

    /** Konto prüfen und speichern. index < 0 = neues Konto, sonst bestehendes ersetzen. */
    suspend fun saveAccount(label: String, server: String, user: String, pass: String, index: Int): Int = withContext(Dispatchers.IO) {
        val existing = accounts.getOrNull(index)?.account
        val pw = pass.trim().ifEmpty { existing?.password ?: "" }
        val acc = Account(Account.normalize(server), user.trim(), pw)
        val info = XtreamApi(acc).userInfo()
        val name = label.trim().ifEmpty { "Konto ${accounts.size + 1}" }
        val sa = SavedAccount(name, acc)
        val wasActive = index == activeIndex
        val newIndex: Int
        if (index in accounts.indices) {
            accounts[index] = sa; newIndex = index
        } else {
            accounts.add(sa); newIndex = accounts.size - 1
        }
        if (accounts.size == 1 || wasActive || index < 0) {
            activeIndex = newIndex
            userInfo = info
            resetCaches()
        }
        persistAccounts()
        newIndex
    }

    /** Aktives Konto wechseln */
    fun switchTo(index: Int) {
        if (index !in accounts.indices || index == activeIndex) return
        activeIndex = index
        userInfo = JSONObject()
        resetCaches()
        persistAccounts()
    }

    /** Konto entfernen */
    fun removeAccount(index: Int) {
        if (index !in accounts.indices) return
        accounts.removeAt(index)
        activeIndex = activeIndex.coerceIn(0, (accounts.size - 1).coerceAtLeast(0))
        userInfo = JSONObject()
        resetCaches()
        persistAccounts()
    }

    suspend fun refreshUserInfo() = withContext(Dispatchers.IO) {
        try { api?.let { userInfo = it.userInfo() } } catch (_: Exception) {}
    }

    fun logout() {
        accounts.clear(); activeIndex = 0
        prefs.edit().clear().apply()
        userInfo = JSONObject(); resetCaches()
    }

    fun filterDE(k: Kind) = deOnly && (k == Kind.MOVIE || k == Kind.SERIES)

    /**
     * Prüft, ob ein Titel deutsch ist. Die Kategorien werden nur einmal ausgewertet.
     * - „EN: …“ / „(FR)“ im Titel → nein, „DE: …“ → ja
     * - steht der Titel in irgendeiner deutschen Kategorie (auch Zusatzkategorie) → ja
     * - nur in klar fremdsprachigen Kategorien („EN | Action“, „UK“, „Türkei“) → nein
     * - neutrale Kategorien ohne Länderkennung („Netflix“, „Kinder“, „4K“) → ja
     */
    fun germanFilter(k: Kind): (Item) -> Boolean {
        val cats = catCache[k] ?: emptyList()
        val de = cats.filter { Lang.reGerman.containsMatchIn(it.name) }.map { it.id }.toHashSet()
        val foreign = cats.filter { it.id !in de && Lang.reForeignVodCat.containsMatchIn(it.name) }.map { it.id }.toHashSet()
        return f@{ item: Item ->
            if (Lang.reTitleForeign.containsMatchIn(item.name)) return@f false
            if (de.isEmpty() || Lang.reTitleDE.containsMatchIn(item.name)) return@f true
            val ids = item.cats()
            if (ids.any { c -> c in de }) return@f true
            ids.none { c -> c in foreign }
        }
    }

    fun isGerman(it: Item, k: Kind): Boolean = germanFilter(k)(it)

    suspend fun rawCategories(k: Kind): List<Category> = withContext(Dispatchers.IO) {
        catCache[k] ?: api!!.categories(k).also { catCache[k] = it }
    }

    suspend fun categories(k: Kind): List<Category> {
        val all = rawCategories(k)
        if (!filterDE(k)) return all
        val de = all.filter { Lang.reGerman.containsMatchIn(it.name) }
        return if (de.isEmpty()) all else de
    }

    suspend fun allItems(k: Kind): List<Item> = withContext(Dispatchers.IO) {
        if (catCache[k] == null) try { catCache[k] = api!!.categories(k) } catch (_: Exception) {}
        listCache["${k.path}:"] ?: api!!.items(k).also { listCache["${k.path}:"] = it }
    }

    suspend fun items(k: Kind, cat: String): List<Item> {
        if (cat.isEmpty()) {
            // „Alle“ – auch bei Anbietern, die die Gesamtliste verweigern
            val all = if (k == Kind.LIVE) allItems(k) else searchPool(k)
            return if (filterDE(k)) withContext(Dispatchers.Default) { val ok = germanFilter(k); all.filter(ok) } else all
        }
        return withContext(Dispatchers.IO) {
            listCache["${k.path}:$cat"] ?: api!!.items(k, cat).also { listCache["${k.path}:$cat"] = it }
        }
    }

    private fun keys(k: Kind, list: List<Item>): Array<String> {
        keyCache[k]?.let { if (it.first === list) return it.second }
        val a = Array(list.size) { Lang.key(list[it].name) }
        keyCache[k] = list to a
        return a
    }

    // ---------- Titel-Bestand für Suche und „Alle“ ----------

    private fun poolFile(k: Kind) = File(ctx.filesDir, "pool_${k.path}_${accountKey.hashCode().toUInt()}_${if (filterDE(k)) "de" else "all"}.json")

    private fun deletePoolFiles() {
        try { ctx.filesDir.listFiles()?.filter { it.name.startsWith("pool_") }?.forEach { it.delete() } } catch (_: Exception) {}
    }

    private fun savePool(k: Kind, list: List<Item>) {
        try {
            val f = poolFile(k)
            val tmp = File(f.path + ".tmp")
            android.util.JsonWriter(java.io.BufferedWriter(java.io.OutputStreamWriter(java.io.FileOutputStream(tmp), Charsets.UTF_8), 256 * 1024)).use { w ->
                w.beginArray()
                for (it in list) {
                    w.beginArray()
                    w.value(it.id); w.value(it.name); w.value(it.icon); w.value(it.categoryId); w.value(it.ext)
                    w.value(it.year); w.value(it.rating); w.value(it.added); w.value(it.categoryIds)
                    w.endArray()
                }
                w.endArray()
            }
            tmp.renameTo(f)
        } catch (_: Exception) {}
    }

    private fun loadPool(k: Kind): List<Item>? {
        val f = poolFile(k)
        if (!f.exists() || System.currentTimeMillis() - f.lastModified() > 12 * 3600_000L) return null
        return try {
            android.util.JsonReader(java.io.BufferedReader(java.io.InputStreamReader(java.io.FileInputStream(f), Charsets.UTF_8), 256 * 1024)).use { r ->
                val out = ArrayList<Item>()
                r.beginArray()
                while (r.hasNext()) {
                    r.beginArray()
                    val id = r.nextString(); val name = r.nextString(); val icon = r.nextString(); val cat = r.nextString(); val ext = r.nextString()
                    val year = r.nextString(); val rating = r.nextString(); val added = r.nextLong(); val ids = r.nextString()
                    while (r.hasNext()) r.skipValue()
                    r.endArray()
                    out.add(Item(id, name, icon, cat, ext, 0, "", year, rating, added, ids))
                }
                r.endArray()
                out.trimToSize()
                if (out.isEmpty()) null else out
            }
        } catch (_: Exception) { null }
    }

    /**
     * Alle Titel zum Durchsuchen (einmal geladen, 12 Std. auf dem Gerät gespeichert).
     * Viele Anbieter liefern die Gesamtliste (ohne category_id) leer oder brechen ab – dann wird
     * Kategorie für Kategorie geladen. Bei „nur deutsch“ werden nur klar fremdsprachige Kategorien
     * übersprungen; neutrale wie „Serien | Krimi“ oder „Netflix“ werden mit geladen.
     */
    suspend fun searchPool(k: Kind): List<Item> {
        val key = "pool:${k.path}:${filterDE(k)}"
        listCache[key]?.let { return it }
        return poolLocks.getValue(k).withLock {
            listCache[key]?.let { return@withLock it }
            val name = when (k) { Kind.MOVIE -> "Filme"; Kind.SERIES -> "Serien"; Kind.LIVE -> "Sender" }
            try {
                loadStatus[k] = "$name werden geladen…"
                withContext(Dispatchers.IO) { loadPool(k) }?.let { listCache[key] = it; return@withLock it }
                val full = if (k in noFullList) emptyList() else
                    try { allItems(k) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { noFullList.add(k); emptyList() }
                val out: List<Item> = if (full.isNotEmpty()) full else {
                    val all = rawCategories(k)
                    val cats = if (filterDE(k)) {
                        val de = all.filter { Lang.reGerman.containsMatchIn(it.name) }
                        all.filter { it in de || !Lang.reForeignVodCat.containsMatchIn(it.name) }
                    } else all
                    val done = java.util.concurrent.atomic.AtomicInteger()
                    loadStatus[k] = "$name: 0 / ${cats.size} Kategorien geladen"
                    val gate = Semaphore(8)
                    val parts = coroutineScope {
                        cats.map { c ->
                            async(Dispatchers.IO) {
                                gate.withPermit {
                                    val r = try { items(k, c.id) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { emptyList() }
                                    loadStatus[k] = "$name: ${done.incrementAndGet()} / ${cats.size} Kategorien geladen"
                                    r
                                }
                            }
                        }.awaitAll()
                    }
                    val seen = HashSet<String>()
                    val merged = ArrayList<Item>()
                    for (p in parts) for (it in p) if (seen.add(it.id)) merged.add(it)
                    merged.trimToSize()
                    // Einzellisten nicht doppelt im Speicher halten
                    for (c in cats) listCache.remove("${k.path}:${c.id}")
                    merged
                }
                if (out.isNotEmpty()) {
                    listCache[key] = out
                    withContext(Dispatchers.IO) { savePool(k, out) }
                }
                out
            } finally {
                loadStatus.remove(k)
            }
        }
    }

    /** im Hintergrund vorladen, damit die erste Suche sofort Ergebnisse hat */
    suspend fun prefetchSearch() {
        for (k in listOf(Kind.SERIES, Kind.MOVIE)) {
            try { searchPool(k) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) {}
        }
    }

    /**
     * Suche über alle Kategorien: Schreibweise egal, Wörter in beliebiger Reihenfolge,
     * fehlende Wörter und Tippfehler erlaubt. Beste Treffer zuerst.
     */
    suspend fun search(k: Kind, query: String): List<Item> {
        val q = Lang.Query(query)
        if (q.isEmpty) return emptyList()
        val raw = searchPool(k)
        return withContext(Dispatchers.Default) {
            val keys = synchronized(keyCache) { keys(k, raw) }
            val ok = if (filterDE(k)) germanFilter(k) else null
            val hits = ArrayList<Long>()
            for (i in raw.indices) {
                val s = Lang.score(q, keys[i])
                if (s < 0) continue
                if (ok != null && !ok(raw[i])) continue
                // Rang, dann kürzerer Titel zuerst
                hits.add((s.toLong() shl 40) or (keys[i].length.toLong().coerceAtMost(0xFFFF) shl 24) or i.toLong())
            }
            hits.sort()
            val seen = HashSet<String>()
            val out = ArrayList<Item>()
            for (h in hits) {
                val it = raw[(h and 0xFFFFFF).toInt()]
                if (seen.add(it.id)) out.add(it)
                if (out.size >= 300) break
            }
            out
        }
    }

    fun streamUrl(k: Kind, it: Item): String? = api?.streamUrl(k, it.id, if (k == Kind.LIVE) "ts" else it.ext)

    fun epgFor(it: Item): EpgChannel? = epg?.forStream(it.id)

    suspend fun loadEpg(force: Boolean): String? {
        val a = api ?: return null
        val e = epg
        if (!force && e != null && e.server == a.acc.server && System.currentTimeMillis() / 1000 - e.updated < 6 * 3600) return null
        if (epgLoading) return null
        epgLoading = true
        return try {
            val cats = rawCategories(Kind.LIVE)
            val streams = allItems(Kind.LIVE)
            val data = withContext(Dispatchers.IO) { EpgBuilder.build(a, cats, streams) }
            epg = data
            withContext(Dispatchers.IO) { epgFile().writeText(data.toJson()) }
            null
        } catch (ex: Exception) {
            ex.message ?: "Fehler"
        } finally {
            epgLoading = false
        }
    }
}
