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
    private var ownDomains: [String: Set<Int>] = [:]

    init(data: BundledData) {
        brands = data.brands
        skeleton = Skeleton(confusables: data.confusables)
        baitWords = Set(data.baitWords.map { $0.lowercased() })
        for (i, b) in brands.enumerated() {
            var labels = Set<String>()
            for d in b.domains + (BrandOverrides.extraDomains[b.name] ?? []) {
                let dl = d.lowercased()
                ownDomains[dl, default: []].insert(i)
                if let first = dl.split(separator: ".").first { labels.insert(String(first)) }
            }
            for k in b.keywords {
                let kl = k.lowercased()
                labels.insert(kl)
                if kl.unicodeScalars.count >= 4, !BrandOverrides.keywordOff.contains(kl) { keywordIndex[kl, default: []].append(i) }
            }
            for l in labels.sorted() where !l.isEmpty {
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
}
