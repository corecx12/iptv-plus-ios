import SwiftUI
import AVFoundation
import MobileVLCKit

/// Ein einziger Player für die ganze App (Anbieter erlaubt nur eine Verbindung).
/// VLC spielt jedes Format direkt: AC3, E-AC3, MP2, HEVC, Zeilensprung, MKV …
final class PlayerModel: ObservableObject {
    static let shared = PlayerModel()

    let player = VLCMediaPlayer()
    @Published var status: String = ""
    @Published var playing = false
    private var timer: Timer?
    private var lastURL: URL?
    private var errorCount = 0

    private init() {
        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .moviePlayback)
        try? AVAudioSession.sharedInstance().setActive(true)
    }

    func attach(_ view: UIView) {
        player.drawable = view
    }

    func play(_ url: URL, live: Bool) {
        player.stop()
        lastURL = url
        errorCount = 0
        let m = VLCMedia(url: url)
        m.addOptions([
            "network-caching": live ? 1500 : 3000,
            "http-user-agent": userAgent,
            "http-reconnect": true,
            "deinterlace": 1,
            "deinterlace-mode": "yadif",
        ])
        player.media = m
        player.play()
        status = "Verbinde…"
        startPolling()
    }

    func stop() {
        timer?.invalidate()
        timer = nil
        player.stop()
        playing = false
        status = ""
    }

    func togglePause() {
        if player.isPlaying { player.pause() } else { player.play() }
    }

    private func startPolling() {
        timer?.invalidate()
        timer = Timer.scheduledTimer(withTimeInterval: 0.5, repeats: true) { [weak self] _ in
            guard let self = self else { return }
            switch self.player.state {
            case .playing:
                self.playing = true
                self.status = ""
            case .buffering, .opening:
                if !self.player.isPlaying { self.status = "Verbinde…" } else { self.status = "" }
            case .error:
                self.playing = false
                self.errorCount += 1
                // kurz warten und erneut versuchen (z. B. Verbindungslimit beim Senderwechsel)
                if self.errorCount <= 3, let u = self.lastURL {
                    self.status = "Verbindung wird freigegeben… (Versuch \(self.errorCount + 1))"
                    let n = self.errorCount
                    DispatchQueue.main.asyncAfter(deadline: .now() + 1.5 * Double(n)) {
                        guard self.lastURL == u else { return }
                        let m = VLCMedia(url: u)
                        m.addOptions(["network-caching": 1500, "http-user-agent": userAgent, "deinterlace": 1, "deinterlace-mode": "yadif"])
                        self.player.media = m
                        self.player.play()
                    }
                } else {
                    self.status = "Sender lässt sich gerade nicht abspielen – evtl. offline oder Verbindungslimit erreicht."
                }
            case .ended, .stopped:
                self.playing = false
            case .paused:
                self.playing = false
            default:
                break
            }
        }
    }
}

struct VLCVideoView: UIViewRepresentable {
    func makeUIView(context: Context) -> UIView {
        let v = UIView()
        v.backgroundColor = .black
        PlayerModel.shared.attach(v)
        return v
    }
    func updateUIView(_ uiView: UIView, context: Context) {}
}

/// Was abgespielt werden soll
struct PlayRequest: Identifiable {
    let id = UUID()
    var title: String
    var kind: Kind
    var item: Item
    var zapList: [Item] = []
    var subtitle: String = ""
}

/// Steuert, was läuft und ob der Player groß oder als Mini-Fenster angezeigt wird
final class PlayerHost: ObservableObject {
    static let shared = PlayerHost()
    @Published var request: PlayRequest?
    @Published var expanded = true

    func open(_ r: PlayRequest) {
        request = r
        expanded = true
    }
    func close() {
        PlayerModel.shared.stop()
        request = nil
    }
}

/// Liegt über der ganzen App: groß (Vollansicht) oder klein in der Ecke (Mini-Player).
/// Das Videobild bleibt dabei dasselbe – die Wiedergabe läuft beim Verkleinern weiter.
struct PlayerOverlay: View {
    @EnvironmentObject var store: Store
    @ObservedObject var host = PlayerHost.shared
    @ObservedObject var pm = PlayerModel.shared
    @State private var tick = Date()
    @State private var openDesc: Set<Double> = []
    @State private var startedID: UUID?
    @State private var dragOffset: CGSize = .zero
    private let clock = Timer.publish(every: 30, on: .main, in: .common).autoconnect()

    var body: some View {
        GeometryReader { geo in
            let landscape = geo.size.width > geo.size.height
            let full = host.expanded
            let miniW: CGFloat = min(240, geo.size.width * 0.55)
            let miniH: CGFloat = miniW * 9 / 16
            ZStack(alignment: full ? .top : .bottomTrailing) {
                if full { Color(.systemBackground).ignoresSafeArea() }
                VStack(spacing: 0) {
                    videoBox(full: full, landscape: landscape)
                        .frame(width: full ? geo.size.width : miniW,
                               height: full ? (landscape ? geo.size.height : geo.size.width * 9 / 16) : miniH)
                        .clipShape(RoundedRectangle(cornerRadius: full ? 0 : 12))
                        .shadow(radius: full ? 0 : 8)
                    if full && !landscape { infoPanel }
                }
                .frame(width: full ? geo.size.width : miniW, height: full ? geo.size.height : miniH)
                .offset(full ? .zero : dragOffset)
                .padding(.trailing, full ? 0 : 12)
                .padding(.bottom, full ? 0 : 62)
                .gesture(full ? nil : DragGesture()
                    .onChanged { v in dragOffset = v.translation }
                    .onEnded { v in
                        // nach unten wischen schließt, sonst zurück in die Ecke
                        if v.translation.height > 120 { host.close() }
                        dragOffset = .zero
                    })
            }
            .frame(width: geo.size.width, height: geo.size.height, alignment: full ? .top : .bottomTrailing)
            .animation(.spring(response: 0.35, dampingFraction: 0.85), value: full)
        }
        .ignoresSafeArea(.container, edges: host.expanded ? [.horizontal] : [])
        .onReceive(clock) { tick = $0 }
        .onAppear { startIfNeeded() }
        .onChange(of: host.request?.id) { _ in startIfNeeded() }
    }

