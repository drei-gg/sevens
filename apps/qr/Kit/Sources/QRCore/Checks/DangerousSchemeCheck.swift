enum DangerousSchemeCheck {
    static let schemes: Set<String> = ["javascript", "data", "file", "vbscript", "blob", "itms-services"]

    static func signal(scheme: String) -> Signal? {
        schemes.contains(scheme) ? Signal(.dangerousScheme, ["scheme": scheme]) : nil
    }
}
