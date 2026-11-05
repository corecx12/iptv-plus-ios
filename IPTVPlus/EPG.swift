import Foundation
import Compression

// MARK: - Datenmodell TV-Programm

struct Prog: Codable, Hashable {
    let s: Double
    let e: Double
    let t: String
    let d: String
}

struct EPGChannel: Codable, Identifiable, Hashable {
    var id: String { "\(streamID)|\(name)" }
    var name: String
    var icon: String
    var streamID: String
    var num: Int
    var progs: [Prog]

    func current(at t: Double = Date().timeIntervalSince1970) -> Prog? {
        progs.first { $0.s <= t && $0.e > t }
    }
    func upcoming(from t: Double = Date().timeIntervalSince1970) -> [Prog] {
        progs.filter { $0.e > t }
    }
}

struct EPGData: Codable {
    var v: Int
    var updated: Double
    var source: String
    var server: String
    var channels: [EPGChannel]
    var map: [String: Int]   // stream_id -> Index in channels
}

// MARK: - Senderbezeichnung vergleichbar machen

enum Canon {
    static let rePrefix = rx("^\\s*(\\[[^\\]]*\\]|\\|[^|]*\\||[a-z0-9]{2,8}\\s*[:|]\\s*)")
    static let reParen = rx("\\([^)]*\\)|\\[[^\\]]*\\]")
    static let reTokens = rx("\\b(hd|fhd|uhd|sd|4k|8k|hevc|h\\.?265|h\\.?264|raw|backup|ger|deu|de|germany|deutschland|50fps|60fps|vip|plus\\+?|multi|audio|fernsehen|television|\\d{3,4}p)\\b")
    static let reNonAlnum = rx("[^a-z0-9]+")
    static let aliases: [String: String] = [
        "ard": "daserste", "erstes": "daserste", "daserstes": "daserste",
        "rtlii": "rtlzwei", "rtl2": "rtlzwei",
        "pro7": "prosieben", "pro7maxx": "prosiebenmaxx", "pro7fun": "prosiebenfun",
        "kabel1": "kabeleins", "kabel1doku": "kabeleinsdoku", "kabel1classics": "kabeleinsclassics",
        "ntvnachrichten": "ntv", "rtlnitro": "nitro", "weltn24": "welt", "n24": "welt",
        "eurosport": "eurosport1", "disney": "disneychannel", "nickelodeon": "nick",
        "bayerisches": "br", "bayerischesfernsehen": "br", "bralpha": "ardalpha", "alpha": "ardalpha",
        "hse24": "hse", "hse24extra": "hseextra", "servustvdeutschland": "servustv", "servustvdetschland": "servustv",
        "servustvtschland": "servustv", "radiobrementv": "radiobremen", "sixxtv": "sixx", "voxtv": "vox",
        "tele5tv": "tele5", "toggo": "toggoplus",
    ]
    static let regional: Set<String> = ["", "nord", "sued", "ost", "west", "aachen", "bielefeld", "dortmund", "duesseldorf",
        "essen", "koeln", "muenster", "siegen", "wuppertal", "bonn", "duisburg", "sachsen", "sachsenanhalt", "thueringen",
        "berlin", "brandenburg", "hamburg", "niedersachsen", "schleswigholstein", "sh", "mv", "mecklenburgvorpommern", "bw",
        "rp", "badenwuerttemberg", "rheinlandpfalz", "hannover", "bayern", "franken", "schwaben", "muenchen", "oberbayern",
        "niederbayern", "unterfranken", "saarland", "hessen"]
    static let regionalPrefixes = ["wdr", "ndr", "mdr", "rbb", "swr", "br", "hr", "sr"]

    static func stripPrefix(_ s: String) -> String {
        var x = s
        for _ in 0..<3 {
            let t = x.rxReplace(rePrefix, "")
            if t == x { break }
            x = t
        }
        return x
    }

    static func key(_ name: String) -> String {
        var s = stripPrefix(name).rxReplace(reParen, " ").lowercased()
        s = s.replacingOccurrences(of: "ä", with: "ae").replacingOccurrences(of: "ö", with: "oe")
            .replacingOccurrences(of: "ü", with: "ue").replacingOccurrences(of: "ß", with: "ss")
        s = s.rxReplace(reTokens, " ")
        var n = s.rxReplace(reNonAlnum, "")
        if let a = aliases[n] { n = a }
        for p in regionalPrefixes where n.hasPrefix(p) {
            if regional.contains(String(n.dropFirst(p.count))) { return p }
        }
        return n
    }

