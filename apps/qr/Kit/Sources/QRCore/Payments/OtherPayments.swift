// WP 1.4 owns this file (and may split it). The signatures below are the seam the
// Classifier (WP 2.1) calls; keep them.

public enum UPIParser {
    /// `upi://pay` and `upi://mandate` (case-insensitive scheme), or nil.
    public static func parse(_ text: String) -> PaymentInfo? { fatalError("WP 1.4") }
}

public enum EPCParser {
    /// EPC069-12 (first line `BCD`), or nil. IBAN mod-97 failure → checksum `.invalid`.
    public static func parse(_ text: String) -> PaymentInfo? { fatalError("WP 1.4") }
}

public enum SwissQRParser {
    /// Swiss QR-bill (first line `SPC`), or nil. IBAN, QRR mod-10 or SCOR mod-97 failure → `.invalid`.
    public static func parse(_ text: String) -> PaymentInfo? { fatalError("WP 1.4") }
}

public enum IBAN {
    public static func isValid(_ iban: String) -> Bool { fatalError("WP 1.4") }
}

public enum CryptoURIParser {
    /// `bitcoin:`, `ethereum:`, `lightning:` and every scheme in `schemes`, or nil.
    public static func parse(_ text: String, schemes: [CryptoScheme]) -> CryptoInfo? { fatalError("WP 1.4") }
}

public enum CryptoAddress {
    /// A bare address (PLAN §6.4: bech32/bech32m with valid checksum and known prefix,
    /// `0x` + 40 hex, base58 26–35 chars starting 1 or 3), or nil.
    public static func detect(_ text: String) -> CryptoInfo? { fatalError("WP 1.4") }
}
