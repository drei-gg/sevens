enum FormPostsElsewhereCheck {
    /// The host of the first form whose action resolves to a different registrable domain.
    static func host(actions: [String?], final: LinkInfo, psl: PublicSuffixList) -> String? {
        let page = final.registrableDomain ?? final.hostASCII
        for case let action? in actions where !action.isEmpty {
            guard let target = WebAddress.resolve(action, against: final, psl: psl) else { continue }
            if (target.link.registrableDomain ?? target.link.hostASCII) != page { return target.link.hostUnicode }
        }
        return nil
    }
}
