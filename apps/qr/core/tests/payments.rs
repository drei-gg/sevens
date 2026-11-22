//! Swift parity: EMVCRC16Tests, EMVCurrencyTests, EMVFixtureTests, EMVParserTests and the
//! Payments* suites (Bech32, crypto, EPC, fixtures, IBAN, Swiss, UPI).

mod common;
use common::*;
use qr_core::data::{CryptoScheme, EmvNetwork};
use qr_core::payments::{
    bech32, crc16_ccitt_false, crypto_address, crypto_uri, currency_alpha, emv, epc, iban, swiss,
    upi,
};
use qr_core::*;
use std::collections::HashMap;

fn map(pairs: &[(&str, &str)]) -> HashMap<String, String> {
    pairs
        .iter()
        .map(|(k, v)| (k.to_string(), v.to_string()))
        .collect()
}

fn ex<'a>(p: &'a PaymentInfo, k: &str) -> Option<&'a str> {
    p.extra.get(k).map(String::as_str)
}

// MARK: EMVCRC16Tests.swift

#[test]
fn check_value() {
    assert_eq!(crc16_ccitt_false(b"123456789"), 0x29B1);
}

#[test]
fn empty_is_init() {
    assert_eq!(crc16_ccitt_false(&[]), 0xFFFF);
}

#[test]
fn published_sample() {
    let body = "00020101021229300012D156000000000510A93FO3230Q31280012D15600000001030812345678520441115802CN5914BEST TRANSPORT6007BEIJING64200002ZH0104最佳运输0202北京540523.7253031565502016233030412340603***0708A60086670902ME91320016A0112233449988770708123456786304";
    assert_eq!(
        format!("{:04X}", crc16_ccitt_false(body.as_bytes())),
        "A13A"
    );
}

// MARK: EMVCurrencyTests.swift

fn cur_table() -> HashMap<String, String> {
    map(&[("608", "PHP"), ("986", "BRL"), ("036", "AUD")])
}

#[test]
fn known() {
    assert_eq!(currency_alpha("608", &cur_table()).as_deref(), Some("PHP"));
    assert_eq!(currency_alpha("36", &cur_table()).as_deref(), Some("AUD"));
}

#[test]
fn unknown_or_malformed() {
    for s in ["999", "", "6O8", "0608"] {
        assert_eq!(currency_alpha(s, &cur_table()), None, "{s}");
    }
}

// MARK: EMVFixtureTests.swift

fn fixture_currencies() -> HashMap<String, String> {
    map(&[
        ("986", "BRL"),
        ("702", "SGD"),
        ("360", "IDR"),
        ("608", "PHP"),
        ("764", "THB"),
        ("458", "MYR"),
        ("704", "VND"),
    ])
}

#[test]
fn file_is_well_formed() {
    let cases = load("payments-emv.json");
    assert!(cases.len() >= 20);
    assert!(cases.iter().all(|c| c.id.starts_with("emv-")));
    assert_eq!(
        cases
            .iter()
            .map(|c| &c.id)
            .collect::<std::collections::HashSet<_>>()
            .len(),
        cases.len()
    );
}

#[test]
fn every_payment_case_parses() {
    let cases: Vec<_> = load("payments-emv.json")
        .into_iter()
        .filter(|c| c.expect.kind == "payment")
        .collect();
    assert!(!cases.is_empty());
    for c in cases {
        let info = emv::parse(c.input.as_deref().unwrap(), &[], &fixture_currencies())
            .unwrap_or_else(|| panic!("{}", c.id));
        let f = fields(&Payload::Payment { info: info.clone() });
        for (k, v) in c.expect.fields.iter().flatten() {
            assert_eq!(f.get(k), Some(v), "{} fields[{k}]", c.id);
        }
        let invalid = c.expect.signals.iter().any(|s| s == "paymentChecksumFail");
        assert_eq!(
            info.checksum == PaymentChecksum::Invalid,
            invalid,
            "{}",
            c.id
        );
        assert_eq!(c.expect.level, if invalid { 2 } else { 0 }, "{}", c.id);
    }
}

#[test]
fn text_cases_have_no_crc_tail() {
    for c in load("payments-emv.json")
        .into_iter()
        .filter(|c| c.expect.kind == "text")
    {
        let info = emv::parse(c.input.as_deref().unwrap(), &[], &HashMap::new());
        assert!(
            info.is_none() || info.unwrap().checksum == PaymentChecksum::Absent,
            "{}",
            c.id
        );
    }
}

