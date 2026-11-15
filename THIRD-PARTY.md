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


Vault uses the operating-system CryptoKit, CommonCrypto, Security/Keychain and AppleArchive frameworks, with PhotosUI, ImageIO and Quick Look. No additional third-party code is bundled. AppleArchive provides the authenticated streaming file format; Vault does not implement its own cipher.
