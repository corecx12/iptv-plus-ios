package app.iptvplus.tv

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/** Zugangsdaten, Zwischenspeicher und TV-Programm */
class Repo(private val ctx: Context) {
    private val prefs = ctx.getSharedPreferences("iptvplus", Context.MODE_PRIVATE)

    var account: Account? = loadAccount()
        private set
    var userInfo: JSONObject = JSONObject()
    var deOnly: Boolean
        get() = prefs.getBoolean("deOnly", true)
        set(v) { prefs.edit().putBoolean("deOnly", v).apply() }

    var epg: EpgData? = EpgData.load(epgFile())
    var epgLoading = false

    private val catCache = HashMap<Kind, List<Category>>()
    private val listCache = HashMap<String, List<Item>>()

    val api: XtreamApi? get() = account?.let { XtreamApi(it) }

    private fun loadAccount(): Account? {
        val s = prefs.getString("server", null) ?: return null
        return Account(s, prefs.getString("user", "") ?: "", prefs.getString("pass", "") ?: "")
    }

    private fun epgFile() = File(ctx.cacheDir, "epg_cache.json")

    suspend fun login(server: String, user: String, pass: String) = withContext(Dispatchers.IO) {
        val pw = pass.trim().ifEmpty { account?.password ?: "" }
        val acc = Account(Account.normalize(server), user.trim(), pw)
        val info = XtreamApi(acc).userInfo()
        val changed = acc != account
        prefs.edit().putString("server", acc.server).putString("user", acc.username).putString("pass", acc.password).apply()
        account = acc
        userInfo = info
        if (changed) { catCache.clear(); listCache.clear(); epg = null; epgFile().delete() }
    }

    suspend fun refreshUserInfo() = withContext(Dispatchers.IO) {
        try { api?.let { userInfo = it.userInfo() } } catch (_: Exception) {}
    }

    fun logout() {
        prefs.edit().remove("server").remove("user").remove("pass").apply()
        account = null; userInfo = JSONObject(); catCache.clear(); listCache.clear(); epg = null; epgFile().delete()
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
