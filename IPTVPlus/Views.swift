import SwiftUI

@main
struct IPTVPlusApp: App {
    @StateObject private var store = Store()
    var body: some Scene {
        WindowGroup {
            RootView().environmentObject(store).preferredColorScheme(.dark).tint(Color(red: 0.24, green: 0.55, blue: 0.99))
        }
    }
}

struct RootView: View {
    @EnvironmentObject var store: Store
    @ObservedObject var host = PlayerHost.shared
    var body: some View {
        if store.account == nil {
            LoginView()
        } else {
            ZStack {
                TabView {
                    BrowseRoot(kind: .live).tabItem { Label("Live TV", systemImage: "tv") }
                    BrowseRoot(kind: .movie).tabItem { Label("Filme", systemImage: "film") }
                    BrowseRoot(kind: .series).tabItem { Label("Serien", systemImage: "rectangle.stack") }
                    EPGView().tabItem { Label("TV-Programm", systemImage: "list.bullet.rectangle") }
                    SettingsView().tabItem { Label("Einstellungen", systemImage: "gearshape") }
                }
                if host.request != nil {
                    PlayerOverlay().transition(.move(edge: .bottom))
                }
            }
            .task { store.loadEPG(force: false); await store.refreshUserInfo() }
        }
    }
}

// MARK: - Anmeldung

struct LoginView: View {
    @EnvironmentObject var store: Store
    @State private var server = ""
    @State private var user = ""
    @State private var pass = ""
    @State private var busy = false
    @State private var error = ""

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("Server-URL (http://anbieter:8080)", text: $server)
                        .keyboardType(.URL).textInputAutocapitalization(.never).autocorrectionDisabled()
                    TextField("Benutzername", text: $user).textInputAutocapitalization(.never).autocorrectionDisabled()
                    SecureField("Passwort", text: $pass).textInputAutocapitalization(.never).autocorrectionDisabled().textContentType(.password)
                } header: { Text("Xtream-Codes-Zugangsdaten") }
                if !error.isEmpty { Text(error).foregroundColor(.red).font(.footnote) }
                Button {
                    busy = true; error = ""
                    Task {
                        do { try await store.saveAccount(label: "", server: server, username: user, password: pass, index: nil) }
                        catch { self.error = error.localizedDescription }
                        busy = false
                    }
                } label: { HStack { Spacer(); if busy { ProgressView() } else { Text("Verbinden").bold() }; Spacer() } }
                .disabled(busy || server.isEmpty || user.isEmpty || pass.isEmpty)
            }
            .navigationTitle("IPTV+")
        }
    }
}

// MARK: - Live TV / Filme / Serien

struct BrowseRoot: View {
    let kind: Kind
    var body: some View {
        NavigationStack { CategoryListView(kind: kind) }
    }
}

struct CategoryListView: View {
    @EnvironmentObject var store: Store
    let kind: Kind
    @State private var cats: [Category] = []
    @State private var error = ""
    @State private var query = ""
    @State private var results: [Item] = []
    @State private var searching = false
    @State private var play: PlayRequest?

    var title: String { kind == .live ? "Live TV" : (kind == .movie ? "Filme" : "Serien") }

