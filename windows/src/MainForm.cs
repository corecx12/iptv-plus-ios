using System.Text.Json;
using LibVLCSharp.Shared;
using LibVLCSharp.WinForms;

namespace IPTVPlus;

public class MainForm : Form
{
    // Farben
    static readonly Color Bg = ColorFromHex("#0E1013");
    static readonly Color Panel = ColorFromHex("#16191E");
    static readonly Color Panel2 = ColorFromHex("#1D2128");
    static readonly Color Accent = ColorFromHex("#3D8BFD");
    static readonly Color TextC = ColorFromHex("#E8EAEE");
    static readonly Color Muted = ColorFromHex("#8B93A1");
    static readonly Color Line = ColorFromHex("#262B33");

    enum Section { Live, Movie, Series, Epg, Settings }
    enum PMode { None, Mini, Full }

    readonly Repo _repo = new();

    // Layout
    Panel _nav, _catsCol, _mainCol, _settingsPanel;
    FlowLayoutPanel _navFlow;
    TextBox _search;
    ListBox _cats, _items;
    Label _header;

    // Player
    Panel _playerPanel;
    VideoView _video;
    LibVLC _libVLC;
    MediaPlayer _mp;
    Label _playerMsg;
    Button _miniClose;
    Panel _fsBar;
    Button _fsBack, _fsPrev, _fsNext, _fsStop;
    PMode _mode = PMode.None;
    Section _section = Section.Live;

    // Zustand
    List<Category> _catList = new();
    List<Item> _currentItems = new();
    Item _seriesOpen;
    string _epgTime = "now";
    Item _playing;
    Kind _playingKind = Kind.Live;
    string _playingTitle = "";
    List<Item> _zap = new();
    int _errorCount;
    System.Windows.Forms.Timer _clock;
    CancellationTokenSource _loadCts;

    public MainForm()
    {
        Text = "IPTV+";
        BackColor = Bg;
        ForeColor = TextC;
        Width = 1280; Height = 760;
        StartPosition = FormStartPosition.CenterScreen;
        KeyPreview = true;
        try { Icon = Icon.ExtractAssociatedIcon(Environment.ProcessPath); } catch { }

        BuildLayout();
        InitPlayer();

        _clock = new System.Windows.Forms.Timer { Interval = 60000 };
        _clock.Tick += (_, _) => RefreshNow();
        _clock.Start();

        Load += async (_, _) =>
        {
            if (_repo.Account == null) ShowSection(Section.Settings);
            else { ShowSection(Section.Live); await _repo.RefreshUserInfoAsync(); _ = LoadEpg(false); }
        };
        FormClosing += (_, _) => StopPlayer();
    }

    static Color ColorFromHex(string h) => ColorTranslator.FromHtml(h);
    long Now => DateTimeOffset.UtcNow.ToUnixTimeSeconds();
    static string Hm(long t) => DateTimeOffset.FromUnixTimeSeconds(t).LocalDateTime.ToString("HH:mm");

    // ---------------- Layout ----------------

    void BuildLayout()
    {
        _nav = new Panel { Dock = DockStyle.Left, Width = 190, BackColor = Panel };
        _navFlow = new FlowLayoutPanel { Dock = DockStyle.Fill, FlowDirection = FlowDirection.TopDown, WrapContents = false, Padding = new Padding(10) };
        _nav.Controls.Add(_navFlow);

        var logo = new Label { Text = "IPTV+", Font = new Font("Segoe UI", 18, FontStyle.Bold), ForeColor = TextC, AutoSize = true, Margin = new Padding(4, 4, 4, 14) };
        _navFlow.Controls.Add(logo);
        foreach (var (s, t) in new[] { (Section.Live, "Live TV"), (Section.Movie, "Filme"), (Section.Series, "Serien"), (Section.Epg, "TV-Programm"), (Section.Settings, "Einstellungen") })
        {
            var b = NavButton(t);
            b.Click += (_, _) => ShowSection(s);
            _navFlow.Controls.Add(b);
        }
        var credit = new Label { Text = "by CoreCX", ForeColor = Muted, AutoSize = true, Margin = new Padding(4, 24, 4, 4), Font = new Font("Segoe UI", 8) };
        _navFlow.Controls.Add(credit);

        _catsCol = new Panel { Dock = DockStyle.Left, Width = 260, BackColor = Bg, Padding = new Padding(10) };
        _search = new TextBox { Dock = DockStyle.Top, BackColor = Panel2, ForeColor = TextC, BorderStyle = BorderStyle.FixedSingle, Height = 28 };
        _search.TextChanged += OnSearchChanged;
        _cats = MakeList(false);
        _cats.Click += OnCategoryClick;
        _cats.SelectedIndexChanged += (_, _) => { };
        var catsHost = new Panel { Dock = DockStyle.Fill };
        catsHost.Controls.Add(_cats);
        var searchHost = new Panel { Dock = DockStyle.Top, Height = 34, Padding = new Padding(0, 0, 0, 6) };
        searchHost.Controls.Add(_search);
        _catsCol.Controls.Add(catsHost);
        _catsCol.Controls.Add(searchHost);

        _mainCol = new Panel { Dock = DockStyle.Fill, BackColor = Bg, Padding = new Padding(10) };
        _header = new Label { Dock = DockStyle.Top, Height = 26, ForeColor = Muted, Font = new Font("Segoe UI", 10) };
        _items = MakeList(true);
        _items.Click += OnItemClick;
        _items.MouseDoubleClick += (_, _) => OnItemClick(_items, EventArgs.Empty);
        _mainCol.Controls.Add(_items);
        _mainCol.Controls.Add(_header);

        _settingsPanel = new Panel { Dock = DockStyle.Fill, BackColor = Bg, AutoScroll = true, Visible = false };
        _mainCol.Controls.Add(_settingsPanel);

        // Reihenfolge: Fill zuerst hinzufügen, dann Left/weitere (Dock-Stacking)
        Controls.Add(_mainCol);
        Controls.Add(_catsCol);
        Controls.Add(_nav);
    }

