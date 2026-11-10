// WP 1.3 owns this file.

public enum CRC16 {
    /// CRC-16/CCITT-FALSE: poly 0x1021, init 0xFFFF, no reflection, no final XOR.
    public static func ccittFalse<S: Sequence>(_ bytes: S) -> UInt16 where S.Element == UInt8 {
        fatalError("WP 1.3")
    }
}