    private func startIfNeeded() {
        guard let r = host.request, r.id != startedID else { return }
        startedID = r.id
        start()
    }

    private func start() {
        guard let r = host.request, let url = store.streamURL(r.kind, r.item) else { return }
        pm.play(url, live: r.kind == .live)
    }

    private func zap(_ d: Int) {
        guard var r = host.request, let i = r.zapList.firstIndex(where: { $0.id == r.item.id }) else { return }
        let j = i + d
        guard j >= 0 && j < r.zapList.count else { return }
        r.item = r.zapList[j]
        r.title = r.item.name
        openDesc = []
        host.request = r
        startedID = r.id
        start()
    }

    private func circle(_ name: String) -> some View {
        Image(systemName: name).font(.headline).padding(10).background(.ultraThinMaterial, in: Circle())
    }

    @ViewBuilder private func videoBox(full: Bool, landscape: Bool) -> some View {
        ZStack(alignment: .topLeading) {
            Color.black
            VLCVideoView()
            if !pm.status.isEmpty {
                VStack(spacing: 8) {
                    if pm.status.hasPrefix("Verbind") { ProgressView().tint(.white) }
                    if full {
                        Text(pm.status).font(.footnote).foregroundColor(.white).multilineTextAlignment(.center)
                        if pm.status.hasPrefix("Sender lässt") {
                            Button("Erneut versuchen") { start() }.buttonStyle(.borderedProminent)
                        }
                    }
                }
                .padding()
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
            if full {
                HStack(spacing: 10) {
                    Button { host.expanded = false } label: { circle("chevron.down") }
                    Spacer()
                    if host.request?.kind == .live && (host.request?.zapList.count ?? 0) > 1 {
                        Button { zap(-1) } label: { circle("chevron.up.circle") }
                        Button { zap(1) } label: { circle("chevron.down.circle") }
                    }
                    Button { host.close() } label: { circle("xmark") }
                }
                .foregroundColor(.white)
                .padding(10)
            } else {
                // Mini-Player: Tippen = groß, X = beenden
                Color.clear.contentShape(Rectangle()).onTapGesture { host.expanded = true }
                HStack {
                    Spacer()
                    Button { host.close() } label: {
                        Image(systemName: "xmark").font(.caption.bold()).padding(6).background(.ultraThinMaterial, in: Circle())
                    }
                    .foregroundColor(.white)
                    .padding(6)
                }
            }
        }
        .contentShape(Rectangle())
        .onTapGesture(count: 2) { if full { pm.togglePause() } }
    }

    private func hm(_ t: Double) -> String {
        let f = DateFormatter(); f.dateFormat = "HH:mm"
        return f.string(from: Date(timeIntervalSince1970: t))
    }

    @ViewBuilder private var infoPanel: some View {
        if let r = host.request {
            ScrollView {
                VStack(alignment: .leading, spacing: 10) {
                    Text(r.title).font(.title3).bold()
                    if !r.subtitle.isEmpty { Text(r.subtitle).font(.subheadline).foregroundColor(.secondary) }
                    if r.kind == .live {
                        Text("Tipp: ⌄ verkleinert das Bild – so kannst du weitersuchen, während der Sender läuft.")
                            .font(.caption).foregroundColor(.secondary)
                        if let ch = store.epgChannel(for: r.item) {
                            let now = tick.timeIntervalSince1970
                            let list = Array(ch.upcoming(from: now).prefix(13))
                            if let cur = list.first, cur.s <= now {
                                VStack(alignment: .leading, spacing: 6) {
                                    Text("● JETZT · \(hm(cur.s)) – \(hm(cur.e)) · noch \(max(1, Int((cur.e - now) / 60))) Min.")
                                        .font(.caption).foregroundColor(.red)
                                    Text(cur.t).font(.headline)
                                    ProgressView(value: min(1, max(0, (now - cur.s) / (cur.e - cur.s))))
                                    if !cur.d.isEmpty { Text(cur.d).font(.subheadline).foregroundColor(.secondary) }
                                }
                                .padding(12)
                                .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 10))
                            }
                            let rest = list.filter { $0.s > now }
                            if !rest.isEmpty {
                                Text("DANACH").font(.caption).foregroundColor(.secondary).padding(.top, 4)
                                ForEach(rest, id: \.s) { p in
                                    VStack(alignment: .leading, spacing: 3) {
                                        HStack(alignment: .top) {
                                            Text(hm(p.s)).font(.subheadline).bold().foregroundColor(.accentColor).frame(width: 50, alignment: .leading)
                                            Text(p.t).font(.subheadline)
                                        }
                                        if openDesc.contains(p.s) && !p.d.isEmpty {
                                            Text(p.d).font(.caption).foregroundColor(.secondary).padding(.leading, 58)
                                        }
                                    }
                                    .contentShape(Rectangle())
                                    .onTapGesture { if openDesc.contains(p.s) { openDesc.remove(p.s) } else { openDesc.insert(p.s) } }
                                    Divider()
                                }
                            }
                        } else {
                            Text(store.epgLoading ? "TV-Programm wird geladen…" : "Für diesen Sender ist kein Programm verfügbar.")
                                .font(.subheadline).foregroundColor(.secondary)
                        }
                    }
                }
                .padding()
                .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
    }
}