    Button NavButton(string text)
    {
        var b = new Button
        {
            Text = text, FlatStyle = FlatStyle.Flat, ForeColor = TextC, BackColor = Panel,
            TextAlign = ContentAlignment.MiddleLeft, Width = 165, Height = 40, Margin = new Padding(0, 0, 0, 4),
            Font = new Font("Segoe UI", 11)
        };
        b.FlatAppearance.BorderSize = 0;
        b.FlatAppearance.MouseOverBackColor = Panel2;
        return b;
    }

    ListBox MakeList(bool items)
    {
        var lb = new ListBox
        {
            Dock = DockStyle.Fill, BackColor = Bg, ForeColor = TextC, BorderStyle = BorderStyle.None,
            DrawMode = DrawMode.OwnerDrawFixed, ItemHeight = items ? 48 : 30, IntegralHeight = false
        };
        lb.DrawItem += items ? DrawItemRow : DrawCatRow;
        return lb;
    }

    void DrawCatRow(object sender, DrawItemEventArgs e)
    {
        if (e.Index < 0) return;
        var lb = (ListBox)sender;
        var row = (Row)lb.Items[e.Index];
        bool sel = (e.State & DrawItemState.Selected) != 0;
        using var bg = new SolidBrush(sel ? Panel2 : Bg);
        e.Graphics.FillRectangle(bg, e.Bounds);
        if (sel) { using var bar = new SolidBrush(Accent); e.Graphics.FillRectangle(bar, new Rectangle(e.Bounds.X, e.Bounds.Y, 3, e.Bounds.Height)); }
        using var f = new Font("Segoe UI", 10, row.Bold ? FontStyle.Bold : FontStyle.Regular);
        using var fg = new SolidBrush(sel ? TextC : Muted);
        var r = new Rectangle(e.Bounds.X + 12, e.Bounds.Y, e.Bounds.Width - 14, e.Bounds.Height);
        TextRenderer.DrawText(e.Graphics, row.Title, f, r, sel ? TextC : Muted, TextFormatFlags.VerticalCenter | TextFormatFlags.EndEllipsis);
    }

    void DrawItemRow(object sender, DrawItemEventArgs e)
    {
        if (e.Index < 0) return;
        var lb = (ListBox)sender;
        var row = (Row)lb.Items[e.Index];
        bool sel = (e.State & DrawItemState.Selected) != 0;
        bool playing = row.Payload is Item it && _playing != null && it.Id == _playing.Id && _mode != PMode.None;
        using var bg = new SolidBrush(playing ? ColorFromHex("#1F2A3A") : (sel ? Panel2 : Bg));
        e.Graphics.FillRectangle(bg, e.Bounds);
        if (playing || sel) { using var bar = new SolidBrush(Accent); e.Graphics.FillRectangle(bar, new Rectangle(e.Bounds.X, e.Bounds.Y, 3, e.Bounds.Height)); }
        int x = e.Bounds.X + 12;
        var nameRect = new Rectangle(x, e.Bounds.Y + 5, e.Bounds.Width - x - 6, 20);
        using var fn = new Font("Segoe UI", 10.5f, FontStyle.Regular);
        TextRenderer.DrawText(e.Graphics, row.Title, fn, nameRect, TextC, TextFormatFlags.EndEllipsis);
        if (!string.IsNullOrEmpty(row.Sub))
        {
            var subRect = new Rectangle(x, e.Bounds.Y + 26, e.Bounds.Width - x - 6, 18);
            using var fs = new Font("Segoe UI", 8.5f);
            TextRenderer.DrawText(e.Graphics, row.Sub, fs, subRect, Muted, TextFormatFlags.EndEllipsis);
        }
    }

    // ---------------- Player ----------------

