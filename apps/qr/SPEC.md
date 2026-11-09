# QR: spec

Draft 0.1 · 9 November 2026 · working name "QR" (final name to be decided)

A QR reader and maker that tells you where a code really goes before you go there. Paid for once.

## Why anyone would pay

The iPhone's camera already scans QR codes, and the app says so on its first screen. What the camera doesn't do:

1. **Warn you about scams.** It opens whatever it scanned. Fake codes stuck over real ones on parking meters, restaurant tables, posters, parcels and invoices are a worldwide scam ("quishing").
2. **Show who a payment code pays** before your banking app opens.
3. **Make a code from anything you share**, such as a Safari page, a note or a contact.
4. **Remember** what you scanned and made.

## Principles

- **Never say "safe."** We show what we see and how worried we are, with reasons.
- **Fast first.** Offline checks finish before the result sheet appears. The network is used when a feature needs it: Peek, domain age, opening a link.
- **We collect nothing.** No analytics, no accounts, no backend of ours. The app shows every kind of network contact it makes (see "Network contact").
- **Global by default.** The core logic is built on standards. Anything regional, like brand lists or payment network names, lives in bundled data files that anyone can extend with a pull request.

## Platform

iOS 17+, iPhone, SwiftUI, Swift 6, with no dependencies beyond Apple's frameworks. It uses the shared `SevenKit` package for the trial, unlock, About screen and design tokens. Android comes later, from this spec.

---

## 1. First launch

1. **Splash:** `$7&D` wordmark (ink, with a red 7), shown only as long as iOS needs it.
2. **Before you buy**, shown once and reachable later from About:
   > **Your iPhone already scans QR codes.** Point the Camera at one, or use Code Scanner in Control Center. If that's all you need, delete this app and keep your seven dollars.
   >
   > **What this adds:** it checks where a code really goes before you open it, shows who a payment code pays, makes codes from anything you share, and remembers what you scanned. Free for 7 days, no card. Then $7 once, if you want to keep it.
3. **Camera permission**, asked when the scanner first opens, not before.

## 2. Scan (home screen)

- **Live scanner:** VisionKit `DataScannerViewController`, recognizing all the barcode types it supports. When several codes are in view, each one is highlighted and can be tapped.
- **Torch, and pinch to zoom.**
- **Scan from a photo:** `PhotosPicker`, which needs no photo-library permission. Vision finds every code in the image, so a screenshot with three codes shows all three.
- **Scan from the clipboard:** pastes an image or a URL.
- Buttons for **Make** and **History**.

## 3. Result sheet

The top always shows the **verdict** and the **payload type**. Below that:

| Payload | What we show | Actions |
|---|---|---|
| Web link | The full URL with the **registrable domain** in large type, plus the verdict and its reasons | Peek · Open · Copy · Share · Make code |
| Payment code (§5) | Who gets paid, the amount, the currency, the network if known, and the checksum result | Copy details · Open in… (the system handles it) |
| Wi-Fi | Network name, security type, and whether a password is present | Join (`NEHotspotConfiguration`) · Copy password |
| Contact (vCard/MeCard) | Name and fields; links inside it get the link checks | Add to Contacts |
| Event (vEvent) | Title, time, place | Add to Calendar |
| Phone / SMS / email | The number or address, and the message body if there is one | Call · Message · Email (iOS confirms first) |
| Location (`geo:`) | Coordinates | Open in Maps |
| Crypto address | The whole address, split into groups so it's easy to compare | Copy |
| Other / plain text | The raw text; any links found inside get checked | Copy · Share |

We never act automatically. Every action is a tap.

## 4. Scam checks

### 4.1 Verdict

| Level | Shown as | When |
|---|---|---|
| 0 | **Looks normal** | No signals |
| 1 | **Be careful** | Any warning-level signal |
| 2 | **Likely scam** | Any strong signal, or three or more warnings |

Every signal shows as one plain sentence under the verdict. No scores, no percentages.

### 4.2 Offline signals (run on every scan)

| Signal | Level |
|---|---|
| `javascript:`, `data:`, or `file:` scheme | strong |
| `user@host` trick (`https://bank.com@evil.site`) | strong |
| Lookalike domain: mixed scripts, or punycode that resembles a domain on the brand list | strong |
| A brand's name in a domain that isn't that brand's (`paypal-verify.help`) | strong |
| Raw IP address as the host | warning |
| Known link shortener: we can't see the destination until you Peek | warning |
| Plain `http` | warning |
| Domain ending that scam reports show is heavily abused (bundled list) | warning |
| Unusually deep subdomains, or very long or encoded paths on a login-like URL | warning |
| Open (no-password) Wi-Fi network | warning |
| Payment code fails its checksum | strong |

### 4.3 Bundled data (`apps/qr/Data/`)

These files are updated with app releases. Pull requests are welcome.

- `brands.json`: the most-impersonated brands worldwide and their official domains (payments, banks, couriers, tech, governments). One global list, grouped by region only so it's easier to maintain.
- `shorteners.json`: known link shorteners.
- `risky-tlds.json`: heavily abused domain endings, based on public abuse reports.
- `payment-networks.json`: payment network identifiers mapped to display names (§5).
- `public_suffix_list.dat`: Mozilla's Public Suffix List (MPL-2.0), used to find the registrable domain.
- `rdap-dns.json`: IANA's directory of public registry (RDAP) services (§6.2).

## 5. Payment codes (offline)

We decode the payment code formats used around the world and show **who you're about to pay**:

