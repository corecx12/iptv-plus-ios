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
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Job
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

class MainActivity : AppCompatActivity() {

    private enum class Section { LIVE, MOVIE, SERIES, EPG, SETTINGS }
    private enum class Mode { NONE, FULL, MINI }

    private lateinit var repo: Repo
    private lateinit var nav: LinearLayout
    private lateinit var search: EditText
    private lateinit var catsView: RecyclerView
    private lateinit var itemsView: RecyclerView
    private lateinit var header: TextView
    private lateinit var settings: ScrollView
    private lateinit var settingsBox: LinearLayout
    private lateinit var playerBox: FrameLayout
    private lateinit var videoLayout: VLCVideoLayout
    private lateinit var spinner: ProgressBar
    private lateinit var playerMsg: TextView
    private lateinit var infoOverlay: View
    private lateinit var catsAdapter: RowAdapter
    private lateinit var itemsAdapter: RowAdapter

    private var section = Section.LIVE
    private var cats: List<Category> = emptyList()
    private var currentItems: List<Item> = emptyList()
    private var seriesOpen: Item? = null
    private var epgTime = "now"
    private var loadJob: Job? = null
    private var searchJob: Job? = null
    private val navButtons = HashMap<Any, TextView>()
    private var miniButtons: List<TextView> = emptyList()

    // Player
    private var libVLC: LibVLC? = null
    private var mp: MediaPlayer? = null
    private var mode = Mode.NONE
    private var playing: Item? = null
    private var playingKind = Kind.LIVE
    private var playingTitle = ""
    private var zapList: List<Item> = emptyList()
    private var errorCount = 0
    private val ui = Handler(Looper.getMainLooper())
    private val hideInfo = Runnable { infoOverlay.visibility = View.GONE }
    private val hm = SimpleDateFormat("HH:mm", Locale.GERMANY)

