import Foundation
import QRCore

/// Pure text for a History row (SPEC §10): a short payload (the host for links, a cut first
/// line for anything else) and a note preview. Payloads come from scanned codes, so control
/// characters are dropped and everything is length-capped.
enum HistoryRowSummary {
    static let maxPayloadLength = 60
    static let maxNoteLength = 80

    static func shortPayload(for item: HistoryItem) -> String {
        if item.payloadEncoding == .base64 {
            guard let data = Data(base64Encoded: item.payload) else {
                return String(localized: "history.row.binary.unknown", defaultValue: "Binary data", bundle: .module)
            }
            return String(localized: "history.row.binary",
                          defaultValue: "Binary data, \(data.count) bytes", bundle: .module)
        }
        if item.typeID == "link" || item.typeID == "appLink", let host = host(in: item.payload) {
            return truncated(host, limit: maxPayloadLength)
        }
        if item.typeID == "wifi", let name = field("S", in: item.payload, separator: ";") {
            return truncated(name, limit: maxPayloadLength)
        }
        if item.typeID == "contact", let name = contactName(in: item.payload) {
            return truncated(name, limit: maxPayloadLength)
        }
        return truncated(item.payload, limit: maxPayloadLength)
    }

    /// A Wi-Fi code's network name (`WIFI:…;S:<name>;…`), with `\;`-style escapes undone.
    /// Display only. nil when it has none.
    static func field(_ key: String, in payload: String, separator: Character) -> String? {
        var parts: [String] = [], current = "", escaped = false
        let body = payload.drop { $0 != ":" }.dropFirst()
        for ch in body {
            if escaped { current.append(ch); escaped = false }
            else if ch == "\\" { escaped = true }
            else if ch == separator { parts.append(current); current = "" }
            else { current.append(ch) }
        }
        parts.append(current)
        let value = parts.first { $0.hasPrefix(key + ":") }.map { String($0.dropFirst(key.count + 1)) }
        return value.flatMap { $0.trimmingCharacters(in: .whitespaces).isEmpty ? nil : $0 }
    }

    /// A vCard's `FN` (or `N`) line, or a MeCard's `N:`. Display only; nil when there is none.
    static func contactName(in payload: String) -> String? {
        if payload.uppercased().hasPrefix("MECARD:") {
            let name = field("N", in: payload, separator: ";")
            return name?.replacingOccurrences(of: ",", with: " ")
        }
        let lines = payload.split(whereSeparator: \.isNewline).map(String.init)
        func value(_ name: String) -> String? {
            guard let line = lines.first(where: {
                let head = $0.prefix { $0 != ":" }.uppercased()
                return head == name || head.hasPrefix(name + ";")
            }), let colon = line.firstIndex(of: ":") else { return nil }
            let text = line[line.index(after: colon)...].trimmingCharacters(in: .whitespaces)
            return text.isEmpty ? nil : text
        }
        if let full = value("FN") { return full }
        return value("N").map { name in
            name.split(separator: ";").reversed().filter { part in !part.isEmpty }.joined(separator: " ")
        }
    }

    /// nil when the note is missing or blank.
    static func notePreview(_ note: String?) -> String? {
        guard let note else { return nil }
        let text = truncated(note, limit: maxNoteLength)
        return text.isEmpty ? nil : text
    }

    /// The host of an `scheme://authority/…` string: userinfo and port removed, lowercased.
    /// This is display only; no decision is made from it. nil when there is no `://` or no host.
    static func host(in payload: String) -> String? {
        let text = payload.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let marker = text.range(of: "://") else { return nil }
        let scheme = text[text.startIndex..<marker.lowerBound]
        guard let first = scheme.first, first.isASCII, first.isLetter,
              scheme.allSatisfy({ $0.isASCII && ($0.isLetter || $0.isNumber || "+.-".contains($0)) }) else { return nil }
        let rest = text[marker.upperBound...]
        var authority = rest.prefix { !"/?#\\".contains($0) }
        if let at = authority.lastIndex(of: "@") { authority = authority[authority.index(after: at)...] }
        var host = String(authority)
        if host.hasPrefix("[") {
            if let close = host.firstIndex(of: "]") { host = String(host[...close]) }
        } else if let colon = host.lastIndex(of: ":"),
                  host[host.index(after: colon)...].allSatisfy({ $0.isASCII && $0.isNumber }) {
            host = String(host[..<colon])
        }
        host = cleaned(host).lowercased()
        return host.isEmpty ? nil : host
    }

    /// Whitespace and control characters collapsed to single spaces, then cut with "…".
    static func truncated(_ text: String, limit: Int) -> String {
        let flat = cleaned(text)
        guard flat.count > limit else { return flat }
        return String(flat.prefix(max(limit - 1, 0))) + "…"
    }

    private static func cleaned(_ text: String) -> String {
        var out = ""
        var pendingSpace = false
        for scalar in text.unicodeScalars {
            switch scalar.properties.generalCategory {
            case .control, .lineSeparator, .paragraphSeparator, .spaceSeparator:
                pendingSpace = !out.isEmpty
            case .format, .surrogate, .privateUse, .unassigned:
                continue
            default:
                if pendingSpace { out.append(" "); pendingSpace = false }
                out.unicodeScalars.append(scalar)
            }
        }
        return out
    }
}
