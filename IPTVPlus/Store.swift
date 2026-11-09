import Foundation
import SwiftUI

struct SavedAccount: Codable, Identifiable, Equatable {
    var id = UUID()
    var label: String
    var account: Account
}

@MainActor
final class Store: ObservableObject {
    @Published var accounts: [SavedAccount] = []
    @Published var activeIndex: Int = 0
    @Published var userInfo: [String: String] = [:]
    @Published var deOnly: Bool {
        didSet { UserDefaults.standard.set(deOnly, forKey: "deOnly"); listCache = listCache.filter { !$0.key.hasPrefix("de:") } }
    }
    @Published var epg: EPGData?
    @Published var epgLoading = false
    @Published var epgError: String?

    private var catCache: [Kind: [Category]] = [:]
    private var listCache: [String: [Item]] = [:]
    /// Suchschlüssel je Liste, damit nicht bei jedem Tastendruck alle Titel neu aufbereitet werden
    private var keyCache: [Kind: (count: Int, first: String, keys: [[UInt8]])] = [:]
    private var epgTask: Task<Void, Never>?

    var account: Account? { accounts.indices.contains(activeIndex) ? accounts[activeIndex].account : nil }
    var api: XtreamAPI? { account.map { XtreamAPI(acc: $0) } }

    init() {
        deOnly = UserDefaults.standard.object(forKey: "deOnly") as? Bool ?? true
        accounts = Keychain.loadAccounts()
        activeIndex = min(UserDefaults.standard.integer(forKey: "activeIndex"), max(0, accounts.count - 1))
        epg = Store.loadEPGCache()
    }

    // MARK: Konten

    private func persist() {
        Keychain.saveAccounts(accounts)
        UserDefaults.standard.set(activeIndex, forKey: "activeIndex")
    }

    private func resetCaches() { catCache = [:]; listCache = [:]; keyCache = [:]; epg = nil; Store.deleteEPGCache() }

    /// Konto prüfen und speichern. index == nil -> neues Konto, sonst bestehendes ersetzen.
    func saveAccount(label: String, server: String, username: String, password: String, index: Int?) async throws {
        var pw = password.trimmingCharacters(in: .whitespaces)
        if pw.isEmpty, let i = index, accounts.indices.contains(i) { pw = accounts[i].account.password }
        let acc = Account(server: Account.normalize(server), username: username.trimmingCharacters(in: .whitespaces), password: pw)
        let info = try await XtreamAPI(acc: acc).userInfo()
        let name = label.trimmingCharacters(in: .whitespaces).isEmpty ? "Konto \(accounts.count + 1)" : label.trimmingCharacters(in: .whitespaces)
        let wasActive = index == activeIndex
        if let i = index, accounts.indices.contains(i) {
            accounts[i] = SavedAccount(id: accounts[i].id, label: name, account: acc)
            if wasActive { userInfo = info.mapValues { str($0) }; resetCaches() }
        } else {
            accounts.append(SavedAccount(label: name, account: acc))
            activeIndex = accounts.count - 1
            userInfo = info.mapValues { str($0) }
            resetCaches()
        }
        persist()
        loadEPG(force: false)
    }

    func switchTo(_ index: Int) {
        guard accounts.indices.contains(index), index != activeIndex else { return }
        PlayerModel.shared.stop()
        activeIndex = index
        userInfo = [:]
        resetCaches()
        persist()
        Task { await refreshUserInfo() }
        loadEPG(force: false)
    }

    func removeAccount(_ index: Int) {
        guard accounts.indices.contains(index) else { return }
        PlayerModel.shared.stop()
        accounts.remove(at: index)
        activeIndex = min(activeIndex, max(0, accounts.count - 1))
        userInfo = [:]
        resetCaches()
        persist()
        if account != nil { Task { await refreshUserInfo() }; loadEPG(force: false) }
    }

    func refreshUserInfo() async {
        guard let api = api else { return }
        if let info = try? await api.userInfo() { userInfo = info.mapValues { str($0) } }
    }

    func logout() {
        PlayerModel.shared.stop()
        accounts = []
        activeIndex = 0
        Keychain.saveAccounts([])
        UserDefaults.standard.set(0, forKey: "activeIndex")
        userInfo = [:]
        resetCaches()
    }

    // MARK: Listen

    func germanCatIDs(_ k: Kind) -> Set<String> {
        Set((catCache[k] ?? []).filter { $0.name.rxMatch(Lang.reGerman) }.map { $0.id })
    }

    func isGerman(_ it: Item, _ k: Kind) -> Bool { Lang.germanFilter(catCache[k] ?? [])(it) }

    func filterDE(_ k: Kind) -> Bool { deOnly && (k == .movie || k == .series) }

