enum UserinfoTrickCheck {
    static func signal(_ link: LinkInfo) -> Signal? {
        guard link.scheme == "http" || link.scheme == "https", let u = link.userinfo, !u.isEmpty else { return nil }
        return Signal(.userinfoTrick, ["userinfo": u, "host": link.hostUnicode])
    }
}
