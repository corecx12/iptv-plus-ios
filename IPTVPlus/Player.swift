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

struct PlayerScreen: View {
    @EnvironmentObject var store: Store
    @ObservedObject var pm = PlayerModel.shared
    @Environment(\.dismiss) private var dismiss
    @State var request: PlayRequest
    @State private var tick = Date()
    @State private var openDesc: Set<Double> = []
    private let clock = Timer.publish(every: 30, on: .main, in: .common).autoconnect()

    var body: some View {
        GeometryReader { geo in
            let landscape = geo.size.width > geo.size.height
            VStack(spacing: 0) {
                ZStack(alignment: .topLeading) {
                    VLCVideoView()
                        .aspectRatio(16 / 9, contentMode: .fit)
                        .frame(maxWidth: .infinity, maxHeight: landscape ? .infinity : nil)
                        .background(Color.black)
                        .onTapGesture { pm.togglePause() }
                    if !pm.status.isEmpty {
                        VStack(spacing: 8) {
                            if pm.status.hasPrefix("Verbind") { ProgressView().tint(.white) }
                            Text(pm.status).font(.footnote).foregroundColor(.white).multilineTextAlignment(.center)
                            if pm.status.hasPrefix("Sender lässt") {
                                Button("Erneut versuchen") { start() }.buttonStyle(.borderedProminent)
                            }
                        }
                        .padding()
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                    }
                    HStack {
                        Button { pm.stop(); dismiss() } label: {
                            Image(systemName: "xmark").font(.headline).padding(10).background(.ultraThinMaterial, in: Circle())
                        }
                        Spacer()
                        if request.kind == .live && request.zapList.count > 1 {
                            Button { zap(-1) } label: { Image(systemName: "chevron.up").font(.headline).padding(10).background(.ultraThinMaterial, in: Circle()) }
                            Button { zap(1) } label: { Image(systemName: "chevron.down").font(.headline).padding(10).background(.ultraThinMaterial, in: Circle()) }
                        }
                    }
                    .foregroundColor(.white)
                    .padding(10)
                }
                .background(Color.black)
                if !landscape { infoPanel }
            }
            .background(Color(.systemBackground))
        }
        .ignoresSafeArea(.container, edges: .horizontal)
        .statusBarHidden(false)
        .onAppear { start() }
        .onReceive(clock) { tick = $0 }
    }

    private func start() {
        guard let url = store.streamURL(request.kind, request.item) else { return }
        pm.play(url, live: request.kind == .live)
    }

    private func zap(_ d: Int) {
        guard let i = request.zapList.firstIndex(where: { $0.id == request.item.id }) else { return }
        let j = i + d
        guard j >= 0 && j < request.zapList.count else { return }
        request.item = request.zapList[j]
        request.title = request.item.name
        openDesc = []
        start()
    }

    private func hm(_ t: Double) -> String {
        let f = DateFormatter(); f.dateFormat = "HH:mm"
        return f.string(from: Date(timeIntervalSince1970: t))
    }

    @ViewBuilder private var infoPanel: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 10) {
                Text(request.title).font(.title3).bold()
                if !request.subtitle.isEmpty { Text(request.subtitle).font(.subheadline).foregroundColor(.secondary) }
                if request.kind == .live {
                    if let ch = store.epgChannel(for: request.item) {
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