    void InitPlayer()
    {
        _playerPanel = new Panel { BackColor = Color.Black, Visible = false };
        Controls.Add(_playerPanel);
        _playerPanel.BringToFront();
        try
        {
            Core.Initialize();
            _libVLC = new LibVLC("--network-caching=1500", "--http-reconnect", "--deinterlace=1", "--deinterlace-mode=yadif");
            _mp = new MediaPlayer(_libVLC);
            _video = new VideoView { Dock = DockStyle.Fill, MediaPlayer = _mp, BackColor = Color.Black };
            _playerPanel.Controls.Add(_video);
            _mp.EncounteredError += (_, _) => BeginInvoke(OnPlayerError);
            _mp.Playing += (_, _) => BeginInvoke(() => { if (_playerMsg != null) _playerMsg.Text = ""; _errorCount = 0; });
            _mp.EndReached += (_, _) => BeginInvoke(() => { if (_playingKind == Kind.Live) OnPlayerError(); });
        }
        catch (Exception ex)
        {
            MessageBox.Show("VLC konnte nicht geladen werden:\n" + ex.Message, "IPTV+");
        }

        _playerMsg = new Label { ForeColor = Color.White, BackColor = Color.Transparent, AutoSize = false, TextAlign = ContentAlignment.MiddleCenter, Dock = DockStyle.Bottom, Height = 60 };
        _playerPanel.Controls.Add(_playerMsg);
        _playerMsg.BringToFront();

        _miniClose = new Button { Text = "✕", FlatStyle = FlatStyle.Flat, ForeColor = Color.White, BackColor = Color.FromArgb(60, 0, 0, 0), Width = 26, Height = 26 };
        _miniClose.FlatAppearance.BorderSize = 0;
        _miniClose.Click += (_, _) => StopPlayer();
        _playerPanel.Controls.Add(_miniClose);
        _miniClose.BringToFront();

        // Maus-Bedienleiste im Vollbild (oben angedockt, damit sie das Video nicht überlagert)
        _fsBar = new Panel { Dock = DockStyle.Top, Height = 52, BackColor = ColorFromHex("#16191E"), Visible = false };
        Button FsBtn(string t, string tip)
        {
            var b = new Button { Text = t, FlatStyle = FlatStyle.Flat, ForeColor = Color.White, BackColor = Color.FromArgb(70, 255, 255, 255), Width = 46, Height = 38, Font = new Font("Segoe UI", 12), Margin = new Padding(6, 7, 0, 7) };
            b.FlatAppearance.BorderSize = 0;
            var tt = new ToolTip(); tt.SetToolTip(b, tip);
            return b;
        }
        var fsFlow = new FlowLayoutPanel { Dock = DockStyle.Fill, FlowDirection = FlowDirection.LeftToRight, WrapContents = false, Padding = new Padding(8, 0, 0, 0) };
        _fsBack = FsBtn("←", "Verkleinern");
        _fsBack.Width = 110; _fsBack.Text = "←  Zurück";
        _fsBack.Click += (_, _) => SetMode(PMode.Mini);
        _fsPrev = FsBtn("▲", "Vorheriger Sender");
        _fsPrev.Click += (_, _) => Zap(-1);
        _fsNext = FsBtn("▼", "Nächster Sender");
        _fsNext.Click += (_, _) => Zap(1);
        _fsStop = FsBtn("✕", "Beenden");
        _fsStop.Click += (_, _) => StopPlayer();
        fsFlow.Controls.Add(_fsBack); fsFlow.Controls.Add(_fsPrev); fsFlow.Controls.Add(_fsNext); fsFlow.Controls.Add(_fsStop);
        _fsBar.Controls.Add(fsFlow);
        _playerPanel.Controls.Add(_fsBar);
        _video?.SendToBack();   // Video als Fill hinter die angedockten Leisten

        if (_video != null)
        {
            _video.MouseClick += (_, e2) => { if (e2.Button == MouseButtons.Left && _mode == PMode.Mini) SetMode(PMode.Full); };
            _video.MouseDoubleClick += (_, _) => { if (_mode == PMode.Full) SetMode(PMode.Mini); };
        }
        Resize += (_, _) => LayoutPlayer();
    }

    void LayoutPlayer()
    {
        if (_mode == PMode.Full)
        {
            _playerPanel.Bounds = new Rectangle(0, 0, ClientSize.Width, ClientSize.Height);
            _miniClose.Visible = false;
            _playerMsg.Visible = true;
            bool live = _playingKind == Kind.Live && _zap.Count > 1;
            _fsPrev.Visible = live; _fsNext.Visible = live;
            _fsBar.Visible = true;
        }
        else if (_mode == PMode.Mini)
        {
            int w = 360, h = 203;
            _playerPanel.Bounds = new Rectangle(ClientSize.Width - w - 16, ClientSize.Height - h - 16, w, h);
            _miniClose.Location = new Point(w - 30, 4);
            _miniClose.Visible = true;
            _playerMsg.Visible = false;
            _fsBar.Visible = false;
        }
        _playerPanel.BringToFront();
        _miniClose.BringToFront();
    }

    void SetMode(PMode m)
    {
        _mode = m;
        _playerPanel.Visible = m != PMode.None;
        if (m == PMode.None) { _items.Invalidate(); return; }
        LayoutPlayer();
        _items.Invalidate();
    }

    void StartStream()
    {
        if (_playing == null || _mp == null) return;
        var url = _repo.StreamUrl(_playingKind, _playing);
        if (url == null) return;
        try
        {
            var media = new Media(_libVLC, new Uri(url));
            media.AddOption(":http-user-agent=" + Http.UserAgent);
            media.AddOption(":network-caching=" + (_playingKind == Kind.Live ? 1500 : 3000));
            _mp.Play(media);
            media.Dispose();
            _playerMsg.Text = "Verbinde…";
        }
        catch (Exception ex) { _playerMsg.Text = ex.Message; }
    }

