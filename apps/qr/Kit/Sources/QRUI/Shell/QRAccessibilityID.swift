// Phase 3 seam (Opus). Accessibility identifiers the UI smoke test (WP 4.1) relies on.
// Screens set these with `.accessibilityIdentifier(QRAccessibilityID.…)`. Add, never rename.

public enum QRAccessibilityID {
    // Scan screen (WP 3.1)
    public static let scanMake = "scan.make"
    public static let scanHistory = "scan.history"
    public static let scanSettings = "scan.settings"
    public static let scanPhoto = "scan.photo"
    public static let scanPaste = "scan.paste"
    // First launch (WP 3.1)
    public static let firstLaunchTrial = "firstlaunch.trial"
    public static let firstLaunchFree = "firstlaunch.free"
    // Result sheet (WP 3.2): the verdict header element; its label starts with the verdict word.
    public static let resultVerdict = "result.verdict"
    public static let resultDone = "result.done"
    public static let resultPeek = "result.peek"
    public static let resultOpen = "result.open"
    // Make (WP 3.3)
    public static let makeTypePicker = "make.type"
    public static let makeLinkField = "make.field.link"
    public static let makeTextField = "make.field.text"
    public static let makeCodeImage = "make.code"
    public static let makeExport = "make.export"
    public static let makeDone = "make.done"
    // History (WP 3.4)
    public static let historyList = "history.list"
    public static let historyRow = "history.row"
    public static let historySearch = "history.search"
}

/// Debug-only launch arguments (parsed by WP 3.1 in the app; ignored in Release).
public enum QRLaunchArgument {
    /// `-qr.inject <base64 of the payload bytes>`: show the result sheet for that code at launch.
    public static let inject = "-qr.inject"
    /// `-qr.skipFirstLaunch`: don't show Before you buy.
    public static let skipFirstLaunch = "-qr.skipFirstLaunch"
    /// `-qr.entitlement <notStarted|trialDay1|trialDay7|expired|unlocked|pending>`: SevenKit
    /// debug preset via `store.applyDebugPreset` (LocalSource).
    public static let entitlement = "-qr.entitlement"
    /// `-qr.resetHistory`: start with an empty History (UI tests).
    public static let resetHistory = "-qr.resetHistory"
}
