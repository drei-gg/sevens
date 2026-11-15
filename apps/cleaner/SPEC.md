# Cleaner

Clean up photos, videos, selected files and contacts in one private, native app. A free seven-day trial with no card, then one purchase to unlock. Nothing renews. The first screen explains the built-in Photos, Contacts and iPhone Storage alternatives. No analytics, ads, crash reporters or app backend.

## Full product baseline

This is the product target, not a list of shipped features. Scope remains broad until implementation or acceptance work establishes a specific reason to adapt it. Treemap is deferred. Photo enhancement is excluded. Contacts cleanup is included in this app.

- **Photos:** distinguish exact resources, recompressed equivalents and similar shots; review bursts, blur/exposure candidates, screenshots, Live Photos, large images and useful local content categories. Show reasons and keeper suggestions. Filter by date, album, type, dimensions and measured size. Progressive/incremental scans must respect limited access and user decisions.
- **Videos:** exact duplicates, similar and overlapping-clip candidates; size/duration/resolution/date filters and recording collections. Playback, scrubbing, speed, audio and comparisons. Delete, compress, trim, export or keep from review.
- **Review:** grids, swipe sessions, full-frame zoom, side-by-side comparisons and useful batch actions. Select/deselect/invert, keeper rules, previewed quick cleanup, protected assets/groups/albums, keep/later and undo of pending decisions. Keep selections, scroll and checkpoints through interruptions. New scans never overwrite explicit decisions.
- **Compression:** photo/video batches, quality and resolution controls, image format conversion, Live-to-still and video trimming. Progress/cancel/retry; meaningful before/after inspection and temporary-space accounting. Separate explicit resizing from same-dimension recompression. Preserve supported capture metadata/color/orientation/alpha/audio/HDR/favorites/albums. Save and validate actual outputs before offering any original removal. Prevent repeated lossy processing.
- **Organization:** albums and favorites, searchable/sortable filing, unfiled collections where available, month/year and optional On This Day sessions. Review collections/tags remain candidates. Accessible adaptive iPhone/iPad layouts.
- **Files:** user-selected recursive folders, size/type/date filters, search, previews, exact duplicates and folder totals; permission-aware batch delete/move/export with conflicts, cancellation/resume and per-item outcomes. No access to other apps' private caches or System Data.
- **Retention:** original and multipart exports with manifests, verified destination readback before deletion, saved destinations and history. A readable JPEG is not a complete Live Photo/RAW backup.
- **Dashboard:** scan coverage, logical byte counts, unknown sizes, pending work and completed actions. Distinguish resource size, estimated reduction and observed local capacity. Explain iCloud deletion and Recently Deleted. Optional reminders/widgets remain in scope.
- **Contacts:** exact/likely candidates and match reasons, underlying account records, field-by-field merge review, incomplete entries/correction/bulk deletion, backups and restore. Respect limited access. A shared number is not identity. Prove supported fields/account preservation before destructive merging.
- **Further candidates:** email cleanup and a protected collection require separate feasibility/value evidence. No backend or destructive custody workflow is assumed.

## Preservation contract

An encode success or smaller file does not establish acceptable visual quality. Use the full original, inspect the actual saved output at useful detail, and test difficult content. Metrics can reject candidates; they cannot authorize deletion or guarantee subjective fidelity. Originals remain by default. Any future replacement is explicit and requires independent saved-output verification, supported metadata/feature preservation and a restartable mutation journal. Failure, cancellation and insufficient temporary storage must leave the source intact.

Contacts data that cannot be read must never be treated as empty. Notes need an additional Apple entitlement. A partial archive is not a lossless backup; it cannot authorize deletion. Account boundaries, linked records, labels, photos, dates, non-Gregorian birthdays, relationships and concurrent changes belong in acceptance tests.

## Current build

Implemented: native five-area navigation, SevenKit purchase/onboarding integration, accessible photo/video inventory and collections; streamed full-resource hashes and byte counts; provisional Vision similarity for nearby stills; native image zoom/video playback; batch selection, durable keep/later/removal decisions, previewed Photos deletion with revision/favorite checks; album creation/filing; selected-folder size/hash discovery; underlying contact inventory, candidate pairs, field comparison and verified partial archives; ordinary sRGB still compression candidates with metadata/detail checks and separate-copy saving.

Initial compression profile: JPEG/PNG stills, 8-bit sRGB, no alpha/auxiliary data, at most 16 MP. This bounded profile is an initial implementation, not the final supported-format decision. Source replacement is not enabled. Contact destructive merges and restore-to-store are not enabled while inaccessible fields and account restoration remain unresolved.

The rest of the full baseline remains implementation/acceptance work, including scalable persistent scan indexes, resumable jobs, quality-category tuning, video transforms, image resize/format profiles, contact mutations/restoration, provider mutations, multipart export, richer review/organization and background operation. No feature has been cut merely to fit this first build. Email/vault remain candidates; treemap stays deferred.

## Data and platform

Native SwiftUI, iOS/iPadOS 18+. Foundation-only CleanerCore owns decisions and preservation rules. CleanerPlatform uses Apple frameworks and pinned PhoneNumberKit. Review state writes atomically and unreadable state fails closed. Library resource sizes on older SDKs are measured from requested original resources; unknown is never zero. Cloud downloads are off by default. No private PhotoKit size API.

User content stays in Apple Photos/Contacts/Files unless a copy or export is explicitly requested. App-private review metadata is local. Existing decisions/history and existing contact exports remain accessible after trial expiry; new scans and cleanup actions require trial/unlock. Debug fixtures use isolated state and never establish production purchase evidence.

## Look and acceptance

K17 ink-on-paper through SevenKit tokens, native controls, role-based Dynamic Type, VoiceOver labels, adaptive tabs and two-column comparisons. Display-size heroes, measured-storage charts, thumbnail cards and quick actions follow the shared polish guide; data hues stay on glyphs and chart marks. Device acceptance must cover library permissions/revocation, iCloud, large libraries, disk pressure, background termination, providers and account-backed contacts. Simulator/unit checks alone do not establish real-library preservation or perceptual quality.