// MARK: EMVParserTests.swift

fn nets() -> Vec<EmvNetwork> {
    let n = |id: &str, name: &str, country: &str, party: &str, src: &str| EmvNetwork {
        id: id.into(),
        name: name.into(),
        country: country.into(),
        party: party.into(),
        source: src.into(),
    };
    vec![
        n(
            "br.gov.bcb.pix",
            "Pix",
            "BR",
            "unknown",
            "https://example.invalid/pix",
        ),
        n(
            "SG.PAYNOW",
            "PayNow",
            "SG",
            "business",
            "https://example.invalid/paynow",
        ),
    ]
}

fn ep(s: &str) -> Option<PaymentInfo> {
    emv::parse(
        s,
        &nets(),
        &map(&[
            ("608", "PHP"),
            ("986", "BRL"),
            ("702", "SGD"),
            ("764", "THB"),
        ]),
    )
}

fn tlv(t: &str, v: &str) -> String {
    format!("{t}{:02}{v}", v.len())
}

fn with_crc(body: &str) -> String {
    let s = format!("{body}6304");
    format!("{s}{:04X}", crc16_ccitt_false(s.as_bytes()))
}

#[test]
fn not_emv() {
    for s in ["hello", "", "00020", " 000201"] {
        assert!(ep(s).is_none(), "{s}");
    }
}

#[test]
fn header_only() {
    let i = ep("000201").unwrap();
    assert_eq!(i.format, PaymentFormat::Emv);
    assert_eq!(i.checksum, PaymentChecksum::Absent);
}

#[test]
fn full_fields() {
    let acct = tlv("00", "SG.paynow") + &tlv("01", "A1") + &tlv("02", "B2");
    let body = tlv("00", "01")
        + &tlv("01", "12")
        + &tlv("26", &acct)
        + &tlv("52", "5411")
        + &tlv("53", "702")
        + &tlv("54", "1.20")
        + &tlv("58", "SG")
        + &tlv("59", "Shop")
        + &tlv("60", "City")
        + &tlv(
            "62",
            &(tlv("05", "R1") + &tlv("08", "why") + &tlv("03", "x")),
        )
        + &tlv(
            "64",
            &(tlv("00", "ZH") + &tlv("01", "店") + &tlv("02", "城")),
        );
    let i = ep(&with_crc(&body)).unwrap();
    assert_eq!(i.checksum, PaymentChecksum::Valid);
    assert_eq!(i.is_dynamic, Some(true));
    assert_eq!(i.network.as_deref(), Some("PayNow"));
    assert_eq!(i.party, PaymentParty::Business);
    assert_eq!(i.payee_account.as_deref(), Some("A1"));
    assert_eq!(i.currency.as_deref(), Some("SGD"));
    assert_eq!(i.amount.as_deref(), Some("1.20"));
    assert_eq!(
        (
            i.country.as_deref(),
            i.payee_name.as_deref(),
            i.city.as_deref()
        ),
        (Some("SG"), Some("Shop"), Some("City"))
    );
    assert_eq!(
        (i.reference.as_deref(), i.note.as_deref()),
        (Some("R1"), Some("why"))
    );
    assert_eq!(ex(&i, "mcc"), Some("5411"));
    assert_eq!(ex(&i, "additional.03"), Some("x"));
    assert_eq!(ex(&i, "alternateLanguage"), Some("ZH"));
    assert_eq!(
        (ex(&i, "alternateName"), ex(&i, "alternateCity")),
        (Some("店"), Some("城"))
    );
}

#[test]
fn account_falls_back_to_sub02() {
    let acct = tlv("00", "br.gov.bcb.pix") + &tlv("02", "ONLY2");
    let i = ep(&with_crc(&(tlv("00", "01") + &tlv("26", &acct)))).unwrap();
    assert_eq!(i.payee_account.as_deref(), Some("ONLY2"));
    assert_eq!(i.network.as_deref(), Some("Pix"));
    assert_eq!(i.party, PaymentParty::Unknown);
}

