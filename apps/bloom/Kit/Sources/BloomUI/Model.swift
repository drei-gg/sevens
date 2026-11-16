import BloomCore
import BloomSurfaces
import LocalAuthentication
import SevenCompanion
import SevenKit
import SwiftUI
import UserNotifications
import WidgetKit

@Observable @MainActor
public final class BloomModel {
  public let store: Store
  public var archive = Archive()
  public var dashboard: Dashboard?
  public var ready = false
  public var busy = false
  public var failure: String?
  public var message: String?
  public var showUnlock = false
  public var locked = false
  public var notificationStatus = "Reminders are off until you allow notifications."
  public var lockEnabled: Bool { didSet { defaults.set(lockEnabled, forKey: "bloom.lock") } }
  let repository: BloomRepository
  let defaults: UserDefaults
  let companion = CompanionSession()
  let notifications = BloomNotifications()
  private var started = false
  private var isTesting = false
  public init(bundle: Bundle = .main) {
    let config =
      SevenConfig(bundle: bundle)
      ?? SevenConfig(trialProductID: "bloom.trial7", unlockProductID: "bloom.unlock")
    let support = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[
      0]
    #if DEBUG
      let args = ProcessInfo.processInfo.arguments
      if args.contains("-bloom.ui") {
        let d = UserDefaults(suiteName: "Bloom.UITests")!
        if args.contains("-bloom.reset") { d.removePersistentDomain(forName: "Bloom.UITests") }
        defaults = d
        lockEnabled = false
        isTesting = true
        let source = LocalSource(defaults: d)
        source.apply(
          args.contains("-bloom.expired")
            ? .expired : args.contains("-bloom.fresh") ? .notStarted : .unlocked)
        store = Store(config: config, source: source, defaults: d)
        if !args.contains("-bloom.fresh") {
          store.markBeforeYouBuyShown()
          store.markTrialEndedShown()
        }
        let file = support.appendingPathComponent("Bloom/uitest.enc")
        if args.contains("-bloom.reset") { try? FileManager.default.removeItem(at: file) }
        repository = BloomRepository(
          file: file, seed: args.contains("-bloom.seed") ? BloomDemo.make() : nil,
          key: Data(repeating: 7, count: 32))
        return
      }
    #endif
    let initialLock = UserDefaults.standard.bool(forKey: "bloom.lock")
    defaults = .standard
    lockEnabled = initialLock
    locked = initialLock
    store = Store(config: config)
    repository = BloomRepository(file: support.appendingPathComponent("Bloom/records.enc"))
  }
  public func start() async {
    guard !started else { return }
    started = true
    companion.receiveRequest = { [weak self] data in await self?.watchRequest(data) ?? Data() }
    if !isTesting {
      companion.activate()
      await notifications.configure()
    }
    await store.start()
    await reload()
  }
  public func resume() async {
    if !started {
      await start()
      return
    }
    await store.refresh()
    if ready { await services() }
  }
  func reload() async {
    do {
      archive = try await repository.read()
      ready = true
      failure = nil
      await services()
    } catch { failure = explain(error) }
  }
  func retry() async {
    do {
      archive = try await repository.retry()
      ready = true
      failure = nil
      await services()
    } catch { failure = explain(error) }
  }
  public func protect() { if lockEnabled { locked = true } }
  public func unlock() async {
    let context = LAContext()
    do {
      if try await context.evaluatePolicy(
        .deviceOwnerAuthentication, localizedReason: "Unlock your private health records.")
      {
        locked = false
      }
    } catch { message = "Your records remain locked. Try again when you are ready." }
  }
  func enableLock(_ value: Bool) async {
    let context = LAContext()
    do {
      if try await context.evaluatePolicy(
        .deviceOwnerAuthentication, localizedReason: "Change protection for your health records.")
      {
        lockEnabled = value
        locked = false
      }
    } catch { message = "Device authentication is required to change the app lock." }
  }
  func save<T: Encodable>(_ op: String, _ value: T, revision: UInt64) async -> Bool {
    do { return await change(try Command(op: op, record: value, revision: revision)) } catch {
      message = explain(error)
      return false
    }
  }
  @discardableResult func change(_ command: Command) async -> Bool {
    guard ready, !busy else { return false }
    busy = true
    defer { busy = false }
    do {
      archive = try await repository.apply(command, paid: store.paidFeaturesAvailable)
      await services()
      return true
    } catch {
      message = explain(error)
      return false
    }
  }
  func delete(_ op: String, id: String) async {
    _ = await change(Command(delete: op, id: id, revision: archive.revision))
  }
  func dose(_ occurrence: Occurrence, status: String) async {
    _ = await save("dose", Dose(occurrence, status: status), revision: archive.revision)
  }
  public func notification(action: String, encodedDose: String?) async {
    if !started { await start() }
    guard ready else { return }
    guard let raw = encodedDose,
      let data = Data(base64Encoded: raw),
      let occurrence = try? BloomCodec.decode(Occurrence.self, from: data)
    else { return }
    let status: String
    switch action {
    case "bloom.taken": status = "taken"
    case "bloom.skipped": status = "skipped"
    case "bloom.snooze": status = "snoozed"
    default: return
    }
    await dose(occurrence, status: status)
  }
  func allowNotifications() async {
    do {
      _ = try await UNUserNotificationCenter.current().requestAuthorization(options: [
        .alert, .sound, .badge,
      ])
      await services()
    } catch { message = explain(error) }
  }
  func services() async {
    do { dashboard = try await repository.dashboard() } catch {
      message = explain(error)
      return
    }
    guard let dashboard else { return }
    if !isTesting {
      notificationStatus = await notifications.reconcile(dashboard.reminders, doses: archive.doses)
    }
    publishWatch()
    let snapshot = SurfaceSnapshot(
      enabled: archive.profile.widgets_enabled,
      cycleDay: archive.profile.discreet ? nil : dashboard.cycle_day,
      nextReminder: archive.profile.widgets_enabled
        ? dashboard.reminders.first.map { Date(timeIntervalSince1970: Double($0.at)) } : nil)
    snapshot.save(group: SevenConfig(bundle: .main)?.appGroup)
    if !isTesting { WidgetCenter.shared.reloadAllTimelines() }
  }
  func watchSnapshot() -> WatchSnapshot {
    let enabled = ready && archive.profile.watch_enabled
    var doses = enabled ? dashboard?.today_doses ?? [] : []
    if archive.profile.discreet { for i in doses.indices { doses[i].title = "Your reminder" } }
    return WatchSnapshot(
      enabled: enabled, cycleDay: enabled && !archive.profile.discreet ? dashboard?.cycle_day : nil,
      doses: doses, canAdd: store.paidFeaturesAvailable)
  }
  func publishWatch() {
    if let data = try? BloomCodec.encode(WatchReply(snapshot: watchSnapshot())) {
      companion.publish(data)
    }
  }
  private func watchRequest(_ data: Data) async -> Data {
    var reply = WatchReply()
    do {
      guard data.count <= 512_000 else {
        throw BloomFailure.message("The Watch request is too large.")
      }
      let request = try BloomCodec.decode(WatchRequest.self, from: data)
      reply.request_id = request.id
      guard request.version == 1, ready else {
        throw BloomFailure.message("Open the app on your iPhone to finish setup.")
      }
      guard archive.profile.watch_enabled else {
        throw BloomFailure.message("Enable the Watch companion on your iPhone first.")
      }
      if let command = request.command {
        guard !busy, ["dose", "day"].contains(command.op) else {
          throw BloomFailure.message("Your phone is busy. Review and retry this request.")
        }
        // Repository serialization and command receipts make retries idempotent.
        busy = true
        do {
          archive = try await repository.apply(command, paid: store.paidFeaturesAvailable)
          busy = false
          await services()
        } catch {
          busy = false
          throw error
        }
      }
      reply.snapshot = watchSnapshot()
    } catch {
      reply.error = explain(error)
      reply.snapshot = watchSnapshot()
    }
    return (try? BloomCodec.encode(reply)) ?? Data()
  }
  func importArchive(_ incoming: Archive) async -> Bool {
    guard !busy else { return false }
    busy = true
    defer { busy = false }
    do {
      archive = try await repository.merge(incoming)
      await services()
      return true
    } catch {
      message = explain(error)
      return false
    }
  }
  func erase() async {
    guard !busy else { return }
    busy = true
    defer { busy = false }
    do {
      archive = try await repository.erase()
      await services()
    } catch { message = explain(error) }
  }
}
func explain(_ error: Swift.Error) -> String {
  if let e = error as? BloomCore.Error { if case .Invalid(let reason) = e { return reason } }
  return error.localizedDescription
}