    void OnPlayerError()
    {
        _errorCount++;
        if (_errorCount <= 3)
        {
            _playerMsg.Text = $"Verbindung wird freigegeben… (Versuch {_errorCount + 1})";
            var target = _playing;
            var t = new System.Windows.Forms.Timer { Interval = 1500 * _errorCount };
            t.Tick += (s, _) => { t.Stop(); t.Dispose(); if (_playing == target && _mode != PMode.None) StartStream(); };
            t.Start();
        }
        else { _playerMsg.Text = "Sender lässt sich gerade nicht abspielen – evtl. offline oder Verbindungslimit erreicht."; }
    }

    void PlayLive(Item item, string title, List<Item> list)
    {
        bool already = _mode != PMode.None && _playingKind == Kind.Live && _playing?.Id == item.Id;
        if (already) { SetMode(PMode.Full); return; }
        _playing = item; _playingKind = Kind.Live; _playingTitle = title; _zap = list; _errorCount = 0;
        SetMode(PMode.Mini);
        StartStream();
        _items.Invalidate();
    }

    void PlayFull(Kind k, Item item, string title)
    {
        _playing = item; _playingKind = k; _playingTitle = title; _zap = new(); _errorCount = 0;
        SetMode(PMode.Full);
        StartStream();
    }

    void StopPlayer()
    {
        try { _mp?.Stop(); } catch { }
        _playing = null;
        SetMode(PMode.None);
    }

    // ---------------- Navigation ----------------

    void ShowSection(Section s)
    {
        _section = s;
        _seriesOpen = null;
        _search.Text = "";
        foreach (Control c in _navFlow.Controls) if (c is Button b) b.BackColor = Panel;
        bool settings = s == Section.Settings;
        _settingsPanel.Visible = settings;
        _items.Visible = !settings;
        _header.Visible = !settings;
        _catsCol.Visible = !settings;
        _search.Visible = s != Section.Epg;
        if (settings) { _settingsPanel.BringToFront(); BuildSettings(); }
        else if (s == Section.Epg) ShowEpgSection();
        else LoadCategories(KindOf(s));
    }

    static Kind KindOf(Section s) => s switch { Section.Movie => Kind.Movie, Section.Series => Kind.Series, _ => Kind.Live };

    async void LoadCategories(Kind k)
    {
        _cats.Items.Clear(); _cats.Items.Add(new Row("Lade…"));
        _items.Items.Clear(); _header.Text = "";
        try
        {
            var cats = await _repo.CategoriesAsync(k);
            var all = new Category("", _repo.FilterDE(k) ? "Alle (deutsch)" : "Alle");
            _catList = new List<Category> { all };
            _catList.AddRange(cats);
            _cats.Items.Clear();
            foreach (var c in _catList) _cats.Items.Add(new Row(c.Name, bold: c.Id.Length == 0, payload: c));
            if (_cats.Items.Count > 0) { _cats.SelectedIndex = 0; await OpenCategory(_catList[0], false); }
        }
        catch (Exception e) { _cats.Items.Clear(); _cats.Items.Add(new Row("Fehler: " + e.Message)); }
    }

    async void OnCategoryClick(object sender, EventArgs e)
    {
        int i = _cats.SelectedIndex;
        if (i < 0) return;
        if (_section == Section.Epg) { _epgTime = (string)((Row)_cats.Items[i]).Payload; RenderEpg(); FocusItems(); return; }
        if (i >= _catList.Count) return;
        await OpenCategory(_catList[i], true);
    }

    async Task OpenCategory(Category c, bool moveFocus)
    {
        _seriesOpen = null;
        var k = KindOf(_section);
        _header.Text = $"{c.Name} – lade…";
        _items.Items.Clear();
        _loadCts?.Cancel();
        _loadCts = new CancellationTokenSource();
        try
        {
            var items = await _repo.ItemsAsync(k, c.Id);
            ShowItems(k, items, c.Name);
            if (moveFocus) FocusItems();
        }
        catch (Exception e) { _header.Text = "Fehler: " + e.Message; }
    }

    void FocusItems() { if (_items.Items.Count > 0) { _items.SelectedIndex = 0; _items.Focus(); } }

    void ShowItems(Kind k, List<Item> items, string title)
    {
        _currentItems = items;
        _header.Text = $"{title} · {items.Count} Einträge";
        _items.BeginUpdate();
        _items.Items.Clear();
        foreach (var it in items) _items.Items.Add(RowFor(k, it));
        _items.EndUpdate();
    }

    Row RowFor(Kind k, Item it)
    {
        if (k == Kind.Live)
        {
            var cur = _repo.EpgFor(it)?.Current(Now);
            return cur != null ? new Row(it.Name, $"{Hm(cur.S)}  {cur.T}", payload: it) : new Row(it.Name, payload: it);
        }
        var sub = string.Join(" · ", new[] { it.Year, (it.Rating is "" or "0") ? "" : "★ " + it.Rating }.Where(x => x.Length > 0));
        return new Row(it.Name, sub, payload: it);
    }