#[test]
fn unknown_network_keeps_id() {
    let acct = tlv("00", "xx.other") + &tlv("01", "A");
    let i = ep(&with_crc(&(tlv("00", "01") + &tlv("27", &acct)))).unwrap();
    assert_eq!(i.network, None);
    assert_eq!(i.party, PaymentParty::Unknown);
    assert_eq!(i.payee_account, None);
    assert_eq!(ex(&i, "networkID"), Some("xx.other"));
}

#[test]
fn second_template_matches() {
    let a = tlv("00", "xx.other") + &tlv("01", "A");
    let b = tlv("00", "BR.GOV.BCB.PIX") + &tlv("01", "B");
    let i = ep(&with_crc(
        &(tlv("00", "01") + &tlv("26", &a) + &tlv("27", &b)),
    ))
    .unwrap();
    assert_eq!(i.network.as_deref(), Some("Pix"));
    assert_eq!(i.payee_account.as_deref(), Some("B"));
    assert_eq!(ex(&i, "networkID"), None);
}

#[test]
fn unknown_currency_kept() {
    let i = ep(&with_crc(&(tlv("00", "01") + &tlv("53", "999")))).unwrap();
    assert_eq!(i.currency, None);
    assert_eq!(ex(&i, "currencyNumeric"), Some("999"));
}

#[test]
fn is_dynamic_values() {
    for (v, expected) in [("11", false), ("12", true)] {
        assert_eq!(
            ep(&with_crc(&(tlv("00", "01") + &tlv("01", v))))
                .unwrap()
                .is_dynamic,
            Some(expected)
        );
    }
    assert_eq!(
        ep(&with_crc(&(tlv("00", "01") + &tlv("01", "13"))))
            .unwrap()
            .is_dynamic,
        None
    );
}

#[test]
fn crc_cases() {
    let good = with_crc(&(tlv("00", "01") + &tlv("59", "Shop")));
    assert_eq!(ep(&good).unwrap().checksum, PaymentChecksum::Valid);
    let n = good.len();
    assert_eq!(
        ep(&(good[..n - 4].to_string() + &good[n - 4..].to_lowercase()))
            .unwrap()
            .checksum,
        PaymentChecksum::Valid
    );
    let flipped = good[..n - 1].to_string() + if good.ends_with('0') { "1" } else { "0" };
    assert_eq!(ep(&flipped).unwrap().checksum, PaymentChecksum::Invalid);
    assert_eq!(
        ep(&(good[..n - 4].to_string() + "ZZZZ")).unwrap().checksum,
        PaymentChecksum::Invalid
    );
    assert_eq!(
        ep(&format!("000201{}", tlv("59", "Shop")))
            .unwrap()
            .checksum,
        PaymentChecksum::Absent
    );
}

#[test]
fn utf16_lengths_are_accepted() {
    let i = ep(&with_crc("0002015904JOSÉ6007Bangkok")).unwrap();
    assert_eq!(i.payee_name.as_deref(), Some("JOSÉ"));
    assert_eq!(i.city.as_deref(), Some("Bangkok"));
    assert_eq!(i.checksum, PaymentChecksum::Valid);
}

#[test]
fn truncated_keeps_what_parsed() {
    let full = with_crc(&(tlv("00", "01") + &tlv("58", "SG") + &tlv("59", "Shop Name Here")));
    let cut = full[..20].to_string() + &full[full.len() - 8..];
    let i = ep(&cut).unwrap();
    assert_eq!(i.country.as_deref(), Some("SG"));
    assert_eq!(i.checksum, PaymentChecksum::Invalid);
    let no_tail = ep(&full[..20]).unwrap();
    assert_eq!(no_tail.country.as_deref(), Some("SG"));
    assert_eq!(no_tail.checksum, PaymentChecksum::Absent);
}

#[test]
fn garbage_does_not_crash() {
    for s in [
        "000201ab".to_string(),
        "0002015999".into(),
        "00020159".into(),
        format!("000201{}", "9".repeat(50)),
        format!("000201{}", "6304".repeat(300)),
    ] {
        assert_eq!(ep(&s).map(|i| i.format), Some(PaymentFormat::Emv));
    }
}

#[test]
fn oversize_is_bounded_and_invalid() {
    let i = ep(&format!("000201{}", "x".repeat(9000))).unwrap();
    assert_eq!(i.checksum, PaymentChecksum::Invalid);
    assert_eq!(i.payee_name, None);
}

