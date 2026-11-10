/// SPEC §4.2: a brand's name in a domain that isn't the brand's. Strong (`brandInDomain`), except
/// the one gentle case: the registrable domain is exactly `<keyword>.<common ending>`
/// (`paypal.me`, `microsoft.de`), which is the warning `brandOtherEnding`.
enum BrandInDomainCheck {
    static func signal(_ address: WebAddress, _ brands: BrandMatcher, riskyTLDs: Set<String>) -> Signal? {
        let link = address.link
        guard let reg = link.registrableDomain else { return nil }
        let tokens = BrandMatcher.tokens(inLabels: link.labelsLeftOfSuffix)
        var matched = brands.brandsMatching(tokens: tokens)
        for i in brands.brandsMatchingShort(hostLabels: link.unicodeLabels) where !matched.contains(i) { matched.append(i) }
        var gentle: Signal?
        for i in matched where !brands.isOwnDomain(reg, of: i) {
            if isOtherEnding(address, brand: i, brands, riskyTLDs) {
                gentle = gentle ?? Signal(.brandOtherEnding, ["brand": brands.brands[i].name, "domain": reg])
            } else {
                return Signal(.brandInDomain, ["brand": brands.brands[i].name, "domain": reg])
            }
        }
        return gentle
    }

    /// Exactly `<keyword>.<suffix>`: nothing else in the label, a known and not private suffix
    /// whose last label is a two-letter country code (or the whole suffix is com/net/org), and
    /// neither the suffix nor its last label on the abused list. Subdomains don't matter.
    static func isOtherEnding(_ address: WebAddress, brand i: Int, _ brands: BrandMatcher, _ riskyTLDs: Set<String>) -> Bool {
        let link = address.link
        guard address.suffixIsKnown, !link.suffixIsPrivate, let suffix = link.publicSuffix?.lowercased(),
              let label = link.registrableFirstLabel, brands.isExactKeyword(label, of: i),
              let lastLabel = suffix.split(separator: ".").last.map(String.init) else { return false }
        let countryCode = lastLabel.unicodeScalars.count == 2 && lastLabel.unicodeScalars.allSatisfy { $0.isASCII && $0.properties.isAlphabetic }
        guard countryCode || ["com", "net", "org"].contains(suffix) else { return false }
        return !riskyTLDs.contains(suffix) && !riskyTLDs.contains(lastLabel)
    }
}
