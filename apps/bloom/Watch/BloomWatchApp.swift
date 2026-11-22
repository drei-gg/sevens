import BloomWire
import SevenCompanion
import SwiftUI

@MainActor @Observable final class WatchModel {
  let session = CompanionSession()
  var snapshot: WatchSnapshot?
  var pending: [WatchRequest] = []
  var message: String?
  var working = false
  init() {
    if let data = UserDefaults.standard.data(forKey: "bloom.snapshot") {
      if data.count <= 512_000, let cached = try? BloomCodec.decode(WatchSnapshot.self, from: data), cached.version == 1 { snapshot = cached }
    }
    if let data = UserDefaults.standard.data(forKey: "bloom.pending") {
      if data.count <= 512_000 { pending = ((try? BloomCodec.decode([WatchRequest].self, from: data)) ?? []).filter { $0.version == 1 } }
    }
    if snapshot?.enabled == false { pending = []; persist() }
    session.receiveSnapshot = { [weak self] data in self?.receive(data) }
    session.activate()
  }
  func receive(_ data: Data) {
    guard data.count <= 512_000, let reply = try? BloomCodec.decode(WatchReply.self, from: data), reply.version == 1, reply.snapshot?.version ?? 1 == 1 else {
      return
    }
    if let snapshot = reply.snapshot, snapshot.replaces(self.snapshot) {
      self.snapshot = snapshot
      UserDefaults.standard.set(try? BloomCodec.encode(snapshot), forKey: "bloom.snapshot")
      if !snapshot.enabled {
        pending = []
        persist()
      }
    }
    if let error = reply.error { message = error }
  }
  func persist() {
    UserDefaults.standard.set(try? BloomCodec.encode(pending), forKey: "bloom.pending")
  }
  func record(_ occurrence: Occurrence, status: String) {
    guard !working, pending.isEmpty, fresh, snapshot?.doses.contains(where: { $0.id == occurrence.id }) == true else {
      message = "Sync or clear pending actions first."
      return
    }
    do {
      let command = try Command(op: "dose", record: Dose(occurrence, status: status), revision: snapshot?.revision)
      pending.append(WatchRequest(command: command))
      persist()
      message = "Pending — not yet saved on iPhone."
      Task { await sync() }
    } catch { message = error.localizedDescription }
  }
  func sync() async {
    guard !working else { return }
    working = true
    defer { working = false }
    guard session.reachable else {
      message =
        pending.isEmpty
        ? "Open the app on your iPhone to refresh."
        : "\(pending.count) pending. Open both apps to sync."
      return
    }
    do {
      while let request = pending.first {
        let data = try await session.request(BloomCodec.encode(request))
        guard data.count <= 512_000, let reply = try? BloomCodec.decode(WatchReply.self, from: data), reply.version == 1, reply.snapshot?.version ?? 1 == 1,
          reply.request_id == request.id
        else {
          message = "No valid acknowledgement. Your action is still pending."
          return
        }
        receive(data)
        if reply.error != nil { return }
        pending.removeAll { $0.id == request.id }
        persist()
      }
      let refresh = WatchRequest()
      let data = try await session.request(BloomCodec.encode(refresh))
      guard data.count <= 512_000, let reply = try? BloomCodec.decode(WatchReply.self, from: data), reply.version == 1, reply.snapshot?.version ?? 1 == 1, reply.request_id == refresh.id else {
        message = "No valid acknowledgement. Refresh again from iPhone."
        return
      }
      receive(data)
      if reply.error == nil {
        message = "Up to date with iPhone."
      }
    } catch { message = "Connection interrupted. Pending actions stay queued." }
  }
  var fresh: Bool {
    snapshot?.fresh() == true
  }
}
@main struct BloomWatchApp: App {
  @State private var model = WatchModel()
  @State private var discardPending = false
  @Environment(\.scenePhase) private var phase
  var body: some Scene {
    WindowGroup {
      NavigationStack {
        List {
          if let snapshot = model.snapshot, snapshot.enabled {
            if let day = snapshot.cycle_day { Text("Cycle day \(day)").font(.headline) }
            if !model.fresh { Text("This snapshot is old. Refresh from iPhone before recording.") }
            ForEach(snapshot.doses) { dose in
              VStack(alignment: .leading) {
                Text(dose.title).font(.headline)
                Text(
                  "\(Date(timeIntervalSince1970: Double(dose.at)).formatted(date: .omitted, time: .shortened)) · \(dose.status.capitalized)"
                )
                HStack {
                  Button("Taken") { model.record(dose, status: "taken") }
                  Button("Skip") { model.record(dose, status: "skipped") }
                }.disabled(!model.fresh || model.working || !model.pending.isEmpty)
              }
            }
            if snapshot.doses.isEmpty { Text("No doses in this snapshot.") }
          } else {
            Text("Enable the Watch companion in your iPhone’s tracking and privacy settings.")
          }
          if !model.pending.isEmpty {
            Text("\(model.pending.count) pending — not saved on iPhone yet.")
            Button("Discard pending actions", role: .destructive) {
              discardPending = true
            }
          }
          if let message = model.message { Text(message).font(.footnote) }
          Button("Sync with iPhone") { Task { await model.sync() } }.disabled(model.working)
        }.navigationTitle(Bundle.main.object(forInfoDictionaryKey: "CFBundleDisplayName") as? String ?? "Cycle & Health")
      }.confirmationDialog("Discard pending actions?", isPresented: $discardPending, titleVisibility: .visible) {
        Button("Discard pending actions", role: .destructive) {
          model.pending = []; model.persist()
          model.message = "Pending actions discarded on Watch. Check iPhone history if a reply was interrupted."
        }
      } message: {
        Text("This only clears the watch queue. Check iPhone history in case the action was saved before the connection dropped.")
      }.task { await model.sync() }.onChange(of: model.session.reachable) { _, reachable in
        if reachable { Task { await model.sync() } }
      }
    }.onChange(of: phase) { _, phase in if phase == .active { Task { await model.sync() } } }
  }
}
