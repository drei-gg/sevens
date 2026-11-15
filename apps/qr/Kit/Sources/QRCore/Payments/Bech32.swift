/// Bech32 (BIP 173) and Bech32m (BIP 350) decoding with checksum verification.
public enum Bech32 {
    public enum Encoding: Sendable { case bech32, bech32m }

    public struct Decoded: Sendable, Equatable {
        public var hrp: String
        /// 5-bit values, checksum removed.
        public var data: [UInt8]
        public var encoding: Encoding
    }

    private static let charset = Array("qpzry9x8gf2tvdw0s3jn54khce6mua7l".utf8)
    private static let generator: [UInt32] = [0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3]

    private static func polymod(_ values: [UInt8]) -> UInt32 {
        var chk: UInt32 = 1
        for v in values {
            let top = chk >> 25
            chk = ((chk & 0x1ffffff) << 5) ^ UInt32(v)
            for i in 0..<5 where (top >> UInt32(i)) & 1 == 1 { chk ^= generator[i] }
        }
        return chk
    }

    /// Verifies the checksum. Nil for mixed case, characters outside 33–126, a missing or
    /// misplaced separator, a data part shorter than the 6-character checksum, a bad
    /// character, a bad checksum, or more than `maxLength` characters.
    public static func decode(_ text: String, maxLength: Int = 90) -> Decoded? {
        let bytes = Array(text.utf8)
        guard bytes.count <= maxLength, bytes.count >= 8 else { return nil }
        var hasLower = false, hasUpper = false
        for b in bytes {
            guard (33...126).contains(b) else { return nil }
            if (97...122).contains(b) { hasLower = true }
            if (65...90).contains(b) { hasUpper = true }
        }
        if hasLower && hasUpper { return nil }
        let lower = bytes.map { (65...90).contains($0) ? $0 + 32 : $0 }
        guard let sep = lower.lastIndex(of: UInt8(ascii: "1")), sep >= 1, lower.count - sep - 1 >= 6 else { return nil }
        let hrp = Array(lower[0..<sep])
        var data: [UInt8] = []
        for c in lower[(sep + 1)...] {
            guard let idx = charset.firstIndex(of: c) else { return nil }
            data.append(UInt8(idx))
        }
        let expand = hrp.map { $0 >> 5 } + [0] + hrp.map { $0 & 31 }
        let encoding: Encoding
        switch polymod(expand + data) {
        case 1: encoding = .bech32
        case 0x2bc830a3: encoding = .bech32m
        default: return nil
        }
        return Decoded(hrp: String(decoding: hrp, as: UTF8.self), data: Array(data.dropLast(6)), encoding: encoding)
    }
}
