import Testing
@testable import QRCore

@Suite struct PaymentsBech32Tests {
    // BIP 173 valid strings.
    @Test(arguments: [
        "A12UEL5L", "a12uel5l",
        "an83characterlonghumanreadablepartthatcontainsthenumber1andtheexcludedcharactersbio1tt5tgs",
        "abcdef1qpzry9x8gf2tvdw0s3jn54khce6mua7lmqqqxw",
        "split1checkupstagehandshakeupstreamerranterredcaperred2y9e3w",
        "?1ezyfcl",
    ])
    func bip173Valid(_ s: String) {
        #expect(Bech32.decode(s)?.encoding == .bech32)
    }

    // BIP 350 valid bech32m strings.
    @Test(arguments: [
        "A1LQFN3A", "a1lqfn3a",
        "abcdef1l7aum6echk45nj3s0wdvt2fg8x9yrzpqzd3ryx",
        "split1checkupstagehandshakeupstreamerranterredcaperredlc445v",
        "?1v759aa",
    ])
    func bip350Valid(_ s: String) {
        #expect(Bech32.decode(s)?.encoding == .bech32m)
    }

    @Test(arguments: [
        "pzry9x0s0muk",            // no separator
        "1pzry9x0s0muk",           // empty HRP
        "x1b4n0q5v",               // invalid data character
        "li1dgmt3",                // too-short checksum
        "A1G7SGD8",                // checksum over the uppercase HRP
        "10a06t8", "1qzzfhee",     // empty HRP
        "M1VUXWEZ",                // bad checksum
        "a12Uel5l",                // mixed case
        "a12uel5l ",               // space
    ])
    func invalid(_ s: String) { #expect(Bech32.decode(s) == nil) }

    @Test func decodedParts() {
        let d = Bech32.decode("A12UEL5L")
        #expect(d?.hrp == "a")
        #expect(d?.data.isEmpty == true)
    }

    @Test func tooLong() {
        let long = "a1" + String(repeating: "q", count: 100)
        #expect(Bech32.decode(long) == nil)
    }
}
