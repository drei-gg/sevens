# scanner-core

Document Scanner's shared logic in Rust, used by the iOS and Android apps through [UniFFI](https://mozilla.github.io/uniffi-rs/). One implementation, so a rule, a limit or a file format can't drift between platforms.

**In the core:** documents and pages, their validation (Swift's order and error cases) and search, the manifest JSON (Swift's encoding, keys in declaration order; Swift's own key order is random, and either reads on both sides), library order, the pure rules the store applies (image limits on create and append, page order, page settings, metadata, late OCR that never overwrites newer edits, which folders are documents or staging, which page images a manifest no longer lists), PDF export plans and portable file names, page geometry (the sizes pages are normalized and rasterized to, paper boxes, where a page image and its invisible text sit on the paper, each export quality's render size and JPEG quality), page settings, and the `.scannerbackup` format: header, index, CRC-32, every pre-restore check and the exact file length.

**Stays native:** `DocumentStore`'s file I/O (folders, `.trash`, atomic writes, iOS data protection, file sizes on disk, whether a page image exists), streaming page bytes into and out of a backup, image processing and page adjustments applied to pixels, PDF rendering (PDFKit), scanning (VisionKit) and OCR (Vision), and every pixel. The store reads and writes the files; the core decides what is valid and what the bytes are.

`ScanDocument.created` is a Foundation reference-date interval (seconds since 2001-01-01 UTC): Swift's `Date` storage and what its default `JSONEncoder` writes. Swift converts with `Date(timeIntervalSinceReferenceDate:)`; Kotlin adds 978,307,200 seconds for Unix time.

## Build

```sh
scripts/rust-core scanner test       # Linux: Swift-parity suite + golden files
scripts/rust-core scanner bindings   # Kotlin + Swift sources into generated/
scripts/rust-core scanner android    # .so for four ABIs into generated/jniLibs
scripts/rust-core scanner apple      # ScannerCoreFFI.xcframework, built in the macOS VM
```

Needs Rust (stable), `rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android i686-linux-android`, `cargo install cargo-ndk`, an Android NDK, and in the VM `rustup target add aarch64-apple-ios aarch64-apple-ios-sim x86_64-apple-ios`. `generated/` is a build output and is not committed.

## Tests

- `tests/swift_parity.rs`: every ScannerCore test from the iOS app, one to one, under the Swift names, run through the rules the store calls with an in-memory library and backup file. Assertions about the disk itself (image bytes read back, folder moves, deletions) stay with the platform stores, and `trashRestoreAndPermanentDeletePreservePages` (folder moves only) is not ported. Further tests cover the rules the Swift suite only reached through the store.
- `tests/golden.rs`: the core's manifest and backup index come back byte for byte; files written by Swift 6.3's real `JSONEncoder()` (random key order) read back equal; Swift's double formatting and lenient `Int` reading.

## Approximations

- Name order follows `localizedStandardCompare` (numbers by value; accents, then case, as tie-breakers) without ICU, so punctuation and non-Latin scripts may order differently from Foundation in rare ties.
- Search folds case and diacritics like `localizedStandardContains`, without locale-specific rules.

## Adding the next Seven's core

See `apps/fast/core/README.md`.

## Portable marks and ink

`annotations.rs` and `ink.rs` own the original-space geometry and optimistic mark commits. Manifest v3 adds rectangular marks; v4 adds bounded constant-width centerlines, color, width relative to the original short edge and a creation clip. Both native authoring adapters convert through `ink_from_display`; all previews/PDF pixels use `ink_display`. Predicted input, native brush archives and undo stacks are not backup formats. Legacy manifests default to empty ink; a newer document must never be downgraded by rectangle/settings edits. Whole-page mark commits preserve concurrent metadata/text while rejecting changed geometry, rectangles or ink.

Ink limits: 100 strokes, 8,192 points/stroke, 32,768 points/page, 100,000 points/document, finite bounded coordinates, normalized width 0.000001–0.1, valid creation clip, and the existing 6 MB manifest limit. Native canvases clip rather than clamping path points. Rendering flattens visible strokes into output images without replacing originals or feeding annotations into OCR; it is not redaction. Pressure/textures, partial erasing and text labels are separate future format decisions.