    var body: some View {
        List {
            if !query.isEmpty {
                if searching { HStack { ProgressView(); Text("Suche…").foregroundColor(.secondary) } }
                else if results.isEmpty {
                    Text("Nichts gefunden." + (store.filterDE(kind) ? " Es werden nur deutsche Titel durchsucht." : "")).foregroundColor(.secondary)
                }
                ForEach(results) { it in ItemLink(kind: kind, item: it, list: results, play: $play) }
            } else {
                if !error.isEmpty { Text(error).foregroundColor(.red) }
                NavigationLink(value: Category(id: "", name: store.filterDE(kind) ? "Alle (deutsch)" : "Alle")) {
                    Text(store.filterDE(kind) ? "Alle (deutsch)" : "Alle").bold()
                }
                ForEach(cats) { c in NavigationLink(value: c) { Text(c.name) } }
            }
        }
        .listStyle(.plain)
        .navigationTitle(title)
        .navigationDestination(for: Category.self) { c in ItemListView(kind: kind, category: c) }
        .navigationDestination(for: Item.self) { it in
            if kind == .series { SeriesDetailView(item: it) } else { MovieDetailView(item: it) }
        }
        .searchable(text: $query, prompt: kind == .live ? "Sender suchen" : "Titel suchen")
        .task(id: query) {
            guard !query.isEmpty else { results = []; return }
            searching = true
            try? await Task.sleep(nanoseconds: 300_000_000)
            if Task.isCancelled { return }
            let r = (try? await store.search(kind, query)) ?? []
            if !Task.isCancelled { results = r; searching = false }
        }
        .task(id: store.deOnly) {
            do { cats = try await store.categories(kind); error = "" } catch { self.error = error.localizedDescription }
        }
        .refreshable { cats = (try? await store.categories(kind)) ?? cats }
        .onChange(of: play?.id) { _ in if let p = play { PlayerHost.shared.open(p); play = nil } }
    }
}

struct ItemListView: View {
    @EnvironmentObject var store: Store
    let kind: Kind
    let category: Category
    @State private var items: [Item] = []
    @State private var loading = true
    @State private var error = ""
    @State private var filter = ""
    @State private var play: PlayRequest?

    var shown: [Item] {
        let q = Lang.normQ(filter)
        return q.isEmpty ? items : items.filter { Lang.normQ($0.name).contains(q) }
    }

    var body: some View {
        List {
            if loading { HStack { ProgressView(); Text("Lade…").foregroundColor(.secondary) } }
            if !error.isEmpty { Text(error).foregroundColor(.red) }
            ForEach(shown) { it in ItemLink(kind: kind, item: it, list: shown, play: $play) }
        }
        .listStyle(.plain)
        .navigationTitle(category.name)
        .navigationBarTitleDisplayMode(.inline)
        .searchable(text: $filter, prompt: "In dieser Kategorie filtern")
        .task {
            do { items = try await store.items(kind, category: category.id); error = "" }
            catch { self.error = error.localizedDescription }
            loading = false
        }
        .onChange(of: play?.id) { _ in if let p = play { PlayerHost.shared.open(p); play = nil } }
    }
}

/// Eine Zeile: Sender (startet direkt) oder Film/Serie (öffnet Details)
struct ItemLink: View {
    @EnvironmentObject var store: Store
    let kind: Kind
    let item: Item
    let list: [Item]
    @Binding var play: PlayRequest?

    var body: some View {
        if kind == .live {
            Button { play = PlayRequest(title: item.name, kind: .live, item: item, zapList: list) } label: { ItemRow(kind: kind, item: item) }
                .buttonStyle(.plain)
        } else {
            NavigationLink(value: item) { ItemRow(kind: kind, item: item) }
        }
    }
}

struct ItemRow: View {
    @EnvironmentObject var store: Store
    let kind: Kind
    let item: Item

    var body: some View {
        HStack(spacing: 12) {
            AsyncImage(url: URL(string: item.icon)) { img in img.resizable().aspectRatio(contentMode: kind == .live ? .fit : .fill) }
                placeholder: { Color(.secondarySystemBackground) }
                .frame(width: kind == .live ? 52 : 46, height: kind == .live ? 40 : 68)
                .clipShape(RoundedRectangle(cornerRadius: 6))
            VStack(alignment: .leading, spacing: 3) {
                Text(item.name).lineLimit(1)
                if kind == .live {
                    if let cur = store.epgChannel(for: item)?.current() {
                        HStack(spacing: 4) {
                            Text(Self.hm(cur.s)).font(.caption).bold().foregroundColor(.accentColor)
                            Text(cur.t).font(.caption).foregroundColor(.secondary).lineLimit(1)
                        }
                        ProgressView(value: min(1, max(0, (Date().timeIntervalSince1970 - cur.s) / (cur.e - cur.s))))
                            .frame(maxWidth: 160)
                    }
                } else {
                    let sub = [item.year, item.rating.isEmpty || item.rating == "0" ? "" : "★ \(item.rating)"].filter { !$0.isEmpty }.joined(separator: " · ")
                    if !sub.isEmpty { Text(sub).font(.caption).foregroundColor(.secondary) }
                }
            }
            Spacer(minLength: 0)
        }
        .contentShape(Rectangle())
    }

