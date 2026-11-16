# Document Scanner

Native SwiftUI, iOS 17+. Scanner-specific code stays in this app; reuse existing SevenKit purchase components. Extract new shared infrastructure only after a second app uses it.

Notes and Files already scan documents. Scanner adds a dedicated local library, editing, searchable PDFs and portable backup. No backend, analytics or subscription. Explicit seven-day trial and one-time unlock from SevenKit. Existing documents remain readable, editable, deletable and exportable after expiry; new documents and added pages (scanned or imported) require access. Backup and restore are included, with no additional app fee.

Look: SevenKit's ink tint and paper (K17). The library is a grid of first-page thumbnails with one large Scan action; a document opens page first, with Export PDF as its one large action. Scanner has no charts or progress visuals, so it uses no data hues; controls stay ink.

## Capture and library

Capture multipage paper with VisionKit or import images and PDFs through Files. Normalize orientation and size, commit the document, then recognize text on-device with Vision. OCR failure leaves the saved scan intact. Completion merges by page ID without overwriting concurrent metadata/order changes or newer page adjustments. Retry OCR explicitly when needed.

Limits: 50 pages, 20 MB per normalized page, 100 MB per document. PDF import rasterizes pages; encrypted PDFs are rejected. Import does not preserve editable PDF objects or the original PDF container. Append camera captures or imported pages to an existing document. Rendering, recognition and export process pages individually; the library displays downsampled thumbnails instead of retaining all full-resolution pages.

Search names, notes and recognized text. Sort newest/oldest/name, filter favorites, and choose system/light/dark appearance. Rename, add a note, reorder or remove pages; retain at least one page or explicitly delete the document. Stale page-order operations reject intervening additions. Unreadable document folders are isolated and reported without hiding healthy documents or deleting their files.

## Editing and recovery

Rotate pages, adjust brightness/contrast, toggle grayscale and reset adjustments to the original. Stored originals are unchanged. Saving adjustments clears the previous recognition and reruns OCR for the edited page. There is no manual crop or annotation tool yet.

Whole-document deletion moves the folder to Recently Deleted. Restore or explicitly delete permanently; there is no automatic purge. This is local recovery, not a backup, and does not undo individual page removals. A removed page's image file is deleted once the document's updated page list is saved. On launch, the app removes staging folders left by an interrupted save or restore, and page images an earlier build left behind; unreadable documents are left untouched.

## Export

Export all or selected pages in document order, with a sanitized document-based filename, standard or reduced-detail quality, A4 or US Letter paper, exact prepared size, preview, Save to Files and system sharing. Searchable text is enabled by default: Vision recognizes the adjusted page and an invisible positioned text layer is embedded in the PDF. An image-only option is available. Source images remain unchanged. OCR accuracy depends on the source and is not guaranteed.

PDFKit previews scroll continuously. The quick document preview uses image-only output; searchable output is prepared in Export PDF. Share recognized text separately for one page or the whole document. App-owned export and preview directories older than 24 hours are cleaned on launch so share extensions have time to finish reading.

## Portable backup

Manual Files export/restore is included in the app price. No Scanner account, automatic cloud sync or app-operated servers. A user may choose local storage, external storage or a Files provider; that provider's account/storage charges remain independent of Scanner.

The versioned `.scannerbackup` archive streams original images plus document metadata, OCR, adjustments, notes, favorites and Recently Deleted. Limits: 1 GB, 1,000 documents and a 16 MB index. CRC32 detects accidental corruption; backups are not encrypted or authenticated. Unreadable documents block export rather than silently producing an incomplete backup.

Restore checks structure, limits and checksums in staging before adding copies with new document IDs. Existing documents are never replaced. Deleted entries restore into Recently Deleted. Ordinary commit errors remove newly added copies; process termination during the multi-folder commit can leave some restored copies. Repeating restore adds another set of copies. This is not crash-atomic whole-library replacement or automatic synchronization.

## Android

Kotlin and Compose (Material 3 Expressive) in `android/`, on the same Rust core: documents, limits, manifests, page order, late-OCR merging, export plans, page geometry and the `.scannerbackup` format are shared, so libraries and backups move between the two phones unchanged. What differs is the platform half:

- **Capture.** The open edition photographs pages with CameraX: no edge detection or perspective correction (no open-source detector good enough to ship was found); pages can be rotated and adjusted afterwards. Personal and Play editions use Google's ML Kit document scanner through Google Play services (a platform exception, like Play Billing), falling back to CameraX until Play services has its module.
- **Recognition.** Tesseract 5 with its English model, bundled, in every edition (Vision on iOS). English only; arm64 and x86_64 devices only (32-bit devices save and export scans, but recognition reports that it could not finish).
- **Searchable PDF.** PdfBox-Android writes the page JPEG and an invisible Helvetica layer (text render mode 3), each line scaled to its recognized bounds; characters Helvetica can't encode become spaces in the layer. Preview renders with PdfRenderer.
- **Files.** Import, Save to files, backup and restore use the system document pickers; Share uses the share sheet.
- **Deleting.** Moving a document to Recently Deleted happens at once with Undo in a snackbar (iOS asks first); permanent deletion still confirms. Recently Deleted's actions are a long-press menu plus Restore on each row (no swipe).
- **Device backup.** Android cloud backup carries only preferences (the library can exceed its 25 MB quota); device-to-device transfer also carries the library.



Portable tests: `scripts/test-linux apps/scanner/Kit`. Twelve tests cover compatibility, removed-page cleanup, launch housekeeping, persistence, delayed/stale OCR, edits, append limits, stale page ordering, trash, export selection and backup round-trip/corruption rejection. Strict Apple Debug build and native simulator UI checks cover creation/OCR/rename/relaunch, selected export, trash recovery, page adjustment persistence and backup Files-picker presentation with enabled Save. A processing audit exports/imports 50 pages and checks searchable Letter PDF text, rotation and reset. Generated PDFs are inspected with PDFKit, Poppler and visual rendering. Synthetic simulator checks do not establish physical-device performance.

Physical-camera acceptance, actual cloud-provider delivery, production purchases, broader real-world OCR/PDF compatibility, localization completion and real-device memory/performance remain release work. No folders, automatic cloud synchronization, annotations or manual crop. Public icon remains a neutral document placeholder. Local source only; no release or device installation is implied.

Build: open `Scanner.xcodeproj` on a Mac, select Scanner and an existing iPhone destination. Debug flags `-scanner.ui -scanner.reset` use an isolated library and local unlocked source; `-scanner.seed` adds two synthetic receipt pages after startup. `-scanner.audit` exposes the processing audit only in this isolated Debug mode. These fixtures are not production purchase evidence.
