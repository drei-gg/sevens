# Cleaner changes

## 18 November 2026 — feature continuation, not released

- Added bounded selected-photo review queues on Android and iOS, with explicit per-photo preparation/save/skip, native progress, visible retry and per-item outcomes.
- Bound compression saves to prepared source snapshots; record source/output guards and history in one write. Retain newly created output receipts so readback/bookkeeping retry verifies the same copy. Preserve originals and current previews on recoverable failures.


- Added native folder filters for filename extension, size, modification date and exact duplicates, composed with search. Draft/apply/cancel, active constraints and matched/total counts keep scope visible. Unknown metadata is not zero; originals, scan results and review records remain unchanged.

- Added native media date filters for day/month/year and On This Day, with shared calendar matching, explicit apply/cancel/clear and preserved selections/decisions. Unknown dates remain unknown. Android filter drafts survive recreation; the selected-item toolbar now reserves actual scroll viewport space so it cannot cover filter actions.

- Added durable foreground comparison checkpoints on both phones. Fresh accessible-library inventory and matching provider revisions gate reuse; cancellation/relaunch preserves completed resources. Native M3 determinate progress, saved-count/continue presentation and clear-progress settings keep review decisions/history separate. Android 11+ uses MediaStore version/generation; older providers remeasure.
- Added explicit Original/2400/1600/800 px resize profiles and output dimensions. Save requires the regenerated preview for the selected size/quality; original bytes and supported metadata remain intact.

- Added native quality controls and explicit regenerated previews on both phones. Save stays disabled for an unpreviewed quality; retries use the original, and failed encoding preserves the prior candidate. Android image encoding now runs off the UI thread.

## 2026-11-15 — one shared core

- Cleaner's review rules, keeper and default-selection rules, removal guard, compression checks, contact candidates and merge plans, and the review and contacts file formats now come from one shared core, so the iPhone and Android apps decide the same way. Existing review files and contact exports open unchanged.
- A review file that cannot be read now says so in plain words ("This file cannot be read.") instead of a decoder message. It is still never reset.

## 2026-11-13 — polish (uncommitted review)

- Restructured the five main areas around a hero, one primary action, quick actions and review tiles. Added media previews and measured-size charts with explicit unknown-size and iCloud caveats.
- Adopted SevenKit K17, display typography and data hues; stacked layouts at accessibility sizes. Added English catalog/plural entries and a deterministic light/dark/AX5 screen tour.
- Refined compression and contact comparison presentation without weakening preservation gates or adding destructive contact operations.

## 0.1.0

- Added explicit native image-size selection (Original, 2400, 1600 or 800 px long edge), shared no-enlargement geometry, measured output dimensions, and Save gating until both size and quality have been previewed. Checked native saved-copy dimensions and capture metadata; the original is retained. — 2026-11-12 (development)

- Started the full Cleaner baseline: photos, videos, selected folders and contacts, using SevenKit conventions and native Apple frameworks.
- Added durable review choices, exact-resource and provisional nearby-photo matching, media review, album filing and reviewed deletion.
- Added original-retaining still-compression candidates with actual saved-output, metadata and full-resolution detail checks.
- Added underlying contact records, PhoneNumberKit candidate matching, field comparison and verified partial archives. Destructive merges remain unavailable when preservation is unproven.
- Added portable preservation tests and native compression/contact archive tests. See SPEC for current support and remaining baseline work.

Source: this repository, `apps/cleaner/`. PhoneNumberKit 5.0.14 is credited in THIRD-PARTY.md and in-app notices.
