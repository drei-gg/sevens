import Foundation

/// Quoted-printable decoding with the charsets vCard 2.1 uses in practice.
enum QuotedPrintable {
    static func decode(_ s: String, charset: String?) -> String {
        let u = Array(s.utf8)
        var bytes: [UInt8] = []
        bytes.reserveCapacity(u.count)
        var i = 0
        while i < u.count {
            if u[i] == 0x3D, i + 2 < u.count, let h = StructuredText.hexValue(u[i + 1]),
               let l = StructuredText.hexValue(u[i + 2]) {
                bytes.append(h << 4 | l)
                i += 3
            } else {
                bytes.append(u[i])
                i += 1
            }
        }
        return decodeBytes(bytes, charset: charset)
    }

    static func decodeBytes(_ bytes: [UInt8], charset: String?) -> String {
        let cs = (charset ?? "").uppercased().replacingOccurrences(of: "_", with: "-")
        switch cs {
        case "ISO-8859-1", "ISO8859-1", "LATIN1", "LATIN-1", "US-ASCII", "ASCII":
            return latin1(bytes)
        case "WINDOWS-1252", "CP1252", "WIN-1252", "WINDOWS1252":
            return windows1252(bytes)
        case "UTF-8", "UTF8":
            return String(decoding: bytes, as: UTF8.self)
        default:
            if let s = String(bytes: bytes, encoding: .utf8) { return s }
            // No usable charset and not UTF-8: the common legacy Windows encoding beats U+FFFD soup.
            return cs.isEmpty ? windows1252(bytes) : String(decoding: bytes, as: UTF8.self)
        }
    }

    private static func latin1(_ bytes: [UInt8]) -> String {
        var v = String.UnicodeScalarView()
        v.append(contentsOf: bytes.map { Unicode.Scalar($0) })
        return String(v)
    }

    private static let cp1252High: [UInt32] = [
        0x20AC, 0x81, 0x201A, 0x0192, 0x201E, 0x2026, 0x2020, 0x2021, 0x02C6, 0x2030, 0x0160, 0x2039, 0x0152, 0x8D, 0x017D, 0x8F,
        0x90, 0x2018, 0x2019, 0x201C, 0x201D, 0x2022, 0x2013, 0x2014, 0x02DC, 0x2122, 0x0161, 0x203A, 0x0153, 0x9D, 0x017E, 0x0178,
    ]

    private static func windows1252(_ bytes: [UInt8]) -> String {
        var v = String.UnicodeScalarView()
        for b in bytes {
            if b >= 0x80 && b <= 0x9F {
                v.append(Unicode.Scalar(cp1252High[Int(b) - 0x80]) ?? Unicode.Scalar(b))
            } else {
                v.append(Unicode.Scalar(b))
            }
        }
        return String(v)
    }
}