#[test]
fn nested_template_does_not_recurse() {
    let inner = tlv("00", "br.gov.bcb.pix") + &tlv("01", &tlv("00", "nested"));
    let i = ep(&with_crc(&(tlv("00", "01") + &tlv("26", &inner)))).unwrap();
    assert_eq!(i.payee_account, Some(tlv("00", "nested")));
}

// MARK: PaymentsBech32Tests.swift

#[test]
fn bip173_valid() {
    for s in [
        "A12UEL5L",
        "a12uel5l",
        "an83characterlonghumanreadablepartthatcontainsthenumber1andtheexcludedcharactersbio1tt5tgs",
        "abcdef1qpzry9x8gf2tvdw0s3jn54khce6mua7lmqqqxw",
        "split1checkupstagehandshakeupstreamerranterredcaperred2y9e3w",
        "?1ezyfcl",
    ] {
        assert_eq!(
            bech32::decode(s, 90).map(|d| d.encoding),
            Some(bech32::Encoding::Bech32),
            "{s}"
        );
    }
}

#[test]
fn bip350_valid() {
    for s in [
        "A1LQFN3A",
        "a1lqfn3a",
        "abcdef1l7aum6echk45nj3s0wdvt2fg8x9yrzpqzd3ryx",
        "split1checkupstagehandshakeupstreamerranterredcaperredlc445v",
        "?1v759aa",
    ] {
        assert_eq!(
            bech32::decode(s, 90).map(|d| d.encoding),
            Some(bech32::Encoding::Bech32m),
            "{s}"
        );
    }
}

#[test]
fn invalid() {
    for s in [
        "pzry9x0s0muk",
        "1pzry9x0s0muk",
        "x1b4n0q5v",
        "li1dgmt3",
        "A1G7SGD8",
        "10a06t8",
        "1qzzfhee",
        "M1VUXWEZ",
        "a12Uel5l",
        "a12uel5l ",
    ] {
        assert!(bech32::decode(s, 90).is_none(), "{s}");
    }
}

#[test]
fn decoded_parts() {
    let d = bech32::decode("A12UEL5L", 90).unwrap();
    assert_eq!(d.hrp, "a");
    assert!(d.data.is_empty());
}

#[test]
fn too_long() {
    assert!(bech32::decode(&format!("a1{}", "q".repeat(100)), 90).is_none());
}

// MARK: PaymentsCryptoTests.swift

fn schemes() -> Vec<CryptoScheme> {
    vec![
        CryptoScheme {
            scheme: "litecoin".into(),
            name: "Litecoin".into(),
        },
        CryptoScheme {
            scheme: "dogecoin".into(),
            name: "Dogecoin".into(),
        },
    ]
}

fn cp(s: &str) -> Option<CryptoInfo> {
    crypto_uri::parse(s, &schemes())
}

#[test]
fn bitcoin() {
    let c =
        cp("Bitcoin:175tWpb8K1S7NmH4Zx6rewF9WQrcZv245W?amount=20.3&label=Luke%20Jr&message=a%26b")
            .unwrap();
    assert_eq!(c.scheme.as_deref(), Some("bitcoin"));
    assert_eq!(c.network, "Bitcoin");
    assert_eq!(c.address, "175tWpb8K1S7NmH4Zx6rewF9WQrcZv245W");
    assert_eq!(c.amount.as_deref(), Some("20.3"));
    assert_eq!(c.label.as_deref(), Some("Luke Jr"));
    assert_eq!(c.message.as_deref(), Some("a&b"));
    assert!(!c.is_lightning_invoice);
}

#[test]
fn bitcoin_lightning_only() {
    let c = cp("bitcoin:?lightning=lnbc2500u1pabc").unwrap();
    assert!(c.is_lightning_invoice);
    assert_eq!(c.amount.as_deref(), Some("0.0025"));
    assert_eq!(c.address, "lnbc2500u1pabc");
    assert!(cp("bitcoin:").is_none());
    assert!(cp("bitcoin:?amount=1").is_none());
}