actor BloomNotifications {
  func configure() {
    let actions = [
      UNNotificationAction(
        identifier: "bloom.taken", title: "Taken", options: [.authenticationRequired]),
      UNNotificationAction(
        identifier: "bloom.skipped", title: "Skipped", options: [.authenticationRequired]),
      UNNotificationAction(
        identifier: "bloom.snooze", title: "In 10 minutes", options: [.authenticationRequired]),
    ]
    UNUserNotificationCenter.current().setNotificationCategories([
      UNNotificationCategory(identifier: "bloom.dose", actions: actions, intentIdentifiers: [])
    ])
  }
  func reconcile(_ reminders: [Reminder], doses: [Dose]) async -> String {
    let center = UNUserNotificationCenter.current()
    let settings = await center.notificationSettings()
    guard
      settings.authorizationStatus == .authorized || settings.authorizationStatus == .provisional
    else { return "Notifications are not allowed. Open Settings to enable reminders." }
    let wanted = Set(reminders.map(\.id))
    let existing = await center.pendingNotificationRequests()
    center.removePendingNotificationRequests(
      withIdentifiers: existing.filter {
        $0.identifier.hasPrefix("bloom.") && !wanted.contains($0.identifier)
      }.map(\.identifier))
    var successful = Set<String>()
    var errors = 0
    for reminder in reminders {
      let content = UNMutableNotificationContent()
      content.title = reminder.title
      content.body = reminder.body
      content.sound = .default
      if let dose = reminder.dose {
        content.categoryIdentifier = "bloom.dose"
        content.userInfo["dose"] = (try? BloomCodec.encode(dose))?.base64EncodedString()
      }
      let interval = max(1, Double(reminder.at) - Date().timeIntervalSince1970)
      let request = UNNotificationRequest(
        identifier: reminder.id, content: content,
        trigger: UNTimeIntervalNotificationTrigger(timeInterval: interval, repeats: false))
      do {
        try await center.add(request)
        successful.insert(reminder.id)
      } catch { errors += 1 }
    }
    if errors == 0 {
      let old = await center.pendingNotificationRequests()
      center.removePendingNotificationRequests(
        withIdentifiers: old.filter {
          $0.identifier.hasPrefix("bloom.") && !successful.contains($0.identifier)
        }.map(\.identifier))
    }
    center.removeDeliveredNotifications(
      withIdentifiers: doses.filter { dose in dose.status != "snoozed" }.map { dose in
        "bloom.dose.\(dose.id)"
      })
    if errors > 0 {
      return
        "\(errors) reminders could not be scheduled. Open the app to retry. Reminder coverage is incomplete."
    }
    return "\(successful.count) reminders scheduled. Open the app regularly to extend coverage."
  }
}
