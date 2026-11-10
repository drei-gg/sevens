# Data sources

Every file in this folder, where it came from, and how it was checked. Access date for everything
below: **2026-11-10** unless a line says otherwise. The curated lists (`brands.json`,
`shorteners.json`, `risky-tlds.json`, `bait-words.json`, `payment-networks.json`, and the curation of
`confusables.json`) are this repository's own work and carry its licence, GPL-3.0. They are factual
lists compiled from the public sources named here.

Rule used throughout : an entry that could not be verified is left out. A short honest
list beats a long guessed one.

## `public_suffix_list.dat` (WP 1.1)

Downloaded by WP 1.1 from https://publicsuffix.org/list/public_suffix_list.dat. Licence MPL-2.0.
The download date is recorded by WP 1.1 when it commits the file.

## `rdap-dns.json` (WP 1.6)

Downloaded by WP 1.6 from https://data.iana.org/rdap/dns.json (IANA RDAP bootstrap registry for DNS).

## `brands.json`

200 brands, 292 official domains. Each brand has at least one verified
official domain.

**Selection.** Brands that phishing-trend reports and national advisories name as commonly
impersonated, by sector, with a regional spread (North America, Latin America, Europe, Africa,
Middle East, South Asia, South-East Asia including the Philippines, East Asia, Oceania). The reports
below show which brands and sectors are impersonated; for national banks, couriers, telecoms and
agencies the regional reports name the sector (banks, parcel services, e-wallets, tax agencies) and
the entry was chosen as a market leader in that sector. No report in this list ranks every brand
here.

| Report | URL |
|---|---|
| Check Point Research, Brand Phishing Report Q3 2025 (Microsoft 40%, Google, Apple, Spotify, Amazon, PayPal, Adobe, Booking, LinkedIn, DHL) | https://blog.checkpoint.com/research/microsoft-dominates-phishing-impersonations-in-q3-2025/ |
| Check Point Research, Brand Phishing Report Q4 2025 (Microsoft, Google, Amazon, Apple, Facebook, PayPal, Adobe, Booking, DHL, LinkedIn; Roblox, Netflix campaigns) | https://blog.checkpoint.com/research/microsoft-remains-the-most-imitated-brand-in-phishing-attacks-in-q4-2025/ |
| Check Point Research, Brand Phishing Report Q2 2025 (adds Spotify) | https://blog.checkpoint.com/research/phishing-trends-q2-2025-microsoft-maintains-top-spot-spotify-reenters-as-a-prime-target/ |
| Check Point Research, Brand Phishing Report Q1 2026 (Microsoft, Apple, Google, Amazon, LinkedIn, Dropbox, Facebook, WhatsApp, Tesla, YouTube; PlayStation, Adobe, DocuSign) | https://blog.checkpoint.com/research/the-phishing-paradox-the-worlds-most-trusted-brands-are-cyber-criminals-entry-point-of-choice/ |
| Check Point Research, Brand Phishing Report Q3 2024 (Alibaba, Adobe enter top 10) | https://blog.checkpoint.com/research/check-point-research-unveils-q3-2024-brand-phishing-trends-microsoft-remains-most-imitated-brand-as-alibaba-and-adobe-enter-top-10/ |
| Check Point Research, Brand Phishing (Mastercard returns to top 10) | https://blog.checkpoint.com/research/microsoft-dominates-as-top-target-for-imitation-mastercard-makes-a-comeback/ |
| Vade, Phishers' Favorites 2021 (Facebook, Microsoft, WhatsApp, Netflix, Adobe, LinkedIn, Chase, PayPal, Wells Fargo), as summarised by Help Net Security | https://www.helpnetsecurity.com/2022/03/04/most-impersonated-brands-phishing/ |
| Group-IB, postal and parcel brand impersonation in the Middle East (Aramex and national posts) | https://www.group-ib.com/media-center/press-releases/delivery-failed/ |
| Citizens Advice complaint data on impersonated brands in the UK (Evri, Royal Mail), via FF News | https://ffnews.com/news/uk-phishing-scams-surge-140-royal-mail-and-paypal-top-list-of-most-impersonated-brands |
| Philippine CICC advisories on GCash and Maya message scams, via Manila Bulletin | https://mb.com.ph/2024/5/16/cicc-warns-public-about-new-g-cash-message-scam-targeting-users-through-viber-and-facebook-messenger |
| Fake LTO violation alerts using BDO, RCBC and Maya payment pages, via Manila Bulletin | https://mb.com.ph/2024/4/24/new-phishing-scam-targets-bdo-rcbc-and-maya-users-with-fake-lto-violation-alerts |
| Kaspersky financial-threat figures for the Philippines (banks first, then e-wallets and online shopping), via Philstar | https://www.philstar.com/business/2025/07/17/2458471/phishing-scams-target-philippine-banks-e-wallets/amp/ |
| CERT-In advisory and 2021 CyberPeace/Autobot Infosec findings: Income Tax Department refund phishing against SBI, ICICI, HDFC, Axis, PNB customers, via Business Insider India | https://www.businessinsider.in/tech/news/sbi-icici-hdfc-axis-bank-pnb-and-the-indian-it-department-targeted-in-phishing-scam/articleshow/81511087.cms |
| Brazil: fake Correios and Caixa Pix messages, via Olhar Digital and TecMundo | https://olhardigital.com.br/2025/01/31/seguranca/10-golpes-de-pix-para-ficar-de-olho-em-2025-e-se-proteger/ and https://www.tecmundo.com.br/seguranca/215931-falso-email-caixa-finge-bloqueio-pix-roubar-dados.htm |

