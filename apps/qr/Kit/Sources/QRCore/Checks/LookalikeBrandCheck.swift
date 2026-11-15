/// SPEC §4.2 `lookalikeBrand`: the registrable domain's first label, or any single word of the
/// address (split on `-` inside each label), reads as a brand keyword of four or more letters
/// once look-alike characters are folded, without being that keyword.
enum LookalikeBrandCheck {
    static func signal(_ link: LinkInfo, _ brands: BrandMatcher) -> Signal? {
        guard let reg = link.registrableDomain, !brands.isOwnDomain(reg) else { return nil }
        if let label = link.registrableFirstLabel, !brands.allLabels.contains(label),
           let i = brands.brandWithSkeleton(brands.skeleton.of(label)) {
            return Signal(.lookalikeBrand, ["brand": brands.brands[i].name, "domain": reg])
        }
        let labels = link.labelsLeftOfSuffix
        var hostWords: [String]?
        for label in labels {
            let words = label.lowercased().split(whereSeparator: { $0 == "-" || $0 == "_" }).map(String.init)
            for w in words where !brands.allLabels.contains(w) {
                guard let i = brands.brandWithKeywordSkeleton(brands.skeleton.of(w)) else { continue }
                if brands.brands[i].ambiguous {
                    let ctx = hostWords ?? BrandMatcher.tokens(inLabels: link.unicodeLabels)
                    hostWords = ctx
                    guard ctx.contains(where: { brands.baitWords.contains($0) }) else { continue }
                }
                return Signal(.lookalikeBrand, ["brand": brands.brands[i].name, "domain": reg])
            }
        }
        return nil
    }
}