    static func hm(_ t: Double) -> String {
        let f = DateFormatter(); f.dateFormat = "HH:mm"
        return f.string(from: Date(timeIntervalSince1970: t))
    }
}

// MARK: - Film-Details

struct MovieDetailView: View {
    @EnvironmentObject var store: Store
    let item: Item
    @State private var plot = ""
    @State private var meta = ""
    @State private var play: PlayRequest?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                HStack(alignment: .top, spacing: 14) {
                    AsyncImage(url: URL(string: item.icon)) { $0.resizable().aspectRatio(contentMode: .fill) } placeholder: { Color(.secondarySystemBackground) }
                        .frame(width: 120, height: 178).clipShape(RoundedRectangle(cornerRadius: 10))
                    VStack(alignment: .leading, spacing: 6) {
                        Text(item.name).font(.title3).bold()
                        if !meta.isEmpty { Text(meta).font(.subheadline).foregroundColor(.secondary) }
                    }
                }
                Button { play = PlayRequest(title: item.name, kind: .movie, item: item, subtitle: meta) } label: {
                    Label("Abspielen", systemImage: "play.fill").frame(maxWidth: .infinity).padding(.vertical, 6)
                }
                .buttonStyle(.borderedProminent)
                if !plot.isEmpty { Text(plot).font(.body) }
            }
            .padding()
        }
        .navigationBarTitleDisplayMode(.inline)
        .task {
            guard let api = store.api, let d = try? await api.info("get_vod_info", "vod_id", item.id), let info = d["info"] as? [String: Any] else { return }
            plot = str(info["plot"]).isEmpty ? str(info["description"]) : str(info["plot"])
            meta = [str(info["genre"]), str(info["duration"]), String(str(info["releasedate"]).prefix(4))].filter { !$0.isEmpty }.joined(separator: " · ")
        }
        .onChange(of: play?.id) { _ in if let p = play { PlayerHost.shared.open(p); play = nil } }
    }
}

// MARK: - Serien-Details

struct Episode: Identifiable, Hashable {
    let id: String
    let num: String
    let title: String
    let ext: String
    let plot: String
    let duration: String
    let season: String
}

struct SeriesDetailView: View {
    @EnvironmentObject var store: Store
    let item: Item
    @State private var seasons: [String] = []
    @State private var episodes: [String: [Episode]] = [:]
    @State private var season = ""
    @State private var plot = ""
    @State private var loading = true
    @State private var play: PlayRequest?

