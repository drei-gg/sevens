// WP 1.6 owns this file. Signatures below are the seam PeekAnalyzer (WP 2.1) calls; keep them.

/// What a forgiving tag scan finds in a page (SPEC §6). Not a DOM.
public struct HTMLSniff: Sendable, Hashable {
    public struct Input: Sendable, Hashable {
        public var type: String?, name: String?, id: String?, autocomplete: String?, placeholder: String?
        public init(type: String? = nil, name: String? = nil, id: String? = nil, autocomplete: String? = nil, placeholder: String? = nil) {
            self.type = type
            self.name = name
            self.id = id
            self.autocomplete = autocomplete
            self.placeholder = placeholder
        }
    }

    /// Entities decoded, whitespace collapsed, at most 200 characters.
    public var title: String?
    /// Every `<form action>` as written (nil entry for a form without action).
    public var formActions: [String?]
    public var inputs: [Input]
    /// The URL part of a `<meta http-equiv="refresh">`, if any.
    public var metaRefreshURL: String?
    /// Inline script assigns `location` / `location.href` or calls `location.replace` / `location.assign` (SPEC §6.1). Reading does not count.
    public var scriptRedirect: Bool
    public var scriptCount: Int
    /// Characters of visible text (outside tags, script and style), whitespace collapsed.
    public var visibleTextLength: Int

    public init(title: String? = nil, formActions: [String?] = [], inputs: [Input] = [], metaRefreshURL: String? = nil,
                scriptRedirect: Bool = false, scriptCount: Int = 0, visibleTextLength: Int = 0) {
        self.title = title
        self.formActions = formActions
        self.inputs = inputs
        self.metaRefreshURL = metaRefreshURL
        self.scriptRedirect = scriptRedirect
        self.scriptCount = scriptCount
        self.visibleTextLength = visibleTextLength
    }
}

public enum HTMLSniffer {
    /// Largest body looked at; the rest is ignored (PeekClient stops at the same size).
    static let maxBytes = 512 * 1024

    /// Decodes by the header `charset`, else a `<meta>` charset, else UTF-8 with replacement.
    public static func sniff(body: [UInt8], contentType: String?) -> HTMLSniff {
        let bytes = body.count > maxBytes ? Array(body[0..<maxBytes]) : body
        let text = HTMLCharset.decode(bytes, contentType: contentType)
        var scanner = HTMLScanner(Array(text.utf8))
        return scanner.scan()
    }
}