    static func clean(_ name: String) -> String {
        let s = stripPrefix(name).trimmingCharacters(in: .whitespaces)
        return s.isEmpty ? name : s
    }
}

// MARK: - gzip entpacken (öffentliches EPG kommt als .xml.gz)

func gunzip(_ data: Data) -> Data? {
    guard data.count > 18, data[0] == 0x1f, data[1] == 0x8b else { return data }
    let bytes = [UInt8](data)
    var idx = 10
    let flg = bytes[3]
    if flg & 4 != 0 { idx += 2 + (Int(bytes[idx]) | Int(bytes[idx + 1]) << 8) }
    if flg & 8 != 0 { while idx < bytes.count && bytes[idx] != 0 { idx += 1 }; idx += 1 }
    if flg & 16 != 0 { while idx < bytes.count && bytes[idx] != 0 { idx += 1 }; idx += 1 }
    if flg & 2 != 0 { idx += 2 }
    guard idx < bytes.count - 8 else { return nil }
    let input = Array(bytes[idx..<(bytes.count - 8)])

    let sp = UnsafeMutablePointer<compression_stream>.allocate(capacity: 1)
    defer { sp.deallocate() }
    guard compression_stream_init(sp, COMPRESSION_STREAM_DECODE, COMPRESSION_ZLIB) == COMPRESSION_STATUS_OK else { return nil }
    defer { compression_stream_destroy(sp) }
    let bufSize = 1 << 20
    let dst = UnsafeMutablePointer<UInt8>.allocate(capacity: bufSize)
    defer { dst.deallocate() }
    var out = Data()
    return input.withUnsafeBufferPointer { buf -> Data? in
        guard let base = buf.baseAddress else { return nil }
        sp.pointee.src_ptr = base
        sp.pointee.src_size = buf.count
        while true {
            sp.pointee.dst_ptr = dst
            sp.pointee.dst_size = bufSize
            let st = compression_stream_process(sp, Int32(COMPRESSION_STREAM_FINALIZE.rawValue))
            out.append(dst, count: bufSize - sp.pointee.dst_size)
            if st == COMPRESSION_STATUS_END { return out }
            if st == COMPRESSION_STATUS_ERROR { return out.isEmpty ? nil : out }
            if sp.pointee.src_size == 0 && sp.pointee.dst_size == bufSize { return out }
        }
    }
}

// MARK: - XMLTV-Parser (streamend)

final class XMLTVParser: NSObject, XMLParserDelegate {
    var from: Double = 0
    var to: Double = 0
    var wants: (String) -> Bool = { _ in false }
    var onChannel: ((String, String) -> Void)?
    var onProg: (String, Prog) -> Void = { _, _ in }

    private var text = ""
    private var collect = false
    private var chID = ""
    private var inChannel = false
    private var inProg = false
    private var pChannel = "", pStart = "", pStop = ""
    private var title = "", titleDE = "", desc = "", descDE = "", lang = ""
    private var count = 0

    static let fmtTZ: DateFormatter = {
        let f = DateFormatter(); f.locale = Locale(identifier: "en_US_POSIX"); f.dateFormat = "yyyyMMddHHmmss Z"; return f
    }()
    static let fmtTZ2: DateFormatter = {
        let f = DateFormatter(); f.locale = Locale(identifier: "en_US_POSIX"); f.dateFormat = "yyyyMMddHHmmssZ"; return f
    }()
    static let fmtLocal: DateFormatter = {
        let f = DateFormatter(); f.locale = Locale(identifier: "en_US_POSIX"); f.dateFormat = "yyyyMMddHHmmss"; return f
    }()

    static func time(_ s: String) -> Double {
        let x = s.trimmingCharacters(in: .whitespaces)
        if x.count < 14 { return 0 }
        if let d = fmtTZ.date(from: x) { return d.timeIntervalSince1970 }
        if let d = fmtTZ2.date(from: x) { return d.timeIntervalSince1970 }
        if let d = fmtLocal.date(from: String(x.prefix(14))) { return d.timeIntervalSince1970 }
        return 0
    }

    func parse(data: Data) -> Int {
        let p = XMLParser(data: data)
        p.delegate = self
        p.parse()
        return count
    }

