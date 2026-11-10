import Foundation

enum ObfuscatedPathCheck {
    /// A bait word in host or path, and (path+query over 150 characters, or 10+ percent-escapes,
    /// or another http URL inside the path).
    static func signal(_ link: LinkInfo, baitWords: [String]) -> Signal? {
        let host = link.hostUnicode.lowercased()
        let path = link.pathAndQuery.lowercased()
        guard let bait = baitWords.first(where: { host.contains($0) || path.contains($0) }) else { return nil }
        let long = link.pathAndQuery.count > 150
        let escapes = percentEscapes(in: link.pathAndQuery)
        let nested = ["http://", "https://", "http%3a%2f%2f", "https%3a%2f%2f"].contains { path.contains($0) }
        guard long || escapes >= 10 || nested else { return nil }
        return Signal(.obfuscatedPath, ["word": bait])
    }

    static func percentEscapes(in s: String) -> Int {
        let u = Array(s.utf8)
        var n = 0
        var i = 0
        while i + 2 < u.count {
            if u[i] == 0x25, isHex(u[i + 1]), isHex(u[i + 2]) { n += 1; i += 3 } else { i += 1 }
        }
        return n
    }

    private static func isHex(_ b: UInt8) -> Bool {
        (48...57).contains(b) || (65...70).contains(b) || (97...102).contains(b)
    }
}
