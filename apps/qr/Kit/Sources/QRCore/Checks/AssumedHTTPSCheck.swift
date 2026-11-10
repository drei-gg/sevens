enum AssumedHTTPSCheck {
    static func signal(_ address: WebAddress) -> Signal? { address.assumedHTTPS ? Signal(.assumedHTTPS) : nil }
}