    void RefreshNow()
    {
        if (_section == Section.Live && _seriesOpen == null && _items.Items.Count > 0 && ((Row)_items.Items[0]).Payload is Item)
        {
            var sel = _items.SelectedIndex;
            ShowItems(Kind.Live, _currentItems, _header.Text.Split(" ·")[0]);
            if (sel >= 0 && sel < _items.Items.Count) _items.SelectedIndex = sel;
        }
        if (_section == Section.Epg) RenderEpg();
    }

    async void OnItemClick(object sender, EventArgs e)
    {
        int i = _items.SelectedIndex;
        if (i < 0 || i >= _items.Items.Count) return;
        var row = (Row)_items.Items[i];
        switch (row.Payload)
        {
            case Item it when _section == Section.Live: PlayLive(it, it.Name, _currentItems); break;
            case Item it when _section == Section.Movie: ShowMovie(it); break;
            case Item it when _section == Section.Series: await OpenSeries(it); break;
            case EpgChannel ch:
                var fake = new Item { Id = ch.StreamId, Name = ch.Name, Ext = "ts" };
                var list = (_repo.Epg?.Channels ?? new()).Select(c => new Item { Id = c.StreamId, Name = c.Name, Ext = "ts" }).ToList();
                PlayLive(fake, ch.Name, list);
                break;
            case Episode ep:
                PlayFull(Kind.Series, new Item { Id = ep.Id, Name = ep.Title, Ext = ep.Ext }, $"{_seriesOpen?.Name} – S{ep.Season}E{ep.Num} {ep.Title}");
                break;
            case string special when special == "back":
                ShowItems(Kind.Series, _currentItems, _header.Text.Split(" ·")[0]);
                break;
        }
    }

    // ---------------- Suche ----------------

    CancellationTokenSource _searchCts;
    async void OnSearchChanged(object sender, EventArgs e)
    {
        if (_section is Section.Epg or Section.Settings) return;
        _searchCts?.Cancel();
        var q = _search.Text.Trim();
        if (q.Length == 0) { if (_catList.Count > 0 && _cats.SelectedIndex >= 0) await OpenCategory(_catList[Math.Max(0, _cats.SelectedIndex)], false); return; }
        _searchCts = new CancellationTokenSource();
        var token = _searchCts.Token;
        var k = KindOf(_section);
        try
        {
            await Task.Delay(350, token);
            _header.Text = "Suche…";
            var r = await _repo.SearchAsync(k, q);
            if (token.IsCancellationRequested) return;
            _seriesOpen = null; _currentItems = r;
            _header.Text = r.Count == 0 ? "Nichts gefunden" + (_repo.FilterDE(k) ? " (nur deutsche Titel)" : "") : $"Suche „{q}“ · {r.Count} Treffer";
            _items.BeginUpdate(); _items.Items.Clear();
            foreach (var it in r) _items.Items.Add(RowFor(k, it));
            _items.EndUpdate();
        }
        catch (TaskCanceledException) { }
        catch (Exception ex) { _header.Text = "Fehler: " + ex.Message; }
    }

    // ---------------- Filme & Serien ----------------

    async void ShowMovie(Item it)
    {
        string plot = "Lade Beschreibung…";
        var dlg = new DetailDialog(it.Name, plot, () => PlayFull(Kind.Movie, it, it.Name));
        _ = Task.Run(async () =>
        {
            try
            {
                var d = await _repo.Api.InfoAsync("get_vod_info", "vod_id", it.Id);
                string text = "Keine Beschreibung verfügbar.";
                if (d.ValueKind == JsonValueKind.Object && d.TryGetProperty("info", out var info))
                {
                    var meta = string.Join(" · ", new[] { Item.S(info, "genre"), Item.S(info, "duration"), Item.S(info, "releasedate") }.Where(x => x.Length > 0));
                    var pl = Item.S(info, "plot"); if (pl.Length == 0) pl = Item.S(info, "description");
                    text = string.Join("\n\n", new[] { meta, pl }.Where(x => x.Length > 0));
                    if (text.Length == 0) text = "Keine Beschreibung verfügbar.";
                }
                BeginInvoke(() => dlg.SetText(text));
            }
            catch { }
        });
        dlg.ShowDialog(this);
    }

    public record Episode(string Id, string Num, string Title, string Ext, string Season, string Plot);

    async Task OpenSeries(Item s)
    {
        _seriesOpen = s;
        _header.Text = $"{s.Name} – lade Episoden…";
        _items.Items.Clear();
        try
        {
            var d = await _repo.Api.InfoAsync("get_series_info", "series_id", s.Id);
            var eps = new List<Episode>();
            if (d.ValueKind == JsonValueKind.Object && d.TryGetProperty("episodes", out var e) && e.ValueKind == JsonValueKind.Object)
            {
                foreach (var season in e.EnumerateObject().OrderBy(p => int.TryParse(p.Name, out var n) ? n : 0))
                {
                    if (season.Value.ValueKind != JsonValueKind.Array) continue;
                    foreach (var x in season.Value.EnumerateArray())
                    {
                        string plot = "";
                        if (x.TryGetProperty("info", out var inf) && inf.ValueKind == JsonValueKind.Object) plot = Item.S(inf, "plot");
                        eps.Add(new Episode(Item.S(x, "id"), Item.S(x, "episode_num"), Item.S(x, "title"), Item.S(x, "container_extension"), season.Name, plot));
                    }
                }
            }
            if (_seriesOpen != s) return;
            _header.Text = $"{s.Name} · {eps.Count} Episoden";
            _items.BeginUpdate(); _items.Items.Clear();
            _items.Items.Add(new Row("‹  Zurück zur Liste", bold: true, payload: "back"));
            string lastSeason = "";
            foreach (var ep in eps)
            {
                var label = ep.Season != lastSeason ? $"Staffel {ep.Season}" : "";
                lastSeason = ep.Season;
                _items.Items.Add(new Row($"S{ep.Season} E{ep.Num}  {ep.Title}", label, payload: ep));
            }
            _items.EndUpdate();
            FocusItems();
        }
        catch (Exception ex) { _header.Text = "Fehler: " + ex.Message; }
    }