#[test]
fn ethereum() {
    let c = cp("ethereum:pay-0xAbC@137/transfer?address=0x1&uint256=1&value=1e18").unwrap();
    assert_eq!(c.address, "0xAbC");
    assert_eq!(c.chain_id.as_deref(), Some("137"));
    assert_eq!(c.amount.as_deref(), Some("1e18"));
    assert_eq!(c.network, "Ethereum");
    let plain = cp("ethereum:0xAbC").unwrap();
    assert_eq!(plain.chain_id, None);
    assert_eq!(plain.amount, None);
}

#[test]
fn lightning_amount() {
    let cases: [(&str, Option<&str>); 13] = [
        ("lnbc2500u1pxyz", Some("0.0025")),
        ("lnbc1m1p", Some("0.001")),
        ("lnbc20m1p", Some("0.02")),
        ("lnbc10n1p", Some("0.00000001")),
        ("lnbc2500000p1p", Some("0.0000025")),
        ("lnbc15u1p", Some("0.000015")),
        ("lnbc3", Some("3")),
        ("lntb100u1p", Some("0.0001")),
        ("lnbcrt50u1p", Some("0.00005")),
        ("lnbc1p", None),
        ("lnbc1pvjluez", None),
        ("lnurl1dp68gurn8ghj7", None),
        ("lnbc5x1p", None),
    ];
    for (invoice, amount) in cases {
        let text = if invoice.contains('1') {
            invoice.to_string()
        } else {
            format!("{invoice}1p")
        };
        let c = cp(&format!("lightning:{text}"));
        assert_eq!(
            c.as_ref().map(|c| c.network.as_str()),
            Some("Lightning"),
            "{invoice}"
        );
        assert_eq!(c.as_ref().map(|c| c.is_lightning_invoice), Some(true));
        assert_eq!(c.and_then(|c| c.amount).as_deref(), amount, "{invoice}");
    }
}

#[test]
fn generic_scheme() {
    let c = cp("LITECOIN:LdP8?amount=1.5&label=x").unwrap();
    assert_eq!(c.scheme.as_deref(), Some("litecoin"));
    assert_eq!(c.network, "Litecoin");
    assert_eq!(c.amount.as_deref(), Some("1.5"));
    assert_eq!(c.label.as_deref(), Some("x"));
}

#[test]
fn rejected() {
    for s in ["monero:abc", "litecoin:", "https://example.com", "no colon"] {
        assert!(cp(s).is_none(), "{s}");
    }
    assert!(crypto_uri::parse("litecoin:Ld", &[]).is_none());
}

#[test]
fn bare_addresses() {
    let net = |s: &str| crypto_address::detect(s).map(|c| c.network);
    assert_eq!(
        net("bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4").as_deref(),
        Some("Bitcoin")
    );
    assert_eq!(
        net(" BC1QW508D6QEJXTDG4Y5R3ZARVARY0C5XW7KV8F3T4\n").as_deref(),
        Some("Bitcoin")
    );
    assert_eq!(
        net("tb1qrp33g0q5c5txsp9arysrx4k6zdkfs4nce4xj0gdcccefvpysxf3q0sl5k7").as_deref(),
        Some("Bitcoin testnet")
    );
    assert_eq!(
        net("bc1p0xlxvlhemja6c4dqv22uapctqupfhlxm9h8z3k2e72q4k9hcz7vqzk5jj0").as_deref(),
        Some("Bitcoin")
    );
    assert_eq!(
        net("0x52908400098527886E0F7030069857D2E4169EE7").as_deref(),
        Some("Ethereum")
    );
    assert_eq!(
        net("1BvBMSEYstWetqTFn5Au4m4GFg7xJaNVN2").as_deref(),
        Some("Bitcoin")
    );
    assert_eq!(
        net("3J98t1WpEZ73CNmQviecrnyiWrnqRhWNLy").as_deref(),
        Some("Bitcoin")
    );
}

#[test]
fn bare_rejected() {
    for s in [
        "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t5",
        "bc1p0xlxvlhemja6c4dqv22uapctqupfhlxm9h8z3k2e72q4k9hcz7vqh2y7hd",
        "tb1qrp33g0q5c5txsp9arysrx4k6zdkfs4nce4xj0gdcccefvpysxf3q0sL5k7",
        "a12uel5l",
        "0x52908400098527886E0F7030069857D2E4169EE",
        "0x52908400098527886E0F7030069857D2E4169EEZ",
        "1BvBMSEYstWetqTFn5Au4m4GFg7xJaNVN0",
        "2BvBMSEYstWetqTFn5Au4m4GFg7xJaNVN2",
        "1BvBMSEYstWetqTFn5",
        "hello world",
        "",
    ] {
        assert!(crypto_address::detect(s).is_none(), "{s}");
    }
}

