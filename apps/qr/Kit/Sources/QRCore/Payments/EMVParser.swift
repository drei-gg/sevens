// WP 1.3 owns this file. The signature below is the seam the Classifier (WP 2.1) calls; keep it.

public enum EMVParser {
    /// An EMVCo merchant-presented payload (PLAN §6.4), or nil if it isn't one.
    /// `checksum` is `.invalid` on a CRC mismatch; the Analyzer turns that into `paymentChecksumFail`.
    public static func parse(_ text: String, networks: [EMVNetwork], currencies: [String: String]) -> PaymentInfo? {
        fatalError("WP 1.3")
    }
}
