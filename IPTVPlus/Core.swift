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
    /// alle Kategorien (category_ids) – ein Titel kann in mehreren stehen
    let categoryIDs: [String]

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
        categoryIDs = (d["category_ids"] as? [Any])?.map { str($0) }.filter { !$0.isEmpty } ?? []
    }

    /// Haupt- und Zusatzkategorien
    var cats: [String] { categoryIDs.isEmpty ? [categoryID] : categoryIDs + [categoryID] }
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
    /// „DE: Titel“, „[DE] Titel“ oder „Titel (DE)“
    static let reTitleDE = rx("^\\s*[\\[|(]?\\s*(de|ger|deu)\\s*[\\]|):\\-]|[\\[(|]\\s*(de|ger|deu)\\s*[\\])|]")
    /// „EN: Titel“, „[FR] Titel“ oder „Titel (EN)“
    static let reTitleForeign = rx("^\\s*[\\[|(]?\\s*(en|eng|uk|us|fr|it|es|tr|pl|nl|ar)\\s*[\\]|):\\-]|[\\[(|]\\s*(en|eng|fr|it|es|tr|pl|nl|ar)\\s*[\\])|]")
    static let reForeignCat = rx("(^|[^a-z])(uk|gb|us|usa|fr|it|es|nl|be|pl|tr|pt|ar|yu|exyu|ex-yu|al|ro|gr|ru|ua|in|pk|af|se|dk|no|fi|hu|cz|sk|hr|rs|ba|bg|ca|au|br|mx|latino|arab|arabic|turkey|turk|france|italy|italia|spain|espana|poland|polska|portugal|netherlands|nederland|kurd|persian|iran|india|africa|asia)([^a-z]|$)")
    /// fremdsprachige Film-/Serienkategorien – ohne Kürzel, die auch deutsche Wörter sind („in“, „es“, „no“ …)
    static let reForeignVodCat = rx("(^|[^a-z])(en|eng|english|uk|gb|us|usa|fr|french|nl|pl|tr|pt|yu|exyu|ex-yu|ro|gr|ru|ua|pk|dk|fi|hu|cz|sk|hr|rs|bg|au|br|mx|latino|arab|arabic|turkey|turk|turkish|france|italy|italia|italian|spain|espana|spanish|poland|polska|portugal|netherlands|nederland|kurd|persian|iran|india|hindi|africa|asia|albania)([^a-z]|$)")
    static let reQPrefix = rx("^\\s*[\\[|(]?\\s*[a-z]{2,3}\\s*[\\]|):\\-]\\s*")
    static let reNonAlnum = rx("[^a-z0-9]+")

    static func normQ(_ s: String) -> String { fold(s).rxReplace(reNonAlnum, "") }

    /// klein, Umlaute ausgeschrieben, Akzente weg (é → e), Sprach-Vorsilbe „DE:“ weg
    static func fold(_ s: String) -> String {
        var x = s.lowercased()
        x = x.replacingOccurrences(of: "ä", with: "ae").replacingOccurrences(of: "ö", with: "oe")
            .replacingOccurrences(of: "ü", with: "ue").replacingOccurrences(of: "ß", with: "ss")
        x = x.folding(options: [.diacriticInsensitive], locale: nil)
        return x.rxReplace(reQPrefix, "")
    }

    /// Lautschrift, damit die genaue Schreibweise egal ist: Schrek = Shrek, Titanik = Titanic, Wikings = Vikings
    static func sound(_ w: String) -> [UInt8] {
        let b = Array(w.utf8)
        let n = b.count
        var o: [UInt8] = []
        o.reserveCapacity(n)
        func ch(_ s: String) -> UInt8 { s.utf8.first! }
        let a_ = ch("a"), z_ = ch("z"), d0 = ch("0"), d9 = ch("9")
        let C = ch("c"), D = ch("d"), E = ch("e"), H = ch("h"), I = ch("i"), K = ch("k"), O = ch("o"), P = ch("p")
        let Q = ch("q"), S = ch("s"), T = ch("t"), U = ch("u"), V = ch("v"), W = ch("w"), X = ch("x"), Y = ch("y"), Z = ch("z"), A = ch("a"), F = ch("f")
        func push(_ c: UInt8) {
            if let l = o.last, l == c, !(c >= d0 && c <= d9) { return }
            o.append(c)
        }
        var i = 0
        while i < n {
            let c = b[i]
            if !((c >= a_ && c <= z_) || (c >= d0 && c <= d9)) { i += 1; continue }
            let d: UInt8 = i + 1 < n ? b[i + 1] : 32
            let e: UInt8 = i + 2 < n ? b[i + 2] : 32
            var step = 2
            if c == S && d == C && e == H { push(S); push(H); step = 3 }
            else if c == C && d == K { push(K) }
            else if c == P && d == H { push(F) }
            else if c == T && d == H { push(T) }
            else if c == Q && d == U { push(K); push(V) }
            else if c == D && d == T { push(T) }
            else if c == A && d == E { push(E) }
            else if c == O && d == E { push(O) }
            else if c == U && d == E { push(U) }
            else if c == I && d == E { push(I) }
            else if c == E && (d == I || d == Y) { push(A); push(I) }
            else {
                step = 1
                switch c {
                case C: push(K)
                case Z: push(S)
                case Y: push(I)
                case W: push(V)
                case X: push(K); push(S)
                default: push(c)
                }
            }
            i += step
        }
        return o
    }

    /// Suchschlüssel eines Titels (einmal pro Liste berechnet)
    static func key(_ title: String) -> [UInt8] { sound(fold(title)) }

    /// erlaubte Tippfehler je Wortlänge
    static func tol(_ len: Int) -> Int { len <= 3 ? 0 : len <= 5 ? 1 : len <= 8 ? 2 : 3 }

    static func contains(_ t: [UInt8], _ p: [UInt8]) -> Bool {
        let m = p.count, n = t.count
        if m == 0 { return true }
        if m > n { return false }
        var i = 0
        while i <= n - m {
            if t[i] == p[0] {
                var j = 1
                while j < m && t[i + j] == p[j] { j += 1 }
                if j == m { return true }
            }
            i += 1
        }
        return false
    }

    /// kleinster Abstand von p zu irgendeinem Stück von t (Levenshtein mit freiem Anfang)
    static func near(_ p: [UInt8], _ t: [UInt8]) -> Int {
        let m = p.count
        var col = Array(0...m)
        var best = m
        for tc in t {
            var diag = col[0]
            col[0] = 0
            for i in 1...m {
                let tmp = col[i]
                var v = diag + (p[i - 1] == tc ? 0 : 1)
                if col[i] + 1 < v { v = col[i] + 1 }
                if col[i - 1] + 1 < v { v = col[i - 1] + 1 }
                col[i] = v
                diag = tmp
            }
            if col[m] < best { best = col[m] }
        }
        return best
    }

    struct Query {
        let whole: [UInt8]
        let words: [[UInt8]]
        init(_ text: String) {
            let f = Lang.fold(text)
            whole = Lang.sound(f)
            var w = f.split(whereSeparator: { !($0.isASCII && ($0.isLetter || $0.isNumber)) }).map { Lang.sound(String($0)) }.filter { !$0.isEmpty }
            if w.count > 1 { w = w.filter { $0.count >= 2 } }
            words = w.isEmpty && !whole.isEmpty ? [whole] : w
        }
        var isEmpty: Bool { whole.isEmpty }
    }

    /// Wie gut passt der Titel? -1 = gar nicht, sonst kleiner = besser.
    /// Wörter dürfen fehlen oder vertauscht sein („haus geldes“ → Haus des Geldes), Tippfehler sind erlaubt.
    static func score(_ q: Query, _ t: [UInt8]) -> Int {
        if q.isEmpty { return -1 }
        if t == q.whole { return 0 }
        if t.starts(with: q.whole) { return 1 }
        if contains(t, q.whole) { return 2 }
        var sum = 0
        var exact = true
        for w in q.words {
            if contains(t, w) { continue }
            exact = false
            let tl = tol(w.count)
            if tl == 0 { return wholeFuzzy(q, t) }
            let d = near(w, t)
            if d > tl { return wholeFuzzy(q, t) }
            sum += d
        }
        return exact ? 3 : 10 + sum
    }

    static func wholeFuzzy(_ q: Query, _ t: [UInt8]) -> Int {
        if q.words.count < 2 { return -1 }
        let tl = tol(q.whole.count)
        if tl == 0 { return -1 }
        let d = near(q.whole, t)
        return d <= tl ? 10 + d : -1
    }

    /// einfacher Treffer-Test (z. B. Filter innerhalb einer Kategorie)
    static func matches(_ query: String, _ title: String) -> Bool { score(Query(query), key(title)) >= 0 }

    /// Deutsch-Prüfung; die Kategorien werden nur einmal ausgewertet.
    /// „EN: …“ → nein, „DE: …“ → ja, in irgendeiner deutschen Kategorie → ja,
    /// nur in klar fremdsprachigen Kategorien → nein, neutrale Kategorien („Netflix“, „Kinder“) → ja
    static func germanFilter(_ cats: [Category]) -> (Item) -> Bool {
        let de = Set(cats.filter { $0.name.rxMatch(reGerman) }.map { $0.id })
        let foreign = Set(cats.filter { !de.contains($0.id) && $0.name.rxMatch(reForeignVodCat) }.map { $0.id })
        return { it in
            if it.name.rxMatch(reTitleForeign) { return false }
            if de.isEmpty || it.name.rxMatch(reTitleDE) { return true }
            let ids = it.cats
            if ids.contains(where: { de.contains($0) }) { return true }
            return !ids.contains(where: { foreign.contains($0) })
        }
    }
}