    // ---------------- TV-Programm ----------------

    void ShowEpgSection()
    {
        _cats.Items.Clear();
        var times = new (string, string)[] { ("now", "Jetzt"), ("0-18:00", "Heute 18:00"), ("0-20:15", "Heute 20:15"), ("0-22:00", "Heute 22:00"), ("1-18:00", "Morgen 18:00"), ("1-20:15", "Morgen 20:15"), ("1-22:00", "Morgen 22:00") };
        foreach (var (v, n) in times) _cats.Items.Add(new Row(n, payload: v));
        _epgTime = "now"; _cats.SelectedIndex = 0;
        RenderEpg();
        if (_repo.Epg == null) _ = LoadEpg(false);
    }

    long EpgTarget()
    {
        if (_epgTime == "now") return Now;
        int day = int.Parse(_epgTime.Split('-')[0]);
        var hm = _epgTime.Split('-')[1].Split(':');
        var dt = DateTime.Now.Date.AddDays(day).AddHours(int.Parse(hm[0])).AddMinutes(int.Parse(hm[1]));
        return ((DateTimeOffset)dt).ToUnixTimeSeconds();
    }

    void RenderEpg()
    {
        var ep = _repo.Epg;
        if (ep == null) { _header.Text = _repo.EpgLoading ? "TV-Programm wird geladen… (beim ersten Mal bis zu einer Minute)" : "Kein TV-Programm geladen."; _items.Items.Clear(); return; }
        long t = EpgTarget(), now = Now;
        _items.BeginUpdate(); _items.Items.Clear();
        int count = 0;
        foreach (var ch in ep.Channels)
        {
            int i = ch.Progs.FindIndex(p => p.E > t);
            if (i < 0) continue;
            var ps = ch.Progs.Skip(i).Take(3).ToList();
            var first = ps[0];
            var next = string.Join("   ·   ", ps.Skip(1).Select(p => $"{Hm(p.S)} {p.T}"));
            var sub = $"{Hm(first.S)}  {first.T}" + (next.Length > 0 ? "\n" + next : "");
            _items.Items.Add(new Row(ch.Name, $"{Hm(first.S)}  {first.T}", payload: ch));
            count++;
        }
        _items.EndUpdate();
        _header.Text = $"{count} deutsche Sender · Quelle: {ep.Source}";
    }

    async Task LoadEpg(bool force)
    {
        if (_section == Section.Epg) RenderEpg();
        var err = await _repo.LoadEpgAsync(force);
        if (err != null && _section == Section.Epg) _header.Text = "TV-Programm konnte nicht geladen werden: " + err;
        else RefreshNow();
        if (_section == Section.Settings) BuildSettings();
    }

    // ---------------- Einstellungen ----------------

