enum RiskyTLDCheck {
    static func signal(_ link: LinkInfo, _ tlds: Set<String>) -> Signal? {
        guard !link.isIPAddress, let last = link.hostASCII.split(separator: ".").last else { return nil }
        let tld = String(last)
        return tlds.contains(tld) ? Signal(.riskyTLD, ["tld": tld]) : nil
    }
}