| Format | How we recognize it | What we show |
|---|---|---|
| EMVCo merchant-presented QR, used by many national schemes in Asia, Latin America, Africa and elsewhere | Payload starts with `000201` and ends with a CRC field | Merchant name (59), city (60), country (58), currency (53), amount (54), whether it's static or dynamic (01), the network looked up from the account identifiers in fields 26–51, and the CRC-16 result |
| UPI | `upi://pay?…` | Payee name (`pn`), payee address (`pa`), amount, note |
| EPC / SEPA credit transfer | First line `BCD` | Beneficiary name, IBAN, amount, reference |
| Swiss QR-bill | First line `SPC` | Creditor, IBAN, amount, reference |
| Crypto payment links | `bitcoin:`, `ethereum:`, `lightning:` and others | Address, amount, label |

**Person or business:** we label it only when the network's identifier clearly says so, as listed in `payment-networks.json`. Otherwise we show the name in large type and let the person judge: "Check this matches where you are."

## 6. Peek (one tap, online)

It's a tap and never automatic, because fetching a link tells whoever made the code that you scanned it.

### 6.1 The link
- Fetches with a fresh, private connection: no cookies, no cache, and nothing kept afterwards.
- Follows up to 10 redirects and shows every hop. A shortened link shows where it actually ends up.
- Reads up to 512 KB of the final page **without running any of its code**, and shows:
  - the page title
  - whether the connection is secure
  - **a password, PIN, one-time code or card-number field** (strong signal if the domain isn't the brand it claims to be)
  - redirects done by script or meta refresh (warning).
- Uses a standard Safari user agent, so we see what a real browser would see.
- Gives up after 8 seconds. Plain `http` links aren't fetched (iOS blocks them by default); they show a warning instead and can still be opened.

### 6.2 Domain age
- Asks the public registry of the domain's ending how old the domain is, sending only the registrable domain.
- Under 30 days: strong signal. Under 6 months: warning.
- Domain endings with no public registry service show "Age unknown" (about 1,200 endings have one; some country endings don't).

## 7. Open
Opens in the in-app browser (`SFSafariViewController`) or in Safari, which adds Safari's own fraudulent-website warning. If the verdict is "Likely scam," the person has to confirm first.

## 8. Make

- **Types:** link, text, Wi-Fi, contact, event, email, SMS, phone, location.
- **Error correction:** chosen automatically, or set by hand.
- **Export:** PNG (any size), SVG (built from the module grid), and a PDF ready to print (one code, or a sheet of labels).
- **Style:** black on white by default, plus foreground and background colors with a contrast check. No logos in the middle for v1.
- **Barcodes:** Aztec, PDF417 and Code 128 through Core Image, in the same flow.

## 9. Share extensions

- **Make QR code:** from any app's share sheet, for a URL, text, contact or location. It shows the code at once, ready to show or save.
- **Check this code:** share an image or screenshot to scan it and see the verdict, without opening the app.

## 10. History

- Scanned and made codes in one list, newest first, searchable.
- Each item: payload, type, verdict at the time, date, and an optional note.
- Swipe to delete, or clear everything. History can be turned off.
- Export as JSON or CSV.
- Stored as files on the phone, with complete file protection. No sync in v1.

## 11. About & settings
- Trial status, Unlock, Restore purchases (from SevenKit).
- **Before you buy** (the screen from §1).
- **Network contact:** the list from §12, in plain words.
- Version, changelog, support email, license (GPL-3.0) and open-source notices (Public Suffix List).

## 12. Network contact

The complete list. Anything not listed here doesn't happen.

| When | Who we contact | What's sent |
|---|---|---|
| You tap Peek | The address in the code, and each redirect | A normal page request with no cookies |
| You tap Peek | The public registry for that domain ending | The domain name |
| You tap Open | Safari opens the link | Whatever Safari sends |
| You buy or restore | Apple, through StoreKit | Handled by iOS |

App Store privacy label: **Data Not Collected.**

## 13. Trial and unlock
Uses SevenKit's standard mechanism: a $0 "7-day trial" purchase and a $7 one-time unlock, with Family Sharing on.

**After the trial (to decide):** I recommend scanning and the offline scam checks **stay free forever**. Putting scam warnings behind a paywall would undercut the whole brand. Peek, Make, the share extensions and History would need the unlock, and history stays readable and exportable either way.

## 14. Accessibility and language
- Full Dynamic Type, VoiceOver labels on every control, and verdicts read aloud as a sentence. Reduce Motion is respected.
- Verdicts never rely on color alone: each has an icon and a word.
- String Catalogs from day one. English at launch, with translations added by community pull requests.

## 15. Not in v1
Product barcode lookup, link "reputation" from third-party services, iPad layout, sync, widgets, Apple Watch, logos inside generated codes, and batch generation.

## 16. Test fixtures
`apps/qr/Fixtures/` holds a payload and its expected result for each signal in §4 and each format in §5:
- valid and corrupted EMVCo payloads (CRC pass and fail)
- UPI, EPC and Swiss samples
- punycode lookalikes, `user@host`, shorteners and IP hosts.

These fixtures double as the Android port's acceptance tests.

## 17. Build budget (about seven engineering days)
1. Project, SevenKit hookup, scanner, result-sheet types
2. Link checks, data files, fixtures
3. Payment decoding, Wi-Fi, contacts and events
4. Peek and domain age
5. Make, export, share extensions
6. History, trial and unlock, Before you buy, About
7. Accessibility pass, polish, testing on the iPhone

## Open questions
1. Final app name.
2. What stays free after the trial (§13 has my recommendation).
3. Which payment networks mark person versus business accounts. To be filled into `payment-networks.json` from each network's published spec.
