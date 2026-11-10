/// SPEC §4.2 `unknownEnding` (info): the ending isn't in the suffix list, so the last label was
/// taken as the suffix and every other check ran on that basis.
enum UnknownEndingCheck {
    static func signal(_ address: WebAddress) -> Signal? {
        guard address.hostKind == .domain, !address.suffixIsKnown, address.link.registrableDomain != nil else { return nil }
        return Signal(.unknownEnding)
    }
}
