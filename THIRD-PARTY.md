# Third-party work

Everything in this repository that someone else made, with its licence. `scripts/check-rules`
fails if a code dependency is added without a row here, without an exact version or revision, or
under a licence not on the allowed list (MIT, BSD-2-Clause, BSD-3-Clause, Apache-2.0, MPL-2.0, ISC,
Zlib, Unicode, GPL-3.0-compatible). Nothing that collects data or phones home is ever allowed.

## Code libraries

No code libraries yet.

| Name | URL | Licence | Version | Used for |
|---|---|---|---|---|

## Bundled data

| Name | URL | Licence | Version | Used for |
|---|---|---|---|---|
| Public Suffix List | https://publicsuffix.org/list/public_suffix_list.dat | MPL-2.0 | as dated in `lists-version.json` | Finding a link's registrable domain (`apps/qr/Kit/Sources/QRCore/Data/public_suffix_list.dat`) |
| IANA RDAP bootstrap for DNS | https://data.iana.org/rdap/dns.json | IANA public data (no licence restrictions stated) | as dated in the file's `publication` field | Finding the registry to ask a domain's age (`rdap-dns.json`) |
| Unicode confusables (UTS #39), curated subset | https://www.unicode.org/Public/security/latest/confusables.txt | Unicode | 18.0.0 | Look-alike letters in addresses (`confusables.json`) |

Where each data entry comes from is recorded in `apps/qr/Kit/Sources/QRCore/Data/DATA-SOURCES.md`.
