import QRCore

/// The four symbologies the Make screen offers.
public enum MakeSymbology: String, Sendable, CaseIterable, Hashable {
    case qr, aztec, pdf417, code128

    /// The contract symbology, for `QuietZone.modules(for:)`.
    public var symbology: Symbology {
        switch self {
        case .qr: return .qr
        case .aztec: return .aztec
        case .pdf417: return .pdf417
        case .code128: return .code128
        }
    }

    /// Only QR and Aztec use the correction setting.
    public var supportsCorrection: Bool { self == .qr || self == .aztec }
}

/// Error correction. For QR, Auto is the smallest version at M, then the highest level that fits
/// that same version (`CodeGenerator.autoQR`). Aztec takes a percentage: L 5, M/Auto 23, Q 40, H 60.
public enum Correction: String, Sendable, CaseIterable, Hashable {
    case auto, L, M, Q, H

    /// The QR level letter CoreImage expects (Auto starts at M; the generator may raise it).
    var qrLevel: String {
        switch self {
        case .auto, .M: return "M"
        case .L: return "L"
        case .Q: return "Q"
        case .H: return "H"
        }
    }

    /// Aztec error-correction percentage.
    var aztecPercent: Float {
        switch self {
        case .L: return 5
        case .auto, .M: return 23
        case .Q: return 40
        case .H: return 60
        }
    }
}