#[test]
fn address_groups_of_four() {
    assert_eq!(
        crypto_address::detect("0x52908400098527886E0F7030069857D2E4169EE7")
            .unwrap()
            .address_groups()
            .len(),
        11
    );
}

// MARK: PaymentsEPCTests.swift

const FULL: &str = "BCD\n002\n1\nSCT\n\nRed Cross\nDE89 3704 0044 0532 0130 00\nEUR12.34\nCHAR\n\nDonation\nThanks";

#[test]
fn full_v002() {
    let p = epc::parse(FULL).unwrap();
    assert_eq!(p.format, PaymentFormat::Epc);
    assert_eq!(p.payee_name.as_deref(), Some("Red Cross"));
    assert_eq!(p.payee_account.as_deref(), Some("DE89370400440532013000"));
    assert_eq!(p.currency.as_deref(), Some("EUR"));
    assert_eq!(p.amount.as_deref(), Some("12.34"));
    assert_eq!(ex(&p, "purpose"), Some("CHAR"));
    assert_eq!(p.note.as_deref(), Some("Donation"));
    assert_eq!(ex(&p, "information"), Some("Thanks"));
    assert_eq!(ex(&p, "bic"), None);
    assert_eq!(p.checksum, PaymentChecksum::Valid);
}

#[test]
fn v001_with_bic_and_crlf() {
    let p = epc::parse("BCD\r\n001\r\n1\r\nSCT\r\nBHBLDEHHXXX\r\nA\r\nDE89370400440532013000\r\nEUR1\r\n\r\nRF18539007547034\r\n").unwrap();
    assert_eq!(ex(&p, "bic"), Some("BHBLDEHHXXX"));
    assert_eq!(p.reference.as_deref(), Some("RF18539007547034"));
    assert_eq!(p.amount.as_deref(), Some("1"));
    assert_eq!(p.checksum, PaymentChecksum::Valid);
}

#[test]
fn bad_iban() {
    assert_eq!(
        epc::parse(&FULL.replace("00\nEUR", "01\nEUR"))
            .unwrap()
            .checksum,
        PaymentChecksum::Invalid
    );
}

#[test]
fn short_and_missing_amount() {
    let p = epc::parse("BCD\n002\n1\nSCT\n\nName\nDE89370400440532013000").unwrap();
    assert_eq!(
        (p.amount, p.currency, p.checksum),
        (None, None, PaymentChecksum::Valid)
    );
}

#[test]
fn no_iban_is_absent() {
    assert_eq!(
        epc::parse("BCD\n002\n1\nSCT").unwrap().checksum,
        PaymentChecksum::Absent
    );
}

#[test]
fn epc_rejected() {
    for s in ["", "BCD\n002\n1\nXYZ", "XCD\n002\n1\nSCT"] {
        assert!(epc::parse(s).is_none(), "{s}");
    }
}

// MARK: PaymentsFixtureTests.swift

#[test]
fn fixture_file_loads() {
    assert!(load("payments-other.json").len() >= 25);
}

#[test]
fn fixture_matches() {
    let schemes = vec![CryptoScheme {
        scheme: "litecoin".into(),
        name: "Litecoin".into(),
    }];
    for c in load("payments-other.json")
        .into_iter()
        .filter(|c| ["payment", "crypto", "text"].contains(&c.expect.kind.as_str()))
    {
        let input = c.input.as_deref().unwrap();
        let id = &c.id;
        let f: Option<HashMap<String, String>> = if id.starts_with("upi-") {
            upi::parse(input).map(|i| fields(&Payload::Payment { info: i }))
        } else if id.starts_with("epc-") {
            epc::parse(input).map(|i| fields(&Payload::Payment { info: i }))
        } else if id.starts_with("swiss-") {
            swiss::parse(input).map(|i| fields(&Payload::Payment { info: i }))
        } else {
            crypto_uri::parse(input, &schemes)
                .or_else(|| crypto_address::detect(input))
                .map(|i| fields(&Payload::Crypto { info: i }))
        };
        if c.expect.kind == "text" {
            assert!(f.is_none(), "{id} must not parse");
            continue;
        }
        let got = f.unwrap_or_else(|| panic!("{id} did not parse"));
        for (k, v) in c.expect.fields.iter().flatten() {
            assert_eq!(got.get(k), Some(v), "{id} fields[{k}]");
        }
        let invalid = got.get("checksum").map(String::as_str) == Some("invalid");
        assert_eq!(c.expect.level, if invalid { 2 } else { 0 }, "{id} level");
        assert_eq!(
            c.expect.signals,
            if invalid {
                vec!["paymentChecksumFail".to_string()]
            } else {
                vec![]
            },
            "{id}"
        );
    }
}

