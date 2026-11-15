// Contract, frozen at qr-contract-1. Change only through Opus, with a recorded reason.

public enum Symbology: String, Codable, Sendable, CaseIterable {
    case qr, microQR, aztec, pdf417, microPDF417, dataMatrix, code128, code39, code93,
         ean13, ean8, upce, itf14, codabar, gs1DataBar, other
}

public struct ScannedCode: Sendable, Hashable, Codable {
    /// nil when the code isn't valid UTF-8 / Latin-1 text.
    public var text: String?
    public var bytes: [UInt8]
    public var symbology: Symbology

    public init(text: String?, bytes: [UInt8], symbology: Symbology) {
        self.text = text
        self.bytes = bytes
        self.symbology = symbology
    }

    /// A text code; `bytes` are its UTF-8.
    public init(text: String, symbology: Symbology = .qr) {
        self.init(text: text, bytes: Array(text.utf8), symbology: symbology)
    }
}
