extension PeekAnalyzer {
    /// Signals from the sniffed page (SPEC §6).
    func pageSignals(_ sniff: HTMLSniff, finalLink: LinkInfo) -> [Signal] {
        var out: [Signal] = []
        if let kind = AsksForSecretCheck.kind(of: sniff.inputs) {
            if let brand = SecretFormBrandMismatchCheck.brand(title: sniff.title, final: finalLink, analyzer.context.brands) {
                out.append(Signal(.secretFormBrandMismatch, ["brand": brand, "kind": kind]))
            } else {
                out.append(Signal(.asksForSecret, ["kind": kind]))
            }
        }
        if let host = FormPostsElsewhereCheck.host(actions: sniff.formActions, final: finalLink, psl: data.publicSuffixes) {
            out.append(Signal(.formPostsElsewhere, ["host": host]))
        }
        if let url = sniff.metaRefreshURL, !url.isEmpty {
            out.append(Signal(.scriptRedirect, ["via": "meta"]))
        } else if sniff.scriptRedirect {
            out.append(Signal(.scriptRedirect, ["via": "script"]))
        }
        if sniff.visibleTextLength < 200, sniff.scriptCount >= 1 { out.append(Signal(.needsJavaScript)) }
        return out
    }
}
