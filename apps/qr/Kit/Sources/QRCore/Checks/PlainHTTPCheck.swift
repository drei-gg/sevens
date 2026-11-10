enum PlainHTTPCheck {
    static func signal(_ link: LinkInfo) -> Signal? { link.scheme == "http" ? Signal(.plainHTTP) : nil }
}
