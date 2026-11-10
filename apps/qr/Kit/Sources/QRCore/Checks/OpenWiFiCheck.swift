enum OpenWiFiCheck {
    static func signal(_ info: WiFiInfo) -> Signal? { info.isOpen ? Signal(.openWiFi) : nil }
}
