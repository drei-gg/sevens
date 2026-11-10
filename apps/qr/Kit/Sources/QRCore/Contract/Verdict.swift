// Contract (PLAN §5.4), frozen at qr-contract-1.

public enum Level: Int, Codable, Sendable, Comparable {
    case normal = 0, careful = 1, likelyScam = 2
    public static func < (a: Level, b: Level) -> Bool { a.rawValue < b.rawValue }
}

public enum Severity: String, Codable, Sendable {
    case info, warning, strong
}

public enum SignalID: String, Codable, Sendable, CaseIterable {
    // offline, links (SPEC 4.2)
    case dangerousScheme, userinfoTrick, mixedScript, lookalikeBrand, brandInDomain   // strong
    case nearBrand, ipHost, shortener, plainHTTP, riskyTLD, deepSubdomains,
         obfuscatedPath, unknownScheme                                                 // warning
    // offline, other payloads
    case openWiFi                                                                      // warning
    case paymentChecksumFail                                                           // strong
    // Peek (SPEC 6)
    case tlsProblem, secretFormBrandMismatch, domainVeryNew                            // strong
    case redirectsToHTTP, redirectLeavesWeb, tooManyRedirects, scriptRedirect,
         asksForSecret, formPostsElsewhere, downloadsFile, domainNew                   // warning
    // info: shown, never change the verdict
    case assumedHTTPS, sharedHostingDomain, needsJavaScript, ageUnknown, peekIncomplete

    /// Fixed per ID, exactly as grouped above.
    public var severity: Severity {
        switch self {
        case .dangerousScheme, .userinfoTrick, .mixedScript, .lookalikeBrand, .brandInDomain,
             .paymentChecksumFail, .tlsProblem, .secretFormBrandMismatch, .domainVeryNew:
            return .strong
        case .nearBrand, .ipHost, .shortener, .plainHTTP, .riskyTLD, .deepSubdomains,
             .obfuscatedPath, .unknownScheme, .openWiFi, .redirectsToHTTP, .redirectLeavesWeb,
             .tooManyRedirects, .scriptRedirect, .asksForSecret, .formPostsElsewhere,
             .downloadsFile, .domainNew:
            return .warning
        case .assumedHTTPS, .sharedHostingDomain, .needsJavaScript, .ageUnknown, .peekIncomplete:
            return .info
        }
    }
}

public struct Signal: Codable, Sendable, Hashable {
    public var id: SignalID
    /// e.g. ["brand": "PayPal"], ["days": "12"], ["tld": "zip"]
    public var args: [String: String]

    public init(_ id: SignalID, _ args: [String: String] = [:]) {
        self.id = id
        self.args = args
    }

    public var severity: Severity { id.severity }
}

extension Array where Element == Signal {
    /// PLAN §6.2: strong first, then warning, then info, each in the enum's declaration order.
    /// Stable: signals with the same ID keep their relative order.
    public func inStableOrder() -> [Signal] {
        let rank: (Signal) -> Int = { s in
            let sev: Int
            switch s.id.severity { case .strong: sev = 0; case .warning: sev = 1; case .info: sev = 2 }
            return sev * 1000 + (SignalID.allCases.firstIndex(of: s.id) ?? 999)
        }
        return enumerated().sorted { a, b in
            let ra = rank(a.element), rb = rank(b.element)
            return ra != rb ? ra < rb : a.offset < b.offset
        }.map(\.element)
    }
}