    void BuildSettings()
    {
        _settingsPanel.Controls.Clear();
        var flow = new FlowLayoutPanel { FlowDirection = FlowDirection.TopDown, WrapContents = false, AutoScroll = true, Dock = DockStyle.Fill, Padding = new Padding(16), Width = 640 };
        _settingsPanel.Controls.Add(flow);

        flow.Controls.Add(H("Einstellungen", 18, TextC, FontStyle.Bold));

        if (_repo.Account != null)
        {
            var u = _repo.UserInfo;
            string exp = u.TryGetValue("exp_date", out var ed) && long.TryParse(ed, out var ex) && ex > 0 ? DateTimeOffset.FromUnixTimeSeconds(ex).LocalDateTime.ToString("dd.MM.yyyy") : "unbegrenzt";
            flow.Controls.Add(H("KONTO", 9, Muted, FontStyle.Bold));
            flow.Controls.Add(H($"Benutzer: {(u.GetValueOrDefault("username") ?? _repo.Account.Username)}\nStatus: {u.GetValueOrDefault("status", "–")}\nGültig bis: {exp}\nVerbindungen: {u.GetValueOrDefault("active_cons", "0")} von {u.GetValueOrDefault("max_connections", "–")}\nServer: {_repo.Account.Server}", 10, TextC, FontStyle.Regular));
        }

        flow.Controls.Add(H("KONTEN", 9, Muted, FontStyle.Bold));
        for (int i = 0; i < _repo.Accounts.Count; i++)
        {
            int idx = i;
            var sa = _repo.Accounts[i];
            var row = new FlowLayoutPanel { FlowDirection = FlowDirection.LeftToRight, WrapContents = false, AutoSize = true, Margin = new Padding(0, 2, 0, 2) };
            var active = idx == _repo.ActiveIndex;
            var pick = new Button { Text = (active ? "● " : "○ ") + sa.Label, Width = 300, Height = 32, FlatStyle = FlatStyle.Flat, ForeColor = TextC, BackColor = active ? Panel2 : Panel, TextAlign = ContentAlignment.MiddleLeft };
            pick.FlatAppearance.BorderSize = 0;
            pick.Click += async (_, _) => { if (idx != _repo.ActiveIndex) { _repo.SwitchTo(idx); StopPlayer(); await _repo.RefreshUserInfoAsync(); _ = LoadEpg(false); BuildSettings(); } };
            var edit = new Button { Text = "Bearbeiten", Width = 90, Height = 32, FlatStyle = FlatStyle.Flat, ForeColor = TextC, BackColor = Panel };
            edit.FlatAppearance.BorderSize = 1; edit.FlatAppearance.BorderColor = Line;
            edit.Click += (_, _) => EditAccount(idx);
            var del = new Button { Text = "Löschen", Width = 80, Height = 32, FlatStyle = FlatStyle.Flat, ForeColor = Color.White, BackColor = ColorFromHex("#A8322C") };
            del.FlatAppearance.BorderSize = 0;
            del.Click += async (_, _) => { if (MessageBox.Show($"Konto „{sa.Label}“ löschen?", "IPTV+", MessageBoxButtons.YesNo) == DialogResult.Yes) { StopPlayer(); _repo.RemoveAccount(idx); await _repo.RefreshUserInfoAsync(); _ = LoadEpg(false); BuildSettings(); } };
            row.Controls.Add(pick); row.Controls.Add(edit); row.Controls.Add(del);
            flow.Controls.Add(row);
        }

        flow.Controls.Add(H(_repo.Accounts.Count == 0 ? "ANMELDUNG – Xtream-Codes-Zugangsdaten" : "KONTO HINZUFÜGEN", 9, Muted, FontStyle.Bold));
        var fLabel = Field("Name (z. B. Hauptkonto)");
        var fServer = Field("Server-URL (http://anbieter:8080)");
        var fUser = Field("Benutzername");
        var fPass = Field("Passwort", true);
        var msg = H("", 9, ColorFromHex("#FF6B6B"), FontStyle.Regular);
        flow.Controls.Add(fLabel); flow.Controls.Add(fServer); flow.Controls.Add(fUser); flow.Controls.Add(fPass); flow.Controls.Add(msg);
        var save = PrimaryButton(_repo.Accounts.Count == 0 ? "Speichern & verbinden" : "Konto hinzufügen & verbinden");
        save.Click += async (_, _) =>
        {
            msg.ForeColor = Muted; msg.Text = "Verbinde…";
            try
            {
                await _repo.SaveAccountAsync(fLabel.Text, fServer.Text, fUser.Text, fPass.Text, -1);
                _ = LoadEpg(false);
                ShowSection(Section.Live);
            }
            catch (Exception ex) { msg.ForeColor = ColorFromHex("#FF6B6B"); msg.Text = ex.Message; }
        };
        flow.Controls.Add(save);

        if (_repo.Account != null)
        {
            flow.Controls.Add(H("FILME & SERIEN", 9, Muted, FontStyle.Bold));
            var chk = new CheckBox { Text = "Nur deutsche Fassungen anzeigen und durchsuchen", ForeColor = TextC, Checked = _repo.DeOnly, AutoSize = true };
            chk.CheckedChanged += (_, _) => _repo.SetDeOnly(chk.Checked);
            flow.Controls.Add(chk);

            flow.Controls.Add(H("TV-PROGRAMM", 9, Muted, FontStyle.Bold));
            var e = _repo.Epg;
            flow.Controls.Add(H(e != null ? $"Quelle: {e.Source}\nStand: {DateTimeOffset.FromUnixTimeSeconds(e.Updated).LocalDateTime:dd.MM. HH:mm}\nSender: {e.Channels.Count}" : (_repo.EpgLoading ? "Wird geladen…" : "Noch nicht geladen."), 10, TextC, FontStyle.Regular));
            var epgBtn = GhostButton("TV-Programm jetzt aktualisieren");
            epgBtn.Click += (_, _) => _ = LoadEpg(true);
            flow.Controls.Add(epgBtn);

            flow.Controls.Add(H("UPDATE", 9, Muted, FontStyle.Bold));
            var upInfo = H($"Installierte Version: {Updater.VersionName()} by CoreCX", 10, TextC, FontStyle.Regular);
            flow.Controls.Add(upInfo);
            var upBtn = GhostButton("Nach Update suchen");
            upBtn.Click += (_, _) => CheckUpdate(upInfo);
            flow.Controls.Add(upBtn);

            flow.Controls.Add(H("ABMELDEN", 9, Muted, FontStyle.Bold));
            var logout = new Button { Text = "Alle Konten abmelden", Width = 240, Height = 34, FlatStyle = FlatStyle.Flat, ForeColor = Color.White, BackColor = ColorFromHex("#A8322C") };
            logout.FlatAppearance.BorderSize = 0;
            logout.Click += (_, _) => { if (MessageBox.Show("Alle Konten abmelden und Zugangsdaten löschen?", "IPTV+", MessageBoxButtons.YesNo) == DialogResult.Yes) { StopPlayer(); _repo.Logout(); ShowSection(Section.Settings); } };
            flow.Controls.Add(logout);
        }
        flow.Controls.Add(H($"IPTV+ für Windows\nInstallierte Version: {Updater.VersionName()} by CoreCX", 9, Muted, FontStyle.Regular));
    }

