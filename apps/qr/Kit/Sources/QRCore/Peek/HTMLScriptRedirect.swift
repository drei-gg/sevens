/// Decides whether inline script *navigates* (SPEC §6.1): an assignment to `location` or to
/// `location.href`, or a call to `location.replace` / `location.assign`, with optional `window.`,
/// `document.`, `top.`, `self.`, `parent.` in front, dot or bracket access, optional spaces.
/// Reading, comparing, or a longer name (`mylocation`, `geolocation`) does not count.
///
/// A forgiving single pass over lowercased bytes: it looks for each `location` word and checks a
/// short, capped window around it, so the cost is linear in the script size.
enum HTMLScriptRedirect {
    private static let word = Array("location".utf8)
    private static let roots: Set<String> = ["window", "document", "top", "self", "parent", "frames", "globalthis"]
    private static let maxSkip = 64

    /// `s` must already be ASCII-lowercased.
    static func contains(_ s: ArraySlice<UInt8>) -> Bool {
        let t = s
        let n = t.endIndex
        let w = word.count
        guard t.count >= w else { return false }
        var i = t.startIndex
        let last = n - w
        while i <= last {
            guard t[i] == word[0] else { i += 1; continue }
            var k = 1
            while k < w, t[i + k] == word[k] { k += 1 }
            if k == w, isStandalone(t, at: i), assigns(t, after: i + w) { return true }
            i += 1
        }
        return false
    }

    // MARK: before the word

    private static func isIdent(_ c: UInt8) -> Bool {
        (c >= 0x61 && c <= 0x7A) || (c >= 0x30 && c <= 0x39) || c == 0x5F || c == 0x24 || c >= 0x80
    }
    private static func isSpace(_ c: UInt8) -> Bool { c == 0x20 || c == 0x09 || c == 0x0A || c == 0x0D || c == 0x0C }

    /// The word is not the tail of a longer name, and a leading `x.` is a known global object.
    private static func isStandalone(_ t: ArraySlice<UInt8>, at i: Int) -> Bool {
        var j = i - 1
        if j >= t.startIndex, isIdent(t[j]) { return false }                   // mylocation, geolocation
        var skipped = 0
        while j >= t.startIndex, isSpace(t[j]), skipped < maxSkip { j -= 1; skipped += 1 }
        guard j >= t.startIndex, t[j] == 0x2E else { return true }             // not after a dot
        j -= 1
        while j >= t.startIndex, isSpace(t[j]), skipped < maxSkip { j -= 1; skipped += 1 }
        let end = j + 1
        while j >= t.startIndex, isIdent(t[j]), end - j <= 12 { j -= 1 }
        let len = end - (j + 1)
        guard len > 0, len <= 10 else { return false }
        if j >= t.startIndex, t[j] == 0x2E { return false }                    // a.window.location: not a global
        return roots.contains(String(decoding: t[(j + 1)..<end], as: UTF8.self))
    }

    // MARK: after the word

    private static func skipSpaces(_ t: ArraySlice<UInt8>, _ i: Int) -> Int {
        var j = i
        var k = 0
        while j < t.endIndex, isSpace(t[j]), k < maxSkip { j += 1; k += 1 }
        return j
    }

    /// `= value` but not `==`, `===` or `=>`.
    private static func isAssignment(_ t: ArraySlice<UInt8>, _ i: Int) -> Bool {
        guard i < t.endIndex, t[i] == 0x3D else { return false }
        return !(i + 1 < t.endIndex && (t[i + 1] == 0x3D || t[i + 1] == 0x3E))
    }

    private static func assigns(_ t: ArraySlice<UInt8>, after start: Int) -> Bool {
        let i = skipSpaces(t, start)
        guard i < t.endIndex else { return false }
        if isAssignment(t, i) { return true }                                  // location = …
        let name: ArraySlice<UInt8>
        var j: Int
        if t[i] == 0x2E {                                                      // location.name
            j = skipSpaces(t, i + 1)
            let s = j
            while j < t.endIndex, isIdent(t[j]), j - s <= 8 { j += 1 }
            name = t[s..<j]
        } else if t[i] == 0x5B {                                               // location["name"]
            j = skipSpaces(t, i + 1)
            guard j < t.endIndex, t[j] == 0x22 || t[j] == 0x27 || t[j] == 0x60 else { return false }
            let quote = t[j]
            let s = j + 1
            j = s
            while j < t.endIndex, t[j] != quote, j - s <= 8 { j += 1 }
            guard j < t.endIndex, t[j] == quote else { return false }
            name = t[s..<j]
            j = skipSpaces(t, j + 1)
            guard j < t.endIndex, t[j] == 0x5D else { return false }
            j += 1
        } else {
            return false
        }
        j = skipSpaces(t, j)
        switch String(decoding: name, as: UTF8.self) {
        case "href": return isAssignment(t, j)
        case "replace", "assign": return j < t.endIndex && t[j] == 0x28
        default: return false
        }
    }
}
