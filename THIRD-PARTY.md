# Third-party work

Everything in this repository that someone else made, with its licence. `scripts/check-rules`
fails if a code dependency is added without a row here, without an exact version or revision, or
under a licence not on the allowed list (MIT, BSD-2-Clause, BSD-3-Clause, Apache-2.0, MPL-2.0, ISC,
Zlib, Unicode, GPL-3.0-compatible). Nothing that collects data or phones home is ever allowed.

## Code libraries

Cleaner uses Apple platform frameworks and the following local-only phone parser.

| Name | URL | Licence | Version | Used for |
|---|---|---|---|---|
| PhoneNumberKit | https://github.com/PhoneNumberKit/PhoneNumberKit | MIT | 5.0.14 (ff40a615c71bf3e1785562c86f7e38bdea29660a) | Contact phone candidate normalization; bundled libphonenumber metadata, no network client |

## Bundled data

| Name | URL | Licence | Version | Used for |
|---|---|---|---|---|
| Libphonenumber metadata | https://github.com/google/libphonenumber | Apache-2.0 | metadata 9.0.43, as bundled in PhoneNumberKit 5.0.14 | International phone numbering plans, transformed XML to JSON by PhoneNumberKit |
