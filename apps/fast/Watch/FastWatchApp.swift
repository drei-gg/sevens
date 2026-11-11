import SwiftUI
import FastCore
import SevenCompanion

@main
struct FastWatchApp: App {
    @State private var model = WatchModel()
    @Environment(\.scenePhase) private var phase
    var body: some Scene {
        WindowGroup {
            WatchHome().environment(model)
                .task { model.start(); await model.refresh() }
                .onChange(of: phase) { _, value in if value == .active { Task { await model.refresh() } } }
        }
    }
}

@Observable @MainActor
final class WatchModel {
    let session = CompanionSession()
    var snapshot: FastWatchSnapshot?
    var pending: FastWatchRequest?
    var busy = false
    var status: String?
    private let defaults = UserDefaults.standard
    private var started = false
    init() {
        if let data = defaults.data(forKey: "fast.watch.snapshot"),
           let reply = try? FastWatchCodec.decode(FastWatchReply.self, from: data), reply.version == 1 {
            snapshot = reply.snapshot
        }
        if let data = defaults.data(forKey: "fast.watch.pending") {
            pending = try? FastWatchCodec.decode(FastWatchRequest.self, from: data)
        }
    }
    func start() {
        guard !started else { return }; started = true
        session.receiveSnapshot = { [weak self] data in self?.receive(data) }
        session.activate()
    }
    private func receive(_ data: Data) {
        guard let reply = try? FastWatchCodec.decode(FastWatchReply.self, from: data), reply.version == 1,
              let incoming = reply.snapshot else { return }
        if let old = snapshot, old.generatedAt > incoming.generatedAt { return }
        snapshot = incoming
        defaults.set(data, forKey: "fast.watch.snapshot")
    }
    func refresh() async {
        guard !busy, session.reachable else { return }
        do { receive(try await session.request(FastWatchCodec.encode(FastWatchRequest(action: .refresh)))) }
        catch { status = String(localized: "Open Fast on your iPhone to reconnect.") }
    }
    func perform(_ action: FastWatchRequest.Action) async {
        await perform(FastWatchRequest(action: action, revision: snapshot?.revision, activeID: snapshot?.activeID))
    }
    func perform(_ request: FastWatchRequest) async {
        guard !busy, pending == nil else { return }
        pending = request
        defaults.set(try? FastWatchCodec.encode(request), forKey: "fast.watch.pending")
        await retry()
    }
    func retry() async {
        guard !busy, let request = pending else { return }
        busy = true; defer { busy = false }
        do {
            let data = try await session.request(FastWatchCodec.encode(request))
            let reply = try FastWatchCodec.decode(FastWatchReply.self, from: data)
            guard reply.version == 1, reply.requestID == request.id else { throw FastWatchFailure.malformed }
            receive(data)
            if let failure = reply.failure {
                status = explanation(failure)
            } else { status = String(localized: "Saved on iPhone.") }
            // A valid reply resolves this attempt, including a definitive rejection.
            pending = nil; defaults.removeObject(forKey: "fast.watch.pending")
        } catch {
            status = String(localized: "Not confirmed. Reconnect and retry this request, or check your iPhone.")
        }
    }
    func dismissPending() {
        pending = nil; defaults.removeObject(forKey: "fast.watch.pending")
        status = String(localized: "Check your iPhone before repeating the action.")
        Task { await refresh() }
    }
    private func explanation(_ failure: FastWatchFailure) -> String {
        switch failure {
        case .locked: String(localized: "Start a trial or unlock in Fast on your iPhone. You can still end an active fast.")
        case .stale: String(localized: "Your records changed on iPhone. Review the updated timer and try again.")
        case .expired: String(localized: "This request expired. Check your iPhone before repeating it.")
        case .unavailable: String(localized: "Open Fast on your iPhone, then try again.")
        case .saveFailed: String(localized: "Your iPhone could not save. Open Fast to check storage and try again.")
        case .unsupported, .malformed: String(localized: "Update Fast on both devices and try again.")
        }
    }
}

struct WatchHome: View {
    @Environment(WatchModel.self) private var model
    @State private var confirmEnd = false
    @State private var endRequest: FastWatchRequest?
    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 14) {
                    if let snapshot = model.snapshot {
                        if let start = snapshot.start {
                            Text("Fasting").font(.headline)
                            Text(start, style: .timer).font(.system(.title2, design: .rounded)).monospacedDigit()
                            if let goal = snapshot.goal {
                                Text("Goal \(goal, format: .dateTime.hour().minute())").font(.footnote).foregroundStyle(.secondary)
                            }
                            Button("End fast", role: .destructive) {
                                endRequest = FastWatchRequest(action: .end, revision: snapshot.revision, activeID: snapshot.activeID)
                                confirmEnd = true
                            }
                                .disabled(!canAct)
                        } else {
                            Image(systemName: "sun.horizon").font(.largeTitle).foregroundStyle(.secondary)
                            Text("At your own pace.").font(.headline)
                            Button("Start 16-hour fast") { Task { await model.perform(.start) } }
                                .disabled(!canAct || !snapshot.canCreateRecords)
                        }
                        Divider()
                        Text("Water: \(Int(snapshot.waterMilliliters)) mL").font(.footnote)
                        Button { Task { await model.perform(.water) } } label: { Label("Add 250 mL", systemImage: "drop") }
                            .disabled(!canAct || !snapshot.canCreateRecords)
                        Text("Last synced \(snapshot.generatedAt, format: .dateTime.month(.abbreviated).day().hour().minute())")
                            .font(.caption2).foregroundStyle(.secondary)
                    } else {
                        Text("Open Fast on your iPhone to sync your timer.")
                    }
                    if !model.session.reachable { Label("iPhone disconnected", systemImage: "iphone.slash").font(.footnote) }
                    if model.busy { ProgressView("Saving…") }
                    if let status = model.status { Text(status).font(.footnote) }
                    if model.pending != nil {
                        Button("Retry same request") { Task { await model.retry() } }.disabled(model.busy || !model.session.reachable)
                        Button("Dismiss request") { model.dismissPending() }.disabled(model.busy)
                    }
                    Button("Refresh") { Task { await model.refresh() } }.disabled(model.busy || !model.session.reachable)
                }.padding(.horizontal, 8)
            }
            .navigationTitle("Fast")
            .confirmationDialog("End your current fast?", isPresented: $confirmEnd) {
                Button("End fast", role: .destructive) {
                    if let request = endRequest { Task { await model.perform(request) } }
                }
            }
            .onChange(of: model.session.reachable) { _, connected in if connected { Task { await model.refresh() } } }
        }
    }
    private var canAct: Bool { model.session.reachable && !model.busy && model.pending == nil }
}
