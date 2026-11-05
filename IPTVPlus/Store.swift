import Foundation
import SwiftUI

@MainActor
final class Store: ObservableObject {
    @Published var account: Account?
    @Published var userInfo: [String: String] = [:]
    @Published var deOnly: Bool {
        didSet { UserDefaults.standard.set(deOnly, forKey: "deOnly"); listCache = listCache.filter { !$0.key.hasPrefix("de:") } }
    }
    @Published var epg: EPGData?
    @Published var epgLoading = false
    @Published var epgError: String?

    private var catCache: [Kind: [Category]] = [:]
    private var listCache: [String: [Item]] = [:]
    private var epgTask: Task<Void, Never>?

    var api: XtreamAPI? { account.map { XtreamAPI(acc: $0) } }

    init() {
        deOnly = UserDefaults.standard.object(forKey: "deOnly") as? Bool ?? true
        account = Keychain.load()
        epg = Store.loadEPGCache()
    }

    // MARK: Konto

    func login(server: String, username: String, password: String) async throws {
        var pw = password.trimmingCharacters(in: .whitespaces)
        if pw.isEmpty, let old = account?.password { pw = old }
        let acc = Account(server: Account.normalize(server), username: username.trimmingCharacters(in: .whitespaces), password: pw)
        let info = try await XtreamAPI(acc: acc).userInfo()
        let changed = acc != account
        Keychain.save(acc)
        account = acc
        userInfo = info.mapValues { str($0) }
        if changed { catCache = [:]; listCache = [:]; epg = nil; Store.deleteEPGCache() }
        loadEPG(force: false)
    }

    func refreshUserInfo() async {
        guard let api = api else { return }
        if let info = try? await api.userInfo() { userInfo = info.mapValues { str($0) } }
    }

    func logout() {
        PlayerModel.shared.stop()
        Keychain.save(nil)
        account = nil
        userInfo = [:]
        catCache = [:]; listCache = [:]
        epg = nil
        Store.deleteEPGCache()
    }

    // MARK: Listen

    func germanCatIDs(_ k: Kind) -> Set<String> {
        Set((catCache[k] ?? []).filter { $0.name.rxMatch(Lang.reGerman) }.map { $0.id })
    }

    func isGerman(_ it: Item, _ k: Kind) -> Bool {
        if it.name.rxMatch(Lang.reTitleForeign) { return false }
        if it.name.rxMatch(Lang.reTitleDE) { return true }
        let ids = germanCatIDs(k)
        return ids.isEmpty ? true : ids.contains(it.categoryID)
    }

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
            return filterDE(k) ? all.filter { isGerman($0, k) } : all
        }
        let key = "\(k.rawValue):\(category)"
        if let c = listCache[key] { return c }
        let items = try await api.items(k, category: category)
        listCache[key] = items
        return items
    }

    /// Suche über ALLE Kategorien, Schreibweise egal, Tippfehler-tolerant
    func search(_ k: Kind, _ query: String) async throws -> [Item] {
        let q = Lang.normQ(query)
        guard !q.isEmpty else { return [] }
        var all = try await allItems(k)
        if filterDE(k) { all = all.filter { isGerman($0, k) } }
        let hits = all.filter { Lang.normQ($0.name).contains(q) }
        if !hits.isEmpty || q.count < 4 { return hits }
        return await Task.detached(priority: .userInitiated) {
            Array(all.filter { Lang.fuzzy(q, Lang.normQ($0.name)) }.prefix(300))
        }.value
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
