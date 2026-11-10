import Testing
import SevenKitCore

@Suite struct TrialLaunchSheetPlanTests {
    @Test func nothingDueShowsNothing() {
        #expect(LaunchSheetPlan.next(beforeYouBuyDue: false, trialEndedDue: false) == .none)
    }

    @Test func eachOneAloneIsShown() {
        #expect(LaunchSheetPlan.next(beforeYouBuyDue: true, trialEndedDue: false) == .beforeYouBuy)
        #expect(LaunchSheetPlan.next(beforeYouBuyDue: false, trialEndedDue: true) == .trialEnded)
    }

    @Test func beforeYouBuyWinsWhenBothAreDue() {
        #expect(LaunchSheetPlan.next(beforeYouBuyDue: true, trialEndedDue: true) == .beforeYouBuy)
    }
}
