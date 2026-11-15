# Changelog

## Look pass — 13 November 2026

Scanner now looks like a document scanner rather than a settings list, in the Sevens' ink-on-paper look (SevenKit K17).

- Library: documents as a grid of first-page thumbnails with page counts, newest first, search on top, an All/Favorites switch once something is starred, and a large Scan button floating at the bottom with Import beside it. Without a camera, Import takes the large button. Recently Deleted and Backup sit on one card under the grid. Long-press a document to star it or move it to Recently Deleted.
- Document: pages first, as a grid of thumbnails with an Add pages tile; Preview, Arrange and Share text as chips; recognized text and details (name, notes) on cards; a star in the toolbar; Export PDF as the one large button. Arrange pages reorders or removes pages in one sheet.
- Page: the page large, Rotate left and right chips, adjustments with their values shown, recognized text below.
- Export: pick pages on their thumbnails, PDF options in one section, and one large button that prepares the PDF and then becomes Share. The ready file's size is shown large.
- Backup: tiles for what the file will hold, Export backup as the large button, Restore beneath it.
- Recently Deleted: thumbnails, Restore on each row, delete permanently by swipe or long-press.
- Counts read “1 page” and “2 pages”; status lines carry an icon and a word; no system grey text on paper.

## 0.1.0 — in development

- Native page scanning and image import into a private document library.
- On-device text recognition, library search and text sharing.
- Rename, reorder and remove pages; preview and share image PDFs.
- Existing SevenKit trial, unlock and restore; saved documents remain accessible.

### App Store review follow-up

- Save page images and the manifest before OCR; failed recognition can be retried without losing the scan.
- Recently Deleted with restore and explicit permanent deletion.
- Export selected pages, document-based filenames, reduced-detail file option and prepared byte-size display.
- OCR completion preserves intervening name/order edits and never recreates a deleted document.
- Clean app-owned export directories after 24 hours on launch.

### Backlog and second wishlist pass

- Non-destructive page rotation, grayscale, brightness/contrast and reset; stale OCR cannot overwrite an edited page.
- Searchable PDF export with positioned on-device OCR, A4/US Letter and optional image-only output.
- PDF import, append-to-existing-document and streaming page processing; exercised with 50 pages.
- Portable Files backup/restore included in the app price, preserving originals, metadata and Recently Deleted. Validated restores add copies without replacing existing documents; no app server or automatic cloud sync.
- Notes, favorites, library sorting, system/light/dark appearance, continuous PDF preview and whole-document text sharing.
- Isolate unreadable document folders, refuse incomplete backups, and reject stale page ordering after an append.
- File-backed exports and managed temporary preview cleanup.

### Audit fixes

- Adding pages to an existing document needs the trial or unlock, like a new document; editing existing pages stays free.
- Removing a page deletes its image file; launch housekeeping clears interrupted staging folders and older leftover images.
- Camera pages are prepared off the main thread, so the app stays responsive after a long scan.

## Shared export infrastructure — unreleased

- Manual backup export now uses SevenKit’s shared Files export picker, extracted for reuse by Vault. Scanner backup format and manual-only behavior are unchanged.
