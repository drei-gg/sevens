import Foundation

/// Brand lookups shared by `brandInDomain`, `lookalikeBrand`, `nearBrand` and Peek's
/// `secretFormBrandMismatch`. Built once per `Analyzer`.
struct BrandMatcher: Sendable {
    struct LabelEntry: Sendable {
        var label: String
        var scalars: [Unicode.Scalar]
        var skeleton: String
        var brand: Int
    }

    let brands: [Brand]
    let skeleton: Skeleton
    let baitWords: Set<String>
    /// First label of each brand domain plus each keyword, deduplicated per brand.
    private(set) var labelEntries: [LabelEntry] = []
    private(set) var allLabels: Set<String> = []
    private var skeletonIndex: [String: Int] = [:]
    private var keywordIndex: [String: [Int]] = [:]
    /// Keywords of exactly three letters (DHL, BPI…): whole token plus a bait word only (SPEC §4.2).
    private var shortKeywordIndex: [String: [Int]] = [:]
    /// Skeleton of each keyword of 4+ letters → brand, for the per-token look-alike comparison.
    private var keywordSkeletonIndex: [String: Int] = [:]
    /// Per brand, its lowercase keywords of 4+ letters (used by `brandOtherEnding`).
    private var longKeywords: [Set<String>] = []
    private var ownDomains: [String: Set<Int>] = [:]

    init(data: BundledData) {
        brands = data.brands
        skeleton = Skeleton(confusables: data.confusables)
        baitWords = Set(data.baitWords.map { $0.lowercased() })
        let shortKeywords = Set(brands.flatMap(\.keywords).map { $0.lowercased() }.filter { $0.unicodeScalars.count == 3 })
        for (i, b) in brands.enumerated() {
            var labels = Set<String>()
            var longs = Set<String>()
            for d in b.domains + (BrandOverrides.extraDomains[b.name] ?? []) {
                let dl = d.lowercased()
                ownDomains[dl, default: []].insert(i)
                if let first = dl.split(separator: ".").first { labels.insert(String(first)) }
            }
            for k in b.keywords {
                let kl = k.lowercased()
                labels.insert(kl)
                let n = kl.unicodeScalars.count
                if BrandOverrides.keywordOff.contains(kl) { continue }
                if n >= 4 {
                    keywordIndex[kl, default: []].append(i)
                    longs.insert(kl)
                    let ks = skeleton.of(kl)
                    if keywordSkeletonIndex[ks] == nil { keywordSkeletonIndex[ks] = i }
                } else if n == 3 {
                    shortKeywordIndex[kl, default: []].append(i)
                }
            }
            longKeywords.append(longs)
            for l in labels.sorted() where !l.isEmpty {
                // Keywords under four letters never take part in look-alike or near-miss matching.
                if shortKeywords.contains(l) { allLabels.insert(l); continue }
                let sk = skeleton.of(l)
                labelEntries.append(LabelEntry(label: l, scalars: Array(l.unicodeScalars), skeleton: sk, brand: i))
                allLabels.insert(l)
                if skeletonIndex[sk] == nil { skeletonIndex[sk] = i }
            }
        }
    }

    func isOwnDomain(_ registrable: String) -> Bool { ownDomains[registrable.lowercased()] != nil }

    func isOwnDomain(_ registrable: String?, of brand: Int) -> Bool {
        guard let r = registrable else { return false }
        return ownDomains[r.lowercased()]?.contains(brand) ?? false
    }

    /// The brand whose label has the same skeleton, if any.
    func brandWithSkeleton(_ sk: String) -> Int? { skeletonIndex[sk] }

    /// Splits labels on `.`, `-`, `_` and letter/digit boundaries; lowercase.
    static func tokens(inLabels labels: [String]) -> [String] {
        var out: [String] = []
        for label in labels {
            var cur = String.UnicodeScalarView()
            var curIsDigit = false
            func flush() { if !cur.isEmpty { out.append(String(cur).lowercased()); cur = String.UnicodeScalarView() } }
            for s in label.unicodeScalars {
                let isDigit = s.properties.numericType == .decimal
                let isWord = isDigit || s.properties.isAlphabetic || s.properties.generalCategory == .nonspacingMark
                    || s.properties.generalCategory == .spacingMark
                if !isWord { flush(); continue }
                if !cur.isEmpty, isDigit != curIsDigit { flush() }
                cur.append(s)
                curIsDigit = isDigit
            }
            flush()
        }
        return out
    }

    /// Brands (by index) with a keyword equal to a token. Ambiguous brands count only when a
    /// bait word is among `baitContext` (default: the tokens themselves).
    func brandsMatching(tokens: [String], baitContext: [String]? = nil) -> [Int] {
        let hasBait = (baitContext ?? tokens).contains { baitWords.contains($0) }
        var found: [Int] = []
        for t in tokens {
            guard let idxs = keywordIndex[t] else { continue }
            for i in idxs where !found.contains(i) {
                if brands[i].ambiguous && !hasBait { continue }
                found.append(i)
            }
        }
        return found
    }

    /// Brands with a three-letter keyword that is a whole token of the host (split on `.`, `-`,
    /// `_`), while a bait word is another token of the same host.
    func brandsMatchingShort(hostLabels: [String]) -> [Int] {
        guard !shortKeywordIndex.isEmpty else { return [] }
        var words: [String] = []
        for label in hostLabels {
            words += label.lowercased().split(whereSeparator: { $0 == "-" || $0 == "_" }).map(String.init)
        }
        var found: [Int] = []
        for (pos, w) in words.enumerated() {
            guard let idxs = shortKeywordIndex[w] else { continue }
            let hasBait = words.enumerated().contains { $0.offset != pos && baitWords.contains($0.element) }
            if !hasBait { continue }
            for i in idxs where !found.contains(i) { found.append(i) }
        }
        return found
    }

    /// True when `label` is exactly one of brand `i`'s keywords of four or more letters and the
    /// brand isn't marked ambiguous.
    func isExactKeyword(_ label: String, of i: Int) -> Bool {
        !brands[i].ambiguous && longKeywords[i].contains(label.lowercased())
    }

    /// The brand whose keyword (4+ letters) has this skeleton.
    func brandWithKeywordSkeleton(_ sk: String) -> Int? { keywordSkeletonIndex[sk] }
}
