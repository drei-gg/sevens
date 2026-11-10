import Foundation
import Observation
import QRCore

/// State behind the About lists rows: what is installed, whether a check is running, what it said.
@MainActor @Observable
final class ListsAboutModel {
    enum Phase: Equatable {
        case idle
        case checking
        case finished(String)
    }

    private(set) var phase: Phase = .idle
    private(set) var lists: ListsVersion
    private let updater: ListsUpdater?
    private let current: @MainActor () -> ListsVersion
    private let bundled: ListsVersion
    private let reset: @MainActor () -> Void

    /// - Parameters:
    ///   - updater: nil in a build without a configured key.
    ///   - current: the lists in use; the app reads `BundledData.shared`.
    ///   - bundled: the lists that came with the app.
    ///   - reset: deletes the saved download and reloads; by default through the updater, or the
    ///     store itself in a build without one.
    init(updater: ListsUpdater? = ListsUpdater.configured(),
         current: @escaping @MainActor () -> ListsVersion = { BundledData.shared.lists },
         bundled: ListsVersion = BundledData.bundledListsVersion(),
         reset: (@MainActor () -> Void)? = nil) {
        self.updater = updater
        self.reset = reset ?? {
            if let updater { updater.resetToBundled() } else {
                ListsStore().remove()
                BundledData.reload(downloaded: nil)
            }
        }
        self.current = current
        self.bundled = bundled
        self.lists = current()
    }

    /// The button shows only when this build has a repository and a key.
    var canCheck: Bool { updater != nil }
    /// Downloaded lists are in use: newer than what came with the app.
    var usingDownloaded: Bool { lists.version > bundled.version }
    var isChecking: Bool { phase == .checking }

    func check() async {
        guard let updater, phase != .checking else { return }
        phase = .checking
        let result = await updater.check()
        lists = current()
        phase = .finished(ListsAboutText.sentence(for: result))
    }

    func resetToBundled() {
        guard phase != .checking else { return }
        reset()
        lists = current()
        phase = .finished(ListsAboutText.resetDone)
    }
}
