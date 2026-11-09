package app.iptvplus.tv

import android.app.AlertDialog
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Film, Serie oder Sender mit Art – Inhalt einer Kachel */
data class Pick(val kind: Kind, val item: Item)

/** Kachel „Alle anzeigen“ */
data class MoreLink(val kind: Kind, val catId: String)

class MainActivity : AppCompatActivity() {

    private enum class Section { HOME, LIVE, MOVIE, SERIES, EPG, SEARCH, SETTINGS }
    private enum class Mode { NONE, FULL, MINI }
    private enum class Page { SHELVES, LIST, SEARCH }

    private lateinit var repo: Repo
    private lateinit var store: ResumeStore

    // Kopfzeile
    private lateinit var topBar: View
    private lateinit var nav: LinearLayout

    // Seite mit Reihen
    private lateinit var pageShelves: View
    private lateinit var heroBackdrop: ImageView
    private lateinit var heroInfo: LinearLayout
    private lateinit var heroKicker: TextView
    private lateinit var heroTitle: TextView
    private lateinit var heroMeta: TextView
    private lateinit var heroPlot: TextView
    private lateinit var heroButtons: LinearLayout
    private lateinit var shelvesView: RecyclerView
    private lateinit var shelvesMsg: TextView
    private lateinit var shelfAdapter: ShelfAdapter

    // Listen-Seite
    private lateinit var pageList: View
    private lateinit var listBox: View
    private lateinit var search: EditText
    private lateinit var catsView: RecyclerView
    private lateinit var itemsView: RecyclerView
    private lateinit var header: TextView
    private lateinit var settings: ScrollView
    private lateinit var settingsBox: LinearLayout
    private lateinit var catsAdapter: RowAdapter
    private lateinit var itemsAdapter: RowAdapter

    // Suche
    private lateinit var pageSearch: View
    private lateinit var globalSearch: EditText
    private lateinit var searchMsg: TextView
    private lateinit var searchShelves: RecyclerView
    private lateinit var searchAdapter: ShelfAdapter

    // Detailseite
    private lateinit var detailPage: View
    private lateinit var detailBackdrop: ImageView
    private lateinit var detailKicker: TextView
    private lateinit var detailTitle: TextView
    private lateinit var detailMeta: TextView
    private lateinit var detailPlot: TextView
    private lateinit var detailButtons: LinearLayout
    private lateinit var detailSeries: View
    private lateinit var seasonTabs: LinearLayout
    private lateinit var episodesView: RecyclerView
    private lateinit var episodesAdapter: CardAdapter

    // Player
    private lateinit var playerBox: FrameLayout
    private lateinit var videoLayout: VLCVideoLayout
    private lateinit var spinner: ProgressBar
    private lateinit var playerMsg: TextView
    private lateinit var pauseBadge: View
    private lateinit var infoOverlay: View
    private lateinit var topPanel: View
    private lateinit var panelTabs: LinearLayout
    private lateinit var panelBody: LinearLayout

    private var section = Section.HOME
    private var page = Page.SHELVES
    private var listKind: Kind? = null
    private var cats: List<Category> = emptyList()
    private var currentItems: List<Item> = emptyList()
    private var epgTime = "now"
    private var loadJob: Job? = null
    private var searchJob: Job? = null
    private var listSearchJob: Job? = null
    /** Suchbegriff, dessen Ergebnisse gerade angezeigt bzw. berechnet werden */
    private var globalQuery = ""
    private val navButtons = LinkedHashMap<Section, TextView>()
    private val navIcons = HashMap<Section, View>()
    private var keepNavFocus = false
    private var navEnteredFromOutside = false
    private var topBarShown = true
    private var heroEnabled = false
    private var heroCard: Card? = null
    private var heroPending: Card? = null
    private var focusShelf = 0
    private var returnFocus: View? = null

    // Detail
    private var detail: Pick? = null
    private var detailMetaData: Meta? = null
    private var seasons: LinkedHashMap<String, List<Episode>> = LinkedHashMap()
    private var currentSeason = ""

    // Player
    private var libVLC: LibVLC? = null
    private var mp: MediaPlayer? = null
    private var mode = Mode.NONE
    private var playing: Item? = null
    private var playingKind = Kind.LIVE
    private var playingTitle = ""
    private var playingSeries: Item? = null
    private var playingEp: Episode? = null
    private var startMs = 0L
    private var exitToMini = false
    private var zapList: List<Item> = emptyList()
    private var errorCount = 0
    private var tickCount = 0
    private var panelTab = 0
    private val ui = Handler(Looper.getMainLooper())
    private val hideInfo = Runnable { if (mp?.isPlaying != false) infoOverlay.visibility = View.GONE }
    private val hm = SimpleDateFormat("HH:mm", Locale.GERMANY)

    private fun dp(x: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, x.toFloat(), resources.displayMetrics).toInt()
    private fun col(id: Int) = ContextCompat.getColor(this, id)
    private fun clean(s: String) = Canon.clean(s)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installCrashLog()
        Ui.init(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)
        repo = Repo(this)
        store = ResumeStore(this)
        store.accountKey = repo.accountKey

        topBar = findViewById(R.id.topBar)
        nav = findViewById(R.id.nav)
        pageShelves = findViewById(R.id.pageShelves)
        heroBackdrop = findViewById(R.id.heroBackdrop)
        heroInfo = findViewById(R.id.heroInfo)
        heroKicker = findViewById(R.id.heroKicker)
        heroTitle = findViewById(R.id.heroTitle)
        heroMeta = findViewById(R.id.heroMeta)
        heroPlot = findViewById(R.id.heroPlot)
        heroButtons = findViewById(R.id.heroButtons)
        shelvesView = findViewById(R.id.shelvesView)
        shelvesMsg = findViewById(R.id.shelvesMsg)
        pageList = findViewById(R.id.pageList)
        listBox = findViewById(R.id.listBox)
        search = findViewById(R.id.search)
        catsView = findViewById(R.id.cats)
        itemsView = findViewById(R.id.items)
        header = findViewById(R.id.header)
        settings = findViewById(R.id.settings)
        settingsBox = findViewById(R.id.settingsBox)
        pageSearch = findViewById(R.id.pageSearch)
        globalSearch = findViewById(R.id.globalSearch)
        searchMsg = findViewById(R.id.searchMsg)
        searchShelves = findViewById(R.id.searchShelves)
        detailPage = findViewById(R.id.detailPage)
        detailBackdrop = findViewById(R.id.detailBackdrop)
        detailKicker = findViewById(R.id.detailKicker)
        detailTitle = findViewById(R.id.detailTitle)
        detailMeta = findViewById(R.id.detailMeta)
        detailPlot = findViewById(R.id.detailPlot)
        detailButtons = findViewById(R.id.detailButtons)
        detailSeries = findViewById(R.id.detailSeries)
        seasonTabs = findViewById(R.id.seasonTabs)
        episodesView = findViewById(R.id.episodesView)
        playerBox = findViewById(R.id.playerBox)
        videoLayout = findViewById(R.id.videoLayout)
        spinner = findViewById(R.id.spinner)
        playerMsg = findViewById(R.id.playerMsg)
        pauseBadge = findViewById(R.id.pauseBadge)
        infoOverlay = findViewById(R.id.infoOverlay)
        topPanel = findViewById(R.id.topPanel)
        panelTabs = findViewById(R.id.panelTabs)
        panelBody = findViewById(R.id.panelBody)

        applyTypography()

        // Listen-Seite
        catsAdapter = RowAdapter(false, { v -> keepNearTop(catsView, v) }) { pos, row -> onCategory(pos, row, moveFocus = true) }
        itemsAdapter = RowAdapter(true, { v -> keepNearTop(itemsView, v) }) { pos, row -> onItem(pos, row) }
        catsView.layoutManager = LinearLayoutManager(this)
        catsView.adapter = catsAdapter
        itemsView.layoutManager = LinearLayoutManager(this)
        itemsView.adapter = itemsAdapter
        itemsView.itemAnimator = null