    var body: some View {
        List {
            Section {
                HStack(alignment: .top, spacing: 14) {
                    AsyncImage(url: URL(string: item.icon)) { $0.resizable().aspectRatio(contentMode: .fill) } placeholder: { Color(.secondarySystemBackground) }
                        .frame(width: 90, height: 134).clipShape(RoundedRectangle(cornerRadius: 8))
                    VStack(alignment: .leading, spacing: 6) {
                        Text(item.name).font(.headline)
                        if !plot.isEmpty { Text(plot).font(.caption).foregroundColor(.secondary).lineLimit(8) }
                    }
                }
            }
            if loading { ProgressView() }
            if seasons.count > 1 {
                Picker("Staffel", selection: $season) { ForEach(seasons, id: \.self) { Text("Staffel \($0)").tag($0) } }
            }
            Section {
                ForEach(episodes[season] ?? []) { ep in
                    Button {
                        let fake = Item(["stream_id": ep.id, "name": ep.title, "container_extension": ep.ext], kind: .movie)
                        play = PlayRequest(title: "\(item.name) – S\(ep.season)E\(ep.num) \(ep.title)", kind: .series, item: fake, subtitle: ep.plot)
                    } label: {
                        VStack(alignment: .leading, spacing: 3) {
                            Text("\(ep.num). \(ep.title)").lineLimit(2)
                            if !ep.duration.isEmpty { Text(ep.duration).font(.caption).foregroundColor(.secondary) }
                        }
                    }
                }
            }
        }
        .navigationTitle(item.name)
        .navigationBarTitleDisplayMode(.inline)
        .task {
            guard let api = store.api, let d = try? await api.info("get_series_info", "series_id", item.id) else { loading = false; return }
            if let info = d["info"] as? [String: Any] { plot = str(info["plot"]) }
            var eps: [String: [Episode]] = [:]
            if let e = d["episodes"] as? [String: Any] {
                for (s, v) in e {
                    let arr = (v as? [[String: Any]]) ?? []
                    eps[s] = arr.map { x in
                        let inf = x["info"] as? [String: Any] ?? [:]
                        return Episode(id: str(x["id"]), num: str(x["episode_num"]), title: str(x["title"]), ext: str(x["container_extension"]),
                                       plot: str(inf["plot"]), duration: str(inf["duration"]), season: s)
                    }
                }
            } else if let e = d["episodes"] as? [[[String: Any]]] {
                for (i, arr) in e.enumerated() {
                    let s = String(i + 1)
                    eps[s] = arr.map { x in
                        Episode(id: str(x["id"]), num: str(x["episode_num"]), title: str(x["title"]), ext: str(x["container_extension"]),
                                plot: "", duration: "", season: s)
                    }
                }
            }
            episodes = eps
            seasons = eps.keys.sorted { (Int($0) ?? 0) < (Int($1) ?? 0) }
            season = seasons.first ?? ""
            loading = false
        }
        .onChange(of: play?.id) { _ in if let p = play { PlayerHost.shared.open(p); play = nil } }
    }
}

// MARK: - TV-Programm

struct EPGView: View {
    @EnvironmentObject var store: Store
    @State private var day = 0
    @State private var time = "now"
    @State private var query = ""
    @State private var play: PlayRequest?
    @State private var tick = Date()
    private let clock = Timer.publish(every: 60, on: .main, in: .common).autoconnect()

    var target: Double {
        if day == 0 && time == "now" { return tick.timeIntervalSince1970 }
        var d = Calendar.current.date(byAdding: .day, value: day, to: Date()) ?? Date()
        if time != "now" {
            let p = time.split(separator: ":").compactMap { Int($0) }
            d = Calendar.current.date(bySettingHour: p[0], minute: p[1], second: 0, of: d) ?? d
        }
        return d.timeIntervalSince1970
    }

    struct Row: Identifiable { let id: String; let ch: EPGChannel; let progs: [Prog] }

    var rows: [Row] {
        guard let e = store.epg else { return [] }
        let T = target
        let q = Lang.normQ(query)
        var out: [Row] = []
        for ch in e.channels {
            guard let i = ch.progs.firstIndex(where: { $0.e > T }) else { continue }
            let ps = Array(ch.progs[i..<min(ch.progs.count, i + 3)])
            if !q.isEmpty && !Lang.normQ(ch.name).contains(q) && !ps.contains(where: { Lang.normQ($0.t).contains(q) }) { continue }
            out.append(Row(id: ch.id, ch: ch, progs: ps))
        }
        return out
    }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Picker("Tag", selection: $day) { Text("Heute").tag(0); Text("Morgen").tag(1) }.pickerStyle(.segmented)
                    Picker("Zeit", selection: $time) {
                        Text("Jetzt").tag("now"); Text("18:00").tag("18:00"); Text("20:15").tag("20:15"); Text("22:00").tag("22:00")
                    }.pickerStyle(.segmented)
                }
                if store.epg == nil {
                    if store.epgLoading {
                        HStack { ProgressView(); Text("TV-Programm für deutsche Sender wird geladen… (beim ersten Mal bis zu einer Minute)").font(.footnote) }
                    } else if let err = store.epgError {
                        Text("TV-Programm konnte nicht geladen werden: \(err)").foregroundColor(.red)
                        Button("Erneut versuchen") { store.loadEPG(force: true) }
                    }
                }
                ForEach(rows) { r in
                    Button {
                        let it = Item(["stream_id": r.ch.streamID, "name": r.ch.name, "stream_icon": r.ch.icon], kind: .live)
                        let list = rows.map { Item(["stream_id": $0.ch.streamID, "name": $0.ch.name, "stream_icon": $0.ch.icon], kind: .live) }
                        play = PlayRequest(title: r.ch.name, kind: .live, item: it, zapList: list)
                    } label: { EPGRow(row: r, now: tick.timeIntervalSince1970) }
                    .buttonStyle(.plain)
                }
                if let e = store.epg {
                    Text("\(rows.count) deutsche Sender · Quelle: \(e.source)").font(.caption2).foregroundColor(.secondary)
                }
            }
            .listStyle(.plain)
            .navigationTitle("TV-Programm")
            .searchable(text: $query, prompt: "Sender oder Sendung")
            .refreshable { store.loadEPG(force: true) }
            .onReceive(clock) { tick = $0 }
            .onChange(of: play?.id) { _ in if let p = play { PlayerHost.shared.open(p); play = nil } }
        }
    }
}