    private fun dp(x: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, x.toFloat(), resources.displayMetrics).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)
        repo = Repo(this)

        nav = findViewById(R.id.nav)
        search = findViewById(R.id.search)
        catsView = findViewById(R.id.cats)
        itemsView = findViewById(R.id.items)
        header = findViewById(R.id.header)
        settings = findViewById(R.id.settings)
        settingsBox = findViewById(R.id.settingsBox)
        playerBox = findViewById(R.id.playerBox)
        videoLayout = findViewById(R.id.videoLayout)
        spinner = findViewById(R.id.spinner)
        playerMsg = findViewById(R.id.playerMsg)
        infoOverlay = findViewById(R.id.infoOverlay)

        findViewById<TextView>(R.id.logo).text = android.text.Html.fromHtml("IPTV<font color='#3D8BFD'>+</font>")

        catsAdapter = RowAdapter(false) { pos, row -> onCategory(pos, row) }
        itemsAdapter = RowAdapter(true) { pos, row -> onItem(pos, row) }
        catsView.layoutManager = LinearLayoutManager(this)
        catsView.adapter = catsAdapter
        itemsView.layoutManager = LinearLayoutManager(this)
        itemsView.adapter = itemsAdapter
        itemsView.itemAnimator = null

        buildNav()
        setupSearch()

        if (repo.account == null) {
            showSection(Section.SETTINGS)
        } else {
            showSection(Section.LIVE)
            lifecycleScope.launch { repo.refreshUserInfo(); loadEpg(false) }
        }
        ui.postDelayed(object : Runnable {
            override fun run() { refreshNow(); ui.postDelayed(this, 60000) }
        }, 60000)
    }

    override fun onDestroy() {
        super.onDestroy()
        releasePlayer()
    }

    override fun onStop() {
        super.onStop()
        // App im Hintergrund: Wiedergabe beenden (Verbindung freigeben)
        if (mode != Mode.NONE) stopPlayer()
    }

    // ---------------- Navigation ----------------

    private fun navButton(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        setTextColor(Color.parseColor("#E8EAEE"))
        setPadding(dp(14), dp(11), dp(14), dp(11))
        isFocusable = true
        isClickable = true
        setBackgroundResource(R.drawable.focus_bg)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(4) }
        setOnClickListener { onClick() }
    }

    private fun buildNav() {
        val full = navButton("▶  Vollbild") { setMode(Mode.FULL) }
        val stop = navButton("■  Stopp") { stopPlayer() }
        miniButtons = listOf(full, stop)
        for (b in miniButtons) { b.setTextColor(Color.parseColor("#3D8BFD")); b.visibility = View.GONE; nav.addView(b) }
        val items = listOf(
            Section.LIVE to "📺  Live TV", Section.MOVIE to "🎬  Filme", Section.SERIES to "🎞  Serien",
            Section.EPG to "📋  TV-Programm", Section.SETTINGS to "⚙  Einstellungen"
        )
        for ((s, label) in items) {
            val b = navButton(label) { showSection(s) }
            navButtons[s] = b
            nav.addView(b)
        }
        val credit = TextView(this).apply {
            text = "Gebaut von CoreCX"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(Color.parseColor("#8B93A1"))
            setPadding(dp(14), dp(20), dp(14), 0)
        }
        nav.addView(credit)
    }

    private fun highlightNav() {
        for ((k, v) in navButtons) v.isSelected = k == section
    }

    private fun showSection(s: Section) {
        section = s
        seriesOpen = null
        highlightNav()
        loadJob?.cancel()
        search.setText("")
        val isSettings = s == Section.SETTINGS
        settings.visibility = if (isSettings) View.VISIBLE else View.GONE
        itemsView.visibility = if (isSettings) View.GONE else View.VISIBLE
        header.visibility = if (isSettings) View.GONE else View.VISIBLE
        findViewById<View>(R.id.colCats).visibility = if (isSettings) View.GONE else View.VISIBLE
        search.visibility = if (s == Section.EPG) View.GONE else View.VISIBLE
        when (s) {
            Section.SETTINGS -> buildSettings()
            Section.EPG -> showEpgSection()
            else -> loadCategories(kindOf(s))
        }
    }

    private fun kindOf(s: Section) = when (s) { Section.MOVIE -> Kind.MOVIE; Section.SERIES -> Kind.SERIES; else -> Kind.LIVE }

    // ---------------- Kategorien & Listen ----------------

    private fun loadCategories(k: Kind) {
        catsAdapter.submit(listOf(Row("Lade…")))
        itemsAdapter.submit(emptyList())
        header.text = ""
        loadJob = lifecycleScope.launch {
            try {
                cats = repo.categories(k)
                val all = Category("", if (repo.filterDE(k)) "Alle (deutsch)" else "Alle")
                val list = listOf(all) + cats
                cats = list
                catsAdapter.submit(list.map { Row(it.name, bold = it.id.isEmpty(), payload = it) })
                if (list.size > 1) onCategory(1, catsAdapter.rows[1]) else onCategory(0, catsAdapter.rows[0])
                catsView.post { catsView.getChildAt(0)?.requestFocus() }
            } catch (e: Exception) {
                catsAdapter.submit(listOf(Row("Fehler: ${e.message}")))
            }
        }
    }

    private fun onCategory(pos: Int, row: Row) {
        if (section == Section.EPG) {
            epgTime = (row.payload as? String) ?: "now"
            catsAdapter.selected = pos; catsAdapter.notifyDataSetChanged()
            renderEpg()
            return
        }
        val c = row.payload as? Category ?: return
        val k = kindOf(section)
        catsAdapter.selected = pos; catsAdapter.notifyDataSetChanged()
        seriesOpen = null
        header.text = "${c.name} – lade…"
        itemsAdapter.submit(emptyList())
        loadJob?.cancel()
        loadJob = lifecycleScope.launch {
            try {
                val items = repo.items(k, c.id)
                showItems(k, items, c.name)
            } catch (e: Exception) {
                header.text = "Fehler: ${e.message}"
            }
        }
    }

    private fun showItems(k: Kind, items: List<Item>, title: String) {
        currentItems = items
        header.text = "$title · ${items.size} Einträge"
        itemsAdapter.submit(items.map { rowFor(k, it) })
        itemsView.scrollToPosition(0)
    }

    private fun rowFor(k: Kind, it: Item): Row {
        if (k == Kind.LIVE) {
            val cur = repo.epgFor(it)?.current()
            return if (cur != null) Row(it.name, "${hm.format(Date(cur.s * 1000))}  ${cur.t}", icon = it.icon, progress = pct(cur), payload = it)
            else Row(it.name, icon = it.icon, payload = it)
        }
        val sub = listOf(it.year, if (it.rating.isEmpty() || it.rating == "0") "" else "★ ${it.rating}").filter { s -> s.isNotEmpty() }.joinToString(" · ")
        return Row(it.name, sub, icon = it.icon, poster = true, payload = it)
    }

    private fun pct(p: Prog): Int {
        val now = System.currentTimeMillis() / 1000
        return (((now - p.s).toDouble() / (p.e - p.s).coerceAtLeast(1)) * 1000).toInt().coerceIn(0, 1000)
    }

    private fun refreshNow() {
        if (section == Section.LIVE && seriesOpen == null && itemsAdapter.rows.isNotEmpty() && itemsAdapter.rows[0].payload is Item) {
            itemsAdapter.submit(currentItems.map { rowFor(Kind.LIVE, it) })
        }
        if (section == Section.EPG) renderEpg()
        if (infoOverlay.visibility == View.VISIBLE) fillInfo()
    }

    private fun onItem(pos: Int, row: Row) {
        when (val p = row.payload) {
            is Item -> when (section) {
                Section.LIVE -> play(Kind.LIVE, p, p.name, currentItems)
                Section.MOVIE -> showMovie(p)
                Section.SERIES -> openSeries(p)
                else -> {}
            }
            is EpgChannel -> {
                val it = Item(p.streamId, p.name, p.icon, "", "ts", p.num, "", "", "")
                val list = (repo.epg?.channels ?: emptyList()).map { c -> Item(c.streamId, c.name, c.icon, "", "ts", c.num, "", "", "") }
                play(Kind.LIVE, it, p.name, list)
            }
            is Episode -> {
                val it = Item(p.id, p.title, "", "", p.ext, 0, "", "", "")
                play(Kind.SERIES, it, "${seriesOpen?.name ?: ""} – S${p.season}E${p.num} ${p.title}", emptyList())
            }
            "back" -> {
                seriesOpen = null
                showItems(Kind.SERIES, currentItems, header.text.toString().substringBefore(" ·"))
            }
        }
    }

    // ---------------- Suche ----------------

    private fun setupSearch() {
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { scheduleSearch(s?.toString() ?: "") }
        })
        search.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                itemsView.post { itemsView.getChildAt(0)?.requestFocus() }
                true
            } else false
        }
    }

    private fun scheduleSearch(q: String) {
        searchJob?.cancel()
        if (section == Section.EPG || section == Section.SETTINGS) return
        if (q.isBlank()) return
        val k = kindOf(section)
        searchJob = lifecycleScope.launch {
            delay(400)
            header.text = "Suche…"
            try {
                val r = repo.search(k, q)
                seriesOpen = null
                currentItems = r
                header.text = if (r.isEmpty()) "Nichts gefunden" + (if (repo.filterDE(k)) " (nur deutsche Titel)" else "") else "Suche „$q“ · ${r.size} Treffer"
                itemsAdapter.submit(r.map { rowFor(k, it) })
            } catch (e: Exception) {
                header.text = "Fehler: ${e.message}"
            }
        }
    }

    // ---------------- Filme & Serien ----------------

    private fun showMovie(it: Item) {
        val dlg = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(it.name)
            .setMessage("Lade Beschreibung…")
            .setPositiveButton("▶ Abspielen") { _, _ -> play(Kind.MOVIE, it, it.name, emptyList()) }
            .setNegativeButton("Zurück", null)
            .show()
        lifecycleScope.launch {
            val text = try {
                val info = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { repo.api!!.info("get_vod_info", "vod_id", it.id) }.optJSONObject("info") ?: JSONObject()
                val meta = listOf(info.s("genre"), info.s("duration"), info.s("releasedate").take(4)).filter { s -> s.isNotEmpty() }.joinToString(" · ")
                val plot = info.s("plot").ifEmpty { info.s("description") }
                listOf(meta, plot).filter { s -> s.isNotEmpty() }.joinToString("\n\n").ifEmpty { "Keine Beschreibung verfügbar." }
            } catch (e: Exception) { "Keine Beschreibung verfügbar." }
            if (dlg.isShowing) dlg.setMessage(text)
        }
    }

    data class Episode(val id: String, val num: String, val title: String, val ext: String, val season: String, val plot: String)

    private fun openSeries(s: Item) {
        seriesOpen = s
        val backTitle = header.text.toString()
        header.text = "${s.name} – lade Episoden…"
        itemsAdapter.submit(emptyList())
        lifecycleScope.launch {
            try {
                val d = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { repo.api!!.info("get_series_info", "series_id", s.id) }
                val eps = ArrayList<Episode>()
                val e = d.opt("episodes")
                if (e is JSONObject) {
                    val keys = e.keys().asSequence().toList().sortedBy { it.toIntOrNull() ?: 0 }
                    for (season in keys) {
                        val arr = e.optJSONArray(season) ?: continue
                        for (i in 0 until arr.length()) {
                            val x = arr.getJSONObject(i)
                            eps.add(Episode(x.s("id"), x.s("episode_num"), x.s("title"), x.s("container_extension"), season, x.optJSONObject("info")?.s("plot") ?: ""))
                        }
                    }
                }
                if (seriesOpen != s) return@launch
                header.text = "${s.name} · ${eps.size} Episoden"
                val rows = ArrayList<Row>()
                rows.add(Row("‹  Zurück zur Liste", payload = "back", bold = true))
                var lastSeason = ""
                for (ep in eps) {
                    val label = if (ep.season != lastSeason) "Staffel ${ep.season}" else ""
                    lastSeason = ep.season
                    rows.add(Row("S${ep.season} E${ep.num}  ${ep.title}", label, ep.plot.take(120), payload = ep))
                }
                itemsAdapter.submit(rows)
                itemsView.post { itemsView.getChildAt(0)?.requestFocus() }
            } catch (ex: Exception) {
                header.text = "Fehler: ${ex.message} ($backTitle)"
            }
        }
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
        catsView.post { catsView.getChildAt(0)?.requestFocus() }
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
            itemsAdapter.submit(emptyList())
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
        itemsAdapter.submit(rows)
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

    private fun label(t: String, size: Float = 14f, color: String = "#8B93A1", top: Int = 14) = TextView(this).apply {
        text = t
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        setTextColor(Color.parseColor(color))
        setPadding(0, dp(top), 0, dp(4))
    }

    private fun field(hint: String, value: String, password: Boolean) = EditText(this).apply {
        setText(value)
        this.hint = hint
        setHintTextColor(Color.parseColor("#8B93A1"))
        setTextColor(Color.parseColor("#E8EAEE"))
        setBackgroundResource(R.drawable.field_bg)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        isSingleLine = true
        inputType = if (password) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                    else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }
    }

    private fun button(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t
        isAllCaps = false
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) }
    }

    private fun buildSettings() {
        val box = settingsBox
        box.removeAllViews()
        val acc = repo.account
        box.addView(label("Einstellungen", 24f, "#E8EAEE", 0))
        if (acc != null) {
            val u = repo.userInfo
            val exp = u.s("exp_date").toLongOrNull()?.takeIf { it > 0 }?.let { SimpleDateFormat("dd.MM.yyyy", Locale.GERMANY).format(Date(it * 1000)) } ?: "unbegrenzt"
            box.addView(label("KONTO"))
            box.addView(label("Benutzer: ${u.s("username").ifEmpty { acc.username }}\nStatus: ${u.s("status").ifEmpty { "–" }}\nGültig bis: $exp\nVerbindungen: ${u.s("active_cons").ifEmpty { "0" }} von ${u.s("max_connections").ifEmpty { "–" }}\nServer: ${acc.server}", 15f, "#E8EAEE", 0))
        }
        box.addView(label(if (acc == null) "ANMELDUNG – Xtream-Codes-Zugangsdaten" else "ANMELDUNG / ZUGANGSDATEN"))
        val fServer = field("Server-URL (http://anbieter:8080)", acc?.server ?: "", false)
        val fUser = field("Benutzername", acc?.username ?: "", false)
        val fPass = field(if (acc == null) "Passwort" else "Passwort (leer = unverändert)", "", true)
        val msg = label("", 14f, "#FF6B6B", 2)
        box.addView(fServer); box.addView(fUser); box.addView(fPass); box.addView(msg)
        box.addView(button("Speichern & verbinden") {
            msg.setTextColor(Color.parseColor("#8B93A1")); msg.text = "Verbinde…"
            lifecycleScope.launch {
                try {
                    repo.login(fServer.text.toString(), fUser.text.toString(), fPass.text.toString())
                    msg.setTextColor(Color.parseColor("#4CC38A")); msg.text = "✓ Verbunden"
                    loadEpg(false)
                    showSection(Section.LIVE)
                } catch (e: Exception) {
                    msg.setTextColor(Color.parseColor("#FF6B6B")); msg.text = e.message ?: "Fehler"
                }
            }
        })
        if (acc != null) {
            box.addView(label("FILME & SERIEN"))
            box.addView(CheckBox(this).apply {
                text = "Nur deutsche Fassungen anzeigen und durchsuchen"
                setTextColor(Color.parseColor("#E8EAEE"))
                isChecked = repo.deOnly
                setOnCheckedChangeListener { _, v -> repo.deOnly = v }
            })
            box.addView(label("TV-PROGRAMM"))
            val e = repo.epg
            box.addView(label(if (e != null) "Quelle: ${e.source}\nStand: ${SimpleDateFormat("dd.MM. HH:mm", Locale.GERMANY).format(Date(e.updated * 1000))}\nSender: ${e.channels.size}"
                else if (repo.epgLoading) "Wird geladen…" else "Noch nicht geladen.", 15f, "#E8EAEE", 0))
            box.addView(button("TV-Programm jetzt aktualisieren") { loadEpg(true); Toast.makeText(this, "TV-Programm wird geladen…", Toast.LENGTH_SHORT).show() })
            box.addView(label("ABMELDEN"))
            box.addView(button("Abmelden (Zugangsdaten löschen)") {
                AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setMessage("Abmelden und Zugangsdaten von diesem Gerät löschen?")
                    .setPositiveButton("Abmelden") { _, _ -> stopPlayer(); repo.logout(); showSection(Section.SETTINGS) }
                    .setNegativeButton("Abbrechen", null).show()
            })
        }
        box.addView(label("IPTV+ für Fire TV\nGebaut von CoreCX", 13f, "#8B93A1", 28))
        (if (acc == null) fServer else box.getChildAt(0)).post { (if (acc == null) fServer else navButtons[Section.SETTINGS])?.requestFocus() }
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
                    MediaPlayer.Event.Playing -> { spinner.visibility = View.GONE; playerMsg.text = ""; errorCount = 0 }
                    MediaPlayer.Event.Buffering -> { spinner.visibility = if (ev.buffering < 100f) View.VISIBLE else View.GONE }
                    MediaPlayer.Event.EncounteredError -> onPlayerError()
                    MediaPlayer.Event.EndReached -> if (playingKind == Kind.LIVE) onPlayerError()
                }
            }
        }
        libVLC = lib; mp = player
        return player
    }

    private fun startStream() {
        val it = playing ?: return
        val url = repo.streamUrl(playingKind, it) ?: return
        val player = ensurePlayer()
        val m = Media(libVLC, Uri.parse(url))
        m.setHWDecoderEnabled(true, false)
        m.addOption(":http-user-agent=$USER_AGENT")
        m.addOption(":network-caching=" + if (playingKind == Kind.LIVE) 1500 else 3000)
        player.media = m
        m.release()
        player.play()
        spinner.visibility = View.VISIBLE
        playerMsg.text = ""
    }

    private fun onPlayerError() {
        errorCount++
        if (errorCount <= 3) {
            playerMsg.text = "Verbindung wird freigegeben… (Versuch ${errorCount + 1})"
            val target = playing
            ui.postDelayed({ if (playing == target && mode != Mode.NONE) startStream() }, 1500L * errorCount)
        } else {
            spinner.visibility = View.GONE
            playerMsg.text = "Sender lässt sich gerade nicht abspielen – evtl. offline oder Verbindungslimit erreicht.\nOK = erneut versuchen"
        }
    }

    private fun play(k: Kind, item: Item, title: String, list: List<Item>) {
        playing = item; playingKind = k; playingTitle = title; zapList = list; errorCount = 0
        setMode(Mode.FULL)
        startStream()
        showInfo()
    }

    private fun zap(d: Int) {
        val cur = playing ?: return
        val i = zapList.indexOfFirst { it.id == cur.id }
        if (i < 0) return
        val j = i + d
        if (j < 0 || j >= zapList.size) return
        playing = zapList[j]; playingTitle = zapList[j].name; errorCount = 0
        startStream()
        showInfo()
    }

    private fun setMode(m: Mode) {
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
                lp.width = dp(400); lp.height = dp(225)
                lp.gravity = Gravity.BOTTOM or Gravity.END; lp.setMargins(0, 0, dp(24), dp(24))
                playerBox.visibility = View.VISIBLE
                playerBox.isFocusable = false
                infoOverlay.visibility = View.GONE
                Toast.makeText(this, "Bild verkleinert – weitersuchen möglich. Play-Taste oder „▶ Vollbild“ = groß, Zurück = beenden", Toast.LENGTH_LONG).show()
                (if (itemsView.visibility == View.VISIBLE) itemsView.getChildAt(0) else null)?.requestFocus() ?: miniButtons[0].requestFocus()
            }
        }
        playerBox.layoutParams = lp
        for (b in miniButtons) b.visibility = if (m == Mode.MINI) View.VISIBLE else View.GONE
        playerBox.post { mp?.updateVideoSurfaces() }
    }

    private fun stopPlayer() {
        mp?.stop()
        playing = null
        setMode(Mode.NONE)
    }

    private fun releasePlayer() {
        mp?.let { it.stop(); it.detachViews(); it.release() }
        libVLC?.release()
        mp = null; libVLC = null
    }

    private fun showInfo() {
        fillInfo()
        infoOverlay.visibility = View.VISIBLE
        ui.removeCallbacks(hideInfo)
        ui.postDelayed(hideInfo, 6000)
    }

    private fun fillInfo() {
        val it = playing ?: return
        findViewById<TextView>(R.id.infoTitle).text = playingTitle
        val now = findViewById<TextView>(R.id.infoNow)
        val bar = findViewById<ProgressBar>(R.id.infoProgress)
        val desc = findViewById<TextView>(R.id.infoDesc)
        val next = findViewById<TextView>(R.id.infoNext)
        val ch = if (playingKind == Kind.LIVE) repo.epgFor(it) else null
        val cur = ch?.current()
        if (cur != null) {
            val left = ((cur.e - System.currentTimeMillis() / 1000) / 60).coerceAtLeast(1)
            now.text = "● JETZT  ${hm.format(Date(cur.s * 1000))} – ${hm.format(Date(cur.e * 1000))}  ·  noch $left Min.\n${cur.t}"
            bar.visibility = View.VISIBLE; bar.progress = pct(cur)
            desc.text = cur.d; desc.visibility = if (cur.d.isEmpty()) View.GONE else View.VISIBLE
            val up = ch.upcoming().filter { p -> p.s > cur.s }.take(4)
            next.text = if (up.isEmpty()) "" else "DANACH\n" + up.joinToString("\n") { p -> "${hm.format(Date(p.s * 1000))}   ${p.t}" }
        } else {
            now.text = if (playingKind == Kind.LIVE) (if (repo.epgLoading) "TV-Programm wird geladen…" else "Kein Programm verfügbar") else ""
            bar.visibility = View.GONE; desc.visibility = View.GONE; next.text = ""
        }
    }

    private val fullKeys = setOf(
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_MENU,
        KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_CHANNEL_DOWN,
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE
    )

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val code = e.keyCode
        if (mode == Mode.FULL && code in fullKeys) {
            if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) {
                when (code) {
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> zap(-1)
                    KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> zap(1)
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                        if (playerMsg.text.startsWith("Sender lässt")) { errorCount = 0; startStream() }
                        else if (infoOverlay.visibility == View.VISIBLE) { infoOverlay.visibility = View.GONE } else { showInfo() }
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> showInfo()
                    KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_MENU -> setMode(Mode.MINI)
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                        mp?.let { if (it.isPlaying) it.pause() else it.play() }
                    }
                }
            }
            return true
        }
        if (mode == Mode.MINI && e.action == KeyEvent.ACTION_DOWN) {
            when (code) {
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MENU -> { setMode(Mode.FULL); return true }
                KeyEvent.KEYCODE_BACK -> {
                    if (!search.hasFocus()) { stopPlayer(); return true }
                }
            }
        }
        if (mode == Mode.MINI && e.action == KeyEvent.ACTION_UP &&
            (code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || code == KeyEvent.KEYCODE_MEDIA_PLAY || code == KeyEvent.KEYCODE_MENU)) return true
        if (mode == Mode.NONE && e.action == KeyEvent.ACTION_DOWN && code == KeyEvent.KEYCODE_BACK && seriesOpen != null) {
            onItem(0, Row("", payload = "back")); return true
        }
        return super.dispatchKeyEvent(e)
    }
}
