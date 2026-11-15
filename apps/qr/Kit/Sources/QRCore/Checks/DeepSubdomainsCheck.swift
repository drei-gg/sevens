enum DeepSubdomainsCheck {
    static func signal(_ link: LinkInfo) -> Signal? {
        let n = link.subdomainLabels.count
        return n >= 4 ? Signal(.deepSubdomains, ["count": String(n)]) : nil
    }
}
