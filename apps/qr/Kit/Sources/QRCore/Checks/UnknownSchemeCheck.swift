enum UnknownSchemeCheck {
    /// For a scheme that isn't http/https, isn't dangerous and isn't a payload type we decode.
    static func signal(scheme: String) -> Signal {
        Signal(.unknownScheme, ["scheme": scheme])
    }
}
