enum ShortenerCheck {
    static func signal(_ link: LinkInfo, _ shorteners: Set<String>) -> Signal? {
        guard !link.isIPAddress else { return nil }
        if shorteners.contains(link.hostASCII) { return Signal(.shortener, ["host": link.hostASCII]) }
        if let r = link.registrableDomain?.lowercased(), shorteners.contains(r) { return Signal(.shortener, ["host": r]) }
        return nil
    }
}