        // Reihen
        shelfAdapter = ShelfAdapter({ i, c, v -> onShelfFocus(shelvesView, i, c, v) }, { i, c, v -> onCardClick(shelfAdapter, i, c, v) })
        shelfAdapter.onBindShelf = { i -> onBindShelf(i) }
        shelvesView.layoutManager = ShelfLayoutManager(this)
        shelvesView.adapter = shelfAdapter
        shelvesView.itemAnimator = null
        shelvesView.setItemViewCacheSize(4)
        shelvesView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) { updateHeroFade() }
        })
        searchAdapter = ShelfAdapter({ i, c, v -> onShelfFocus(searchShelves, i, c, v) }, { i, c, v -> onCardClick(searchAdapter, i, c, v) })
        searchShelves.layoutManager = ShelfLayoutManager(this)
        searchShelves.adapter = searchAdapter
        searchShelves.itemAnimator = null

        // Folgen auf der Detailseite
        episodesAdapter = CardAdapter(CardStyle.WIDE, { _, _ -> }, { card, _ -> (card.payload as? Episode)?.let { playEpisode(it) } })
        episodesView.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        episodesView.adapter = episodesAdapter
        episodesView.itemAnimator = null

        buildNav()
        setupSearch()
        setupGlobalSearch()
        findViewById<View>(R.id.root).viewTreeObserver.addOnGlobalFocusChangeListener { old, new ->
            val tabs = navButtons.values
            navEnteredFromOutside = tabs.any { it === new } && tabs.none { it === old }
        }

        val startApp: () -> Unit = {
            if (repo.account == null) {
                showSection(Section.SETTINGS)
            } else {
                showSection(Section.HOME)
                lifecycleScope.launch { repo.refreshUserInfo(); loadEpg(false) }
                // Serien & Filme für die Suche im Hintergrund vorladen (nach der Startseite)
                lifecycleScope.launch { delay(6000); repo.prefetchSearch() }
            }
        }
        // nach einem Absturz zuerst den Bericht zeigen und erst danach laden
        if (!showLastCrash(startApp)) startApp()
        ui.postDelayed(object : Runnable {
            override fun run() { refreshNow(); ui.postDelayed(this, 60000) }
        }, 60000)
    }

    // ---------------- Absturz-Bericht ----------------

    private fun crashFile() = java.io.File(filesDir, "last_crash.txt")

    /** Bei einem Absturz die Fehlermeldung speichern, damit sie beim nächsten Start angezeigt werden kann */
    private fun installCrashLog() {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        val f = crashFile()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val sw = java.io.StringWriter()
                e.printStackTrace(java.io.PrintWriter(sw))
                f.writeText("Version ${Updater.versionName(this)} · " + SimpleDateFormat("dd.MM. HH:mm", Locale.GERMANY).format(Date()) + "\n" + sw.toString().take(6000))
            } catch (_: Throwable) {}
            prev?.uncaughtException(t, e)
        }
    }

    private fun showLastCrash(then: () -> Unit): Boolean {
        val f = crashFile()
        if (!f.exists()) return false
        val text = try { f.readText() } catch (e: Exception) { "" }
        if (text.isBlank()) { f.delete(); return false }
        val tv = TextView(this).apply {
            this.text = text
            setTextColor(col(R.color.muted))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        val sv = ScrollView(this).apply { addView(tv); isFocusable = true }
        AlertDialog.Builder(this, R.style.AppleDialog)
            .setTitle("Die App ist beim letzten Mal abgestürzt")
            .setMessage("Bitte ein Foto von diesem Text machen und an Claude schicken.")
            .setView(sv)
            .setPositiveButton("OK", null)
            .setOnDismissListener { f.delete(); then() }
            .show().also { Ui.fontify(it.window?.decorView); tv.typeface = android.graphics.Typeface.MONOSPACE }
        return true
    }

    override fun onDestroy() {
        super.onDestroy()
        releasePlayer()
    }

    override fun onStop() {
        super.onStop()
        // App im Hintergrund: Position merken, Wiedergabe beenden (Verbindung freigeben)
        if (mode != Mode.NONE) { saveProgress(); stopPlayer() }
    }

    /** Apple-Schriftstufen auf die festen Texte anwenden */
    private fun applyTypography() {
        Ui.fontify(findViewById(R.id.root))
        findViewById<TextView>(R.id.logo).apply {
            type(Ui.T.TITLE2)
            text = android.text.Html.fromHtml("IPTV<font color='#0A84FF'>+</font>")
        }
        heroKicker.type(Ui.T.CAPTION); heroTitle.type(Ui.T.LARGE_TITLE); heroMeta.type(Ui.T.SUBHEAD_MEDIUM); heroPlot.type(Ui.T.CALLOUT)
        detailKicker.type(Ui.T.CAPTION); detailTitle.type(Ui.T.LARGE_TITLE); detailMeta.type(Ui.T.SUBHEAD_MEDIUM); detailPlot.type(Ui.T.BODY)
        heroKicker.isAllCaps = true; detailKicker.isAllCaps = true
        heroKicker.letterSpacing = 0.06f; detailKicker.letterSpacing = 0.06f
        header.type(Ui.T.TITLE3)
        shelvesMsg.type(Ui.T.BODY); searchMsg.type(Ui.T.SUBHEAD)
        search.typeface = Ui.regular
        globalSearch.typeface = Ui.regular
        findViewById<TextView>(R.id.infoKicker).apply { type(Ui.T.CAPTION); isAllCaps = true; letterSpacing = 0.06f }
        findViewById<TextView>(R.id.infoTitle).type(Ui.T.TITLE2)
        findViewById<TextView>(R.id.infoNow).type(Ui.T.CALLOUT)
        for (id in listOf(R.id.infoTimeL, R.id.infoTimeR)) findViewById<TextView>(id).apply { type(Ui.T.FOOTNOTE); fontFeatureSettings = "tnum" }
        findViewById<TextView>(R.id.infoHint).type(Ui.T.FOOTNOTE)
        playerMsg.type(Ui.T.BODY)
        findViewById<TextView>(R.id.banner).type(Ui.T.SUBHEAD_MEDIUM)
    }

    // ---------------- Kopfzeile & Reiter ----------------

    private fun tabLabel(s: Section) = when (s) {
        Section.HOME -> "Start"; Section.LIVE -> "Live TV"; Section.MOVIE -> "Filme"; Section.SERIES -> "Serien"
        Section.EPG -> "TV-Programm"; Section.SEARCH -> "Suchen"; Section.SETTINGS -> "Einstellungen"
    }

    private fun onTabFocus(s: Section, v: View, has: Boolean) {
        v.focusLift(has, 1.06f)
        if (!has) return
        showTopBar(true)
        val cur = navButtons[section]
        if (navEnteredFromOutside && cur != null && cur !== v) {
            // von unten in die Leiste: immer auf dem aktuellen Reiter landen
            cur.requestFocus()
        } else if (s != section) {
            // Apple TV: Reiter wechselt schon beim Darüberfahren
            showSection(s, keepFocus = true)
        }
    }

    private fun buildNav() {
        nav.removeAllViews()
        navButtons.clear()
        for (s in listOf(Section.HOME, Section.LIVE, Section.MOVIE, Section.SERIES, Section.EPG, Section.SEARCH, Section.SETTINGS)) {
            val icon = when (s) { Section.SEARCH -> R.drawable.ic_tab_search; Section.SETTINGS -> R.drawable.ic_tab_settings; else -> 0 }
            val b = TextView(this).apply {
                isFocusable = true; isClickable = true
                setBackgroundResource(R.drawable.tab_bg)
                setTextColor(ContextCompat.getColorStateList(this@MainActivity, R.color.tab_text))
                type(Ui.T.TAB)
                gravity = Gravity.CENTER
                contentDescription = tabLabel(s)
                if (icon != 0) {
                    // Symbol-Reiter: Lupe und Einstellungen
                    val d = ContextCompat.getDrawable(this@MainActivity, icon)!!.mutate()
                    androidx.core.graphics.drawable.DrawableCompat.setTintList(d, ContextCompat.getColorStateList(this@MainActivity, R.color.tab_text))
                    d.setBounds(0, 0, dp(19), dp(19))
                    setCompoundDrawables(d, null, null, null)
                    setPadding(dp(14), dp(10), dp(14), dp(10))
                } else {
                    text = tabLabel(s)
                    setPadding(dp(20), dp(9), dp(20), dp(9))
                }
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(2) }
                setOnClickListener { showSection(s) }
                setOnFocusChangeListener { v, has -> onTabFocus(s, v, has) }
            }
            navButtons[s] = b
            nav.addView(b)
        }
    }

    private fun highlightNav() {
        for ((k, v) in navButtons) v.isSelected = k == section
    }

    private fun showTopBar(show: Boolean) {
        if (show == topBarShown) return
        topBarShown = show
        topBar.animate().alpha(if (show) 1f else 0f).translationY(if (show) 0f else -dp(24).toFloat()).setDuration(220).start()
    }

    private fun navHasFocus() = nav.findFocus() != null

    /** Kurzer Hinweis oben in der Mitte (statt Android-Toast) */
    private val hideBanner = Runnable {
        val b = findViewById<TextView>(R.id.banner)
        b.animate().alpha(0f).setDuration(200).withEndAction { b.visibility = View.GONE }.start()
    }

    private fun showBanner(msg: String, long: Boolean) {
        val b = findViewById<TextView>(R.id.banner)
        b.text = msg
        ui.removeCallbacks(hideBanner)
        if (b.visibility != View.VISIBLE) { b.alpha = 0f; b.visibility = View.VISIBLE }
        b.animate().alpha(1f).setDuration(200).start()
        ui.postDelayed(hideBanner, if (long) 4500L else 2500L)
    }

    private fun focusCurrentTab() {
        showTopBar(true)
        navButtons[section]?.requestFocus()
    }

    // ---------------- Seiten ----------------

    private fun showPage(p: Page) {
        page = p
        pageShelves.visibility = if (p == Page.SHELVES) View.VISIBLE else View.GONE
        pageList.visibility = if (p == Page.LIST) View.VISIBLE else View.GONE
        pageSearch.visibility = if (p == Page.SEARCH) View.VISIBLE else View.GONE
        if (p != Page.SHELVES) showTopBar(true)
    }

    private fun setListMode(withCats: Boolean, withSearch: Boolean, withSettings: Boolean) {
        findViewById<View>(R.id.colCats).visibility = if (withCats) View.VISIBLE else View.GONE
        search.visibility = if (withSearch) View.VISIBLE else View.GONE
        settings.visibility = if (withSettings) View.VISIBLE else View.GONE
        listBox.visibility = if (withSettings) View.GONE else View.VISIBLE
    }

    private fun showSection(s: Section, keepFocus: Boolean = false) {
        keepNavFocus = keepFocus
        section = s
        listKind = null
        // kleines Vorschaubild gehört zu Live TV / TV-Programm
        if (mode == Mode.MINI && s != Section.LIVE && s != Section.EPG) stopPlayer()
        if (detailPage.visibility == View.VISIBLE) closeDetail(restoreFocus = false)
        highlightNav()
        loadJob?.cancel()
        searchJob?.cancel(); listSearchJob?.cancel()
        globalQuery = ""
        search.setText("")
        when (s) {
            Section.HOME, Section.MOVIE, Section.SERIES -> { showPage(Page.SHELVES); loadShelves(s) }
            // Live TV wie früher: Kategorien links, Senderliste rechts, Vorschau im kleinen Fenster
            Section.LIVE -> {
                listKind = Kind.LIVE
                showPage(Page.LIST)
                setListMode(withCats = true, withSearch = true, withSettings = false)
                loadCategories(Kind.LIVE, null, focusList = !keepFocus)
            }
            Section.EPG -> { showPage(Page.LIST); setListMode(withCats = true, withSearch = false, withSettings = false); showEpgSection() }
            Section.SEARCH -> {
                showPage(Page.SEARCH)
                if (!keepFocus) globalSearch.post { globalSearch.requestFocus() }
            }
            Section.SETTINGS -> { showPage(Page.LIST); setListMode(withCats = false, withSearch = false, withSettings = true); buildSettings() }
        }
    }

    private fun kindOf(s: Section) = when (s) { Section.MOVIE -> Kind.MOVIE; Section.SERIES -> Kind.SERIES; else -> Kind.LIVE }

    // ---------------- Reihen (Start, Live TV, Filme, Serien) ----------------

    /** Abstand, ab dem die erste Reihe beginnt (darüber das große Titelbild) */
    private fun heroTop() = resources.displayMetrics.heightPixels - dp(214)

    private fun posterCard(k: Kind, it: Item) = Card(clean(it.name), "", it.icon, payload = Pick(k, it))

    private fun channelCard(it: Item): Card {
        val cur = repo.epgFor(it)?.current()
        val sub = if (cur != null) "${hm.format(Date(cur.s * 1000))}  ${cur.t}" else ""
        return Card(clean(it.name), sub, it.icon, if (cur != null) pct(cur) else -1, Pick(Kind.LIVE, it))
    }

    private fun resumeCard(r: Resume): Card {
        val isEp = r.seriesId.isNotEmpty()
        val sub = (if (isEp) "S${r.season} · F${r.episode}  ·  " else "") + "noch ${r.remainingMin} Min."
        return Card(if (isEp) clean(r.seriesName) else clean(r.title), sub, if (isEp) r.seriesIcon.ifEmpty { r.icon } else r.icon,
            (r.fraction * 1000).toInt(), payload = r)
    }

    private fun continueShelf(k: Kind?): Shelf? {
        val list = store.all().filter { k == null || it.kind == k }
        if (list.isEmpty()) return null
        return Shelf("Weiterschauen", CardStyle.POSTER, list.take(20).map { resumeCard(it) })
    }

    private var shelfGen = 0
    private var shelfWantFocus = false
    private val shelfLoading = HashSet<String>()

    /** Reihe kommt ins Bild: ihre Kategorie nachladen (nur diese – spart Speicher) */
    private fun onBindShelf(i: Int) {
        val sh = shelfAdapter.shelves.getOrNull(i) ?: return
        if (!sh.placeholder) return
        val src = sh.source as? MoreLink ?: return
        val key = "${src.kind}:${src.catId}:${sh.title}"
        if (!shelfLoading.add(key)) return
        val gen = shelfGen
        lifecycleScope.launch {
            val list = try { repo.items(src.kind, src.catId) } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Exception) { emptyList() } catch (e: OutOfMemoryError) { emptyList() }
            shelfLoading.remove(key)
            if (gen != shelfGen) return@launch
            val idx = shelfAdapter.shelves.indexOfFirst { it.source === src }
            if (idx < 0) return@launch
            val limit = if (src.kind == Kind.LIVE) 40 else 30
            val cards = if (src.kind == Kind.LIVE) list.take(limit).map { channelCard(it) }
                else list.sortedByDescending { it.added }.take(limit).map { posterCard(src.kind, it) }
            if (cards.isEmpty()) { shelfAdapter.removeAt(idx); return@launch }
            val more = if (list.size > limit) listOf(Card("Alle", more = true, payload = src)) else emptyList()
            shelfAdapter.replaceCards(idx, cards + more)
            if (heroEnabled && heroCard == null) applyHero(cards.first())
            if (shelfWantFocus && page == Page.SHELVES && currentFocus?.let { f -> pageShelves.findFocus() === f } != true && !navHasFocus()) {
                shelfWantFocus = false
                shelvesView.post { focusFirstShelfCard() }
            }
        }
    }

    /** Kategorien mit neuen Titeln zuerst */
    private val reNew = Regex("neu|new|kino|aktuell|recent|top|20(2[4-9])", RegexOption.IGNORE_CASE)

    private fun lazyShelf(title: String, k: Kind, c: Category) =
        Shelf(title, if (k == Kind.LIVE) CardStyle.CHANNEL else CardStyle.POSTER, placeholderCards(), MoreLink(k, c.id), placeholder = true)

    private fun loadShelves(s: Section) {
        loadJob?.cancel()
        shelfGen++
        shelfLoading.clear()
        shelfAdapter.submit(emptyList())
        heroEnabled = s != Section.LIVE
        heroCard = null
        shelvesView.setPadding(0, if (heroEnabled) heroTop() else dp(96), 0, dp(40))
        shelvesView.scrollToPosition(0)
        heroBackdrop.setImageDrawable(null)
        heroKicker.text = ""; heroTitle.text = ""; heroMeta.text = ""; heroPlot.text = ""
        heroButtons.removeAllViews()
        updateHeroFade()
        shelvesMsg.text = "Wird geladen…"
        shelvesMsg.visibility = View.VISIBLE
        val wantFocus = !keepNavFocus
        shelfWantFocus = wantFocus
        loadJob = lifecycleScope.launch {
            var first = true
            fun add(sh: Shelf) {
                if (sh.cards.isEmpty()) return
                if (first) {
                    first = false
                    shelvesMsg.visibility = View.GONE
                    shelfAdapter.submit(listOf(sh))
                    if (!sh.placeholder) {
                        if (heroEnabled) sh.cards.firstOrNull { !it.more }?.let { applyHero(it) }
                        if (wantFocus && !navHasFocus()) { shelfWantFocus = false; shelvesView.post { focusFirstShelfCard() } }
                    }
                } else shelfAdapter.append(sh)
            }
            try {
                if (repo.account == null) throw ApiException("Noch kein Konto eingerichtet. Unter Einstellungen anmelden.")
                when (s) {
                    Section.HOME -> buildHome(::add)
                    Section.LIVE -> buildLive(::add)
                    else -> buildVod(kindOf(s), ::add)
                }
                if (first) { shelvesMsg.text = "Hier gibt es noch nichts zu sehen."; shelvesMsg.visibility = View.VISIBLE }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: OutOfMemoryError) {
                if (first) { shelvesMsg.text = "Zu viele Titel für den Speicher dieses Geräts."; shelvesMsg.visibility = View.VISIBLE }
            } catch (e: Exception) {
                if (first) { shelvesMsg.text = e.message ?: "Inhalte konnten nicht geladen werden."; shelvesMsg.visibility = View.VISIBLE }
            }
        }
    }

    private fun focusFirstShelfCard() {
        if (heroEnabled && heroButtons.childCount > 0) { heroButtons.getChildAt(0).requestFocus(); return }
        val row = shelvesView.findViewHolderForAdapterPosition(0) as? ShelfAdapter.VH ?: return
        row.rv.post { row.rv.getChildAt(0)?.requestFocus() }
    }

    private suspend fun buildHome(add: (Shelf) -> Unit) {
        continueShelf(null)?.let(add)
        val mc = try { repo.categories(Kind.MOVIE) } catch (e: Exception) { emptyList() }
        (mc.firstOrNull { reNew.containsMatchIn(it.name) } ?: mc.firstOrNull())?.let { add(lazyShelf("Neue Filme", Kind.MOVIE, it)) }
        val sc = try { repo.categories(Kind.SERIES) } catch (e: Exception) { emptyList() }
        (sc.firstOrNull { reNew.containsMatchIn(it.name) } ?: sc.firstOrNull())?.let { add(lazyShelf("Neue Serien", Kind.SERIES, it)) }
        val chans = repo.epg?.channels?.take(24)?.map { c -> Item(c.streamId, c.name, c.icon, "", "ts", c.num, "", "", "") }
        if (chans != null) add(Shelf("Live jetzt", CardStyle.CHANNEL, chans.map { channelCard(it) }))
        else {
            val lc = try { repo.categories(Kind.LIVE) } catch (e: Exception) { emptyList() }
            (lc.firstOrNull { Lang.reGerman.containsMatchIn(it.name) } ?: lc.firstOrNull())?.let { add(lazyShelf("Live jetzt", Kind.LIVE, it)) }
        }
        // weitere Kategorien als Reihen
        for (c in mc.filter { reNew.containsMatchIn(it.name) }.drop(1).take(3)) add(lazyShelf(clean(c.name), Kind.MOVIE, c))
    }

    /** Filme/Serien: eine Reihe pro Kategorie, Inhalte laden erst beim Anzeigen */
    private suspend fun buildVod(k: Kind, add: (Shelf) -> Unit) {
        continueShelf(k)?.let(add)
        val cs = repo.categories(k).sortedBy { if (reNew.containsMatchIn(it.name)) 0 else 1 }
        for (c in cs) add(lazyShelf(clean(c.name), k, c))
    }

    private suspend fun buildLive(add: (Shelf) -> Unit) {
        val cs = repo.categories(Kind.LIVE).sortedBy { if (Lang.reGerman.containsMatchIn(it.name)) 0 else 1 }
        for (c in cs) add(lazyShelf(clean(c.name), Kind.LIVE, c))
    }

    /** Titelbild ausblenden, wenn nach unten gescrollt wird; Kopfzeile blendet mit aus */
    private fun updateHeroFade() {
        val off = shelvesView.computeVerticalScrollOffset().toFloat()
        val keep = heroInfo.hasFocus()
        val a = if (!heroEnabled) 0f else if (keep) 1f else (1f - off / dp(70)).coerceIn(0f, 1f)
        heroInfo.alpha = a
        heroInfo.visibility = if (a > 0.02f) View.VISIBLE else View.INVISIBLE
        heroBackdrop.alpha = if (!heroEnabled) 0f else 1f - 0.6f * (off / dp(220)).coerceIn(0f, 1f)
        if (page == Page.SHELVES) showTopBar(keep || navHasFocus() || off < dp(40))
    }

    /** Reihe beim Fokussieren an feste Höhe holen (wie Apple TV) */
    private fun onShelfFocus(rv: RecyclerView, idx: Int, card: Card, v: View) {
        val row = rv.findContainingItemView(v) ?: return
        val off = rv.computeVerticalScrollOffset()
        val dy = if (idx == 0) {
            val wanted = (row.bottom + off - (rv.height - dp(14))).coerceAtLeast(0)
            wanted - off
        } else row.top - (if (rv === shelvesView) dp(56) else dp(8))
        if (dy != 0) rv.smoothScrollBy(0, dy)
        if (rv === shelvesView) {
            focusShelf = idx
            if (heroEnabled && !card.more) { heroPending = card; ui.removeCallbacks(heroRunnable); ui.postDelayed(heroRunnable, 380) }
        }
    }

    private val heroRunnable = Runnable { heroPending?.let { if (it !== heroCard) applyHero(it) } }

    /** Pfeil nach oben in der ersten Reihe: zurück zum Titelbild */
    private fun heroUpFromFirstRow(): Boolean {
        if (page != Page.SHELVES || !heroEnabled || heroButtons.childCount == 0) return false
        val f = currentFocus ?: return false
        val row = shelvesView.findContainingItemView(f) ?: return false
        if (shelvesView.getChildAdapterPosition(row) != 0) return false
        heroInfo.visibility = View.VISIBLE; heroInfo.alpha = 1f
        heroButtons.getChildAt(0).requestFocus()
        shelvesView.smoothScrollBy(0, -shelvesView.computeVerticalScrollOffset())
        return true
    }

    /** Kapsel-Taste (Abspielen, Details …) */
    private fun pill(label: String, play: Boolean, onClick: () -> Unit) = TextView(this).apply {
        text = label
        type(Ui.T.BUTTON)
        gravity = Gravity.CENTER_VERTICAL
        setTextColor(ContextCompat.getColorStateList(this@MainActivity, R.color.cell_text))
        setBackgroundResource(R.drawable.button_bg)
        if (play) {
            val d = ContextCompat.getDrawable(this@MainActivity, R.drawable.ic_play)!!.mutate()
            androidx.core.graphics.drawable.DrawableCompat.setTintList(d, ContextCompat.getColorStateList(this@MainActivity, R.color.cell_text))
            d.setBounds(0, 0, dp(15), dp(15))
            setCompoundDrawables(d, null, null, null)
            compoundDrawablePadding = dp(8)
        }
        setPadding(dp(if (play) 22 else 26), dp(12), dp(26), dp(12))
        isFocusable = true; isClickable = true
        setOnClickListener { onClick() }
        setOnFocusChangeListener { v, has -> v.focusLift(has, 1.06f) }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(14) }
    }

    /** Großes Titelbild mit Infos zum gewählten Film, zur Serie oder zum Sender */
    private fun applyHero(card: Card) {
        heroCard = card
        val hadFocus = heroButtons.findFocus() != null
        heroButtons.removeAllViews()
        when (val p = card.payload) {
            is Pick -> {
                val it = p.item
                heroTitle.text = clean(it.name)
                if (p.kind == Kind.LIVE) {
                    val cur = repo.epgFor(it)?.current()
                    heroKicker.text = "Live"
                    heroMeta.text = if (cur != null) "Jetzt  ·  ${hm.format(Date(cur.s * 1000))} – ${hm.format(Date(cur.e * 1000))}  ·  ${cur.t}" else ""
                    heroPlot.text = cur?.d ?: ""
                    heroBackdrop.setImageDrawable(null)
                    heroButtons.addView(pill("Einschalten", true) { playLiveFull(it, liveListOfShelf(focusShelf)) })
                } else {
                    heroKicker.text = if (p.kind == Kind.MOVIE) "Film" else "Serie"
                    heroMeta.text = listOf(it.year, if (it.rating.isEmpty() || it.rating == "0") "" else "★ ${it.rating.take(3).replace('.', ',')}").filter { s -> s.isNotEmpty() }.joinToString("  ·  ")
                    heroPlot.text = ""
                    heroBackdrop.load(it.icon) { crossfade(300) }
                    if (p.kind == Kind.MOVIE) {
                        val r = store.get(Kind.MOVIE, it.id)
                        heroButtons.addView(pill(if (r != null) "Fortsetzen" else "Abspielen", true) { playMovie(it, r?.pos ?: 0L) })
                    } else {
                        heroButtons.addView(pill("Ansehen", true) { openDetail(Kind.SERIES, it) })
                    }
                    heroButtons.addView(pill("Details", false) { openDetail(p.kind, it) })
                    lifecycleScope.launch {
                        val m = try { Meta.from(repo.info(p.kind, it.id)) } catch (e: Exception) { return@launch }
                        if (heroCard !== card) return@launch
                        m.line().takeIf { s -> s.isNotEmpty() }?.let { s -> heroMeta.text = s }
                        heroPlot.text = m.plot
                        if (m.backdrop.isNotEmpty() && m.backdrop != it.icon) heroBackdrop.load(m.backdrop) { crossfade(400) }
                    }
                }
            }
            is Resume -> {
                val isEp = p.seriesId.isNotEmpty()
                heroKicker.text = "Weiterschauen"
                heroTitle.text = clean(if (isEp) p.seriesName else p.title)
                heroMeta.text = (if (isEp) "Staffel ${p.season}, Folge ${p.episode}  ·  " else "") + "noch ${p.remainingMin} Min."
                heroPlot.text = ""
                heroBackdrop.load(if (isEp) p.seriesIcon.ifEmpty { p.icon } else p.icon) { crossfade(300) }
                heroButtons.addView(pill("Fortsetzen", true) { resumePlay(p) })
                val k = if (isEp) Kind.SERIES else Kind.MOVIE
                val id = if (isEp) p.seriesId else p.id
                val item = Item(id, if (isEp) p.seriesName else p.title, if (isEp) p.seriesIcon else p.icon, "", p.ext, 0, "", "", "")
                heroButtons.addView(pill("Details", false) { openDetail(k, item) })
                lifecycleScope.launch {
                    val m = try { Meta.from(repo.info(k, id)) } catch (e: Exception) { return@launch }
                    if (heroCard !== card) return@launch
                    heroPlot.text = m.plot
                    if (m.backdrop.isNotEmpty()) heroBackdrop.load(m.backdrop) { crossfade(400) }
                }
            }
        }
        if (hadFocus) heroButtons.getChildAt(0)?.requestFocus()
    }

    private fun liveListOfShelf(i: Int): List<Item> =
        shelfAdapter.shelves.getOrNull(i)?.cards?.mapNotNull { (it.payload as? Pick)?.takeIf { p -> p.kind == Kind.LIVE }?.item } ?: emptyList()

    private fun onCardClick(adapter: ShelfAdapter, idx: Int, card: Card, v: View) {
        returnFocus = v
        when (val p = card.payload) {
            is MoreLink -> openList(p.kind, p.catId)
            is Resume -> resumePlay(p)
            is Pick -> when (p.kind) {
                Kind.LIVE -> {
                    val list = adapter.shelves.getOrNull(idx)?.cards?.mapNotNull { (it.payload as? Pick)?.takeIf { x -> x.kind == Kind.LIVE }?.item } ?: listOf(p.item)
                    playLiveFull(p.item, list)
                }
                else -> openDetail(p.kind, p.item)
            }
        }
    }

    /** Weiterschauen-Reihe nach dem Ansehen aktualisieren, ohne die Seite neu aufzubauen */
    private fun refreshContinueShelf() {
        if (page != Page.SHELVES || !(section == Section.HOME || section == Section.MOVIE || section == Section.SERIES)) return
        val fresh = continueShelf(if (section == Section.HOME) null else kindOf(section))
        val list = shelfAdapter.shelves.toMutableList()
        val has = list.firstOrNull()?.title == "Weiterschauen"
        when {
            has && fresh != null -> { list[0] = fresh; shelfAdapter.setRaw(list); shelfAdapter.notifyItemChanged(0) }
            has && fresh == null -> { list.removeAt(0); shelfAdapter.setRaw(list); shelfAdapter.notifyItemRemoved(0) }
            !has && fresh != null -> { list.add(0, fresh); shelfAdapter.setRaw(list); shelfAdapter.notifyItemInserted(0) }
        }
    }

    // ---------------- „Alle anzeigen“: Kategorie-Liste ----------------

    private fun openList(k: Kind, catId: String?) {
        listKind = k
        showPage(Page.LIST)
        setListMode(withCats = true, withSearch = true, withSettings = false)
        loadCategories(k, catId)
    }

    /** Liste so verschieben, dass die fokussierte Zeile als zweite Zeile oben steht */
    private fun keepNearTop(rv: RecyclerView, v: View) {
        val first = rv.getChildAt(0) ?: return
        val rowH = (first.height).coerceAtLeast(dp(40))
        // Liste: eine Zeile Abstand nach oben, damit man sieht, was davor kommt; Poster-Raster: Reihe oben
        val target = if (rv.layoutManager is GridLayoutManager) dp(6) else rowH
        val dy = v.top - target
        if (dy != 0) rv.smoothScrollBy(0, dy)
    }

    /** Filme/Serien als Poster-Raster, alles andere als Liste */
    private fun submitItems(rows: List<Row>) {
        val grid = rows.isNotEmpty() && rows.all { it.poster }
        val lm = itemsView.layoutManager
        if (grid) {
            val w = itemsView.width.takeIf { it > 0 } ?: (resources.displayMetrics.widthPixels - dp(96) - dp(250))
            val span = (w / dp(132)).coerceIn(3, 7)
            val cell = w / span
            itemsAdapter.posterHeight = ((cell - dp(16)) * 1.5f).toInt()
            if (lm !is GridLayoutManager) itemsView.layoutManager = GridLayoutManager(this, span)
            else if (lm.spanCount != span) lm.spanCount = span
        } else if (rows.isNotEmpty() && lm is GridLayoutManager) {
            itemsView.layoutManager = LinearLayoutManager(this)
        }
        itemsAdapter.submit(rows)
    }

    private fun loadCategories(k: Kind, preselect: String? = null, focusList: Boolean = true) {
        catsAdapter.submit(listOf(Row("Wird geladen…")))
        submitItems(emptyList())
        header.text = ""
        loadJob?.cancel()
        loadJob = lifecycleScope.launch {
            try {
                cats = repo.categories(k)
                val all = Category("", if (repo.filterDE(k)) "Alle (deutsch)" else "Alle")
                val list = listOf(all) + cats
                cats = list
                catsAdapter.submit(list.map { Row(clean(it.name), bold = it.id.isEmpty(), payload = it) })
                val idx = list.indexOfFirst { it.id == preselect }.takeIf { it >= 0 } ?: (if (list.size > 1) 1 else 0)
                onCategory(idx, catsAdapter.rows[idx], moveFocus = preselect != null)
                if (preselect == null) { if (focusList) catsView.post { catsView.getChildAt(0)?.requestFocus() } }
                else (catsView.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(idx, dp(40))
            } catch (e: Exception) {
                catsAdapter.submit(listOf(Row("Fehler: ${e.message}")))
            }
        }
    }

    private fun onCategory(pos: Int, row: Row, moveFocus: Boolean = false) {
        if (section == Section.EPG) {
            epgTime = (row.payload as? String) ?: "now"
            catsAdapter.selected = pos; catsAdapter.notifyDataSetChanged()
            renderEpg()
            if (moveFocus) focusFirstItem()
            return
        }
        val c = row.payload as? Category ?: return
        val k = listKind ?: kindOf(section)
        catsAdapter.selected = pos; catsAdapter.notifyDataSetChanged()
        header.text = "${clean(c.name)} – wird geladen…"
        submitItems(emptyList())
        loadJob?.cancel()
        loadJob = lifecycleScope.launch {
            try {
                val items = repo.items(k, c.id)
                showItems(k, items, clean(c.name))
                if (moveFocus) focusFirstItem()
            } catch (e: Exception) {
                header.text = "Fehler: ${e.message}"
            }
        }
    }

    /** Fokus auf den ersten Eintrag in der rechten Liste setzen */
    private fun focusFirstItem() {
        itemsView.post { itemsView.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() ?: itemsView.getChildAt(0)?.requestFocus() }
    }

    private fun showItems(k: Kind, items: List<Item>, title: String) {
        currentItems = items
        header.text = "$title · ${items.size} Einträge"
        itemsAdapter.selected = -1
        submitItems(items.map { rowFor(k, it) })
        itemsView.scrollToPosition(0)
        markPlaying(focus = false)
    }

    private fun rowFor(k: Kind, it: Item): Row {
        if (k == Kind.LIVE) {
            val cur = repo.epgFor(it)?.current()
            return if (cur != null) Row(clean(it.name), "${hm.format(Date(cur.s * 1000))}  ${cur.t}", icon = it.icon, progress = pct(cur), payload = it)
            else Row(clean(it.name), icon = it.icon, payload = it)
        }
        val sub = listOf(it.year, if (it.rating.isEmpty() || it.rating == "0") "" else "★ ${it.rating}").filter { s -> s.isNotEmpty() }.joinToString(" · ")
        return Row(clean(it.name), sub, icon = it.icon, poster = true, payload = it)
    }

    private fun pct(p: Prog): Int {
        val now = System.currentTimeMillis() / 1000
        return (((now - p.s).toDouble() / (p.e - p.s).coerceAtLeast(1)) * 1000).toInt().coerceIn(0, 1000)
    }

    private fun refreshNow() {
        if (page == Page.LIST && listBox.visibility == View.VISIBLE) {
            val k = listKind ?: kindOf(section)
            if (section == Section.EPG) renderEpg()
            else if (k == Kind.LIVE && itemsAdapter.rows.isNotEmpty() && itemsAdapter.rows[0].payload is Item) submitItems(currentItems.map { rowFor(Kind.LIVE, it) })
        }
        if (infoOverlay.visibility == View.VISIBLE) fillInfo()
    }

    private fun onItem(pos: Int, row: Row) {
        when (val p = row.payload) {
            is Item -> when (listKind ?: kindOf(section)) {
                Kind.LIVE -> pickLive(p, clean(p.name), currentItems)
                Kind.MOVIE -> { returnFocus = currentFocus; openDetail(Kind.MOVIE, p) }
                Kind.SERIES -> { returnFocus = currentFocus; openDetail(Kind.SERIES, p) }
            }
            is EpgChannel -> {
                val it = Item(p.streamId, p.name, p.icon, "", "ts", p.num, "", "", "")
                val list = (repo.epg?.channels ?: emptyList()).map { c -> Item(c.streamId, c.name, c.icon, "", "ts", c.num, "", "", "") }
                pickLive(it, clean(p.name), list)
            }
        }
    }

    // ---------------- Suche ----------------

    private fun hideKeyboard(v: View) {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(v.windowToken, 0)
    }

    /** Suchen-/Fertig-/Weiter-Taste der Tastatur, Enter oder Play: sofort suchen */
    private fun onSubmit(field: EditText, submit: () -> Unit) {
        field.setOnEditorActionListener { _, _, ev ->
            if (ev == null || ev.action == KeyEvent.ACTION_DOWN) submit()
            true
        }
    }

    private fun setupSearch() {
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { scheduleSearch(s?.toString() ?: "", now = false) }
        })
        onSubmit(search) { submitListSearch() }
    }

    private fun submitListSearch() {
        hideKeyboard(search)
        scheduleSearch(search.text.toString(), now = true)
    }

    /** Suche innerhalb der Kategorie-Liste */
    private fun scheduleSearch(q: String, now: Boolean) {
        if (page != Page.LIST || listKind == null) return
        listSearchJob?.cancel()
        if (q.isBlank()) return
        val k = listKind ?: return
        listSearchJob = lifecycleScope.launch {
            if (!now) delay(400)
            header.text = "Suche…"
            try {
                val r = repo.search(k, q)
                currentItems = r
                header.text = if (r.isEmpty()) "Nichts gefunden" + (if (repo.filterDE(k)) " (nur deutsche Titel)" else "") else "Suche „${q.trim()}“ · ${r.size} Treffer"
                submitItems(r.map { rowFor(k, it) })
                if (now && r.isNotEmpty()) itemsView.post { itemsView.getChildAt(0)?.requestFocus() }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                header.text = "Fehler: ${e.message}"
            }
        }
    }

    /** Such-Reiter: Filme, Serien und Sender auf einmal */
    private fun setupGlobalSearch() {
        searchMsg.text = "Titel eingeben und Play oder Suchen drücken."
        globalSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { runGlobalSearch(s?.toString() ?: "", now = false) }
        })
        onSubmit(globalSearch) { submitGlobalSearch() }
        // Cursor ans Ende, damit Löschen/Weitertippen beim erneuten Öffnen sofort passt
        globalSearch.setOnFocusChangeListener { _, has -> if (has) globalSearch.post { globalSearch.setSelection(globalSearch.text.length) } }
    }

    private fun submitGlobalSearch() {
        hideKeyboard(globalSearch)
        runGlobalSearch(globalSearch.text.toString(), now = true)
    }

    private fun focusFirstSearchResult() {
        searchShelves.post {
            val row = searchShelves.findViewHolderForAdapterPosition(0) as? ShelfAdapter.VH
            row?.rv?.getChildAt(0)?.requestFocus()
        }
    }

    /** now = sofort suchen (Play/Suchen gedrückt) und danach zum ersten Treffer springen */
    private fun runGlobalSearch(raw: String, now: Boolean) {
        val q = raw.trim()
        if (!now && q == globalQuery) return
        searchJob?.cancel()
        globalQuery = q
        if (q.isEmpty()) { searchAdapter.submit(emptyList()); searchMsg.text = "Titel eingeben und Play oder Suchen drücken."; return }
        if (repo.account == null) { searchMsg.text = "Noch kein Konto eingerichtet."; return }
        searchJob = lifecycleScope.launch {
            if (!now) delay(450)
            searchMsg.text = "Suche „$q“…"
            val errors = ArrayList<String>()
            // Ergebnisse je Bereich, sobald sie da sind (Serien warten nicht auf Filme)
            val found = linkedMapOf<Kind, List<Item>?>(Kind.MOVIE to null, Kind.SERIES to null, Kind.LIVE to null)
            var focused = false
            fun show(final: Boolean) {
                val shelves = listOf(
                    Shelf("Filme", CardStyle.POSTER, (found[Kind.MOVIE] ?: emptyList()).map { posterCard(Kind.MOVIE, it) }),
                    Shelf("Serien", CardStyle.POSTER, (found[Kind.SERIES] ?: emptyList()).map { posterCard(Kind.SERIES, it) }),
                    Shelf("Sender", CardStyle.CHANNEL, (found[Kind.LIVE] ?: emptyList()).map { channelCard(it) })
                ).filter { it.cards.isNotEmpty() }
                searchAdapter.submit(shelves)
                val status = repo.loadStatus.values.joinToString("  ·  ")
                searchMsg.text = when {
                    !final -> if (status.isNotEmpty()) "Suche „$q“… $status" else "Suche „$q“…"
                    shelves.isNotEmpty() -> ""
                    errors.isNotEmpty() -> "Suche nicht möglich: ${errors.first()}"
                    else -> "Keine Treffer für „$q“." + if (repo.deOnly) " (nur deutsche Filme und Serien)" else ""
                }
                if (now && !focused && shelves.isNotEmpty() && (globalSearch.hasFocus() || currentFocus == null)) { focused = true; focusFirstSearchResult() }
            }
            val ticker = launch { while (true) { delay(700); show(false) } }
            val jobs = found.keys.toList().map { k ->
                async {
                    val r = try {
                        repo.search(k, q).take(40)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        errors.add(e.message ?: "Fehler"); emptyList()
                    }
                    found[k] = r
                    show(false)
                }
            }
            jobs.forEach { it.await() }
            ticker.cancel()
            show(true)
        }
    }

    // ---------------- Detailseite ----------------

    data class Episode(val id: String, val num: String, val title: String, val ext: String, val season: String, val plot: String,
                       val image: String = "", val duration: String = "")

    private fun epTitle(raw: String): String {
        val t = raw.replace(Regex("^.*?S\\d+\\s*E\\d+\\s*[-–:|]?\\s*", RegexOption.IGNORE_CASE), "").trim()
        return t.ifEmpty { raw }
    }

    private fun openDetail(k: Kind, it: Item) {
        if (returnFocus == null) returnFocus = currentFocus
        detail = Pick(k, it)
        detailMetaData = null
        seasons = LinkedHashMap()
        detailPage.alpha = 0f
        detailPage.visibility = View.VISIBLE
        detailPage.animate().alpha(1f).setDuration(220).start()
        detailKicker.text = if (k == Kind.MOVIE) "Film" else "Serie"
        detailTitle.text = clean(it.name)
        detailMeta.text = it.year
        detailPlot.text = ""
        detailBackdrop.setImageDrawable(null)
        detailBackdrop.load(it.icon) { crossfade(250) }
        detailSeries.visibility = View.GONE
        seasonTabs.removeAllViews()
        episodesAdapter.submit(emptyList())
        buildDetailButtons()
        detailButtons.post { detailButtons.getChildAt(0)?.requestFocus() }
        lifecycleScope.launch {
            val o = try { repo.info(k, it.id) } catch (e: Exception) { JSONObject() }
            if (detail?.item !== it) return@launch
            val m = Meta.from(o)
            detailMetaData = m
            detailMeta.text = m.line().ifEmpty { it.year }
            detailPlot.text = m.plot.ifEmpty { "Keine Beschreibung verfügbar." }
            if (m.backdrop.isNotEmpty() && m.backdrop != it.icon) detailBackdrop.load(m.backdrop) { crossfade(400) }
            if (k == Kind.SERIES) {
                seasons = parseEpisodes(o)
                showSeasons()
                buildDetailButtons()
                if (detailPage.findFocus() == null) detailButtons.getChildAt(0)?.requestFocus()
            }
        }
    }

    private fun parseEpisodes(d: JSONObject): LinkedHashMap<String, List<Episode>> {
        val out = LinkedHashMap<String, List<Episode>>()
        val e = d.opt("episodes")
        if (e is JSONObject) {
            val keys = e.keys().asSequence().toList().sortedBy { it.toIntOrNull() ?: 0 }
            for (season in keys) {
                val arr = e.optJSONArray(season) ?: continue
                val list = ArrayList<Episode>()
                for (i in 0 until arr.length()) {
                    val x = arr.getJSONObject(i)
                    val inf = x.optJSONObject("info") ?: JSONObject()
                    val dur = inf.s("duration").split(":").mapNotNull { s -> s.toIntOrNull() }.let { p ->
                        if (p.size == 3 && (p[0] > 0 || p[1] > 0)) (if (p[0] > 0) "${p[0]} Std. " else "") + "${p[1]} Min." else ""
                    }
                    list.add(Episode(x.s("id"), x.s("episode_num"), epTitle(x.s("title")), x.s("container_extension"), season,
                        inf.s("plot"), inf.s("movie_image"), dur))
                }
                if (list.isNotEmpty()) out[season] = list
            }
        }
        return out
    }

    private fun showSeasons() {
        seasonTabs.removeAllViews()
        if (seasons.isEmpty()) { detailSeries.visibility = View.GONE; return }
        detailSeries.visibility = View.VISIBLE
        val last = detail?.let { store.lastOfSeries(it.item.id) }
        currentSeason = last?.season?.takeIf { seasons.containsKey(it) } ?: seasons.keys.first()
        for (s in seasons.keys) {
            seasonTabs.addView(TextView(this).apply {
                text = "Staffel $s"
                type(Ui.T.TAB)
                setTextColor(ContextCompat.getColorStateList(this@MainActivity, R.color.tab_text))
                setBackgroundResource(R.drawable.tab_bg)
                setPadding(dp(18), dp(8), dp(18), dp(8))
                isFocusable = true; isClickable = true
                isSelected = s == currentSeason
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(4) }
                setOnFocusChangeListener { v, has ->
                    v.focusLift(has, 1.06f)
                    if (has && s != currentSeason) { currentSeason = s; showEpisodes() }
                }
                setOnClickListener { episodesView.post { episodesView.getChildAt(0)?.requestFocus() } }
            })
        }
        showEpisodes()
    }

    private fun showEpisodes() {
        for (i in 0 until seasonTabs.childCount) seasonTabs.getChildAt(i).isSelected = (seasonTabs.getChildAt(i) as TextView).text.toString() == "Staffel $currentSeason"
        val eps = seasons[currentSeason] ?: emptyList()
        val fallback = detailMetaData?.backdrop ?: detail?.item?.icon ?: ""
        episodesAdapter.submit(eps.map { ep ->
            val r = store.get(Kind.SERIES, ep.id)
            Card("${ep.num}. ${ep.title}", ep.duration, ep.image.ifEmpty { fallback }, r?.let { (it.fraction * 1000).toInt() } ?: -1, ep)
        })
        val last = detail?.let { store.lastOfSeries(it.item.id) }
        val idx = eps.indexOfFirst { it.id == last?.id }.coerceAtLeast(0)
        episodesView.scrollToPosition(idx)
    }

    private fun buildDetailButtons() {
        val d = detail ?: return
        val hadFocus = detailButtons.findFocus() != null
        detailButtons.removeAllViews()
        if (d.kind == Kind.MOVIE) {
            val r = store.get(Kind.MOVIE, d.item.id)
            if (r != null) {
                detailButtons.addView(pill("Fortsetzen  ·  noch ${r.remainingMin} Min.", true) { playMovie(d.item, r.pos) })
                detailButtons.addView(pill("Von vorn", false) { playMovie(d.item, 0L) })
            } else {
                detailButtons.addView(pill("Abspielen", true) { playMovie(d.item, 0L) })
            }
        } else if (seasons.isNotEmpty()) {
            val all = seasons.values.flatten()
            val last = store.lastOfSeries(d.item.id)
            val ep = all.firstOrNull { it.id == last?.id }
            if (ep != null && last != null) {
                detailButtons.addView(pill("Weiter: S${ep.season} F${ep.num}", true) { playEpisode(ep, last.pos) })
            } else {
                val first = all.first()
                detailButtons.addView(pill("S${first.season} F${first.num} abspielen", true) { playEpisode(first, 0L) })
            }
        }
        if (hadFocus) detailButtons.getChildAt(0)?.requestFocus()
    }

    private fun closeDetail(restoreFocus: Boolean = true) {
        detail = null
        detailPage.visibility = View.GONE
        if (restoreFocus) {
            val rf = returnFocus
            returnFocus = null
            if (rf != null && rf.isAttachedToWindow && rf.isShown) rf.requestFocus() else focusCurrentTab()
        } else returnFocus = null
    }

    /** nach dem Ansehen: Fortschritt auf der Detailseite und in den Reihen auffrischen */
    private fun refreshAfterPlayback() {
        if (detailPage.visibility == View.VISIBLE) {
            buildDetailButtons()
            if (seasons.isNotEmpty()) {
                val f = episodesView.findFocus()
                val pos = f?.let { episodesView.findContainingItemView(it) }?.let { episodesView.getChildAdapterPosition(it) } ?: -1
                showEpisodes()
                if (pos >= 0) episodesView.post { episodesView.findViewHolderForAdapterPosition(pos)?.itemView?.requestFocus() }
            }
        }
        refreshContinueShelf()
    }

    // ---------------- TV-Programm ----------------

    private fun showEpgSection() {
        val times = listOf("now" to "Jetzt", "0-18:00" to "Heute 18:00", "0-20:15" to "Heute 20:15", "0-22:00" to "Heute 22:00",
            "1-18:00" to "Morgen 18:00", "1-20:15" to "Morgen 20:15", "1-22:00" to "Morgen 22:00")
        catsAdapter.submit(times.map { Row(it.second, payload = it.first) })
        epgTime = "now"
        catsAdapter.selected = 0
        renderEpg()
        if (repo.epg == null) loadEpg(false)
        if (!keepNavFocus) catsView.post { catsView.getChildAt(0)?.requestFocus() }
    }

    private fun epgTarget(): Long {
        if (epgTime == "now") return System.currentTimeMillis() / 1000
        val day = epgTime.substringBefore("-").toInt()
        val (h, m) = epgTime.substringAfter("-").split(":").map { it.toInt() }
        val c = Calendar.getInstance()
        c.add(Calendar.DAY_OF_YEAR, day)
        c.set(Calendar.HOUR_OF_DAY, h); c.set(Calendar.MINUTE, m); c.set(Calendar.SECOND, 0)
        return c.timeInMillis / 1000
    }

    private fun renderEpg() {
        val e = repo.epg
        if (e == null) {
            header.text = if (repo.epgLoading) "TV-Programm für deutsche Sender wird geladen… (beim ersten Mal bis zu einer Minute)" else "Kein TV-Programm geladen."
            submitItems(emptyList())
            return
        }
        val t = epgTarget()
        val now = System.currentTimeMillis() / 1000
        val rows = ArrayList<Row>()
        for (ch in e.channels) {
            val i = ch.progs.indexOfFirst { it.e > t }
            if (i < 0) continue
            val ps = ch.progs.subList(i, minOf(ch.progs.size, i + 3))
            val first = ps[0]
            val live = first.s <= now && first.e > now
            val next = ps.drop(1).joinToString("   ·   ") { "${hm.format(Date(it.s * 1000))} ${it.t}" }
            rows.add(Row(ch.name, "${hm.format(Date(first.s * 1000))}  ${first.t}", next, ch.icon, if (live) pct(first) else -1, payload = ch))
        }
        header.text = "${rows.size} deutsche Sender · Quelle: ${e.source}"
        submitItems(rows)
        markPlaying(focus = false)
    }

    private fun loadEpg(force: Boolean) {
        lifecycleScope.launch {
            if (section == Section.EPG) renderEpg()
            val err = repo.loadEpg(force)
            if (err != null && section == Section.EPG) header.text = "TV-Programm konnte nicht geladen werden: $err"
            else refreshNow()
            if (section == Section.SETTINGS) buildSettings()
        }
    }

    // ---------------- Einstellungen ----------------

    private fun label(t: String, size: Float = 14f, color: String = "#AEAEB2", top: Int = 14) = TextView(this).apply {
        text = t
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        setTextColor(Color.parseColor(color))
        setPadding(dp(4), dp(top), dp(4), dp(4))
    }

    /** Abschnittsüberschrift einer gruppierten Liste (wie in den Apple-Einstellungen) */
    private fun sectionHeader(t: String) = TextView(this).apply {
        text = t
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        typeface = Ui.semibold
        setTextColor(col(R.color.muted))
        setPadding(dp(16), dp(22), dp(16), dp(8))
    }

    /** Erklärtext unter einer Gruppe */
    private fun footnote(t: String) = TextView(this).apply {
        text = t
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setTextColor(col(R.color.muted))
        setPadding(dp(16), dp(6), dp(16), 0)
    }

    /** Einstellungszeile: Titel links, Wert rechts. Ohne onClick = reine Info-Zeile */
    private fun cell(title: String, value: String = "", onLong: (() -> Unit)? = null, onClick: (() -> Unit)? = null): LinearLayout {
        val focusable = onClick != null
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(if (focusable) R.drawable.cell_bg else R.drawable.info_cell_bg)
            setPadding(dp(18), dp(12), dp(18), dp(12))
            minimumHeight = dp(50)
            isFocusable = focusable; isClickable = focusable
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6) }
        }
        row.addView(TextView(this).apply {
            text = title
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setTextColor(ContextCompat.getColorStateList(this@MainActivity, R.color.cell_text))
            isDuplicateParentStateEnabled = true
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        if (value.isNotEmpty()) row.addView(TextView(this).apply {
            text = value
            tag = "value"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setTextColor(ContextCompat.getColorStateList(this@MainActivity, R.color.cell_sub))
            isDuplicateParentStateEnabled = true
            gravity = Gravity.END
            setPadding(dp(16), 0, 0, 0)
        })
        if (onClick != null) {
            row.setOnClickListener { onClick() }
            row.setOnFocusChangeListener { v, has -> v.focusLift(has, 1.02f) }
        }
        if (onLong != null) row.setOnLongClickListener { onLong(); true }
        return row
    }

    private fun field(hint: String, value: String, password: Boolean) = EditText(this).apply {
        setText(value)
        this.hint = hint
        setHintTextColor(ContextCompat.getColorStateList(this@MainActivity, R.color.cell_sub))
        setTextColor(ContextCompat.getColorStateList(this@MainActivity, R.color.cell_text))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        setBackgroundResource(R.drawable.field_bg)
        setPadding(dp(18), dp(12), dp(18), dp(12))
        isSingleLine = true
        inputType = if (password) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                    else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6) }
    }

    /** Kapsel-Taste im Apple-TV-Stil */
    private fun button(t: String, onClick: () -> Unit) = TextView(this).apply {
        text = t
        gravity = Gravity.CENTER
        typeface = Ui.semibold
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        setTextColor(ContextCompat.getColorStateList(this@MainActivity, R.color.cell_text))
        setBackgroundResource(R.drawable.button_bg)
        setPadding(dp(28), dp(12), dp(28), dp(12))
        isFocusable = true; isClickable = true
        setOnClickListener { onClick() }
        setOnFocusChangeListener { v, has -> v.focusLift(has, 1.05f) }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8); gravity = Gravity.CENTER_HORIZONTAL
        }
    }

    private fun buildSettings() {
        val box = settingsBox
        // Fokus nach dem Neuaufbau an derselben Stelle wiederherstellen
        val focusIdx = box.focusedChild?.let { box.indexOfChild(it) } ?: -1
        box.removeAllViews()
        val acc = repo.account
        box.addView(TextView(this).apply {
            text = "Einstellungen"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
            typeface = Ui.semibold
            setTextColor(col(R.color.text))
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(4), 0, dp(4))
        })
        var firstFocus: View? = null

        // ---- Konten ----
        box.addView(sectionHeader("Konten"))
        if (repo.accounts.isEmpty()) {
            box.addView(cell("Noch kein Konto eingerichtet"))
        }
        for ((i, sa) in repo.accounts.withIndex()) {
            val active = i == repo.activeIndex
            val row = cell(sa.label + "  ·  " + sa.account.username, if (active) "✓ Aktiv" else "", onLong = { editAccountDialog(i) }) {
                if (!active) { repo.switchTo(i); store.accountKey = repo.accountKey; stopPlayer(); showBanner("Konto „${sa.label}“ ist jetzt aktiv", false); lifecycleScope.launch { repo.refreshUserInfo(); loadEpg(false); buildSettings() } }
            }
            if (active) row.findViewWithTag<TextView>("value")?.setTextColor(col(R.color.accent))
            if (firstFocus == null) firstFocus = row
            box.addView(row)
        }
        if (repo.accounts.isNotEmpty()) box.addView(footnote("OK = Konto wechseln   ·   OK gedrückt halten = bearbeiten oder löschen"))

        // ---- Konto hinzufügen / Zugangsdaten ----
        box.addView(sectionHeader(if (repo.accounts.isEmpty()) "Anmelden mit Xtream-Codes-Zugangsdaten" else "Konto hinzufügen"))
        val fLabel = field("Name (z. B. Wohnzimmer, Konto 2)", "", false)
        val fServer = field("Server-URL (http://anbieter:8080)", "", false)
        val fUser = field("Benutzername", "", false)
        val fPass = field("Passwort", "", true)
        val msg = label("", 15f, "#FF453A", 2)
        box.addView(fLabel); box.addView(fServer); box.addView(fUser); box.addView(fPass)
        box.addView(button(if (repo.accounts.isEmpty()) "Speichern und verbinden" else "Konto hinzufügen und verbinden") {
            msg.setTextColor(col(R.color.muted)); msg.text = "Verbinde…"
            lifecycleScope.launch {
                try {
                    repo.saveAccount(fLabel.text.toString(), fServer.text.toString(), fUser.text.toString(), fPass.text.toString(), -1)
                    store.accountKey = repo.accountKey
                    msg.setTextColor(col(R.color.ok)); msg.text = "✓ Verbunden"
                    repo.refreshUserInfo(); loadEpg(false)
                    showSection(Section.HOME)
                } catch (e: Exception) {
                    msg.setTextColor(col(R.color.err)); msg.text = e.message ?: "Verbindung fehlgeschlagen. Server-URL und Zugangsdaten prüfen."
                }
            }
        })
        box.addView(msg.apply { gravity = Gravity.CENTER_HORIZONTAL })

        if (acc != null) {
            val u = repo.userInfo
            val exp = u.s("exp_date").toLongOrNull()?.takeIf { it > 0 }?.let { SimpleDateFormat("dd.MM.yyyy", Locale.GERMANY).format(Date(it * 1000)) } ?: "unbegrenzt"
            box.addView(sectionHeader("Aktives Konto"))
            box.addView(cell("Benutzer", u.s("username").ifEmpty { acc.username }))
            box.addView(cell("Status", u.s("status").ifEmpty { "–" }))
            box.addView(cell("Gültig bis", exp))
            box.addView(cell("Verbindungen", "${u.s("active_cons").ifEmpty { "0" }} von ${u.s("max_connections").ifEmpty { "–" }}"))
            box.addView(cell("Server", acc.server))

            box.addView(sectionHeader("Filme & Serien"))
            box.addView(cell("Nur deutsche Fassungen", if (repo.deOnly) "Ein" else "Aus") {
                repo.deOnly = !repo.deOnly
                buildSettings()
            })
            box.addView(footnote("Zeigt und durchsucht nur Titel in deutscher Fassung."))

            box.addView(sectionHeader("TV-Programm"))
            val e = repo.epg
            if (e != null) {
                box.addView(cell("Quelle", e.source))
                box.addView(cell("Stand", SimpleDateFormat("dd.MM. HH:mm", Locale.GERMANY).format(Date(e.updated * 1000))))
                box.addView(cell("Sender", e.channels.size.toString()))
            } else {
                box.addView(cell("Status", if (repo.epgLoading) "Wird geladen…" else "Noch nicht geladen"))
            }
            box.addView(cell("TV-Programm aktualisieren") { loadEpg(true); showBanner("TV-Programm wird geladen…", false) })
        }

        // ---- Update: erst prüfen, dann fragen ----
        box.addView(sectionHeader("Software-Update"))
        val upInfo = footnote("Installierte Version: ${Updater.versionName(this)}")
        box.addView(cell("Nach Update suchen") { checkUpdate(upInfo) })
        box.addView(upInfo)

        box.addView(TextView(this).apply {
            text = android.text.Html.fromHtml("IPTV<font color='#0A84FF'>+</font> für Fire TV<br><small>Gebaut von CoreCX</small>")
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(col(R.color.muted))
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(30), 0, 0)
        })
        Ui.fontify(box)
        val target = firstFocus
        if (focusIdx >= 0) box.post { (box.getChildAt(focusIdx)?.takeIf { it.isFocusable } ?: target ?: navButtons[Section.SETTINGS])?.requestFocus() }
        else if (!keepNavFocus) box.post { (target ?: navButtons[Section.SETTINGS])?.requestFocus() }
    }

    /** Konto bearbeiten oder löschen */
    private fun editAccountDialog(index: Int) {
        val sa = repo.accounts.getOrNull(index) ?: return
        val pad = dp(20)
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, 0) }
        val fLabel = field("Name", sa.label, false)
        val fServer = field("Server-URL", sa.account.server, false)
        val fUser = field("Benutzername", sa.account.username, false)
        val fPass = field("Passwort (leer = unverändert)", "", true)
        wrap.addView(fLabel); wrap.addView(fServer); wrap.addView(fUser); wrap.addView(fPass)
        AlertDialog.Builder(this, R.style.AppleDialog)
            .setTitle("Konto bearbeiten")
            .setView(wrap)
            .setPositiveButton("Speichern") { _, _ ->
                lifecycleScope.launch {
                    try {
                        repo.saveAccount(fLabel.text.toString(), fServer.text.toString(), fUser.text.toString(), fPass.text.toString(), index)
                        store.accountKey = repo.accountKey
                        repo.refreshUserInfo(); buildSettings()
                        showBanner("Gespeichert", false)
                    } catch (e: Exception) {
                        showBanner("Fehler: ${e.message}", true)
                    }
                }
            }
            .setNegativeButton("Löschen") { _, _ ->
                AlertDialog.Builder(this, R.style.AppleDialog)
                    .setMessage("Konto „${sa.label}“ wirklich löschen?")
                    .setPositiveButton("Löschen") { _, _ -> stopPlayer(); repo.removeAccount(index); store.accountKey = repo.accountKey; lifecycleScope.launch { repo.refreshUserInfo(); loadEpg(false) }; buildSettings() }
                    .setNegativeButton("Abbrechen", null).show()
            }
            .setNeutralButton("Abbrechen", null)
            .show().also { Ui.fontify(it.window?.decorView) }
    }

    private var updating = false

    /** Schritt 1: nur prüfen, ob ein Update vorhanden ist; bei Bedarf nachfragen */
    private fun checkUpdate(info: TextView) {
        if (updating) return
        updating = true
        info.text = "Suche nach Update…"
        lifecycleScope.launch {
            try {
                val r = Updater.latest()
                val cur = Updater.currentVersion(this@MainActivity)
                when {
                    r == null -> info.text = "Installierte Version: ${Updater.versionName(this@MainActivity)} by CoreCX – kein Release gefunden."
                    r.number > cur -> {
                        info.text = "Update verfügbar: Build ${r.number} - CoreCX (installiert: ${Updater.versionName(this@MainActivity)})"
                        AlertDialog.Builder(this@MainActivity, R.style.AppleDialog)
                            .setTitle("Update verfügbar")
                            .setMessage("Build ${r.number} - CoreCX ist verfügbar.\nJetzt herunterladen und installieren?")
                            .setPositiveButton("Installieren") { _, _ -> runUpdate(info, r) }
                            .setNegativeButton("Später", null)
                            .show().also { d -> Ui.fontify(d.window?.decorView) }
                    }
                    else -> info.text = "Du hast die neueste Version (Build $cur - CoreCX)."
                }
            } catch (e: Exception) {
                info.text = "Update-Prüfung fehlgeschlagen: ${e.message}"
            } finally {
                updating = false
            }
        }
    }

    /** Schritt 2: nach Zustimmung herunterladen und installieren */
    private fun runUpdate(info: TextView, r: Updater.Release) {
        if (updating) return
        updating = true
        lifecycleScope.launch {
            try {
                info.text = "Lade Build ${r.number} - CoreCX… 0 %"
                val apk = Updater.download(this@MainActivity, r) { p -> info.text = "Lade Build ${r.number} - CoreCX… $p %" }
                info.text = "Build ${r.number} - CoreCX geladen – Installation wird gestartet…"
                if (!Updater.install(this@MainActivity, apk)) {
                    info.text = "Bitte „Apps unbekannter Herkunft“ für IPTV+ erlauben, dann „Nach Update suchen“ erneut."
                }
            } catch (e: Exception) {
                info.text = "Update fehlgeschlagen: ${e.message}"
            } finally {
                updating = false
            }
        }
    }


    // ---------------- Player ----------------

    private fun ensurePlayer(): MediaPlayer {
        mp?.let { return it }
        val lib = LibVLC(this, arrayListOf("--network-caching=1500", "--http-reconnect", "--deinterlace=1", "--deinterlace-mode=yadif", "--audio-time-stretch"))
        val player = MediaPlayer(lib)
        player.attachViews(videoLayout, null, false, false)
        player.setEventListener { ev ->
            runOnUiThread {
                when (ev.type) {
                    MediaPlayer.Event.Playing -> { spinner.visibility = View.GONE; playerMsg.text = ""; errorCount = 0; pauseBadge.visibility = View.GONE }
                    MediaPlayer.Event.Paused -> pauseBadge.visibility = View.VISIBLE
                    MediaPlayer.Event.Buffering -> { spinner.visibility = if (ev.buffering < 100f) View.VISIBLE else View.GONE }
                    MediaPlayer.Event.EncounteredError -> onPlayerError()
                    MediaPlayer.Event.EndReached -> if (playingKind == Kind.LIVE) onPlayerError() else onVodEnd()
                }
            }
        }
        libVLC = lib; mp = player
        return player
    }

    private fun startStream(fromMs: Long = 0L) {
        val it = playing ?: return
        val url = repo.streamUrl(playingKind, it) ?: return
        val player = ensurePlayer()
        val m = Media(libVLC, Uri.parse(url))
        m.setHWDecoderEnabled(true, false)
        m.addOption(":http-user-agent=$USER_AGENT")
        m.addOption(":network-caching=" + if (playingKind == Kind.LIVE) 1500 else 3000)
        if (fromMs > 5000 && playingKind != Kind.LIVE) m.addOption(":start-time=${fromMs / 1000}")
        player.media = m
        m.release()
        player.play()
        spinner.visibility = View.VISIBLE
        pauseBadge.visibility = View.GONE
        playerMsg.text = ""
    }

    private fun onPlayerError() {
        errorCount++
        if (errorCount <= 3) {
            playerMsg.text = "Verbindung wird neu aufgebaut… (Versuch ${errorCount + 1})"
            val target = playing
            val at = if (playingKind == Kind.LIVE) 0L else (mp?.time ?: 0L).coerceAtLeast(startMs)
            ui.postDelayed({ if (playing == target && mode != Mode.NONE) startStream(at) }, 1500L * errorCount)
        } else {
            spinner.visibility = View.GONE
            playerMsg.text = if (playingKind == Kind.LIVE) "Dieser Sender lässt sich gerade nicht abspielen. Vielleicht ist er offline oder das Verbindungslimit ist erreicht.\nOK = erneut versuchen"
                else "Der Titel lässt sich gerade nicht abspielen.\nOK = erneut versuchen"
        }
    }

    /** Film/Folge zu Ende: aus „Weiterschauen“ entfernen und zurück */
    private fun onVodEnd() {
        playing?.let { store.remove(playingKind, it.id) }
        closePlayer(save = false)
    }

    private fun playMovie(it: Item, from: Long) {
        playingSeries = null; playingEp = null
        play(Kind.MOVIE, it, clean(it.name), emptyList(), from)
    }

    private fun playEpisode(ep: Episode, from: Long = -1L) {
        val s = detail?.item
        playingSeries = s; playingEp = ep
        val start = if (from >= 0) from else store.get(Kind.SERIES, ep.id)?.pos ?: 0L
        val item = Item(ep.id, ep.title, s?.icon ?: "", "", ep.ext, 0, "", "", "")
        play(Kind.SERIES, item, ep.title, emptyList(), start)
    }

    private fun resumePlay(r: Resume) {
        if (r.seriesId.isEmpty()) {
            playMovie(Item(r.id, r.title, r.icon, "", r.ext, 0, "", "", ""), r.pos)
        } else {
            playingSeries = Item(r.seriesId, r.seriesName, r.seriesIcon, "", "", 0, "", "", "")
            playingEp = Episode(r.id, r.episode, r.title, r.ext, r.season, "")
            play(Kind.SERIES, Item(r.id, r.title, r.seriesIcon, "", r.ext, 0, "", "", ""), r.title, emptyList(), r.pos)
        }
    }

    /** Sender aus einer Reihe: direkt Vollbild, Zurück beendet */
    private fun playLiveFull(item: Item, list: List<Item>) {
        playingSeries = null; playingEp = null
        play(Kind.LIVE, item, clean(item.name), list.ifEmpty { listOf(item) }, 0L)
    }

    private var playerReturnFocus: View? = null

    private fun play(k: Kind, item: Item, title: String, list: List<Item>, from: Long) {
        if (mode == Mode.NONE) playerReturnFocus = currentFocus
        playing = item; playingKind = k; playingTitle = title; zapList = list; errorCount = 0
        startMs = from
        exitToMini = false
        topPanel.visibility = View.GONE
        setMode(Mode.FULL)
        startStream(from)
        showInfo()
        ui.removeCallbacks(tick); tickCount = 0; ui.postDelayed(tick, 1000)
    }

    /** Erster Klick auf einen Sender in einer Liste: Live-Video im kleinen Fenster starten, Fokus bleibt in der Liste.
     *  Zweiter Klick auf denselben (laufenden) Sender: Vollbild öffnen. */
    private fun pickLive(item: Item, title: String, list: List<Item>) {
        val alreadyPlaying = mode != Mode.NONE && playingKind == Kind.LIVE && playing?.id == item.id
        if (alreadyPlaying) {
            setMode(Mode.FULL)
            showInfo()
            return
        }
        playingSeries = null; playingEp = null
        playing = item; playingKind = Kind.LIVE; playingTitle = title; zapList = list; errorCount = 0; startMs = 0
        exitToMini = true
        setMode(Mode.MINI, announce = false)
        startStream()
        markPlaying(focus = false)
        ui.removeCallbacks(tick); tickCount = 0; ui.postDelayed(tick, 1000)
    }

    /** aktuell laufenden Sender in der Liste markieren (und optional auswählen) */
    private fun markPlaying(focus: Boolean) {
        val id = playing?.id ?: return
        val idx = itemsAdapter.rows.indexOfFirst { r ->
            when (val p = r.payload) { is Item -> p.id == id; is EpgChannel -> p.streamId == id; else -> false }
        }
        val old = itemsAdapter.selected
        itemsAdapter.selected = idx
        if (old >= 0 && old < itemsAdapter.rows.size) itemsAdapter.notifyItemChanged(old)
        if (idx >= 0) itemsAdapter.notifyItemChanged(idx)
        if (idx < 0 || !focus) return
        (itemsView.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(idx, dp(60))
        itemsView.post { itemsView.post { itemsView.findViewHolderForAdapterPosition(idx)?.itemView?.requestFocus() } }
    }

    private fun zap(d: Int) {
        val cur = playing ?: return
        val i = zapList.indexOfFirst { it.id == cur.id }
        if (i < 0) return
        val j = i + d
        if (j < 0 || j >= zapList.size) return
        playing = zapList[j]; playingTitle = clean(zapList[j].name); errorCount = 0
        startStream()
        showInfo()
        markPlaying(focus = false)
    }

    private fun setMode(m: Mode, announce: Boolean = true) {
        val fromFull = mode == Mode.FULL
        mode = m
        val lp = playerBox.layoutParams as FrameLayout.LayoutParams
        when (m) {
            Mode.NONE -> playerBox.visibility = View.GONE
            Mode.FULL -> {
                lp.width = ViewGroup.LayoutParams.MATCH_PARENT; lp.height = ViewGroup.LayoutParams.MATCH_PARENT
                lp.gravity = Gravity.NO_GRAVITY; lp.setMargins(0, 0, 0, 0)
                playerBox.visibility = View.VISIBLE
                playerBox.isFocusable = true
                playerBox.requestFocus()
            }
            Mode.MINI -> {
                // kleines Bild unten rechts – nur auf Listen-Seiten
                lp.width = dp(256); lp.height = dp(144)
                lp.gravity = Gravity.BOTTOM or Gravity.END; lp.setMargins(0, 0, dp(48), dp(27))
                playerBox.visibility = View.VISIBLE
                playerBox.isFocusable = false
                infoOverlay.visibility = View.GONE
                topPanel.visibility = View.GONE
                if (announce && fromFull) showBanner("Bild verkleinert  ·  Sender nochmal wählen = Vollbild  ·  Zurück = beenden", true)
                if (fromFull && itemsView.visibility == View.VISIBLE && itemsAdapter.rows.isNotEmpty()) markPlaying(focus = true)
            }
        }
        playerBox.layoutParams = lp
        playerBox.post { mp?.updateVideoSurfaces() }
    }

    private fun stopPlayer() {
        ui.removeCallbacks(tick)
        mp?.stop()
        playing = null
        pauseBadge.visibility = View.GONE
        topPanel.visibility = View.GONE
        setMode(Mode.NONE)
    }

    /** Player verlassen: Position merken, zurück dorthin, wo man herkam */
    private fun closePlayer(save: Boolean = true) {
        if (save) saveProgress()
        stopPlayer()
        val rf = playerReturnFocus
        playerReturnFocus = null
        refreshAfterPlayback()
        if (rf != null && rf.isAttachedToWindow && rf.isShown) rf.requestFocus()
        else if (detailPage.visibility == View.VISIBLE) detailButtons.getChildAt(0)?.requestFocus()
        else focusCurrentTab()
    }

    private fun leavePlayer() {
        if (playingKind == Kind.LIVE && exitToMini) setMode(Mode.MINI) else closePlayer()
    }

    private fun releasePlayer() {
        mp?.let { it.stop(); it.detachViews(); it.release() }
        libVLC?.release()
        mp = null; libVLC = null
    }

    // ---- Fortschritt (Weiterschauen) ----

    private val tick = object : Runnable {
        override fun run() {
            tickCount++
            if (mode == Mode.FULL && infoOverlay.visibility == View.VISIBLE && playingKind != Kind.LIVE) fillInfo()
            if (playingKind != Kind.LIVE && tickCount % 5 == 0) saveProgress()
            if (mode != Mode.NONE) ui.postDelayed(this, 1000)
        }
    }

    private fun saveProgress() {
        if (playingKind == Kind.LIVE) return
        val p = mp ?: return
        val it = playing ?: return
        val len = p.length
        val pos = p.time
        if (len <= 0 || pos <= 0) return
        val now = System.currentTimeMillis()
        val s = playingSeries
        val ep = playingEp
        val r = if (playingKind == Kind.SERIES && s != null && ep != null)
            Resume(Kind.SERIES, it.id, ep.title, s.icon, it.ext, pos, len, now, s.id, s.name, s.icon, ep.season, ep.num)
        else Resume(Kind.MOVIE, it.id, playingTitle, it.icon, it.ext, pos, len, now)
        store.put(r)
    }

    // ---- Leiste unten ----

    private fun fmt(ms: Long): String {
        val t = (ms / 1000).coerceAtLeast(0)
        val h = t / 3600; val m = (t % 3600) / 60; val s = t % 60
        return if (h > 0) String.format(Locale.GERMANY, "%d:%02d:%02d", h, m, s) else String.format(Locale.GERMANY, "%d:%02d", m, s)
    }

    private fun showInfo() {
        fillInfo()
        infoOverlay.visibility = View.VISIBLE
        ui.removeCallbacks(hideInfo)
        ui.postDelayed(hideInfo, 6000)
    }

    private fun fillInfo() {
        val it = playing ?: return
        val kicker = findViewById<TextView>(R.id.infoKicker)
        val title = findViewById<TextView>(R.id.infoTitle)
        val now = findViewById<TextView>(R.id.infoNow)
        val bar = findViewById<ProgressBar>(R.id.infoProgress)
        val tl = findViewById<TextView>(R.id.infoTimeL)
        val tr = findViewById<TextView>(R.id.infoTimeR)
        val hint = findViewById<TextView>(R.id.infoHint)
        if (playingKind == Kind.LIVE) {
            kicker.text = "● Live"
            kicker.setTextColor(col(R.color.err))
            title.text = playingTitle
            val cur = repo.epgFor(it)?.current()
            if (cur != null) {
                now.text = cur.t
                bar.visibility = View.VISIBLE; bar.progress = pct(cur)
                tl.text = hm.format(Date(cur.s * 1000)); tr.text = hm.format(Date(cur.e * 1000))
            } else {
                now.text = if (repo.epgLoading) "TV-Programm wird geladen…" else "Kein Programm verfügbar"
                bar.visibility = View.INVISIBLE; tl.text = ""; tr.text = ""
            }
            hint.text = "▲▼ Sender wechseln   ·   ≡ Info und Ton   ·   Zurück"
        } else {
            kicker.setTextColor(col(R.color.muted))
            val ep = playingEp
            val s = playingSeries
            if (playingKind == Kind.SERIES && ep != null) {
                kicker.text = "Staffel ${ep.season} · Folge ${ep.num}"
                title.text = clean(s?.name ?: ep.title)
                now.text = ep.title
            } else {
                kicker.text = "Film"
                title.text = playingTitle
                now.text = ""
            }
            now.visibility = if (now.text.isNullOrEmpty()) View.GONE else View.VISIBLE
            val p = mp
            val len = p?.length ?: 0L
            val pos = p?.time ?: 0L
            bar.visibility = View.VISIBLE
            bar.progress = if (len > 0) ((pos.toDouble() / len) * 1000).toInt().coerceIn(0, 1000) else 0
            tl.text = fmt(pos)
            tr.text = if (len > 0) "−" + fmt(len - pos) else ""
            hint.text = "◀ ▶ Spulen   ·   OK Pause   ·   ▲ Info, Ton und Untertitel"
            return
        }
        now.visibility = View.VISIBLE
    }

    private fun togglePause() {
        val p = mp ?: return
        if (p.isPlaying) { p.pause(); pauseBadge.visibility = View.VISIBLE; fillInfo(); infoOverlay.visibility = View.VISIBLE; ui.removeCallbacks(hideInfo) }
        else { p.play(); pauseBadge.visibility = View.GONE; showInfo() }
    }

    private fun seek(dir: Int, repeat: Int) {
        val p = mp ?: return
        val len = p.length
        if (len <= 0) return
        val step = when { repeat < 3 -> 10_000L; repeat < 12 -> 30_000L; else -> 60_000L }
        val t = (p.time + dir * step).coerceIn(0L, (len - 3000).coerceAtLeast(0L))
        p.setTime(t)
        showInfo()
    }

    // ---- Panel oben: Info / Ton / Untertitel ----

    private fun openPanel(tab: Int = 0) {
        infoOverlay.visibility = View.GONE
        topPanel.alpha = 0f
        topPanel.visibility = View.VISIBLE
        topPanel.animate().alpha(1f).setDuration(180).start()
        panelTabs.removeAllViews()
        for ((i, name) in listOf("Info", "Ton", "Untertitel").withIndex()) {
            panelTabs.addView(TextView(this).apply {
                text = name
                type(Ui.T.TAB)
                setTextColor(ContextCompat.getColorStateList(this@MainActivity, R.color.tab_text))
                setBackgroundResource(R.drawable.tab_bg)
                setPadding(dp(20), dp(8), dp(20), dp(8))
                isFocusable = true; isClickable = true
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(2) }
                setOnFocusChangeListener { v, has -> v.focusLift(has, 1.06f); if (has && panelTab != i) showPanelTab(i, -1) }
                setOnClickListener { panelBody.getChildAt(0)?.let { c -> if (c.isFocusable) c.requestFocus() } }
            })
        }
        showPanelTab(tab, -1)
        panelTabs.getChildAt(tab)?.requestFocus()
    }

    private fun closePanel() {
        topPanel.visibility = View.GONE
        playerBox.requestFocus()
    }

    private fun panelText(t: String, style: Ui.T, color: Int, top: Int = 0, lines: Int = 0) = TextView(this).apply {
        text = t
        type(style)
        setTextColor(color)
        if (lines > 0) { maxLines = lines; ellipsize = android.text.TextUtils.TruncateAt.END }
        setLineSpacing(0f, 1.15f)
        setPadding(dp(4), dp(top), dp(4), 0)
    }

    private fun panelOption(label: String, checked: Boolean, onClick: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setBackgroundResource(R.drawable.focus_bg)
        setPadding(dp(16), dp(11), dp(16), dp(11))
        isFocusable = true; isClickable = true
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(4) }
        addView(TextView(this@MainActivity).apply {
            text = if (checked) "✓" else ""
            type(Ui.T.HEADLINE)
            setTextColor(ContextCompat.getColorStateList(this@MainActivity, R.color.cell_text))
            isDuplicateParentStateEnabled = true
            layoutParams = LinearLayout.LayoutParams(dp(28), ViewGroup.LayoutParams.WRAP_CONTENT)
        })
        addView(TextView(this@MainActivity).apply {
            text = label
            type(Ui.T.BODY)
            setTextColor(ContextCompat.getColorStateList(this@MainActivity, R.color.cell_text))
            isDuplicateParentStateEnabled = true
        })
        setOnClickListener { onClick() }
        setOnFocusChangeListener { v, has -> v.focusLift(has, 1.02f) }
    }

    private fun showPanelTab(i: Int, focusIndex: Int) {
        panelTab = i
        for (t in 0 until panelTabs.childCount) panelTabs.getChildAt(t).isSelected = t == i
        panelBody.removeAllViews()
        val p = mp
        when (i) {
            0 -> {
                val it = playing ?: return
                if (playingKind == Kind.LIVE) {
                    val ch = repo.epgFor(it)
                    val cur = ch?.current()
                    if (cur == null) {
                        panelBody.addView(panelText("Kein Programm verfügbar.", Ui.T.BODY, col(R.color.muted)))
                    } else {
                        panelBody.addView(panelText(cur.t, Ui.T.TITLE3, col(R.color.text)))
                        val left = ((cur.e - System.currentTimeMillis() / 1000) / 60).coerceAtLeast(1)
                        panelBody.addView(panelText("${hm.format(Date(cur.s * 1000))} – ${hm.format(Date(cur.e * 1000))}  ·  noch $left Min.", Ui.T.SUBHEAD_MEDIUM, col(R.color.muted), 4))
                        if (cur.d.isNotEmpty()) panelBody.addView(panelText(cur.d, Ui.T.CALLOUT, Color.parseColor("#D1D1D6"), 10, 5))
                        val up = ch.upcoming().filter { x -> x.s > cur.s }.take(4)
                        if (up.isNotEmpty()) {
                            panelBody.addView(panelText("Danach", Ui.T.HEADLINE, col(R.color.text), 14))
                            for (x in up) panelBody.addView(panelText("${hm.format(Date(x.s * 1000))}    ${x.t}", Ui.T.CALLOUT, col(R.color.muted), 4, 1))
                        }
                    }
                } else {
                    val ep = playingEp
                    panelBody.addView(panelText(if (ep != null) "S${ep.season} F${ep.num}  ·  ${ep.title}" else playingTitle, Ui.T.TITLE3, col(R.color.text)))
                    val plotView = panelText(ep?.plot ?: "", Ui.T.CALLOUT, Color.parseColor("#D1D1D6"), 10, 6)
                    panelBody.addView(plotView)
                    if (ep?.plot.isNullOrEmpty()) {
                        val k = if (playingKind == Kind.SERIES) Kind.SERIES else Kind.MOVIE
                        val id = if (k == Kind.SERIES) playingSeries?.id else it.id
                        if (id != null) lifecycleScope.launch {
                            val m = try { Meta.from(repo.info(k, id)) } catch (e: Exception) { return@launch }
                            val line = m.line()
                            plotView.text = listOf(line, m.plot).filter { s -> s.isNotEmpty() }.joinToString("\n\n").ifEmpty { "Keine Beschreibung verfügbar." }
                        }
                    }
                }
            }
            1 -> {
                val tracks = p?.audioTracks
                if (p == null || tracks == null || tracks.isEmpty()) {
                    panelBody.addView(panelText("Für diesen Titel gibt es keine weiteren Tonspuren.", Ui.T.BODY, col(R.color.muted)))
                } else {
                    val cur = p.audioTrack
                    for ((n, t) in tracks.withIndex()) {
                        val label = if (t.id < 0) "Aus" else t.name ?: "Spur ${t.id}"
                        panelBody.addView(panelOption(label, t.id == cur) { p.setAudioTrack(t.id); showPanelTab(1, n) })
                    }
                }
            }
            2 -> {
                val tracks = p?.spuTracks
                if (p == null || tracks == null || tracks.isEmpty()) {
                    panelBody.addView(panelText("Für diesen Titel gibt es keine Untertitel.", Ui.T.BODY, col(R.color.muted)))
                } else {
                    val cur = p.spuTrack
                    for ((n, t) in tracks.withIndex()) {
                        val label = if (t.id < 0) "Aus" else t.name ?: "Untertitel ${t.id}"
                        panelBody.addView(panelOption(label, t.id == cur) { p.setSpuTrack(t.id); showPanelTab(2, n) })
                    }
                }
            }
        }
        if (focusIndex >= 0) panelBody.getChildAt(focusIndex)?.let { v -> panelBody.post { v.requestFocus() } }
    }

    // ---------------- Tasten ----------------

    private val fullKeys = setOf(
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_MENU,
        KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_CHANNEL_DOWN,
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_REWIND
    )

    private fun handleFullKey(code: Int, rep: Int) {
        val live = playingKind == Kind.LIVE
        when (code) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> if (rep == 0) { if (live) zap(-1) else if (code == KeyEvent.KEYCODE_DPAD_UP) openPanel() }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> if (rep == 0) { if (live) zap(1) else showInfo() }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> if (live) { if (rep == 0) showInfo() } else seek(-1, rep)
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> if (live) { if (rep == 0) showInfo() } else seek(1, rep)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> if (rep == 0) {
                if (playerMsg.text.endsWith("erneut versuchen")) { errorCount = 0; startStream(if (live) 0L else (mp?.time ?: startMs)) }
                else if (live) { if (infoOverlay.visibility == View.VISIBLE) infoOverlay.visibility = View.GONE else showInfo() }
                else togglePause()
            }
            KeyEvent.KEYCODE_MENU -> if (rep == 0) openPanel()
            KeyEvent.KEYCODE_BACK -> if (rep == 0) leavePlayer()
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> if (rep == 0) togglePause()
        }
    }

    /** Zurück-Taste außerhalb des Players */
    private fun handleBack(): Boolean {
        if (detailPage.visibility == View.VISIBLE) { closeDetail(); return true }
        if (listKind != null && page == Page.LIST && section != Section.LIVE) {
            listKind = null
            showSection(section)
            return true
        }
        if (!navHasFocus()) {
            if (page == Page.SHELVES) shelvesView.smoothScrollBy(0, -shelvesView.computeVerticalScrollOffset())
            focusCurrentTab()
            return true
        }
        if (section != Section.HOME) { showSection(Section.HOME, keepFocus = true); return true }
        return false
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val code = e.keyCode
        val down = e.action == KeyEvent.ACTION_DOWN
        if (mode == Mode.FULL) {
            if (topPanel.visibility == View.VISIBLE) {
                if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_MENU) {
                    if (down && e.repeatCount == 0) closePanel()
                    return true
                }
                return super.dispatchKeyEvent(e)
            }
            if (code in fullKeys) {
                if (down) handleFullKey(code, e.repeatCount)
                return true
            }
        }
        // Play im Suchfeld startet die Suche
        if (code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || code == KeyEvent.KEYCODE_MEDIA_PLAY || code == KeyEvent.KEYCODE_SEARCH) {
            val f = currentFocus
            if (f === globalSearch || (code == KeyEvent.KEYCODE_SEARCH && page == Page.SEARCH)) {
                if (down && e.repeatCount == 0) submitGlobalSearch()
                return true
            }
            if (f === search) {
                if (down && e.repeatCount == 0) submitListSearch()
                return true
            }
        }
        if (mode == Mode.MINI && down) {
            when (code) {
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MENU -> { setMode(Mode.FULL); showInfo(); return true }
                KeyEvent.KEYCODE_BACK -> {
                    if (!search.hasFocus()) { stopPlayer(); return true }
                }
            }
        }
        if (mode == Mode.MINI && e.action == KeyEvent.ACTION_UP &&
            (code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || code == KeyEvent.KEYCODE_MEDIA_PLAY || code == KeyEvent.KEYCODE_MENU)) return true
        if (code == KeyEvent.KEYCODE_BACK && down && e.repeatCount == 0 && handleBack()) return true
        if (down && code == KeyEvent.KEYCODE_DPAD_UP && heroUpFromFirstRow()) return true
        return super.dispatchKeyEvent(e)
    }

}
