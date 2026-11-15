import Foundation
import QRCore

/// D5: QRCore gives signal IDs and args; this turns them into one plain sentence each.
/// Rules: at most 20 words, no blame, no scores, and never the banned reassurance word (G7).
/// Unknown fails soft (SPEC §4.2): where a list is only incomplete the sentence says what we
/// don't know ("an address we know for X"), not that the site is false; facts stay facts.
/// Every `SignalID` has a sentence, with or without its args (a test checks all of them).
///
/// Args read (all optional; a missing one gets a generic wording):
/// `brand` (lookalikeBrand, brandInDomain, nearBrand, secretFormBrandMismatch), `scheme`
/// (dangerousScheme, unknownScheme), `host` (userinfoTrick), `tld` (riskyTLD), `days`
/// (domainNew, domainVeryNew), `kind` = password | code | card (asksForSecret,
/// secretFormBrandMismatch).
public enum SignalText {
    /// The longest an arg is shown (they come from scanned codes).
    static let maxArgLength = 40

    public static func sentence(for signal: Signal) -> String {
        let args = signal.args
        switch signal.id {
        case .dangerousScheme:
            if let scheme = arg(args, "scheme") {
                return String(localized: "signal.dangerousScheme.scheme",
                              defaultValue: "This code starts with “\(scheme):”, which can run code or open files instead of showing a website.",
                              bundle: .module)
            }
            return String(localized: "signal.dangerousScheme",
                          defaultValue: "This code can run a script or open a file instead of showing a website.", bundle: .module)
        case .userinfoTrick:
            if let host = arg(args, "host") {
                return String(localized: "signal.userinfoTrick.host",
                              defaultValue: "The text before the @ sign is a name, not the destination. The link really goes to \(host).",
                              bundle: .module)
            }
            return String(localized: "signal.userinfoTrick",
                          defaultValue: "The text before the @ sign is a name, not the destination. The link goes to the address after it.",
                          bundle: .module)
        case .mixedScript:
            return String(localized: "signal.mixedScript",
                          defaultValue: "The address mixes letters from different alphabets, a trick used to imitate real sites.",
                          bundle: .module)
        case .lookalikeBrand:
            if let brand = arg(args, "brand") {
                return String(localized: "signal.lookalikeBrand.brand",
                              defaultValue: "This address imitates the look of \(brand), and it isn’t one we know for them.", bundle: .module)
            }
            return String(localized: "signal.lookalikeBrand",
                          defaultValue: "This address imitates the look of a well-known brand, and it isn’t one we know for them.", bundle: .module)
        case .brandInDomain:
            if let brand = arg(args, "brand") {
                return String(localized: "signal.brandInDomain.brand",
                              defaultValue: "The name \(brand) appears in this address, but it isn’t one we know for them.", bundle: .module)
            }
            return String(localized: "signal.brandInDomain",
                          defaultValue: "A well-known brand’s name appears in this address, but it isn’t one we know for them.",
                          bundle: .module)
        case .nearBrand:
            if let brand = arg(args, "brand") {
                return String(localized: "signal.nearBrand.brand",
                              defaultValue: "This address is one letter away from \(brand). Check the spelling closely.", bundle: .module)
            }
            return String(localized: "signal.nearBrand",
                          defaultValue: "This address is one letter away from a well-known brand. Check the spelling closely.",
                          bundle: .module)
        case .ipHost:
            return String(localized: "signal.ipHost",
                          defaultValue: "The address is a string of numbers instead of a name. Real services rarely do this.", bundle: .module)
        case .shortener:
            return String(localized: "signal.shortener",
                          defaultValue: "This is a shortened link, so its real destination is hidden until you follow it.", bundle: .module)
        case .plainHttp:
            return String(localized: "signal.plainHTTP",
                          defaultValue: "This link isn’t encrypted, so anyone on the network could read or change what you send.",
                          bundle: .module)
        case .riskyTld:
            if let ending = arg(args, "tld").map(withoutLeadingDots), !ending.isEmpty {
                return String(localized: "signal.riskyTLD.tld",
                              defaultValue: "Addresses ending in .\(ending) show up often in scam reports.", bundle: .module)
            }
            return String(localized: "signal.riskyTLD",
                          defaultValue: "This address uses an ending that shows up often in scam reports.", bundle: .module)
        case .deepSubdomains:
            return String(localized: "signal.deepSubdomains",
                          defaultValue: "The address has an unusually long chain of parts before the main name, which can hide the real site.",
                          bundle: .module)
        case .obfuscatedPath:
            return String(localized: "signal.obfuscatedPath",
                          defaultValue: "The address is very long or heavily encoded, which can hide where it really goes.", bundle: .module)
        case .unknownScheme:
            if let scheme = arg(args, "scheme") {
                return String(localized: "signal.unknownScheme.scheme",
                              defaultValue: "This code opens another app (“\(scheme):”) instead of a website.", bundle: .module)
            }
            return String(localized: "signal.unknownScheme",
                          defaultValue: "This code opens another app instead of a website.", bundle: .module)
        case .openWiFi:
            return String(localized: "signal.openWiFi",
                          defaultValue: "This Wi-Fi network has no password, so others nearby could see what you do on it.", bundle: .module)
        case .paymentChecksumFail:
            return String(localized: "signal.paymentChecksumFail",
                          defaultValue: "This payment code failed its built-in check. It may be damaged or altered.", bundle: .module)
        case .tlsProblem:
            return String(localized: "signal.tlsProblem",
                          defaultValue: "The site’s certificate didn’t check out, so this connection can’t be trusted.", bundle: .module)
        case .secretFormBrandMismatch:
            let what = secret(args["kind"])
            if let brand = arg(args, "brand") {
                return String(localized: "signal.secretFormBrandMismatch.brand",
                              defaultValue: "This page asks for \(what) and mentions \(brand), but it isn’t their site.", bundle: .module)
            }
            return String(localized: "signal.secretFormBrandMismatch",
                          defaultValue: "This page asks for \(what) and mentions a well-known brand, but it isn’t their site.",
                          bundle: .module)
        case .domainVeryNew:
            if let days = dayCount(args) {
                return String(localized: "signal.domainVeryNew.days",
                              defaultValue: "This domain was registered \(days) ago, which is very recent.", bundle: .module)
            }
            return String(localized: "signal.domainVeryNew",
                          defaultValue: "This domain was registered within the last month.", bundle: .module)
        case .redirectsToHttp:
            return String(localized: "signal.redirectsToHTTP",
                          defaultValue: "This link sends you on to a page that isn’t encrypted.", bundle: .module)
        case .redirectLeavesWeb:
            if let scheme = arg(args, "scheme") {
                return String(localized: "signal.redirectLeavesWeb.scheme",
                              defaultValue: "This link redirects out of the web to open another app (“\(scheme):”).", bundle: .module)
            }
            return String(localized: "signal.redirectLeavesWeb",
                          defaultValue: "This link redirects out of the web to open another app.", bundle: .module)
        case .tooManyRedirects:
            return String(localized: "signal.tooManyRedirects",
                          defaultValue: "This link bounces you through an unusually long chain of redirects.", bundle: .module)
        case .scriptRedirect:
            return String(localized: "signal.scriptRedirect",
                          defaultValue: "The page uses a script to send you somewhere else as soon as it loads.", bundle: .module)
        case .asksForSecret:
            let what = secret(args["kind"])
            return String(localized: "signal.asksForSecret",
                          defaultValue: "This page asks you to enter \(what).", bundle: .module)
        case .formPostsElsewhere:
            return String(localized: "signal.formPostsElsewhere",
                          defaultValue: "A form on this page sends what you type to a different website.", bundle: .module)
        case .downloadsFile:
            return String(localized: "signal.downloadsFile",
                          defaultValue: "This link downloads a file instead of showing a page.", bundle: .module)
        case .domainNew:
            if let days = dayCount(args) {
                return String(localized: "signal.domainNew.days",
                              defaultValue: "This domain was registered \(days) ago.", bundle: .module)
            }
            return String(localized: "signal.domainNew",
                          defaultValue: "This domain was registered within the last six months.", bundle: .module)
        case .assumedHttps:
            return String(localized: "signal.assumedHTTPS",
                          defaultValue: "The code had no prefix, so it was read as an https web address.", bundle: .module)
        case .sharedHostingDomain:
            return String(localized: "signal.sharedHostingDomain",
                          defaultValue: "This site sits on a shared hosting domain, so the domain’s age says little about it.", bundle: .module)
        case .needsJavaScript:
            return String(localized: "signal.needsJavaScript",
                          defaultValue: "The page shows almost nothing without JavaScript, so its contents couldn’t be read.", bundle: .module)
        case .ageUnknown:
            return String(localized: "signal.ageUnknown",
                          defaultValue: "The age of this domain couldn’t be found.", bundle: .module)
        case .peekIncomplete:
            return String(localized: "signal.peekIncomplete",
                          defaultValue: "Peek didn’t finish, so what it shows may be incomplete.", bundle: .module)
        case .brandOtherEnding:
            if let brand = arg(args, "brand") {
                return String(localized: "signal.brandOtherEnding.brand",
                              defaultValue: "This isn’t an address we know for \(brand).", bundle: .module)
            }
            return String(localized: "signal.brandOtherEnding",
                          defaultValue: "This uses a well-known brand’s name, but it isn’t an address we know for them.",
                          bundle: .module)
        case .unknownEnding:
            return String(localized: "signal.unknownEnding",
                          defaultValue: "We don’t recognize this domain ending.", bundle: .module)
        case .knownByYou:
            return String(localized: "signal.knownByYou",
                          defaultValue: "You told us you know this address.", bundle: .module)
        }
    }

