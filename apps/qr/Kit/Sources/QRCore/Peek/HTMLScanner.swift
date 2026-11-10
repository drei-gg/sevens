import Foundation

/// The forgiving tag scanner behind `HTMLSniffer`. Works on UTF-8 bytes; every loop advances,
/// so a body cut mid-tag ends the scan instead of looping.
struct HTMLScanner {
    private let b: [UInt8]
    private let lower: [UInt8]   // ASCII-lowercased copy, same length

    private var result = HTMLSniff()
    private var visible = 0
    private var lastWasSpace = true

    init(_ bytes: [UInt8]) {
        b = bytes
        lower = bytes.map { $0 >= 0x41 && $0 <= 0x5A ? $0 + 32 : $0 }
    }

    mutating func scan() -> HTMLSniff {
        var i = 0
        let n = b.count
        while i < n {
            if b[i] != 0x3C {
                let end = b[i...].firstIndex(of: 0x3C) ?? n
                addText(b[i..<end])
                i = end
                continue
            }
            if i + 3 < n, b[i + 1] == 0x21, b[i + 2] == 0x2D, b[i + 3] == 0x2D {          // <!--
                i = find(Array("-->".utf8), in: b, from: i + 4).map { $0 + 3 } ?? n
                continue
            }
            if i + 1 < n, b[i + 1] == 0x21 || b[i + 1] == 0x3F {                          // <!doctype, <?xml
                i = (b[i...].firstIndex(of: 0x3E)).map { $0 + 1 } ?? n
                continue
            }
            let closing = i + 1 < n && b[i + 1] == 0x2F
            let nameStart = i + (closing ? 2 : 1)
            guard nameStart < n, isLetter(lower[nameStart]) else {
                addText(b[i..<(i + 1)])                                                    // a lone "<" is text
                i += 1
                continue
            }
            var nameEnd = nameStart
            while nameEnd < n, isNameByte(lower[nameEnd]) { nameEnd += 1 }
            let name = String(decoding: lower[nameStart..<nameEnd], as: UTF8.self)
            guard let (attrs, tagEnd) = parseAttributes(from: nameEnd) else { break }       // cut mid-tag
            i = tagEnd
            if closing { continue }
            switch name {
            case "title": i = readTitle(from: i)
            case "script": i = readScript(attrs: attrs, from: i)
            case "style": i = skipRaw("</style", from: i)
            case "form": result.formActions.append(attrs["action"].map { HTMLEntities.decode(Array($0.utf8)) })
            case "input":
                result.inputs.append(.init(type: attrs["type"], name: attrs["name"], id: attrs["id"],
                                           autocomplete: attrs["autocomplete"], placeholder: attrs["placeholder"]))
            case "meta": readMeta(attrs)
            default: break
            }
        }
        result.visibleTextLength = visible
        return result
    }

    // MARK: tags

    /// Attribute names lowercased, first occurrence wins, values as written. Returns the index after `>`;
    /// nil when the input ends inside the tag.
    private func parseAttributes(from start: Int) -> ([String: String], Int)? {
        var attrs: [String: String] = [:]
        var i = start
        let n = b.count
        while i < n {
            let c = b[i]
            if c == 0x3E { return (attrs, i + 1) }
            if isSpace(c) || c == 0x2F { i += 1; continue }
            let nameStart = i
            while i < n, !isSpace(b[i]), b[i] != 0x3D, b[i] != 0x3E, b[i] != 0x2F { i += 1 }
            if i == nameStart { i += 1; continue }                                         // stray "=": skip it
            let key = String(decoding: lower[nameStart..<i], as: UTF8.self)
            var j = i
            while j < n, isSpace(b[j]) { j += 1 }
            var value = ""
            if j < n, b[j] == 0x3D {
                j += 1
                while j < n, isSpace(b[j]) { j += 1 }
                guard j < n else { return nil }
                if b[j] == 0x22 || b[j] == 0x27 {
                    let quote = b[j]
                    guard let close = b[(j + 1)...].firstIndex(of: quote) else { return nil }
                    value = String(decoding: b[(j + 1)..<close], as: UTF8.self)
                    i = close + 1
                } else {
                    let vs = j
                    while j < n, !isSpace(b[j]), b[j] != 0x3E { j += 1 }
                    value = String(decoding: b[vs..<j], as: UTF8.self)
                    i = j
                }
            }
            if attrs[key] == nil { attrs[key] = value }
        }
        return nil
    }

