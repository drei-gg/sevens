import Foundation

/// mailto: / MATMSG:
public enum EmailParser {
    public static func parse(_ text: String) -> EmailInfo? {
        let t = StructuredText.prepared(text)
        if let rest = StructuredText.stripPrefix(t, "mailto:") { return mailto(StructuredText.trimmed(rest)) }
        if let body = StructuredText.stripPrefix(t, "MATMSG:") { return matmsg(body) }
        return nil
    }

    private static func firstAddress(_ list: String) -> String? {
        let first = list.split(separator: ",", omittingEmptySubsequences: true).first.map(String.init) ?? ""
        return StructuredText.nonEmpty(StructuredText.percentDecode(first))
    }

    private static func mailto(_ rest: String) -> EmailInfo? {
        let (path, query) = StructuredText.splitFirst(rest, "?")
        let pairs = query.map { StructuredText.queryPairs($0) } ?? []
        func get(_ k: String) -> String? { pairs.first { $0.key == k && !$0.value.isEmpty }?.value }
        guard let address = firstAddress(path) ?? get("to").flatMap({ StructuredText.nonEmpty($0.split(separator: ",").first.map(String.init)) })
        else { return nil }
        return EmailInfo(address: address, subject: get("subject"), body: get("body"))
    }

    private static func matmsg(_ body: String) -> EmailInfo? {
        var to: String?, sub: String?, text: String?
        for (key, raw) in StructuredText.keyed(body) {
            let v = StructuredText.unescapeBackslash(raw)
            switch key {
            case "TO": if to == nil { to = StructuredText.nonEmpty(v) }
            case "SUB": if sub == nil, !v.isEmpty { sub = v }
            case "BODY": if text == nil, !v.isEmpty { text = v }
            default: break
            }
        }
        guard let to else { return nil }
        return EmailInfo(address: to, subject: sub, body: text)
    }
}
