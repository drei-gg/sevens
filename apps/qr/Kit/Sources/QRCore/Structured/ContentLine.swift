import Foundation

/// One unfolded vCard / iCalendar content line: `group.NAME;PARAM=v;FLAG:value`.
struct ContentLine {
    var name: String
    var params: [(key: String, value: String)]
    var rawValue: String

    func param(_ key: String) -> String? { params.first { $0.key == key }?.value }

    var isQuotedPrintable: Bool {
        params.contains { $0.key == "QUOTED-PRINTABLE" || ($0.key == "ENCODING" && $0.value.uppercased() == "QUOTED-PRINTABLE") }
    }

    /// Quoted-printable decoded (when flagged) with newlines normalised; escapes untouched.
    func decoded(_ raw: String) -> String {
        let s = isQuotedPrintable ? QuotedPrintable.decode(raw, charset: param("CHARSET")) : raw
        return StructuredText.normalizeNewlines(s)
    }

    /// The whole value as TEXT.
    var text: String { StructuredText.unescapeText(decoded(rawValue)) }

    /// Structured value split on `sep`, each component as TEXT.
    func components(_ sep: Unicode.Scalar) -> [String] {
        StructuredText.splitUnescaped(rawValue, sep).map { StructuredText.unescapeText(decoded($0)) }
    }
}

enum ContentLines {
    /// Unfolds (CRLF or LF then space/tab) and joins quoted-printable soft line breaks.
    static func logical(_ text: String) -> [String] {
        let norm = StructuredText.normalizeNewlines(text)
        var lines: [String] = []
        var cur: String?
        for raw in norm.split(separator: "\n", omittingEmptySubsequences: false) {
            let line = String(raw)
            if let c = cur, isQuotedPrintableOpen(c) {
                cur = String(c.dropLast()) + line
                continue
            }
            if let c = cur, let f = line.unicodeScalars.first, f == " " || f == "\t" {
                cur = c + String(line.dropFirst())
                continue
            }
            if let c = cur { lines.append(c) }
            cur = line
        }
        if let c = cur { lines.append(c) }
        return lines
    }

    private static func isQuotedPrintableOpen(_ line: String) -> Bool {
        guard line.unicodeScalars.last == "=", let colon = line.firstIndex(of: ":") else { return false }
        return line[..<colon].uppercased().contains("QUOTED-PRINTABLE")
    }

    static func parse(_ line: String) -> ContentLine? {
        let sc = Array(line.unicodeScalars)
        var inQuote = false
        var colon: Int?
        for (i, c) in sc.enumerated() {
            if c == "\"" { inQuote.toggle() } else if c == ":" && !inQuote { colon = i; break }
        }
        guard let ci = colon else { return nil }
        var parts: [String] = []
        var cur: [Unicode.Scalar] = []
        inQuote = false
        for c in sc[..<ci] {
            if c == "\"" { inQuote.toggle() }
            if c == ";" && !inQuote {
                parts.append(StructuredText.make(cur)); cur = []
            } else {
                cur.append(c)
            }
        }
        parts.append(StructuredText.make(cur))
        var name = parts[0].trimmingCharacters(in: .whitespaces).uppercased()
        if let dot = name.firstIndex(of: ".") { name = String(name[name.index(after: dot)...]) }
        var params: [(key: String, value: String)] = []
        for p in parts.dropFirst() {
            let (k, v) = StructuredText.splitFirst(p, "=")
            let key = k.trimmingCharacters(in: .whitespaces).uppercased()
            if key.isEmpty { continue }
            var val = (v ?? "").trimmingCharacters(in: .whitespaces)
            if val.count >= 2, val.hasPrefix("\""), val.hasSuffix("\"") { val = String(val.dropFirst().dropLast()) }
            params.append((key, val))
        }
        return ContentLine(name: name, params: params, rawValue: StructuredText.make(Array(sc[(ci + 1)...])))
    }
}
