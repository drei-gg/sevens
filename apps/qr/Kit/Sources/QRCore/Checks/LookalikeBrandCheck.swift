enum LookalikeBrandCheck {
    static func signal(_ link: LinkInfo, _ brands: BrandMatcher) -> Signal? {
        guard let reg = link.registrableDomain, let label = link.registrableFirstLabel,
              !brands.isOwnDomain(reg), !brands.allLabels.contains(label),
              let i = brands.brandWithSkeleton(brands.skeleton.of(label)) else { return nil }
        return Signal(.lookalikeBrand, ["brand": brands.brands[i].name, "domain": reg])
    }
}
