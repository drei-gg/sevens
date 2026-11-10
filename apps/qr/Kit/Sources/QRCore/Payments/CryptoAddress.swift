public enum CryptoAddress {
    /// A bare address (PLAN §6.4: bech32/bech32m with valid checksum and known prefix,
    /// `0x` + 40 hex, base58 26–35 chars starting 1 or 3), or nil.
    public static func detect(_ text: String) -> CryptoInfo? {
        guard text.utf8.count <= 512 else { return nil }
        let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
        let u = Array(t.utf8)
        guard !u.isEmpty, u.allSatisfy({ (33...126).contains($0) }) else { return nil }

        if let d = Bech32.decode(t) {
            let network: String
            switch d.hrp {
            case "bc": network = "Bitcoin"
            case "tb": network = "Bitcoin testnet"
            case "ltc": network = "Litecoin"
            default: return nil
            }
            // Segwit: version 0 uses bech32, versions 1-16 use bech32m.
            guard let version = d.data.first, version <= 16,
                  (version == 0) == (d.encoding == .bech32) else { return nil }
            return CryptoInfo(network: network, address: t)
        }
        if u.count == 42, t.hasPrefix("0x"), u.dropFirst(2).allSatisfy(isHex) {
            return CryptoInfo(network: "Ethereum", address: t)
        }
        if (26...35).contains(u.count), u[0] == UInt8(ascii: "1") || u[0] == UInt8(ascii: "3"),
           u.allSatisfy(isBase58) {
            return CryptoInfo(network: "Bitcoin", address: t)
        }
        return nil
    }

    private static func isHex(_ b: UInt8) -> Bool {
        (48...57).contains(b) || (65...70).contains(b) || (97...102).contains(b)
    }

    private static func isBase58(_ b: UInt8) -> Bool {
        switch b {
        case 48, 79, 73, 108: return false  // 0 O I l
        case 49...57, 65...90, 97...122: return true
        default: return false
        }
    }
}