struct EPGRow: View {
    let row: EPGView.Row
    let now: Double
    var body: some View {
        HStack(alignment: .top, spacing: 10) {
            AsyncImage(url: URL(string: row.ch.icon)) { $0.resizable().aspectRatio(contentMode: .fit) } placeholder: { Color.clear }
                .frame(width: 44, height: 32)
            VStack(alignment: .leading, spacing: 4) {
                Text(row.ch.name).font(.subheadline).bold().lineLimit(1)
                ForEach(Array(row.progs.enumerated()), id: \.offset) { i, p in
                    HStack(spacing: 6) {
                        Text(ItemRow.hm(p.s)).font(.caption).bold().foregroundColor(i == 0 ? .accentColor : .secondary)
                        Text(p.t).font(i == 0 ? .caption.bold() : .caption).foregroundColor(i == 0 ? .primary : .secondary).lineLimit(1)
                    }
                    if i == 0 && p.s <= now && p.e > now {
                        ProgressView(value: min(1, max(0, (now - p.s) / (p.e - p.s)))).frame(maxWidth: 200)
                    }
                }
            }
        }
        .padding(.vertical, 2)
        .contentShape(Rectangle())
    }
}

// MARK: - Einstellungen

struct SettingsView: View {
    @EnvironmentObject var store: Store
    @State private var confirmLogout = false
    @State private var editing: SavedAccount?
    @State private var addNew = false

