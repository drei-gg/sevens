enum NearBrandCheck {
    /// Not a skeleton match, brand label of 6+ characters, distance exactly 1 from the
    /// registrable domain's first label.
    static func signal(_ link: LinkInfo, _ brands: BrandMatcher) -> Signal? {
        guard let reg = link.registrableDomain, let label = link.registrableFirstLabel,
              !brands.isOwnDomain(reg), !brands.allLabels.contains(label) else { return nil }
        let sk = brands.skeleton.of(label)
        if brands.brandWithSkeleton(sk) != nil { return nil }
        let scalars = Array(label.unicodeScalars)
        for e in brands.labelEntries where e.scalars.count >= 6 && abs(e.scalars.count - scalars.count) <= 1 {
            if BrandOverrides.nearBrandOff.contains(brands.brands[e.brand].name) { continue }
            if DamerauLevenshtein.distance(scalars, e.scalars) == 1 {
                return Signal(.nearBrand, ["brand": brands.brands[e.brand].name, "domain": reg])
            }
        }
        return nil
    }
}
