import BloomUI
import SwiftUI
import UIKit
import UserNotifications

@main struct BloomApp: App {
  @UIApplicationDelegateAdaptor(BloomDelegate.self) private var delegate
  @Environment(\.scenePhase) private var phase
  var body: some Scene {
    WindowGroup {
      BloomRootView().environment(delegate.model)
        .task { await delegate.model.start() }
        .preferredColorScheme(appearance)
    }.onChange(of: phase) { _, phase in
      if phase == .active {
        Task { await delegate.model.resume() }
      } else {
        delegate.model.protect()
      }
    }
  }
  private var appearance: ColorScheme? {
    #if DEBUG
      if ProcessInfo.processInfo.arguments.contains("-bloom.dark") { return .dark }
      if ProcessInfo.processInfo.arguments.contains("-bloom.light") { return .light }
    #endif
    return nil
  }
}
@MainActor
final class BloomDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate {
  let model = BloomModel()
  func application(
    _ application: UIApplication,
    didFinishLaunchingWithOptions options: [UIApplication.LaunchOptionsKey: Any]? = nil
  ) -> Bool {
    UNUserNotificationCenter.current().delegate = self
    Task { await model.start() }
    return true
  }
  nonisolated func userNotificationCenter(
    _ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse
  ) async {
    let action = response.actionIdentifier
    let encoded = response.notification.request.content.userInfo["dose"] as? String
    await model.notification(action: action, encodedDose: encoded)
  }
  nonisolated func userNotificationCenter(
    _ center: UNUserNotificationCenter, willPresent notification: UNNotification
  ) async -> UNNotificationPresentationOptions { [.banner, .sound] }
}