// MARK: PaymentsIBANTests.swift

#[test]
fn valid_ibans() {
    for s in [
        "DE89370400440532013000",
        "DE89 3704 0044 0532 0130 00",
        "de89370400440532013000",
        "CH9300762011623852957",
        "CH4431999123000889012",
        "NL91ABNA0417164300",
        "FR1420041010050500013M02606",
        "GB82WEST12345698765432",
    ] {
        assert!(iban::is_valid(s), "{s}");
    }
}

#[test]
fn invalid_ibans() {
    for s in [
        "",
        "DE89370400440532013001",
        "DE8937040044053201300",
        "1289370400440532013000",
        "DEAB370400440532013000",
        "DE89-3704-0044-0532-0130-00",
        "DE89370400440532013000DE89370400440532013000",
        "DE89370400440532013000é",
    ] {
        assert!(!iban::is_valid(s), "{s}");
    }
}

// MARK: PaymentsSwissTests.swift

fn bill(iban: &str, ref_type: &str, reference: &str) -> String {
    let mut v: Vec<&str> = vec![
        "SPC",
        "0200",
        "1",
        iban,
        "S",
        "Robert Schneider AG",
        "Rue du Lac",
        "1268",
        "2501",
        "Biel",
        "CH",
    ];
    v.extend(std::iter::repeat_n("", 7));
    v.extend([
        "1949.75",
        "CHF",
        "S",
        "Pia Rutschmann",
        "Hauptstrasse",
        "1",
        "8001",
        "Zürich",
        "CH",
        ref_type,
        reference,
        "Invoice",
        "EPD",
        "Billing",
    ]);
    v.join("\n")
}

const IBAN: &str = "CH4431999123000889012";

#[test]
fn qrr_valid() {
    let p = swiss::parse(&bill(IBAN, "QRR", "210000000003139471430009017")).unwrap();
    assert_eq!(p.format, PaymentFormat::Swiss);
    assert_eq!(p.payee_account.as_deref(), Some(IBAN));
    assert_eq!(p.payee_name.as_deref(), Some("Robert Schneider AG"));
    assert_eq!(p.city.as_deref(), Some("Biel"));
    assert_eq!(p.country.as_deref(), Some("CH"));
    assert_eq!(p.amount.as_deref(), Some("1949.75"));
    assert_eq!(p.currency.as_deref(), Some("CHF"));
    assert_eq!(ex(&p, "debtorName"), Some("Pia Rutschmann"));
    assert_eq!(ex(&p, "referenceType"), Some("QRR"));
    assert_eq!(p.note.as_deref(), Some("Invoice"));
    assert_eq!(ex(&p, "billingInfo"), Some("Billing"));
    assert_eq!(p.checksum, PaymentChecksum::Valid);
}

#[test]
fn qrr_bad_check_digit() {
    assert_eq!(
        swiss::parse(&bill(IBAN, "QRR", "210000000003139471430009018"))
            .unwrap()
            .checksum,
        PaymentChecksum::Invalid
    );
    assert_eq!(
        swiss::parse(&bill(IBAN, "QRR", "2100")).unwrap().checksum,
        PaymentChecksum::Invalid
    );
}

#[test]
fn scor() {
    assert_eq!(
        swiss::parse(&bill(IBAN, "SCOR", "RF18539007547034"))
            .unwrap()
            .checksum,
        PaymentChecksum::Valid
    );
    assert_eq!(
        swiss::parse(&bill(IBAN, "SCOR", "RF19539007547034"))
            .unwrap()
            .checksum,
        PaymentChecksum::Invalid
    );
    assert_eq!(
        swiss::parse(&bill(IBAN, "SCOR", "RF47 ABC1 23"))
            .unwrap()
            .checksum,
        PaymentChecksum::Valid
    );
}