Fields: `keywords` are lowercase ASCII tokens of at least 4 characters; none is shared by two
brands, none is a bait word. Brands whose name is shorter than 4 characters (DHL, UPS, BDO, SBI,
ING, UBS…) have no keyword: they are still caught by the look-alike check, which works on domain
labels. `ambiguous` is true where the keyword is an ordinary word.

**Domain verification (C13).** Every domain was requested on 2026-11-10 with
`curl -sSIL --max-time 10 --max-redirs 8 https://<domain>/` (a HEAD request following redirects).
Where HEAD failed or returned 4xx/5xx it was retried with a plain GET and a browser User-Agent, then
with `www.` added. A domain was accepted only when the server answered (any HTTP status below 500;
403 and 405 from bot protection count as an answer) and the final URL's host was on one of the
brand's own domains. Domains that redirected to a different registrable domain of the same company
(for example `hdfcbank.com` → `www.hdfc.bank.in`, `office.com` → `m365.cloud.microsoft`,
`zoom.us` → `www.zoom.com`, `zellepay.com` → `www.zelle.com`, `metamask.io` → `metamask.com`,
`etisalat.ae` → `www.eand.ae`, `indosatooredoo.com` → `im3.id`, `kakao.com` → `www.kakaocorp.com`,
`icicibank.com`, `axisbank.com`, `onlinesbi.sbi`) were kept and the landing domain was added too.
`snb.com.sa` was checked the same way.

