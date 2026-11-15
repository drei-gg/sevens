import Foundation

enum HTMLEntities {
    /// Decodes `&amp; &lt; &gt; &quot; &apos; &nbsp;` and numeric references (decimal and hex).
    /// Anything else, or a reference without `;`, stays as written.
    static func decode<C: Collection>(_ bytes: C) -> String where C.Element == UInt8 {
        let b = Array(bytes)
        guard b.contains(0x26) else { return String(decoding: b, as: UTF8.self) }
        var out: [UInt8] = []
        out.reserveCapacity(b.count)
        var i = 0
        while i < b.count {
            if b[i] == 0x26, let (scalar, next) = reference(b, at: i) {
                out.append(contentsOf: Array(String(Character(scalar)).utf8))
                i = next
            } else {
                out.append(b[i])
                i += 1
            }
        }
        return String(decoding: out, as: UTF8.self)
    }

    private static func reference(_ b: [UInt8], at start: Int) -> (Unicode.Scalar, Int)? {
        var end = start + 1
        let limit = min(b.count, start + 12)
        while end < limit, b[end] != 0x3B, b[end] != 0x26, b[end] != 0x20 { end += 1 }
        guard end < b.count, b[end] == 0x3B, end > start + 1 else { return nil }
        let name = String(decoding: b[(start + 1)..<end], as: UTF8.self)
        let scalar: Unicode.Scalar?
        switch name {
        case "amp": scalar = "&"
        case "lt": scalar = "<"
        case "gt": scalar = ">"
        case "quot": scalar = "\""
        case "apos": scalar = "'"
        case "nbsp": scalar = "\u{A0}"
        default:
            if name.hasPrefix("#x") || name.hasPrefix("#X") {
                scalar = UInt32(name.dropFirst(2), radix: 16).flatMap(Unicode.Scalar.init)
            } else if name.hasPrefix("#") {
                scalar = UInt32(name.dropFirst(1), radix: 10).flatMap(Unicode.Scalar.init)
            } else {
                scalar = nil
            }
        }
        guard let scalar else { return nil }
        return (scalar, end + 1)
    }
}
