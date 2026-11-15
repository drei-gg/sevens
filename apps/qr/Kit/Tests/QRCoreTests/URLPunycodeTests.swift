import Testing
@testable import QRCore

/// RFC 3492 §7.1 sample strings (Korean omitted) plus bad input.
@Suite struct URLPunycodeTests {
    struct Vector: Sendable, CustomTestStringConvertible {
        var name: String
        var unicode: String
        var ascii: String
        var testDescription: String { name }
    }

    static let vectors: [Vector] = [
        Vector(name: "Arabic", unicode: "\u{644}\u{64A}\u{647}\u{645}\u{627}\u{628}\u{62A}\u{643}\u{644}\u{645}\u{648}\u{634}\u{639}\u{631}\u{628}\u{64A}\u{61F}", ascii: "egbpdaj6bu4bxfgehfvwxn"),
        Vector(name: "Chinese simplified", unicode: "\u{4ED6}\u{4EEC}\u{4E3A}\u{4EC0}\u{4E48}\u{4E0D}\u{8BF4}\u{4E2D}\u{6587}", ascii: "ihqwcrb4cv8a8dqg056pqjye"),
        Vector(name: "Chinese traditional", unicode: "\u{4ED6}\u{5011}\u{7232}\u{4EC0}\u{9EBD}\u{4E0D}\u{8AAA}\u{4E2D}\u{6587}", ascii: "ihqwctvzc91f659drss3x8bo0yb"),
        Vector(name: "Czech", unicode: "Pro\u{10D}prost\u{11B}nemluv\u{ED}\u{10D}esky", ascii: "Proprostnemluvesky-uyb24dma41a"),
        Vector(name: "Hebrew", unicode: "\u{5DC}\u{5DE}\u{5D4}\u{5D4}\u{5DD}\u{5E4}\u{5E9}\u{5D5}\u{5D8}\u{5DC}\u{5D0}\u{5DE}\u{5D3}\u{5D1}\u{5E8}\u{5D9}\u{5DD}\u{5E2}\u{5D1}\u{5E8}\u{5D9}\u{5EA}", ascii: "4dbcagdahymbxekheh6e0a7fei0b"),
        Vector(name: "Hindi", unicode: "\u{92F}\u{939}\u{932}\u{94B}\u{917}\u{939}\u{93F}\u{928}\u{94D}\u{926}\u{940}\u{915}\u{94D}\u{92F}\u{94B}\u{902}\u{928}\u{939}\u{940}\u{902}\u{92C}\u{94B}\u{932}\u{938}\u{915}\u{924}\u{947}\u{939}\u{948}\u{902}", ascii: "i1baa7eci9glrd9b2ae1bj0hfcgg6iyaf8o0a1dig0cd"),
        Vector(name: "Japanese", unicode: "\u{306A}\u{305C}\u{307F}\u{3093}\u{306A}\u{65E5}\u{672C}\u{8A9E}\u{3092}\u{8A71}\u{3057}\u{3066}\u{304F}\u{308C}\u{306A}\u{3044}\u{306E}\u{304B}", ascii: "n8jok5ay5dzabd5bym9f0cm5685rrjetr6pdxa"),
        Vector(name: "Russian", unicode: "\u{43F}\u{43E}\u{447}\u{435}\u{43C}\u{443}\u{436}\u{435}\u{43E}\u{43D}\u{438}\u{43D}\u{435}\u{433}\u{43E}\u{432}\u{43E}\u{440}\u{44F}\u{442}\u{43F}\u{43E}\u{440}\u{443}\u{441}\u{441}\u{43A}\u{438}", ascii: "b1abfaaepdrnnbgefbadotcwatmq2g4l"),
        Vector(name: "Spanish", unicode: "Porqu\u{E9}nopuedensimplementehablarenEspa\u{F1}ol", ascii: "PorqunopuedensimplementehablarenEspaol-fmd56a"),
        Vector(name: "Vietnamese", unicode: "T\u{1EA1}isaoh\u{1ECD}kh\u{F4}ngth\u{1EC3}ch\u{1EC9}n\u{F3}iti\u{1EBF}ngVi\u{1EC7}t", ascii: "TisaohkhngthchnitingVit-kjcr8268qyxafd2f1b9g"),
        Vector(name: "3nen B gumi", unicode: "3\u{5E74}B\u{7D44}\u{91D1}\u{516B}\u{5148}\u{751F}", ascii: "3B-ww4c5e180e575a65lsy2b"),
        Vector(name: "hitotsu yane", unicode: "\u{3072}\u{3068}\u{3064}\u{5C4B}\u{6839}\u{306E}\u{4E0B}2", ascii: "2-u9tlzr9756bt3uc0v"),
    ]

    @Test("RFC 3492 encode", arguments: vectors)
    func encodeVector(v: Vector) {
        #expect(Punycode.encode(v.unicode) == v.ascii)
    }

    @Test("RFC 3492 decode", arguments: vectors)
    func decodeVector(v: Vector) {
        #expect(Punycode.decode(v.ascii) == v.unicode)
    }

    @Test func bucherRoundTrip() {
        #expect(Punycode.encode("bücher") == "bcher-kva")
        #expect(Punycode.decode("bcher-kva") == "bücher")
        #expect(Punycode.decode("BCHER-KVA") == "BüCHER")
    }

    @Test func badInputIsNil() {
        #expect(Punycode.decode("") == nil)
        #expect(Punycode.decode("ab!c") == nil)
        #expect(Punycode.decode("99999999999") == nil)
        #expect(Punycode.decode("bücher-kva") == nil)
        #expect(Punycode.encode("") == nil)
    }
}
