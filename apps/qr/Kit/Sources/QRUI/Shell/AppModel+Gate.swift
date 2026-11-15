import SwiftUI
import SevenKit

/// Gate wiring (SPEC §13): what the unlock sheet, Before-you-buy and trial-ended screens say
/// for this app. All words are QR-specific and live here; SevenKit hard-codes none. No price
/// is written anywhere: Before-you-buy uses the `{price}` placeholder, filled from StoreKit
/// at runtime, with a price-free fallback (G7).
public enum QRGate {
    /// `CFBundleDisplayName`, else `CFBundleName`, else a fixed name.
    public static func appName(bundle: Bundle = .main) -> String {
        for key in ["CFBundleDisplayName", "CFBundleName"] {
            if let name = (bundle.object(forInfoDictionaryKey: key) as? String)?
                .trimmingCharacters(in: .whitespacesAndNewlines), !name.isEmpty {
                return name
            }
        }
        return String(localized: "app.name.fallback", defaultValue: "QR Code Kit", bundle: .module)
    }

    /// What the unlock adds (SPEC §13 as amended by D2).
    public static func unlockContent(appName: String = QRGate.appName()) -> UnlockContent {
        UnlockContent(appName: appName, features: [
            String(localized: "gate.feature.peek",
                   defaultValue: "Peek: follow a link’s redirects and see what its page asks for before you open it", bundle: .module),
            String(localized: "gate.feature.make",
                   defaultValue: "Make QR codes from text, links, Wi-Fi, contacts and more, and export them", bundle: .module),
            String(localized: "gate.feature.extension",
                   defaultValue: "Make QR code from the Share sheet in any app", bundle: .module),
            String(localized: "gate.feature.history",
                   defaultValue: "History: save what you scan and make, with search, notes and export", bundle: .module),
        ])
    }

    /// SPEC §1 step 2 with D1's wording. `{price}` is replaced by the StoreKit price.
    public static func beforeYouBuyContent() -> BeforeYouBuyContent {
        BeforeYouBuyContent(
            title: String(localized: "gate.byb.title", defaultValue: "Before you buy", bundle: .module),
            paragraphs: [
                .init(String(localized: "gate.byb.p1",
                             defaultValue: "Your iPhone already scans QR codes. Point the Camera at one, or use Code Scanner in Control Center. If that’s all you need, delete this app and keep your money.",
                             bundle: .module)),
                .init(String(localized: "gate.byb.p2",
                             defaultValue: "What this adds: it checks where a code really goes before you open it, shows who a payment code pays, makes codes from anything you share, and remembers what you scanned. Free for 7 days from when you tap Start, no card. Then {price} once, if you want to keep it.",
                             bundle: .module),
                      withoutPrice: String(localized: "gate.byb.p2.noPrice",
                                           defaultValue: "What this adds: it checks where a code really goes before you open it, shows who a payment code pays, makes codes from anything you share, and remembers what you scanned. Free for 7 days from when you tap Start, no card. Then a one-time price, if you want to keep it.",
                                           bundle: .module)),
            ],
            startTrialLabel: String(localized: "gate.byb.start", defaultValue: "Start my free 7 days", bundle: .module),
            justScanLabel: String(localized: "gate.byb.justScan", defaultValue: "Just scan for free", bundle: .module),
            continueLabel: String(localized: "gate.byb.continue", defaultValue: "Continue", bundle: .module)
        )
    }

    /// The one sheet after the trial ends: what stays free, what the unlock keeps.
    public static func trialEndedContent() -> TrialEndedContent {
        TrialEndedContent(
            stillFree: [
                String(localized: "gate.free.scan", defaultValue: "Scanning from the camera, photos and clipboard", bundle: .module),
                String(localized: "gate.free.verdict", defaultValue: "The verdict and every scam check", bundle: .module),
                String(localized: "gate.free.payments", defaultValue: "Payment-code details", bundle: .module),
                String(localized: "gate.free.open", defaultValue: "Opening links", bundle: .module),
                String(localized: "gate.free.check", defaultValue: "Check this code in the Share sheet", bundle: .module),
                String(localized: "gate.free.history", defaultValue: "Reading and exporting the history you already saved", bundle: .module),
            ],
            unlockKeeps: [
                String(localized: "gate.keeps.peek", defaultValue: "Peek", bundle: .module),
                String(localized: "gate.keeps.make", defaultValue: "Make and export", bundle: .module),
                String(localized: "gate.keeps.extension", defaultValue: "Make QR code in the Share sheet", bundle: .module),
                String(localized: "gate.keeps.history", defaultValue: "Saving new scans to History", bundle: .module),
            ]
        )
    }
}

extension View {
    /// Put once at the root: installs the model, SevenKit's `Store` and the unlock texts.
    public func qrStore(_ model: AppModel) -> some View {
        environment(model).sevenStore(model.store, unlock: QRGate.unlockContent())
    }
}
