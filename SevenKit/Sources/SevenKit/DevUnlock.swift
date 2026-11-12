import Foundation
import SevenKitCore

/// Personal-device test builds only: every paid feature unlocked, no StoreKit. The private build
/// helper adds `SEVENS_DEV_UNLOCK` to the compilation conditions on its command line; no committed
/// project or xcconfig sets it (`scripts/check-rules` fails if one does), so store archives never
/// compile it in. Second guard: it only acts under a `.dev` bundle identifier.
enum DevUnlock {
    static var isActive: Bool {
        #if SEVENS_DEV_UNLOCK
        return (Bundle.main.bundleIdentifier ?? "").split(separator: ".").contains("dev")
        #else
        return false
        #endif
    }

    @MainActor static func source() -> (any EntitlementSource)? {
        guard isActive else { return nil }
        return FixedSource(facts: EntitlementFacts(hasUnlock: true))
    }
}
