package app.iptvplus.tv

import android.content.Context
import kotlinx.coroutines.Dispatchers
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

    private val catCache = HashMap<Kind, List<Category>>()
    private val listCache = HashMap<String, List<Item>>()
    private val infoCache = HashMap<String, JSONObject>()

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

    private fun resetCaches() { catCache.clear(); listCache.clear(); infoCache.clear(); epg = null; epgFile().delete() }

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

    private fun germanCatIds(k: Kind): Set<String> =
        (catCache[k] ?: emptyList()).filter { Lang.reGerman.containsMatchIn(it.name) }.map { it.id }.toSet()

    fun isGerman(it: Item, k: Kind): Boolean {
        if (Lang.reTitleForeign.containsMatchIn(it.name)) return false
        if (Lang.reTitleDE.containsMatchIn(it.name)) return true
        val ids = germanCatIds(k)
        return ids.isEmpty() || ids.contains(it.categoryId)
    }

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
            val all = allItems(k)
            return if (filterDE(k)) all.filter { isGerman(it, k) } else all
        }
        return withContext(Dispatchers.IO) {
            listCache["${k.path}:$cat"] ?: api!!.items(k, cat).also { listCache["${k.path}:$cat"] = it }
        }
    }

    /** Suche über alle Kategorien, Schreibweise egal, Tippfehler-tolerant */
    suspend fun search(k: Kind, query: String): List<Item> {
        val q = Lang.normQ(query)
        if (q.isEmpty()) return emptyList()
        var all = allItems(k)
        if (filterDE(k)) all = all.filter { isGerman(it, k) }
        return withContext(Dispatchers.Default) {
            val hits = all.filter { Lang.normQ(it.name).contains(q) }
            if (hits.isNotEmpty() || q.length < 4) hits else all.filter { Lang.fuzzy(q, Lang.normQ(it.name)) }.take(300)
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