    async void CheckUpdate(Label info)
    {
        info.Text = "Suche nach Update…";
        try
        {
            var r = await Updater.LatestAsync();
            int cur = Updater.CurrentBuild();
            if (r == null) { info.Text = $"Installierte Version: {Updater.VersionName()} by CoreCX – kein Release gefunden."; return; }
            if (r.Number > cur)
            {
                info.Text = $"Update verfügbar: Build {r.Number} - CoreCX (installiert: {Updater.VersionName()})";
                if (MessageBox.Show($"Build {r.Number} - CoreCX ist verfügbar.\nJetzt herunterladen und installieren?", "Update", MessageBoxButtons.YesNo) == DialogResult.Yes)
                    RunUpdate(info, r);
            }
            else info.Text = $"Du hast die neueste Version (Build {cur} - CoreCX).";
        }
        catch (Exception e) { info.Text = "Update-Prüfung fehlgeschlagen: " + e.Message; }
    }

    async void RunUpdate(Label info, Updater.Release r)
    {
        try
        {
            var progress = new Progress<int>(p => info.Text = $"Lade Build {r.Number} - CoreCX… {p} %");
            var exe = await Updater.DownloadAsync(r, progress);
            info.Text = "Installation wird gestartet…";
            Updater.ApplyAndRestart(exe);
        }
        catch (Exception e) { info.Text = "Update fehlgeschlagen: " + e.Message; }
    }

    void EditAccount(int idx)
    {
        var sa = _repo.Accounts[idx];
        using var dlg = new AccountDialog(sa.Label, sa.Server, sa.User);
        if (dlg.ShowDialog(this) == DialogResult.OK)
        {
            _ = Task.Run(async () =>
            {
                try { await _repo.SaveAccountAsync(dlg.AccLabel, dlg.AccServer, dlg.AccUser, dlg.AccPass, idx); BeginInvoke(() => { _ = _repo.RefreshUserInfoAsync(); BuildSettings(); }); }
                catch (Exception ex) { BeginInvoke(() => MessageBox.Show(ex.Message, "IPTV+")); }
            });
        }
    }

    // ---------------- UI-Helfer ----------------

    Label H(string text, float size, Color color, FontStyle style) => new() { Text = text, Font = new Font("Segoe UI", size, style), ForeColor = color, AutoSize = true, Margin = new Padding(0, 10, 0, 4), MaximumSize = new Size(600, 0) };
    TextBox Field(string ph, bool pass = false) { var t = new TextBox { Width = 560, BackColor = Panel2, ForeColor = TextC, BorderStyle = BorderStyle.FixedSingle, Margin = new Padding(0, 2, 0, 6), PlaceholderText = ph }; if (pass) t.UseSystemPasswordChar = true; return t; }
    Button PrimaryButton(string t) { var b = new Button { Text = t, Width = 280, Height = 36, FlatStyle = FlatStyle.Flat, ForeColor = Color.White, BackColor = Accent, Margin = new Padding(0, 6, 0, 4) }; b.FlatAppearance.BorderSize = 0; return b; }
    Button GhostButton(string t) { var b = new Button { Text = t, Width = 280, Height = 32, FlatStyle = FlatStyle.Flat, ForeColor = TextC, BackColor = Panel, Margin = new Padding(0, 4, 0, 4) }; b.FlatAppearance.BorderSize = 1; b.FlatAppearance.BorderColor = Line; return b; }

    protected override void OnKeyDown(KeyEventArgs e)
    {
        if (_mode == PMode.Full)
        {
            if (e.KeyCode == Keys.Escape) { SetMode(PMode.Mini); e.Handled = true; }
            else if (e.KeyCode == Keys.Up) { Zap(-1); e.Handled = true; }
            else if (e.KeyCode == Keys.Down) { Zap(1); e.Handled = true; }
            else if (e.KeyCode == Keys.F) { SetMode(PMode.Full); }
        }
        base.OnKeyDown(e);
    }

    void Zap(int d)
    {
        if (_playing == null || _zap.Count == 0) return;
        int i = _zap.FindIndex(x => x.Id == _playing.Id);
        if (i < 0) return;
        int j = i + d;
        if (j < 0 || j >= _zap.Count) return;
        _playing = _zap[j]; _playingTitle = _zap[j].Name; _errorCount = 0;
        StartStream();
    }
}

public class Row
{
    public string Title, Sub;
    public bool Bold;
    public object Payload;
    public Row(string title, string sub = "", bool bold = false, object payload = null) { Title = title; Sub = sub; Bold = bold; Payload = payload; }
    public override string ToString() => Title;
}
