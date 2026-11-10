import Foundation

extension MakeSymbology {
    var title: String {
        switch self {
        case .qr: return String(localized: "make.symbology.qr", defaultValue: "QR code", bundle: .module)
        case .aztec: return String(localized: "make.symbology.aztec", defaultValue: "Aztec code", bundle: .module)
        case .pdf417: return String(localized: "make.symbology.pdf417", defaultValue: "PDF417", bundle: .module)
        case .code128: return String(localized: "make.symbology.code128", defaultValue: "Code 128 barcode", bundle: .module)
        }
    }

    /// Payload cut to a length VoiceOver can bear.
    static func shortPayload(_ text: String, limit: Int = 40) -> String {
        let flat = text.replacingOccurrences(of: "\r\n", with: " ").replacingOccurrences(of: "\n", with: " ")
        return flat.count > limit ? String(flat.prefix(limit)) + "…" : flat
    }

    /// "QR code for <short payload>".
    func accessibilityLabel(for payload: String) -> String {
        let name = title, short = Self.shortPayload(payload)
        return String(localized: "make.code.accessibility", defaultValue: "\(name) for \(short)", bundle: .module)
    }
}

extension Correction {
    var title: String {
        switch self {
        case .auto: return String(localized: "make.correction.auto", defaultValue: "Auto", bundle: .module)
        case .L: return String(localized: "make.correction.L", defaultValue: "L, lowest", bundle: .module)
        case .M: return String(localized: "make.correction.M", defaultValue: "M, medium", bundle: .module)
        case .Q: return String(localized: "make.correction.Q", defaultValue: "Q, higher", bundle: .module)
        case .H: return String(localized: "make.correction.H", defaultValue: "H, highest", bundle: .module)
        }
    }
}