    func parse(file: URL) -> Int {
        guard let s = InputStream(url: file) else { return 0 }
        let p = XMLParser(stream: s)
        p.delegate = self
        p.parse()
        return count
    }

    func parser(_ parser: XMLParser, didStartElement name: String, namespaceURI: String?, qualifiedName: String?, attributes a: [String: String] = [:]) {
        switch name {
        case "channel":
            inChannel = true
            chID = (a["id"] ?? "").lowercased().trimmingCharacters(in: .whitespaces)
        case "display-name":
            if inChannel && onChannel != nil { collect = true; text = "" }
        case "programme":
            pChannel = (a["channel"] ?? "").lowercased().trimmingCharacters(in: .whitespaces)
            inProg = wants(pChannel)
            if inProg {
                pStart = a["start"] ?? ""; pStop = a["stop"] ?? ""
                title = ""; titleDE = ""; desc = ""; descDE = ""
            }
        case "title", "desc":
            if inProg { collect = true; text = ""; lang = (a["lang"] ?? "").lowercased() }
        default: break
        }
    }

    func parser(_ parser: XMLParser, foundCharacters string: String) {
        if collect { text += string }
    }

    func parser(_ parser: XMLParser, didEndElement name: String, namespaceURI: String?, qualifiedName: String?) {
        switch name {
        case "display-name":
            if collect { onChannel?(chID, text.trimmingCharacters(in: .whitespacesAndNewlines)) }
            collect = false
        case "channel":
            inChannel = false
        case "title":
            if inProg && collect {
                let v = text.trimmingCharacters(in: .whitespacesAndNewlines)
                if lang.hasPrefix("de") { titleDE = v } else if title.isEmpty { title = v }
            }
            collect = false
        case "desc":
            if inProg && collect {
                let v = text.trimmingCharacters(in: .whitespacesAndNewlines)
                if lang.hasPrefix("de") { descDE = v } else if desc.isEmpty { desc = v }
            }
            collect = false
        case "programme":
            if inProg {
                let s = XMLTVParser.time(pStart)
                var e = XMLTVParser.time(pStop)
                if e == 0 { e = s + 1800 }
                if s > 0 && e >= from && s <= to {
                    let t = titleDE.isEmpty ? title : titleDE
                    var d = descDE.isEmpty ? desc : descDE
                    if d.count > 400 { d = String(d.prefix(400)) + "…" }
                    onProg(pChannel, Prog(s: s, e: e, t: t, d: d))
                    count += 1
                }
            }
            inProg = false
        default: break
        }
    }
}

// MARK: - TV-Programm zusammenbauen (Anbieter + öffentliches deutsches EPG)

enum EPGBuilder {
    static let fallbackURL = URL(string: "https://epgshare01.online/epgshare01/epg_ripper_DE1.xml.gz")!

    final class Cand {
        var ch: EPGChannel
        var epgID: String
        var key: String
        var german: Bool
        init(ch: EPGChannel, epgID: String, key: String, german: Bool) {
            self.ch = ch; self.epgID = epgID; self.key = key; self.german = german
        }
    }

    static func download(_ url: URL) async throws -> Data {
        var req = URLRequest(url: url)
        req.setValue(userAgent, forHTTPHeaderField: "User-Agent")
        req.timeoutInterval = 300
        let (file, resp) = try await URLSession.shared.download(for: req)
        if let h = resp as? HTTPURLResponse, h.statusCode != 200 { throw APIError.http(h.statusCode) }
        let data = try Data(contentsOf: file, options: .mappedIfSafe)
        return gunzip(data) ?? data
    }