    func date(_ s: String) -> String {
        guard let t = Double(s), t > 0 else { return "unbegrenzt" }
        let f = DateFormatter(); f.dateStyle = .medium; f.locale = Locale(identifier: "de_DE")
        return f.string(from: Date(timeIntervalSince1970: t))
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    ForEach(Array(store.accounts.enumerated()), id: \.element.id) { i, sa in
                        Button {
                            if i != store.activeIndex { store.switchTo(i) }
                        } label: {
                            HStack {
                                Image(systemName: i == store.activeIndex ? "checkmark.circle.fill" : "circle")
                                    .foregroundColor(i == store.activeIndex ? .accentColor : .secondary)
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(sa.label).foregroundColor(.primary)
                                    Text(sa.account.username).font(.caption).foregroundColor(.secondary)
                                }
                                Spacer()
                                Button { editing = sa } label: { Image(systemName: "pencil") }
                                    .buttonStyle(.borderless).foregroundColor(.accentColor)
                            }
                        }
                    }
                    .onDelete { idx in idx.forEach { store.removeAccount($0) } }
                    Button { addNew = true } label: { Label("Konto hinzufügen", systemImage: "plus") }
                } header: { Text("Konten") } footer: { Text("Antippen wechselt das Konto. Zum Löschen nach links wischen.") }

                if store.account != nil {
                    Section("Aktives Konto") {
                        LabeledContent("Status", value: store.userInfo["status"] ?? "–")
                        LabeledContent("Gültig bis", value: date(store.userInfo["exp_date"] ?? ""))
                        LabeledContent("Verbindungen", value: "\(store.userInfo["active_cons"] ?? "0") von \(store.userInfo["max_connections"] ?? "–")")
                        LabeledContent("Server", value: store.account?.server ?? "")
                    }
                    Section("Filme & Serien") {
                        Toggle("Nur deutsche Fassungen anzeigen und durchsuchen", isOn: $store.deOnly)
                    }
                    Section("TV-Programm") {
                        if let e = store.epg {
                            LabeledContent("Quelle", value: e.source)
                            LabeledContent("Stand", value: Date(timeIntervalSince1970: e.updated).formatted(date: .abbreviated, time: .shortened))
                            LabeledContent("Sender", value: "\(e.channels.count)")
                        } else {
                            Text(store.epgLoading ? "Wird geladen…" : "Noch nicht geladen.").foregroundColor(.secondary)
                        }
                        Button(store.epgLoading ? "Lädt…" : "Jetzt aktualisieren") { store.loadEPG(force: true) }.disabled(store.epgLoading)
                    }
                    Section {
                        Button("Alle Konten abmelden", role: .destructive) { confirmLogout = true }
                    } footer: { Text("Entfernt alle gespeicherten Zugangsdaten von diesem iPhone.") }
                }
                Section {
                    VStack(spacing: 4) {
                        let v = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? ""
                        let b = Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? ""
                        Text("Installierte Version: \(v).\(b) by CoreCX").font(.footnote).bold()
                    }
                    .frame(maxWidth: .infinity)
                    .foregroundColor(.secondary)
                }
            }
            .navigationTitle("Einstellungen")
            .sheet(isPresented: $addNew) { AccountEditor(index: nil, saved: nil) }
            .sheet(item: $editing) { sa in AccountEditor(index: store.accounts.firstIndex(of: sa), saved: sa) }
            .confirmationDialog("Alle Konten abmelden und Zugangsdaten löschen?", isPresented: $confirmLogout, titleVisibility: .visible) {
                Button("Abmelden", role: .destructive) { PlayerHost.shared.close(); store.logout() }
            }
        }
    }
}

/// Konto anlegen oder bearbeiten
struct AccountEditor: View {
    @EnvironmentObject var store: Store
    @Environment(\.dismiss) private var dismiss
    let index: Int?
    let saved: SavedAccount?
    @State private var label = ""
    @State private var server = ""
    @State private var user = ""
    @State private var pass = ""
    @State private var busy = false
    @State private var error = ""

    var body: some View {
        NavigationStack {
            Form {
                Section("Name") {
                    TextField("z. B. Wohnzimmer, Konto 2", text: $label)
                }
                Section("Xtream-Codes-Zugangsdaten") {
                    TextField("Server-URL (http://anbieter:8080)", text: $server)
                        .keyboardType(.URL).textInputAutocapitalization(.never).autocorrectionDisabled()
                    TextField("Benutzername", text: $user).textInputAutocapitalization(.never).autocorrectionDisabled()
                    SecureField(saved == nil ? "Passwort" : "Passwort (leer = unverändert)", text: $pass)
                        .textInputAutocapitalization(.never).autocorrectionDisabled().textContentType(.password)
                }
                if !error.isEmpty { Text(error).foregroundColor(.red).font(.footnote) }
            }
            .navigationTitle(saved == nil ? "Konto hinzufügen" : "Konto bearbeiten")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Abbrechen") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button(busy ? "…" : "Speichern") {
                        busy = true; error = ""
                        Task {
                            do { try await store.saveAccount(label: label, server: server, username: user, password: pass, index: index); dismiss() }
                            catch { self.error = error.localizedDescription }
                            busy = false
                        }
                    }.disabled(busy || server.isEmpty || user.isEmpty || (saved == nil && pass.isEmpty))
                }
            }
            .onAppear {
                if let sa = saved { label = sa.label; server = sa.account.server; user = sa.account.username }
            }
        }
    }
}
