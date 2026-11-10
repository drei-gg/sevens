enum BrandInDomainCheck {
    static func signal(_ link: LinkInfo, _ brands: BrandMatcher) -> Signal? {
        guard let reg = link.registrableDomain else { return nil }
        let tokens = BrandMatcher.tokens(inLabels: link.labelsLeftOfSuffix)
        for i in brands.brandsMatching(tokens: tokens) where !brands.isOwnDomain(reg, of: i) {
            return Signal(.brandInDomain, ["brand": brands.brands[i].name, "domain": reg])
        }
        return nil
    }
}
