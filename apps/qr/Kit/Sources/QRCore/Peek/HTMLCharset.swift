import Foundation

/// Byte-to-text decoding for HTMLSniffer. Only encodings that behave the same on every platform.
enum HTMLCharset {
    enum Encoding: Equatable { case utf8, windows1252, utf16LE, utf16BE }

    static func decode(_ bytes: [UInt8], contentType: String?) -> String {
        var body = bytes[...]
        var encoding: Encoding
        if body.starts(with: [0xEF, 0xBB, 0xBF]) {
            body = body.dropFirst(3)
            encoding = .utf8
        } else if body.starts(with: [0xFF, 0xFE]) {
            body = body.dropFirst(2)
            encoding = .utf16LE
        } else if body.starts(with: [0xFE, 0xFF]) {
            body = body.dropFirst(2)
            encoding = .utf16BE
        } else if let label = contentType.flatMap(charset(inHeader:)) ?? metaCharset(bytes) {
            encoding = encodingFor(label: label)
        } else {
            encoding = .utf8
        }
        switch encoding {
        case .utf8: return String(decoding: body, as: UTF8.self)
        case .windows1252: return String(String.UnicodeScalarView(body.map { windows1252($0) }))
        case .utf16LE: return utf16(body, bigEndian: false)
        case .utf16BE: return utf16(body, bigEndian: true)
        }
    }

    static func encodingFor(label raw: String) -> Encoding {
        switch raw.lowercased() {
        case "utf-8", "utf8", "unicode-1-1-utf-8": return .utf8
        // The HTML standard decodes these as windows-1252.
        case "iso-8859-1", "iso8859-1", "iso_8859-1", "latin1", "latin-1", "l1", "us-ascii", "ascii",
             "windows-1252", "cp1252", "x-cp1252": return .windows1252
        case "utf-16", "utf-16le", "ucs-2": return .utf16LE
        case "utf-16be": return .utf16BE
        default: return .utf8
        }
    }

    /// `charset=` in a Content-Type value.
    static func charset(inHeader header: String) -> String? {
        let lower = Array(header.lowercased().utf8)
        return token(after: Array("charset".utf8), in: lower, from: 0)
    }

    /// `<meta charset=…>` or `<meta http-equiv=Content-Type content="…charset=…">` in the first 1024 bytes.
    static func metaCharset(_ bytes: [UInt8]) -> String? {
        let head = bytes.prefix(1024).map { $0 >= 0x41 && $0 <= 0x5A ? $0 + 32 : $0 }
        let meta = Array("<meta".utf8), charset = Array("charset".utf8)
        var i = 0
        while let at = find(meta, in: head, from: i) {
            let end = head[at...].firstIndex(of: 0x3E) ?? head.count
            if let t = token(after: charset, in: Array(head[at..<end]), from: 0) { return t }
            i = at + 5
        }
        return nil
    }

    private static func token(after key: [UInt8], in hay: [UInt8], from: Int) -> String? {
        guard let at = find(key, in: hay, from: from) else { return nil }
        var i = at + key.count
        while i < hay.count, hay[i] == 0x20 || hay[i] == 0x09 { i += 1 }
        guard i < hay.count, hay[i] == 0x3D else { return nil }
        i += 1
        while i < hay.count, hay[i] == 0x20 || hay[i] == 0x09 { i += 1 }
        if i < hay.count, hay[i] == 0x22 || hay[i] == 0x27 { i += 1 }
        let start = i
        while i < hay.count, (hay[i] >= 0x61 && hay[i] <= 0x7A) || (hay[i] >= 0x30 && hay[i] <= 0x39) || hay[i] == 0x2D || hay[i] == 0x5F { i += 1 }
        return i > start ? String(decoding: hay[start..<i], as: UTF8.self) : nil
    }

    private static func utf16(_ body: ArraySlice<UInt8>, bigEndian: Bool) -> String {
        var units: [UInt16] = []
        units.reserveCapacity(body.count / 2)
        var it = body.makeIterator()
        while let a = it.next(), let b = it.next() {
            units.append(bigEndian ? UInt16(a) << 8 | UInt16(b) : UInt16(b) << 8 | UInt16(a))
        }
        return String(decoding: units, as: UTF16.self)
    }

    private static let cp1252High: [UInt32] = [
        0x20AC, 0x81, 0x201A, 0x0192, 0x201E, 0x2026, 0x2020, 0x2021, 0x02C6, 0x2030, 0x0160, 0x2039, 0x0152, 0x8D, 0x017D, 0x8F,
        0x90, 0x2018, 0x2019, 0x201C, 0x201D, 0x2022, 0x2013, 0x2014, 0x02DC, 0x2122, 0x0161, 0x203A, 0x0153, 0x9D, 0x017E, 0x0178,
    ]

    private static func windows1252(_ b: UInt8) -> Unicode.Scalar {
        let v = (b >= 0x80 && b <= 0x9F) ? cp1252High[Int(b) - 0x80] : UInt32(b)
        return Unicode.Scalar(v) ?? "\u{FFFD}"
    }
}

/// First index at or after `from` where `needle` occurs in `hay`, or nil.
func find(_ needle: [UInt8], in hay: [UInt8], from: Int) -> Int? {
    guard !needle.isEmpty, hay.count >= needle.count, from >= 0 else { return nil }
    let last = hay.count - needle.count
    var i = from
    let first = needle[0]
    while i <= last {
        if hay[i] == first {
            var j = 1
            while j < needle.count, hay[i + j] == needle[j] { j += 1 }
            if j == needle.count { return i }
        }
        i += 1
    }
    return nil
}