    /// One sentence per signal, in the given order; identical sentences appear once.
    public static func sentences(for signals: [Signal]) -> [String] {
        var seen = Set<String>()
        return signals.map(sentence(for:)).filter { seen.insert($0).inserted }
    }

    // MARK: Args

    /// A non-empty arg without control or invisible formatting characters, cut to `maxArgLength`.
    static func arg(_ args: [String: String], _ key: String) -> String? {
        guard let raw = args[key] else { return nil }
        let kept = raw.unicodeScalars.filter { scalar in
            switch scalar.properties.generalCategory {
            case .control, .format, .lineSeparator, .paragraphSeparator, .surrogate, .privateUse, .unassigned:
                return false
            default:
                return true
            }
        }
        let text = String(String.UnicodeScalarView(kept)).trimmingCharacters(in: .whitespaces)
        guard !text.isEmpty else { return nil }
        return text.count > maxArgLength ? String(text.prefix(maxArgLength)) + "…" : text
    }

    private static func withoutLeadingDots(_ text: String) -> String {
        String(text.drop { dot in dot == "." })
    }

    private static func secret(_ kind: String?) -> String {
        switch kind {
        case "password":
            return String(localized: "signal.secret.password", defaultValue: "a password", bundle: .module)
        case "code":
            return String(localized: "signal.secret.code", defaultValue: "a one-time code or PIN", bundle: .module)
        case "card":
            return String(localized: "signal.secret.card", defaultValue: "card details", bundle: .module)
        default:
            return String(localized: "signal.secret.other", defaultValue: "private details", bundle: .module)
        }
    }

    /// "1 day" / "12 days" from `args["days"]`; nil when absent or not a count.
    private static func dayCount(_ args: [String: String]) -> String? {
        guard let text = args["days"]?.trimmingCharacters(in: .whitespaces), let days = Int(text), days >= 0 else { return nil }
        if days == 1 { return String(localized: "signal.days.one", defaultValue: "1 day", bundle: .module) }
        return String(localized: "signal.days.other", defaultValue: "\(days) days", bundle: .module)
    }
}
