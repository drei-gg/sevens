import Foundation

/// What every check needs; built once per `Analyzer` / `PeekAnalyzer`.
struct CheckContext: Sendable {
    let data: BundledData
    let brands: BrandMatcher
    let riskyTLDs: Set<String>
    let baitWords: [String]
    init(data: BundledData) {
        self.data = data
        self.riskyTLDs = Set(data.riskyTLDs.map { $0.tld.lowercased() })
        self.baitWords = data.baitWords.map { $0.lowercased() }
        self.brands = BrandMatcher(data: data)
    }
}

extension LinkInfo {
    /// Host labels, Unicode form.
    var unicodeLabels: [String] { hostUnicode.split(separator: ".", omittingEmptySubsequences: true).map(String.init) }

    /// Labels left of the public suffix (Unicode form), including the registrable domain's first label.
    var labelsLeftOfSuffix: [String] {
        guard let suffix = publicSuffix else { return unicodeLabels }
        let n = suffix.split(separator: ".").count
        let all = unicodeLabels
        return Array(all.prefix(max(0, all.count - n)))
    }

    /// Labels left of the registrable domain.
    var subdomainLabels: [String] {
        guard let reg = registrableDomain else { return [] }
        let n = reg.split(separator: ".").count
        let all = unicodeLabels
        return Array(all.prefix(max(0, all.count - n)))
    }

    var registrableFirstLabel: String? {
        registrableDomain?.split(separator: ".").first.map { String($0).lowercased() }
    }
}
