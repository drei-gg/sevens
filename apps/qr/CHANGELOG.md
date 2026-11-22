# Changelog

## 18 November 2026 — feature continuation, not released

- Added reviewed batch QR creation on iOS and Android, with numbered multipage label PDFs, paper/grid choices and exact-input preservation. Generated PDFs are decoded back in native platform tests.

## One core for both phones (15 November 2026, not released)

- The scam checks, payment and contact decoding, Peek's page reading, Make's link tidying, the History and known-address files and the scam lists' rules now come from one shared core, so the iPhone and Android apps reach the same verdict on the same code. Saved History, known addresses and downloaded lists open unchanged.

## Polish pass (13 November 2026, not released)

Laid out like the best scanner apps, inside the Sevens' look.

- Scan: the camera fills the screen under the bar, a frame shows where to hold the code, and Photo, the torch and Paste sit together on one bar within reach of your thumb. Without a camera, the same frame on paper says why and keeps Photo and Paste.
- Result: the verdict comes first and big: a large symbol and the word (Looks normal, Be careful, Likely scam) above the domain or payee. The main action (Open link, Join Wi-Fi, Add to Contacts…) is one large button at the bottom with Peek beside it; Copy, Share and Make a code are one-tap chips. On a likely scam, Peek leads and Open moves to the chips (it still asks first).
- History: totals at the top (scanned, made, warnings), and each row starts with its type's symbol, then the address, network or person's name, the verdict at the time and the date.
- History detail and the Check this code extension show the verdict the same way as a result.
- Make: your code is the first thing on the screen and updates as you type; kinds are a row of chips; Export is one large button at the bottom once there is a code, out of the way while you type. At the largest text sizes the code follows the fields.

## 0.1.1 (fix pass 1, not released)

- Scam checks: a brand's exact name on a country ending we don't list (paypal.pt) is now a caution, "This isn't an address we know for PayPal", instead of "Likely scam". Brands' own secondary and regional domains are listed (paypal.me, wa.me, microsoft.de and hundreds more).
- Scam checks now catch look-alike words joined to others (secure-paypa1.com), three-letter brands next to a bait word (dhl-tracking.help), and brand names under an ending we don't recognize.
- Sign-in links that carry a return address are no longer flagged; full-width letters are read the way browsers read them.
- "I know this address": on a caution that comes only from our lists, you can tell the app you know the address. Manage them in Settings.
- About shows how old the scam lists are, and Check for newer lists fetches a signed update from our public code, only when you tap.
- Make: scrolling puts the keyboard away, so the code and its options are never hidden under it.
- Make: smaller codes for links (the address part in capitals), automatic error correction uses spare room without growing the code, and an offer to remove tracking tags.
- Peek no longer reports a script redirect when a page only reads its own address.
- No splash screen: the app opens straight to the scanner.
- The Sevens' look on native controls: ink instead of system blue, paper behind every screen, and a red Unlock button. Every color passes contrast in light and dark mode, including secondary text, which is now darker than the system grey. Verdicts are an icon plus a word, in amber and red that pass as text.
- Type: every screen draws from the same seven text roles, with fewer sizes per screen. On a result, the verdict word is a heading above the domain or payee, which stays the largest text. Dates in History use aligned digits.
- Increase Contrast: secondary text, amber, red and separators get stronger, and every color pair reaches 7:1.
- Curly apostrophes in every message.

## 0.1.0 (build 1, not released)

The first build, made from the spec and the implementation plan. Not on the App Store yet.

- Scan codes with the camera, from a photo, or from the clipboard. Several codes in view: tap the one you want.
- Every scan shows a verdict (Looks normal, Be careful, Likely scam) with the reasons in plain words. These checks run on the phone and are free forever.
- Payment codes show who gets paid before your banking app opens: EMVCo national schemes, UPI, SEPA (EPC) and Swiss QR-bills, plus crypto payment links.
- Peek follows a link's redirects and reads the page without running it, then asks the domain's registry how old it is.
- Make codes for links, text, Wi-Fi, contacts, events, email, SMS, phone and places. Export PNG, SVG or a PDF ready to print, one code or a sheet of labels.
- History of what you scanned and made, searchable, with notes, export to JSON or CSV.
- Share extensions: "Check this code" (free) and "Make QR code".
- A free 7-day trial that starts when you tap Start, then one payment to unlock. No subscription.