    func categories(_ k: Kind) async throws -> [Category] {
        guard let api = api else { return [] }
        if catCache[k] == nil { catCache[k] = try await api.categories(k) }
        var cats = catCache[k] ?? []
        if filterDE(k) {
            let de = cats.filter { $0.name.rxMatch(Lang.reGerman) }
            if !de.isEmpty { cats = de }
        }
        return cats
    }

    func allItems(_ k: Kind) async throws -> [Item] {
        guard let api = api else { return [] }
        if catCache[k] == nil { catCache[k] = try? await api.categories(k) }
        let key = "\(k.rawValue):"
        if let c = listCache[key] { return c }
        let items = try await api.items(k)
        listCache[key] = items
        return items
    }

    /// Kategorie "" = alle (bei Filmen/Serien nur deutsche, wenn eingestellt)
    func items(_ k: Kind, category: String) async throws -> [Item] {
        guard let api = api else { return [] }
        if category.isEmpty {
            let all = try await allItems(k)
            if !filterDE(k) { return all }
            let ok = Lang.germanFilter(catCache[k] ?? [])
            return all.filter(ok)
        }
        let key = "\(k.rawValue):\(category)"
        if let c = listCache[key] { return c }
        let items = try await api.items(k, category: category)
        listCache[key] = items
        return items
    }

    /// Suche über ALLE Kategorien: Schreibweise egal, Wörter in beliebiger Reihenfolge,
    /// fehlende Wörter und Tippfehler erlaubt. Beste Treffer zuerst.
    func search(_ k: Kind, _ query: String) async throws -> [Item] {
        let q = Lang.Query(query)
        guard !q.isEmpty else { return [] }
        let all = try await allItems(k)
        let cached = keyCache[k].flatMap { $0.count == all.count && $0.first == (all.first?.id ?? "") ? $0.keys : nil }
        let ok: ((Item) -> Bool)? = filterDE(k) ? Lang.germanFilter(catCache[k] ?? []) : nil
        let (hits, keys) = await Task.detached(priority: .userInitiated) { () -> ([Item], [[UInt8]]) in
            let keys = cached ?? all.map { Lang.key($0.name) }
            var scored: [(s: Int, len: Int, i: Int)] = []
            for i in all.indices {
                let s = Lang.score(q, keys[i])
                if s < 0 { continue }
                if let ok = ok, !ok(all[i]) { continue }
                scored.append((s, keys[i].count, i))
            }
            scored.sort { ($0.s, $0.len, $0.i) < ($1.s, $1.len, $1.i) }
            var seen = Set<String>()
            var out: [Item] = []
            for h in scored {
                let it = all[h.i]
                if seen.insert(it.id).inserted { out.append(it) }
                if out.count >= 300 { break }
            }
            return (out, keys)
        }.value
        if cached == nil { keyCache[k] = (all.count, all.first?.id ?? "", keys) }
        return hits
    }

    func streamURL(_ k: Kind, _ it: Item) -> URL? {
        api?.streamURL(k, id: it.id, ext: k == .live ? "ts" : it.ext)
    }

    // MARK: TV-Programm

    func epgChannel(for it: Item) -> EPGChannel? {
        guard let e = epg, let i = e.map[it.id], i < e.channels.count else { return nil }
        return e.channels[i]
    }

    func loadEPG(force: Bool) {
        guard let api = api else { return }
        if !force, let e = epg, e.server == api.acc.server, Date().timeIntervalSince1970 - e.updated < 6 * 3600 { return }
        if epgLoading { return }
        epgLoading = true
        epgError = nil
        epgTask = Task {
            do {
                let cats = try await self.categoriesRaw(.live)
                let streams = try await self.allItems(.live)
                let data = try await Task.detached(priority: .utility) {
                    try await EPGBuilder.build(api: api, cats: cats, streams: streams)
                }.value
                self.epg = data
                Store.saveEPGCache(data)
            } catch {
                self.epgError = error.localizedDescription
            }
            self.epgLoading = false
        }
    }

    private func categoriesRaw(_ k: Kind) async throws -> [Category] {
        guard let api = api else { return [] }
        if catCache[k] == nil { catCache[k] = try await api.categories(k) }
        return catCache[k] ?? []
    }

    static var epgFile: URL {
        FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0].appendingPathComponent("epg_cache.json")
    }
    static func loadEPGCache() -> EPGData? {
        guard let d = try? Data(contentsOf: epgFile), let e = try? JSONDecoder().decode(EPGData.self, from: d), e.v == 2 else { return nil }
        return e
    }
    static func saveEPGCache(_ e: EPGData) {
        if let d = try? JSONEncoder().encode(e) { try? d.write(to: epgFile) }
    }
    static func deleteEPGCache() { try? FileManager.default.removeItem(at: epgFile) }
}
