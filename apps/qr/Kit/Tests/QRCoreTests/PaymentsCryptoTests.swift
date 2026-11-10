import Testing
@testable import QRCore

@Suite struct PaymentsCryptoTests {
    private let schemes = [CryptoScheme(scheme: "litecoin", name: "Litecoin"), CryptoScheme(scheme: "dogecoin", name: "Dogecoin")]
    private func parse(_ s: String) -> CryptoInfo? { CryptoURIParser.parse(s, schemes: schemes) }

    @Test func bitcoin() throws {
        let c = try #require(parse("Bitcoin:175tWpb8K1S7NmH4Zx6rewF9WQrcZv245W?amount=20.3&label=Luke%20Jr&message=a%26b"))
        #expect(c.scheme == "bitcoin")
        #expect(c.network == "Bitcoin")
        #expect(c.address == "175tWpb8K1S7NmH4Zx6rewF9WQrcZv245W")
        #expect(c.amount == "20.3")
        #expect(c.label == "Luke Jr")
        #expect(c.message == "a&b")
        #expect(!c.isLightningInvoice)
    }

    @Test func bitcoinLightningOnly() throws {
        let c = try #require(parse("bitcoin:?lightning=lnbc2500u1pabc"))
        #expect(c.isLightningInvoice)
        #expect(c.amount == "0.0025")
        #expect(c.address == "lnbc2500u1pabc")
        #expect(parse("bitcoin:") == nil)
        #expect(parse("bitcoin:?amount=1") == nil)
    }

    @Test func ethereum() throws {
        let c = try #require(parse("ethereum:pay-0xAbC@137/transfer?address=0x1&uint256=1&value=1e18"))
        #expect(c.address == "0xAbC")
        #expect(c.chainID == "137")
        #expect(c.amount == "1e18")
        #expect(c.network == "Ethereum")
        let plain = try #require(parse("ethereum:0xAbC"))
        #expect(plain.chainID == nil)
        #expect(plain.amount == nil)
    }

    @Test(arguments: [
        ("lnbc2500u1pxyz", "0.0025"), ("lnbc1m1p", "0.001"), ("lnbc20m1p", "0.02"), ("lnbc10n1p", "0.00000001"),
        ("lnbc2500000p1p", "0.0000025"), ("lnbc15u1p", "0.000015"), ("lnbc3", "3"), ("lntb100u1p", "0.0001"),
        ("lnbcrt50u1p", "0.00005"), ("lnbc1p", nil), ("lnbc1pvjluez", nil), ("lnurl1dp68gurn8ghj7", nil),
        ("lnbc5x1p", nil),
    ] as [(String, String?)])
    func lightningAmount(_ invoice: String, _ amount: String?) {
        // "lnbc1p…" is ambiguous only without a separator: the last "1" ends the human-readable part.
        let text = invoice.contains("1") ? invoice : invoice + "1p"
        let c = parse("lightning:" + text)
        #expect(c?.network == "Lightning")
        #expect(c?.isLightningInvoice == true)
        #expect(c?.amount == amount)
    }

    @Test func genericScheme() throws {
        let c = try #require(parse("LITECOIN:LdP8?amount=1.5&label=x"))
        #expect(c.scheme == "litecoin")
        #expect(c.network == "Litecoin")
        #expect(c.amount == "1.5")
        #expect(c.label == "x")
    }

    @Test func rejected() {
        #expect(parse("monero:abc") == nil)
        #expect(parse("litecoin:") == nil)
        #expect(parse("https://example.com") == nil)
        #expect(parse("no colon") == nil)
        #expect(CryptoURIParser.parse("litecoin:Ld", schemes: []) == nil)
    }

    @Test func bareAddresses() {
        #expect(CryptoAddress.detect("bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4")?.network == "Bitcoin")
        #expect(CryptoAddress.detect(" BC1QW508D6QEJXTDG4Y5R3ZARVARY0C5XW7KV8F3T4\n")?.network == "Bitcoin")
        #expect(CryptoAddress.detect("tb1qrp33g0q5c5txsp9arysrx4k6zdkfs4nce4xj0gdcccefvpysxf3q0sl5k7")?.network == "Bitcoin testnet")
        #expect(CryptoAddress.detect("bc1p0xlxvlhemja6c4dqv22uapctqupfhlxm9h8z3k2e72q4k9hcz7vqzk5jj0")?.network == "Bitcoin")
        #expect(CryptoAddress.detect("0x52908400098527886E0F7030069857D2E4169EE7")?.network == "Ethereum")
        #expect(CryptoAddress.detect("1BvBMSEYstWetqTFn5Au4m4GFg7xJaNVN2")?.network == "Bitcoin")
        #expect(CryptoAddress.detect("3J98t1WpEZ73CNmQviecrnyiWrnqRhWNLy")?.network == "Bitcoin")
    }

    @Test func bareRejected() {
        #expect(CryptoAddress.detect("bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t5") == nil)   // checksum
        #expect(CryptoAddress.detect("bc1p0xlxvlhemja6c4dqv22uapctqupfhlxm9h8z3k2e72q4k9hcz7vqh2y7hd") == nil) // v1 needs bech32m
        #expect(CryptoAddress.detect("tb1qrp33g0q5c5txsp9arysrx4k6zdkfs4nce4xj0gdcccefvpysxf3q0sL5k7") == nil) // mixed case
        #expect(CryptoAddress.detect("a12uel5l") == nil)                                        // unknown HRP
        #expect(CryptoAddress.detect("0x52908400098527886E0F7030069857D2E4169EE") == nil)       // 39 hex
        #expect(CryptoAddress.detect("0x52908400098527886E0F7030069857D2E4169EEZ") == nil)
        #expect(CryptoAddress.detect("1BvBMSEYstWetqTFn5Au4m4GFg7xJaNVN0") == nil)             // 0 not base58
        #expect(CryptoAddress.detect("2BvBMSEYstWetqTFn5Au4m4GFg7xJaNVN2") == nil)
        #expect(CryptoAddress.detect("1BvBMSEYstWetqTFn5") == nil)                             // short
        #expect(CryptoAddress.detect("hello world") == nil)
        #expect(CryptoAddress.detect("") == nil)
    }

    @Test func addressGroupsOfFour() {
        #expect(CryptoAddress.detect("0x52908400098527886E0F7030069857D2E4169EE7")?.addressGroups.count == 11)
    }
}
