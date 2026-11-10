enum IPHostCheck {
    static func signal(_ address: WebAddress) -> Signal? {
        address.hostKind == .domain ? nil : Signal(.ipHost, ["host": address.link.hostASCII])
    }
}