    static func build(api: XtreamAPI, cats: [Category], streams: [Item]) async throws -> EPGData {
        var de = Set<String>(), foreign = Set<String>()
        for c in cats {
            if c.name.rxMatch(Lang.reGerman) { de.insert(c.id) } else if c.name.rxMatch(Lang.reForeignCat) { foreign.insert(c.id) }
        }
        var list: [Cand] = []
        var byEPG: [String: Cand] = [:], byKey: [String: Cand] = [:], streamMap: [String: Cand] = [:]
        let sorted = streams.enumerated().sorted { a, b in
            let da = de.contains(a.element.categoryID), db = de.contains(b.element.categoryID)
            return da != db ? da : a.offset < b.offset
        }.map { $0.element }
        for s in sorted where !foreign.contains(s.categoryID) {
            let k = Canon.key(s.name)
            let german = de.contains(s.categoryID) || s.epgID.hasSuffix(".de")
            var cd: Cand? = s.epgID.isEmpty ? nil : byEPG[s.epgID]
            if cd == nil && !k.isEmpty { cd = byKey[k] }
            if cd == nil {
                if k.isEmpty { continue }
                let c = Cand(ch: EPGChannel(name: Canon.clean(s.name), icon: s.icon, streamID: s.id, num: s.num, progs: []),
                             epgID: s.epgID, key: k, german: german)
                list.append(c); cd = c
            }
            guard let c = cd else { continue }
            if german { c.german = true }
            if c.epgID.isEmpty && !s.epgID.isEmpty { c.epgID = s.epgID }
            if c.ch.icon.isEmpty { c.ch.icon = s.icon }
            if !s.epgID.isEmpty && byEPG[s.epgID] == nil { byEPG[s.epgID] = c }
            if !k.isEmpty && byKey[k] == nil { byKey[k] = c }
            streamMap[s.id] = c
        }

        let now = Date().timeIntervalSince1970
        let from = now - 3 * 3600, to = now + 44 * 3600
        var progs: [ObjectIdentifier: [Prog]] = [:]
        var nProv = 0, nPub = 0

        // 1) Programm vom Anbieter
        var idMap: [String: Cand] = [:]
        for c in list where !c.epgID.isEmpty { idMap[c.epgID] = c }
        if !idMap.isEmpty, let data = try? await download(api.xmltvURL) {
            let p = XMLTVParser()
            p.from = from; p.to = to
            p.wants = { idMap[$0] != nil }
            p.onProg = { id, prog in if let c = idMap[id] { progs[ObjectIdentifier(c), default: []].append(prog) } }
            _ = p.parse(data: data)
            nProv = list.filter { progs[ObjectIdentifier($0)] != nil }.count
        }

        // 2) Lücken mit öffentlichem deutschen Programm füllen (Zuordnung über den Namen)
        var missing: [String: Cand] = [:]
        for c in list where progs[ObjectIdentifier(c)] == nil { missing[c.key] = c }
        var filled = Set<ObjectIdentifier>()
        if !missing.isEmpty, let data = try? await download(fallbackURL) {
            var pubMap: [String: Cand] = [:]
            let p = XMLTVParser()
            p.from = from; p.to = to
            p.onChannel = { id, name in
                if pubMap[id] != nil { return }
                let k = Canon.key(name)
                if let c = missing[k], !filled.contains(ObjectIdentifier(c)) {
                    pubMap[id] = c; filled.insert(ObjectIdentifier(c))
                }
            }
            p.wants = { pubMap[$0] != nil }
            p.onProg = { id, prog in if let c = pubMap[id] { progs[ObjectIdentifier(c), default: []].append(prog) } }
            _ = p.parse(data: data)
            nPub = filled.filter { progs[$0] != nil }.count
        }

        var out: [EPGChannel] = []
        var index: [ObjectIdentifier: Int] = [:]
        for c in list {
            guard var ps = progs[ObjectIdentifier(c)], !ps.isEmpty else { continue }
            if !c.german && !filled.contains(ObjectIdentifier(c)) { continue }
            ps.sort { $0.s < $1.s }
            var dd: [Prog] = []
            for p in ps where dd.last?.s != p.s { dd.append(p) }
            var ch = c.ch
            ch.progs = dd
            index[ObjectIdentifier(c)] = out.count
            out.append(ch)
        }
        // nach Sendernummer sortieren
        let order = out.indices.sorted { a, b in
            let na = out[a].num == 0 ? Int.max : out[a].num, nb = out[b].num == 0 ? Int.max : out[b].num
            return na != nb ? na < nb : out[a].name.lowercased() < out[b].name.lowercased()
        }
        var remap: [Int: Int] = [:]
        var final: [EPGChannel] = []
        for (newI, oldI) in order.enumerated() { remap[oldI] = newI; final.append(out[oldI]) }
        var map: [String: Int] = [:]
        for (sid, c) in streamMap {
            if let i = index[ObjectIdentifier(c)], let j = remap[i] { map[sid] = j }
        }
        if final.isEmpty { throw APIError.format }
        let src = nPub > 0 ? "Anbieter (\(nProv)) + epgshare01 (\(nPub))" : (nProv > 0 ? "Anbieter" : "epgshare01")
        return EPGData(v: 2, updated: now, source: src, server: api.acc.server, channels: final, map: map)
    }
}
