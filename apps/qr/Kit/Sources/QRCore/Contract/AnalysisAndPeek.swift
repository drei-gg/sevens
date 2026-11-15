// Contract, frozen at qr-contract-1.
// `Analyzer` lives in Analyzer.swift and `PeekAnalyzer` in Peek/PeekAnalyzer.swift (WP 2.1).

public struct Analysis: Sendable, Hashable {
    public var code: ScannedCode
    public var payload: Payload
    /// This payload's own, in a stable order (SPEC §4.2).
    public var signals: [Signal]
    /// Links found inside text, contacts, events, SMS and email bodies.
    public var embedded: [Analysis]
    /// Max over self and embedded.
    public var level: Level

    public init(code: ScannedCode, payload: Payload, signals: [Signal], embedded: [Analysis], level: Level) {
        self.code = code
        self.payload = payload
        self.signals = signals
        self.embedded = embedded
        self.level = level
    }

    /// Every signal of this analysis and its embedded analyses, own first.
    public var allSignals: [Signal] { signals + embedded.flatMap(\.allSignals) }
}

// --- Peek (pure half) ---

public struct PeekHop: Sendable, Hashable, Codable {
    public var url: String
    public var status: Int?
    public init(url: String, status: Int?) {
        self.url = url
        self.status = status
    }
}

public enum StopReason: String, Sendable, Codable {
    case finished, timeout, httpTarget, nonWebTarget, tooManyRedirects, tlsFailure, networkError
}

/// What PeekClient hands back.
public struct PeekCapture: Sendable {
    /// Every URL requested or redirected to, in order; the first is the scanned link.
    /// A redirect target that was not fetched (http, non-web) is the last hop with status nil.
    public var hops: [PeekHop]
    public var stoppedBecause: StopReason
    public var contentType: String?, contentDisposition: String?
    /// ≤ 512 KB.
    public var body: [UInt8]
    public var tlsVersion: String?

    public init(hops: [PeekHop], stoppedBecause: StopReason, contentType: String? = nil,
                contentDisposition: String? = nil, body: [UInt8] = [], tlsVersion: String? = nil) {
        self.hops = hops
        self.stoppedBecause = stoppedBecause
        self.contentType = contentType
        self.contentDisposition = contentDisposition
        self.body = body
        self.tlsVersion = tlsVersion
    }
}

public struct PeekReport: Sendable {
    public var finalLink: LinkInfo?
    /// Offline checks rerun on every hop.
    public var hopAnalyses: [Analysis]
    public var title: String?
    public var signals: [Signal]
    /// Combined with the original scan's level.
    public var level: Level

    public init(finalLink: LinkInfo?, hopAnalyses: [Analysis], title: String?, signals: [Signal], level: Level) {
        self.finalLink = finalLink
        self.hopAnalyses = hopAnalyses
        self.title = title
        self.signals = signals
        self.level = level
    }
}

// --- make ---

public enum MakeRequest: Sendable, Hashable {
    case link(String), text(String), wifi(WiFiInfo), contact(ContactInfo),
         event(EventInfo), email(EmailInfo), sms(SMSInfo), phone(String), geo(GeoInfo)
}
