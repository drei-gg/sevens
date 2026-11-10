import Foundation

enum ObfuscatedPathCheck {
    /// A bait word in host or path, and (path over 150 characters, or 10+ percent-escapes in the path,
    /// or another http URL inside the path; the query and fragment never count).
    static func signal(_ link: LinkInfo, baitWords: [String]) -> Signal? {
        let host = link.hostUnicode.lowercased()
        let path = link.pathAndQuery.lowercased()
        guard let bait = baitWords.first(where: { host.contains($0) || path.contains($0) }) else { return nil }
        // Only the path counts, for length, escapes and nested links: sign-in links carry long,
        // encoded queries (SPEC §4.2).
        let rawPath = String(link.pathAndQuery.prefix { $0 != "?" && $0 != "#" })
        let pathOnly = rawPath.lowercased()
        let long = rawPath.count > 150
        let escapes = percentEscapes(in: rawPath)
        let nested = ["http://", "https://", "http%3a%2f%2f", "https%3a%2f%2f"].contains { pathOnly.contains($0) }
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
