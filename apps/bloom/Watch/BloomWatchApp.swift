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
      snapshot = try? BloomCodec.decode(WatchSnapshot.self, from: data)
    }
    if let data = UserDefaults.standard.data(forKey: "bloom.pending") {
      pending = (try? BloomCodec.decode([WatchRequest].self, from: data)) ?? []
    }
    session.receiveSnapshot = { [weak self] data in self?.receive(data) }
    session.activate()
  }
  func receive(_ data: Data) {
    guard let reply = try? BloomCodec.decode(WatchReply.self, from: data), reply.version == 1 else {
      return
    }
    if let snapshot = reply.snapshot {
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
    guard pending.count < 56 else {
      message = "Sync or clear pending actions first."
      return
    }
    do {
      let command = try Command(op: "dose", record: Dose(occurrence, status: status))
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
        guard let reply = try? BloomCodec.decode(WatchReply.self, from: data), reply.version == 1,
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
      let data = try await session.request(BloomCodec.encode(WatchRequest()))
      receive(data)
      if let reply = try? BloomCodec.decode(WatchReply.self, from: data), reply.error == nil {
        message = "Up to date with iPhone."
      }
    } catch { message = "Connection interrupted. Pending actions stay queued." }
  }
  var fresh: Bool {
    guard let snapshot else { return false }
    return Date().timeIntervalSince1970 - Double(snapshot.generated) < 86400
      && BloomDate.day(Date(timeIntervalSince1970: Double(snapshot.generated)))
        == BloomDate.day(Date())
  }
}
@main struct BloomWatchApp: App {
  @State private var model = WatchModel()
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
              model.pending = []
              model.persist()
              model.message =
                "Pending actions discarded on Watch. Check iPhone history if a reply was interrupted."
            }
          }
          if let message = model.message { Text(message).font(.footnote) }
          Button("Sync with iPhone") { Task { await model.sync() } }.disabled(model.working)
        }.navigationTitle(Bundle.main.object(forInfoDictionaryKey: "CFBundleDisplayName") as? String ?? "Cycle & Health")
      }.task { await model.sync() }.onChange(of: model.session.reachable) { _, reachable in
        if reachable { Task { await model.sync() } }
      }
    }.onChange(of: phase) { _, phase in if phase == .active { Task { await model.sync() } } }
  }
}
