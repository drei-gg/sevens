import Foundation

/// sms: / smsto: / SMSTO:
public enum SMSParser {
    public static func parse(_ text: String) -> SMSInfo? {
        let t = StructuredText.prepared(text)
        if let rest = StructuredText.stripPrefix(t, "smsto:") {
            // SMSTO:number:body. The body is raw text (not percent-decoded) and may contain colons.
            let (num, body) = StructuredText.splitFirst(rest, ":")
            guard let number = StructuredText.nonEmpty(StructuredText.percentDecode(num)) else { return nil }
            let b = body.flatMap { $0.isEmpty ? nil : $0 }
            return SMSInfo(number: number, body: b)
        }
        guard let rest = StructuredText.stripPrefix(t, "sms:") else { return nil }
        let sc = Array(StructuredText.trimmed(rest).unicodeScalars)
        let cut = sc.firstIndex { $0 == "?" || $0 == "&" } ?? sc.count
        let path = StructuredText.make(Array(sc[..<cut]))
        guard let number = StructuredText.nonEmpty(StructuredText.percentDecode(path)) else { return nil }
        var body: String?
        if cut < sc.count {
            let q = StructuredText.make(Array(sc[(cut + 1)...]))
            body = StructuredText.queryPairs(q).first { $0.key == "body" }.flatMap { $0.value.isEmpty ? nil : $0.value }
        }
        return SMSInfo(number: number, body: body)
    }
}
