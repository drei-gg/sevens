# Cleaner changes

## 2026-11-15 — one shared core

- Cleaner's review rules, keeper and default-selection rules, removal guard, compression checks, contact candidates and merge plans, and the review and contacts file formats now come from one shared core, so the iPhone and Android apps decide the same way. Existing review files and contact exports open unchanged.
- A review file that cannot be read now says so in plain words ("This file cannot be read.") instead of a decoder message. It is still never reset.

## 2026-11-13 — polish (uncommitted review)

- Restructured the five main areas around a hero, one primary action, quick actions and review tiles. Added media previews and measured-size charts with explicit unknown-size and iCloud caveats.
- Adopted SevenKit K17, display typography and data hues; stacked layouts at accessibility sizes. Added English catalog/plural entries and a deterministic light/dark/AX5 screen tour.
- Refined compression and contact comparison presentation without weakening preservation gates or adding destructive contact operations.

## 0.1.0 — 2026-11-12 (development)

- Started the full Cleaner baseline: photos, videos, selected folders and contacts, using SevenKit conventions and native Apple frameworks.
- Added durable review choices, exact-resource and provisional nearby-photo matching, media review, album filing and reviewed deletion.
- Added original-retaining still-compression candidates with actual saved-output, metadata and full-resolution detail checks.
- Added underlying contact records, PhoneNumberKit candidate matching, field comparison and verified partial archives. Destructive merges remain unavailable when preservation is unproven.
- Added portable preservation tests and native compression/contact archive tests. See SPEC for current support and remaining baseline work.

Source: this repository, `apps/cleaner/`. PhoneNumberKit 5.0.14 is credited in THIRD-PARTY.md and in-app notices.
