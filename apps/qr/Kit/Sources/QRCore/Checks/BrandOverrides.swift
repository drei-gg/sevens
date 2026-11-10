/// C16: in-code loosening where `brands.json` is missing an official domain or a brand
/// word misfires on ordinary sites. Brand names are spelled as in `brands.json`.
/// These belong in the data file; WP 1.2 owns it, so they live here until Opus moves them.
enum BrandOverrides {
    /// `nearBrand` never fires for these brands.
    static let nearBrandOff: Set<String> = []
    /// Keywords (lowercase) that never match in `brandInDomain` / `secretFormBrandMismatch`.
    static let keywordOff: Set<String> = []

    /// Official regional sites of a brand: `label` × `suffixes`, plus whole domains.
    private static let regional: [(brand: String, labels: [String], suffixes: [String])] = [
        ("Google", ["google"], ["co.uk", "com.ph", "de", "fr", "es", "it", "ca", "co.jp", "co.in", "com.au", "com.br", "com.mx", "nl", "pl", "com.sg", "co.id", "co.kr", "com.tr", "com.ar", "co.za", "ae", "com.sa", "com.hk", "com.tw", "co.th", "com.vn", "com.my", "pt", "se", "ch", "at", "be", "ie", "co.nz", "ru", "cl", "com.co", "com.pe"]),
        ("eBay", ["ebay"], ["co.uk", "de", "fr", "it", "es", "com.au", "ca", "ph", "com.sg", "com.my", "ie", "nl", "at", "ch", "pl", "com.hk"]),
        ("PayPal", ["paypal"], ["co.uk", "de", "fr", "it", "es", "com.au", "ca", "com.br", "ph", "com.mx", "nl", "at", "ch", "pl", "com.sg", "co.jp", "com.hk"]),
        ("Yahoo", ["yahoo"], ["co.jp", "co.uk", "fr", "de", "it", "es", "ca", "com.au", "co.in", "com.br", "com.tw", "com.hk"]),
    ]
    private static let whole: [(brand: String, domains: [String])] = []

    /// Extra official domains by brand name.
    static let extraDomains: [String: [String]] = {
        var out: [String: [String]] = [:]
        for r in regional {
            for l in r.labels { out[r.brand, default: []] += r.suffixes.map { l + "." + $0 } }
        }
        for w in whole { out[w.brand, default: []] += w.domains }
        return out
    }()
}