Left out because no official domain could be verified from this machine (no answer, timeout or
connection reset): Sberbank, First Bank of Nigeria, Bangkok Bank, PostNL, Receita Federal and
Agencia Tributaria (the latter answered only on `sede.agenciatributaria.gob.es`, which was not kept
because the brand was cut by the 200 cap anyway). Individual domains that did not answer were
dropped from their brand: `ing.nl`, `bcp.com.pe`, `opay.com`, `alahli.com`, `indosat.com`,
`ezpass.com`, `movistar.com` (the last landed on a different company's site).

A further set of verified candidates was cut only to respect the 200-brand cap; they can be added
back later.

## `shorteners.json`

63 link-shortening hosts. Each answered `curl -sSIL --max-time 10 https://<host>/` on 2026-11-10
(any status below 500; shorteners often return 400/404/405 for a bare root, which still means the
host is live). Candidates that did not answer (for example `bit.do`, `po.st`, `shorte.st`, `u.to`,
`su.pr`, `clck.ru`) or whose root returned a Cloudflare 52x error were dropped. Hosts that are chat
or media deep-link domains rather than shorteners (`t.me`, `wa.me`, `m.me`, `youtu.be`,
`forms.gle`, `maps.app.goo.gl`) are not listed, so ordinary Telegram, WhatsApp and YouTube links
do not warn. The list is compiled from well-known public shorteners; there is no single source.

## `risky-tlds.json`

25 endings, all named in one report: Interisle Consulting Group, *Phishing Landscape 2025: An Annual
Study of the Scope and Distribution of Phishing* (study period May 2024 – April 2025), accessed
2026-11-10, https://interisle.net/PhishingLandscape2025. The names come from its rankings by
Phishing Score, by malicious registrations and by phishing volume. The very large general-purpose
endings (.com, .net, .info, .online) and the big new endings that carry a lot of ordinary traffic
(.xyz, .shop, .live, .club), although they appear in the report by volume, are left out: a volume
ranking is not a reason to warn about a whole ending. `.tk`, `.ml`, `.ga`, `.cf`, `.gq` appear in
the report's five-year comparison; their registries stopped taking registrations, so they matter
mainly for old links.

## `confusables.json`

Curated subset of Unicode's `confusables.txt` (UTS #39, version 18.0.0, dated 2026-08-06),
https://www.unicode.org/Public/security/latest/confusables.txt, accessed 2026-11-10. Selection:
lowercase Greek and Cyrillic letters in the Basic Multilingual Plane, and the fullwidth lowercase
letters and digits, whose Unicode skeleton is a single ASCII letter or digit. Values are lowercase
ASCII. Because the app also maps `i` to `l`, `1` to `l` and `0` to `o` (SPEC §4.2), targets of `i`,
`1` and `0` are written as `l`, `l` and `o` so that one pass gives the same skeleton as an all-ASCII
spelling. The five ASCII pairs from SPEC §4.2 are added by hand: `0→o`, `1→l`, `i→l`, `rn→m`,
`vv→w`. Licence: Unicode License v3 (https://www.unicode.org/license.txt); the data comes from the
Unicode Consortium and is reduced here.

## `bait-words.json`

Hand-picked: the words that phishing pages put in domains and paths (login, verify, secure, account,
update, wallet, billing, password…), plus common Spanish, Portuguese, French, German, Indonesian and
Filipino equivalents. Words common enough on ordinary sites (www, home, shop, service) are left out.

## `payment-networks.json`

`emv` has 5 entries, each with the published document that states the identifier (D11). `party` is
"business" only where the document says the identifier is for a merchant or biller; everything else
is "unknown".

| id | Document | Where it states the identifier |
|---|---|---|
| `br.gov.bcb.pix` (Pix) | Banco Central do Brasil, *Manual de Padrões para Iniciação do Pix* (BR Code manual), https://www.bcb.gov.br/content/estabilidadefinanceira/spb_docs/ManualBRCode.pdf | Merchant account information, tag 26, sub-tag 00 "GUI" = `BR.GOV.BCB.PIX` |
| `A000000677010111`, `A000000677010112`, `A000000677012006` (PromptPay) | Bank of Thailand, *Policy Guideline: Standardized Thai QR Code for Payment Transactions*, https://www.bot.or.th/content/dam/bot/fipcs/documents/FPG/2562/EngPDF/25620084.pdf | Tag 29 sub-tag 00 AID `A000000677010111` (credit transfer with PromptPay ID: mobile, national or tax ID, e-wallet); tag 30 sub-tag 00 AID `A000000677010112` "for domestic merchant" and `A000000677012006` "for cross-border merchant" (bill payment, biller ID) |
| `A0000006150001` (DuitNow QR) | PayNet developer documentation, DuitNow QR merchant-presented mode, QR data object, https://docs.developer.paynet.my/docs/duitNow-QR/integration/QR-generation-specification/merchant-presented-mode/qr-data-object | Merchant account information, sub-tag 00 "Malaysia AID: A0000006150001" |

Looked for and not included because no published specification stating the identifier could be
read: QRIS (Indonesia), QR Ph (Philippines; the BSP's published FAQ was not retrievable), PayNow /
SGQR (Singapore), VietQR / NAPAS, Hong Kong FPS (the HKMA's Common QR Code Specification leaves the
operator template to the operator), KHQR (Cambodia; the document's sub-tag 00 is an account ID, not a
network ID), UPI. Identifiers for these appear in third-party libraries, which is not a source we can
cite. They can be added when someone reads the scheme's own document.

`cryptoSchemes` is the list of URI schemes wallets register for payment requests: bitcoin, ethereum,
litecoin, bitcoincash, dogecoin, monero, lightning, dash, zcash, nano, solana. Names are the
display names.

## `currencies.json`

ISO 4217 numeric code → alphabetic code, 178 entries including the funds and special codes
(XAU, XXX…). Source: the SIX Group list-one XML, the ISO 4217 maintenance agency's current table,
published 2026-09-17, https://www.six-group.com/dam/download/financial-information/data-center/iso-currrency/lists/list-one.xml,
accessed 2026-11-10. Numeric codes are three-digit strings with leading zeros kept.
