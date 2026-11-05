import Foundation
import Security

// MARK: - Hilfsfunktionen

let userAgent = "VLC/3.0.20 LibVLC/3.0.20"

func str(_ v: Any?) -> String {
    if let s = v as? String { return s }
    if let n = v as? NSNumber { return n.stringValue }
    return ""
}

func rx(_ pattern: String, _ opts: NSRegularExpression.Options = [.caseInsensitive]) -> NSRegularExpression {
    return try! NSRegularExpression(pattern: pattern, options: opts)
}

extension String {
    func rxMatch(_ re: NSRegularExpression) -> Bool {
        return re.firstMatch(in: self, options: [], range: NSRange(startIndex..., in: self)) != nil
    }
    func rxReplace(_ re: NSRegularExpression, _ with: String) -> String {
        return re.stringByReplacingMatches(in: self, options: [], range: NSRange(startIndex..., in: self), withTemplate: with)
    }
}

// MARK: - Modelle

struct Account: Codable, Equatable {
    var server: String = ""
    var username: String = ""
    var password: String = ""

    static func normalize(_ s: String) -> String {
        var x = s.trimmingCharacters(in: .whitespacesAndNewlines)
        while x.hasSuffix("/") { x.removeLast() }
        if !x.isEmpty && !x.lowercased().hasPrefix("http://") && !x.lowercased().hasPrefix("https://") { x = "http://" + x }
        for suffix in ["/player_api.php", "/get.php"] where x.hasSuffix(suffix) { x = String(x.dropLast(suffix.count)) }
        return x
    }
}

enum Kind: String {
    case live, movie, series
    var catAction: String {
        switch self { case .live: return "get_live_categories"; case .movie: return "get_vod_categories"; case .series: return "get_series_categories" }
    }
    var listAction: String {
        switch self { case .live: return "get_live_streams"; case .movie: return "get_vod_streams"; case .series: return "get_series" }
    }
}

struct Category: Identifiable, Hashable {
    let id: String
    let name: String
}

struct Item: Identifiable, Hashable {
    let id: String
    let name: String
    let icon: String
    let categoryID: String
    let ext: String
    let num: Int
    let epgID: String
    let year: String
    let rating: String

    init(_ d: [String: Any], kind: Kind) {
        id = kind == .series ? str(d["series_id"]) : str(d["stream_id"])
        name = str(d["name"])
        icon = kind == .live ? str(d["stream_icon"]) : (kind == .series ? str(d["cover"]) : str(d["stream_icon"]))
        categoryID = str(d["category_id"])
        ext = str(d["container_extension"])
        num = Int(str(d["num"])) ?? 0
        epgID = str(d["epg_channel_id"]).lowercased().trimmingCharacters(in: .whitespaces)
        let y = str(d["year"])
        year = y.isEmpty ? String(str(d["releaseDate"]).prefix(4)) : y
        rating = str(d["rating"])
    }
}

enum APIError: LocalizedError {
    case http(Int), auth, format
    var errorDescription: String? {
        switch self {
        case .http(let c): return "Server antwortet mit HTTP \(c)"
        case .auth: return "Login abgelehnt – Benutzername/Passwort oder Server prüfen."
        case .format: return "Unerwartete Antwort vom Server"
        }
    }
}

// MARK: - Xtream-API

struct XtreamAPI {
    let acc: Account

    func endpoint(_ params: [String: String]) -> URL {
        var c = URLComponents(string: acc.server + "/player_api.php")!
        var q = [URLQueryItem(name: "username", value: acc.username), URLQueryItem(name: "password", value: acc.password)]
        for (k, v) in params { q.append(URLQueryItem(name: k, value: v)) }
        c.queryItems = q
        return c.url!
    }

    func json(_ params: [String: String]) async throws -> Any {
        var req = URLRequest(url: endpoint(params))
        req.setValue(userAgent, forHTTPHeaderField: "User-Agent")
        req.timeoutInterval = 90
        let (data, resp) = try await URLSession.shared.data(for: req)
        if let h = resp as? HTTPURLResponse, h.statusCode != 200 { throw APIError.http(h.statusCode) }
        return try JSONSerialization.jsonObject(with: data, options: [.fragmentsAllowed])
    }

    func userInfo() async throws -> [String: Any] {
        guard let d = try await json([:]) as? [String: Any], let u = d["user_info"] as? [String: Any] else { throw APIError.auth }
        if str(u["auth"]) != "1" { throw APIError.auth }
        return u
    }

    func categories(_ k: Kind) async throws -> [Category] {
        let arr = (try await json(["action": k.catAction]) as? [[String: Any]]) ?? []
        return arr.map { Category(id: str($0["category_id"]), name: str($0["category_name"])) }
    }

    func items(_ k: Kind, category: String? = nil) async throws -> [Item] {
        var p = ["action": k.listAction]
        if let c = category, !c.isEmpty { p["category_id"] = c }
        let arr = (try await json(p) as? [[String: Any]]) ?? []
        return arr.map { Item($0, kind: k) }
    }

    func info(_ action: String, _ key: String, _ id: String) async throws -> [String: Any] {
        return (try await json(["action": action, key: id]) as? [String: Any]) ?? [:]
    }