#[test]
fn non_reference() {
    let p = swiss::parse(&bill(IBAN, "NON", "")).unwrap();
    assert_eq!(ex(&p, "referenceType"), Some("NON"));
    assert_eq!(p.reference, None);
    assert_eq!(p.checksum, PaymentChecksum::Valid);
}

#[test]
fn swiss_bad_iban() {
    assert_eq!(
        swiss::parse(&bill(
            "CH4431999123000889013",
            "QRR",
            "210000000003139471430009017"
        ))
        .unwrap()
        .checksum,
        PaymentChecksum::Invalid
    );
}

#[test]
fn combined_address() {
    let p = swiss::parse("SPC\r\n0200\r\n1\r\nCH9300762011623852957\r\nK\r\nMuster GmbH\r\nHauptstrasse 5\r\n8000 Zürich\r\n\r\n\r\nCH").unwrap();
    assert_eq!(p.city, None);
    assert_eq!(
        ex(&p, "creditorAddress"),
        Some("Hauptstrasse 5\n8000 Zürich")
    );
    assert_eq!(p.country.as_deref(), Some("CH"));
}

#[test]
fn truncated() {
    let p = swiss::parse("SPC\n0200\n1\nCH9300762011623852957\nS\nMuster").unwrap();
    assert_eq!(p.payee_name.as_deref(), Some("Muster"));
    assert_eq!(p.amount, None);
    assert_eq!(p.checksum, PaymentChecksum::Valid);
    assert_eq!(
        swiss::parse("SPC\n0200\n1").unwrap().checksum,
        PaymentChecksum::Absent
    );
}

#[test]
fn swiss_rejected() {
    for s in [
        "SPC\n0100\n1\nCH93",
        "SPC\n0200\n2\nCH93",
        "XPC\n0200\n1",
        "",
    ] {
        assert!(swiss::parse(s).is_none(), "{s}");
    }
}

// MARK: PaymentsUPITests.swift

#[test]
fn pay_with_amount() {
    let p = upi::parse(
        "upi://pay?pa=shop@okbank&pn=Corner%20Shop&am=250.00&cu=inr&tn=Order+1&tr=T1&mc=5411",
    )
    .unwrap();
    assert_eq!(p.format, PaymentFormat::Upi);
    assert_eq!(p.payee_account.as_deref(), Some("shop@okbank"));
    assert_eq!(p.payee_name.as_deref(), Some("Corner Shop"));
    assert_eq!(p.amount.as_deref(), Some("250.00"));
    assert_eq!(p.currency.as_deref(), Some("INR"));
    assert_eq!(p.note.as_deref(), Some("Order+1"));
    assert_eq!(p.reference.as_deref(), Some("T1"));
    assert_eq!(ex(&p, "mcc"), Some("5411"));
    assert_eq!(ex(&p, "kind"), Some("pay"));
    assert_eq!(p.checksum, PaymentChecksum::Absent);
    assert_eq!(p.party, PaymentParty::Unknown);
}

#[test]
fn pay_without_amount_case_insensitive() {
    let p = upi::parse("UPI://Pay?PA=a@b").unwrap();
    assert_eq!(p.payee_account.as_deref(), Some("a@b"));
    assert_eq!((p.amount, p.currency), (None, None));
}

#[test]
fn mandate() {
    let p = upi::parse("upi://mandate?pa=x@y&mode=13&url=https%3A%2F%2Fa.example").unwrap();
    assert_eq!(ex(&p, "kind"), Some("mandate"));
    assert_eq!(ex(&p, "mode"), Some("13"));
    assert_eq!(ex(&p, "url"), Some("https://a.example"));
}

#[test]
fn not_upi() {
    for s in [
        "upi://collect?pa=x@y".to_string(),
        "https://pay".into(),
        "".into(),
        format!("upi://{}", "a".repeat(9000)),
    ] {
        assert!(upi::parse(&s).is_none());
    }
}

#[test]
fn bad_percent_escape_stays_literal() {
    assert_eq!(
        upi::parse("upi://pay?pa=a@b&pn=100%")
            .unwrap()
            .payee_name
            .as_deref(),
        Some("100%")
    );
}
