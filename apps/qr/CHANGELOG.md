# Changelog

## 0.1.1 (fix pass 1, not released)

- Scam checks: a brand's exact name on a country ending we don't list (paypal.pt) is now a caution, "This isn't an address we know for PayPal", instead of "Likely scam". Brands' own secondary and regional domains are listed (paypal.me, wa.me, microsoft.de and hundreds more).
- Scam checks now catch look-alike words joined to others (secure-paypa1.com), three-letter brands next to a bait word (dhl-tracking.help), and brand names under an ending we don't recognize.
- Sign-in links that carry a return address are no longer flagged; full-width letters are read the way browsers read them.
- "I know this address": on a caution that comes only from our lists, you can tell the app you know the address. Manage them in Settings.
- About shows how old the scam lists are, and Check for newer lists fetches a signed update from our public code, only when you tap.
- Make: smaller codes for links (the address part in capitals), automatic error correction uses spare room without growing the code, and an offer to remove tracking tags.
- Peek no longer reports a script redirect when a page only reads its own address.
- No splash screen: the app opens straight to the scanner. The app looks like iOS everywhere except About: system colors, bars and buttons, verdicts as an icon plus a word in the system's orange and red, and our own colors and wordmark only on the About screen.

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