    private func enc(_ s: String) -> String {
        var allowed = CharacterSet.urlPathAllowed
        allowed.remove(charactersIn: "/?#")
        return s.addingPercentEncoding(withAllowedCharacters: allowed) ?? s
    }

    func streamURL(_ k: Kind, id: String, ext: String) -> URL {
        let e = ext.isEmpty ? (k == .live ? "ts" : "mp4") : ext
        return URL(string: "\(acc.server)/\(k.rawValue)/\(enc(acc.username))/\(enc(acc.password))/\(id).\(e)")!
    }

    var xmltvURL: URL {
        var c = URLComponents(string: acc.server + "/xmltv.php")!
        c.queryItems = [URLQueryItem(name: "username", value: acc.username), URLQueryItem(name: "password", value: acc.password)]
        return c.url!
    }
}

// MARK: - Schlüsselbund (Zugangsdaten sicher speichern)

enum Keychain {
    static let service = "iptvplus.account"

    static func save(_ acc: Account?) {
        let base: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service]
        SecItemDelete(base as CFDictionary)
        guard let acc = acc, let data = try? JSONEncoder().encode(acc) else { return }
        var add = base
        add[kSecValueData as String] = data
        add[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlock
        SecItemAdd(add as CFDictionary, nil)
    }

    static func load() -> Account? {
        let q: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service,
                                kSecReturnData as String: true, kSecMatchLimit as String: kSecMatchLimitOne]
        var out: AnyObject?
        guard SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess, let d = out as? Data else { return nil }
        return try? JSONDecoder().decode(Account.self, from: d)
    }

    // Mehrere Konten (als eigener Schlüsselbund-Eintrag)
    static let accountsService = "iptvplus.accounts"

    static func saveAccounts(_ list: [SavedAccount]) {
        let base: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: accountsService]
        SecItemDelete(base as CFDictionary)
        guard !list.isEmpty, let data = try? JSONEncoder().encode(list) else { return }
        var add = base
        add[kSecValueData as String] = data
        add[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlock
        SecItemAdd(add as CFDictionary, nil)
    }

    static func loadAccounts() -> [SavedAccount] {
        let q: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: accountsService,
                                kSecReturnData as String: true, kSecMatchLimit as String: kSecMatchLimitOne]
        var out: AnyObject?
        if SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess, let d = out as? Data,
           let list = try? JSONDecoder().decode([SavedAccount].self, from: d) {
            return list
        }
        // einzelnes Konto aus älterer Version übernehmen
        if let old = load() {
            let migrated = [SavedAccount(label: "Konto 1", account: old)]
            saveAccounts(migrated)
            save(nil)
            return migrated
        }
        return []
    }
}

// MARK: - Deutsch-Erkennung und Suche

enum Lang {
    static let reGerman = rx("(^|[^a-z])(de|ger|deu)([^a-z]|$)|deutsch|germany|österreich|schweiz")
    static let reTitleDE = rx("^\\s*[\\[|(]?\\s*(de|ger|deu)\\s*[\\]|):\\-]")
    static let reTitleForeign = rx("^\\s*[\\[|(]?\\s*(en|eng|uk|us|fr|it|es|tr|pl|nl|ar|multi)\\s*[\\]|):\\-]")
    static let reForeignCat = rx("(^|[^a-z])(uk|gb|us|usa|fr|it|es|nl|be|pl|tr|pt|ar|yu|exyu|ex-yu|al|ro|gr|ru|ua|in|pk|af|se|dk|no|fi|hu|cz|sk|hr|rs|ba|bg|ca|au|br|mx|latino|arab|arabic|turkey|turk|france|italy|italia|spain|espana|poland|polska|portugal|netherlands|nederland|kurd|persian|iran|india|africa|asia)([^a-z]|$)")
    static let reQPrefix = rx("^\\s*[\\[|(]?\\s*[a-z]{2,3}\\s*[\\]|):\\-]\\s*")
    static let reNonAlnum = rx("[^a-z0-9]+")

    static func normQ(_ s: String) -> String {
        var x = s.lowercased()
        x = x.replacingOccurrences(of: "ä", with: "ae").replacingOccurrences(of: "ö", with: "oe")
            .replacingOccurrences(of: "ü", with: "ue").replacingOccurrences(of: "ß", with: "ss")
        x = x.rxReplace(reQPrefix, "")
        return x.rxReplace(reNonAlnum, "")
    }

    static func lev(_ a: [Character], _ b: [Character]) -> Int {
        if a.isEmpty { return b.count }
        if b.isEmpty { return a.count }
        var prev = Array(0...b.count)
        var cur = Array(repeating: 0, count: b.count + 1)
        for i in 1...a.count {
            cur[0] = i
            for j in 1...b.count {
                cur[j] = min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (a[i - 1] == b[j - 1] ? 0 : 1))
            }
            swap(&prev, &cur)
        }
        return prev[b.count]
    }

    /// Tippfehler-tolerant: "spiederman" findet "Spider-Man"
    static func fuzzy(_ q: String, _ t: String) -> Bool {
        let qa = Array(q), ta = Array(t)
        let tol = qa.count >= 8 ? 2 : 1
        for L in max(3, qa.count - tol)...(qa.count + tol) where L <= ta.count {
            var i = 0
            while i + L <= ta.count {
                if lev(qa, Array(ta[i..<(i + L)])) <= tol { return true }
                i += 1
            }
        }
        return false
    }
}