    private mutating func readTitle(from i: Int) -> Int {
        guard result.title == nil else { return i }
        let close = find(Array("</title".utf8), in: lower, from: i)
        let end = close ?? min(b.count, i + 2000)
        let text = collapse(HTMLEntities.decode(b[i..<end]))
        if !text.isEmpty { result.title = String(text.prefix(200)) }
        return close ?? end
    }

    private mutating func readScript(attrs: [String: String], from i: Int) -> Int {
        result.scriptCount += 1
        let close = find(Array("</script".utf8), in: lower, from: i)
        let end = close ?? b.count
        if attrs["src"] == nil, !result.scriptRedirect, containsRedirect(lower[i..<end]) {
            result.scriptRedirect = true
        }
        return end
    }

    private func skipRaw(_ closeTag: String, from i: Int) -> Int {
        find(Array(closeTag.utf8), in: lower, from: i) ?? b.count
    }

    private mutating func readMeta(_ attrs: [String: String]) {
        guard result.metaRefreshURL == nil,
              attrs["http-equiv"]?.trimmingCharacters(in: .whitespaces).lowercased() == "refresh",
              let content = attrs["content"] else { return }
        result.metaRefreshURL = Self.refreshURL(HTMLEntities.decode(Array(content.utf8)))
    }

    /// `5; url=https://x` -> `https://x`; no URL -> nil.
    static func refreshURL(_ content: String) -> String? {
        guard let sep = content.firstIndex(where: { $0 == ";" || $0 == "," }) else { return nil }
        var rest = content[content.index(after: sep)...].trimmingCharacters(in: .whitespacesAndNewlines)
        if rest.lowercased().hasPrefix("url") {
            let after = rest.dropFirst(3).drop(while: { $0 == " " || $0 == "\t" })
            if after.first == "=" { rest = String(after.dropFirst()).trimmingCharacters(in: .whitespacesAndNewlines) }
        }
        let quotes: Set<Character> = ["\"", "'"]
        while let f = rest.first, quotes.contains(f) { rest.removeFirst() }
        while let l = rest.last, quotes.contains(l) { rest.removeLast() }
        rest = rest.trimmingCharacters(in: .whitespacesAndNewlines)
        return rest.isEmpty ? nil : rest
    }

    private func containsRedirect(_ s: ArraySlice<UInt8>) -> Bool {
        let t = Array(s)
        for p in ["location.href", "location.replace", "location.assign", "top.location"]
        where find(Array(p.utf8), in: t, from: 0) != nil { return true }
        let key = Array("window.location".utf8)
        var from = 0
        while let at = find(key, in: t, from: from) {
            var j = at + key.count
            while j < t.count, isSpace(t[j]) { j += 1 }
            if j < t.count, t[j] == 0x3D, !(j + 1 < t.count && t[j + 1] == 0x3D) { return true }
            from = at + 1
        }
        return false
    }

    // MARK: text

    private mutating func addText(_ run: ArraySlice<UInt8>) {
        for s in HTMLEntities.decode(run).unicodeScalars {
            if Self.isWhitespace(s) {
                lastWasSpace = true
            } else {
                if lastWasSpace, visible > 0 { visible += 1 }
                visible += 1
                lastWasSpace = false
            }
        }
    }

    private func collapse(_ s: String) -> String {
        var out = String.UnicodeScalarView()
        var pendingSpace = false
        for u in s.unicodeScalars {
            if Self.isWhitespace(u) { pendingSpace = !out.isEmpty; continue }
            if pendingSpace { out.append(" "); pendingSpace = false }
            out.append(u)
        }
        return String(out)
    }

    private static func isWhitespace(_ s: Unicode.Scalar) -> Bool {
        s == " " || s == "\t" || s == "\n" || s == "\r" || s == "\u{0C}" || s == "\u{A0}" || s.properties.isWhitespace
    }

    private func isSpace(_ c: UInt8) -> Bool { c == 0x20 || c == 0x09 || c == 0x0A || c == 0x0D || c == 0x0C }
    private func isLetter(_ c: UInt8) -> Bool { c >= 0x61 && c <= 0x7A }
    private func isNameByte(_ c: UInt8) -> Bool { isLetter(c) || (c >= 0x30 && c <= 0x39) || c == 0x2D || c == 0x3A }
}
