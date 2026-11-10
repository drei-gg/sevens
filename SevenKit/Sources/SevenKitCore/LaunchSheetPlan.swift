import Foundation

/// Which sheet may open at launch (SPEC §13). Before you buy and the trial-ended sheet can both
/// be due on one launch (a reinstall after the trial); Before you buy comes first and the
/// trial-ended sheet waits for the next launch, so the two are never shown together.
public enum LaunchSheetPlan: Sendable, Equatable {
    case none
    case beforeYouBuy
    case trialEnded

    public static func next(beforeYouBuyDue: Bool, trialEndedDue: Bool) -> LaunchSheetPlan {
        if beforeYouBuyDue { return .beforeYouBuy }
        return trialEndedDue ? .trialEnded : .none
    }
}
