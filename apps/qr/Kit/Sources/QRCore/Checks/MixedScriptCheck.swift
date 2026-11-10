enum MixedScriptCheck {
    static func signal(_ address: WebAddress) -> Signal? {
        let link = address.link
        guard address.hostKind == .domain else { return nil }
        for label in link.unicodeLabels {
            let scripts = ScriptMix.scripts(in: label)
            if ScriptMix.isMixed(scripts) {
                let names = scripts.map(\.rawValue).sorted().joined(separator: "+")
                return Signal(.mixedScript, ["label": label, "scripts": names])
            }
        }
        if address.hasMalformedLabel { return Signal(.mixedScript, ["label": link.hostUnicode, "scripts": "malformed"]) }
        return nil
    }
}
