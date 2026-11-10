import Foundation

/// SPEC §6: a page that asks for a secret, whose title or host names a brand that doesn't own
/// the final domain.
enum SecretFormBrandMismatchCheck {
    static func brand(title: String?, final: LinkInfo, _ brands: BrandMatcher) -> String? {
        let titleTokens = (title ?? "").lowercased().split(whereSeparator: { !$0.isLetter }).map(String.init)
        let hostTokens = BrandMatcher.tokens(inLabels: final.unicodeLabels)
        let all = titleTokens + hostTokens
        let found = brands.brandsMatching(tokens: all, baitContext: all)
        guard let first = found.first else { return nil }
        // The page is on one of the matched brands' own domains: nothing is off.
        if found.contains(where: { brands.isOwnDomain(final.registrableDomain, of: $0) }) { return nil }
        return brands.brands[first].name
    }
}
