//! Every ScannerCore test from the iOS app (Kit/Tests/ScannerCoreTests), ported one to one, so the
//! crate is held to the behaviour the Swift implementation shipped with. Names match the Swift
//! test functions.
//!
//! The Swift tests drive `DocumentStore`, whose file I/O stays native. Each port runs the same
//! scenario through the rules the store calls (with an in-memory library and backup file standing
//! in for the folders), and leaves out only the assertions about disk itself: image bytes read
//! back, folders moved to and from `.trash`, files deleted. `trashRestoreAndPermanentDeletePreservePages`
//! is nothing but folder moves and is not ported; it stays with each platform's store tests.

use scanner_core::*;
use std::collections::HashMap;

fn page(text: &str) -> ScanPage {
    new_scan_page(text.into())
}

/// `DocumentStore.create`: image limits, then a new valid document.
fn create(title: &str, images: &[Vec<u8>], text: &[&str]) -> Result<ScanDocument, ScanError> {
    let sizes = images.iter().map(|i| i.len() as u64).collect();
    new_document_from_scan(
        title.into(),
        sizes,
        text.iter().map(|t| t.to_string()).collect(),
        816_091_200.0,
    )
}

/// `DocumentStore.save`'s rules: validate, then the manifest bytes; reading back as the library does.
fn save_and_reopen(document: &ScanDocument) -> Result<ScanDocument, ScanError> {
    document_validate(document)?;
    let bytes = manifest_encode(document)?;
    manifest_read(&bytes, document_folder_name(document.id))
}

/// A document as a backup holds it: deleted or not, and its page images.
type Entry = (ScanDocument, bool, Vec<Vec<u8>>);

/// An in-memory `.scannerbackup`, written the way `exportBackup` streams it.
fn export(entries: &[Entry]) -> Result<Vec<u8>, ScanError> {
    let index = entries
        .iter()
        .map(|(document, deleted, images)| BackupEntry {
            document: document.clone(),
            deleted: *deleted,
            sizes: images.iter().map(|i| i.len() as i64).collect(),
        })
        .collect();
    let mut out = backup_header(index)?;
    for (_, _, images) in entries {
        for image in images {
            out.extend_from_slice(image);
            out.extend(backup_checksum_bytes(crc32(image)));
        }
    }
    Ok(out)
}

/// `restoreBackup` over an in-memory file: every check before anything is added, page checksums
/// as they are copied (in 1 MB chunks, as the store does), new document IDs. All or nothing.
fn restore(file: &[u8]) -> Result<Vec<Entry>, ScanError> {
    let length = file.len() as u64;
    let preamble = &file[..file.len().min(13)];
    let index_length = backup_index_length(length, preamble)? as usize;
    let header = file
        .get(13..13 + index_length + 4)
        .ok_or(ScanError::InvalidDocument)?;
    let index = read_backup_index(length, header)?;
    let mut cursor = 13 + index_length + 4;
    let mut restored = vec![];
    for entry in index.entries {
        let mut images = vec![];
        for size in &entry.sizes {
            let image = &file[cursor..cursor + *size as usize];
            let checksum = image.chunks(1_048_576).fold(0, crc32_continue);
            cursor += *size as usize;
            let stored = u32::from_le_bytes(file[cursor..cursor + 4].try_into().unwrap());
            cursor += 4;
            if stored != checksum {
                return Err(ScanError::InvalidDocument);
            }
            images.push(image.to_vec());
        }
        let copy = restored_copy(&entry.document);
        restored.push((
            manifest_read(&document_encode(&copy)?, document_folder_name(copy.id))?,
            entry.deleted,
            images,
        ));
    }
    Ok(restored)
}

// MARK: BacklogTests.swift

#[test]
fn old_manifests_open_without_new_fields() {
    let (id, page) = (Uuid::new_v4(), Uuid::new_v4());
    let json = format!(
        r#"{{"version":1,"id":"{}","title":"Old scan","created":0,"pages":[{{"id":"{}","text":"Original OCR"}}]}}"#,
        id, page
    );
    let document = document_decode(json.as_bytes()).unwrap();
    document_validate(&document).unwrap();
    assert_eq!(
        scan_page_settings(&document.pages[0]),
        page_settings_default()
    );
    assert!(document.note.is_none() && document.favorite.is_none());
}

#[test]
fn edits_keep_original_and_reject_stale_recognition() {
    let doc = create("Old", &[vec![1, 2]], &["Old text"]).unwrap();
    let mut settings = PageSettings {
        quarter_turns: 1,
        brightness: 0.15,
        grayscale: true,
        ..page_settings_default()
    };
    let edited =
        save_and_reopen(&document_with_page_settings(&doc, doc.pages[0].id, settings).unwrap())
            .unwrap();
    let merged = document_fill_recognized_text(
        &edited,
        HashMap::from([(doc.pages[0].id, "Stale OCR".to_string())]),
        Some(HashMap::from([(doc.pages[0].id, page_settings_default())])),
        true,
    );
    assert!(merged.pages[0].text.is_empty());
    // Images are untouched by edits: native (`store.images`).
    let named = save_and_reopen(&document_with_metadata(
        &merged,
        "Edited".into(),
        "Tax deduction".into(),
        true,
    ))
    .unwrap();
    assert!(document_matches(&named, "deduction".into()));
    assert_eq!(scan_page_settings(&named.pages[0]), settings);
    settings.contrast = f64::INFINITY;
    assert_eq!(
        page_settings_validate(settings),
        Err(ScanError::InvalidDocument)
    );
}

#[test]
fn backup_round_trip_and_corruption_preserve_existing_library() {
    let images = vec![vec![17u8; 1_100_000], vec![2, 3]];
    let mut doc = create("Receipt", &images, &["Coffee", "Total"]).unwrap();
    let settings = PageSettings {
        grayscale: true,
        ..page_settings_default()
    };
    doc = save_and_reopen(&document_with_page_settings(&doc, doc.pages[0].id, settings).unwrap())
        .unwrap();
    doc = save_and_reopen(&document_with_metadata(
        &doc,
        doc.title.clone(),
        "Business".into(),
        true,
    ))
    .unwrap();
    let deleted = create("Trashed", &[vec![9]], &["Gone"]).unwrap();
    let archive = export(&[
        (doc.clone(), false, images.clone()),
        (deleted.clone(), true, vec![vec![9]]),
    ])
    .unwrap();

    let library = restore(&archive).unwrap();
    assert_eq!(library.len(), 2);
    let copies: Vec<_> = library.iter().filter(|e| !e.1).collect();
    let trash: Vec<_> = library.iter().filter(|e| e.1).collect();
    assert!(copies.len() == 1 && trash.len() == 1);
    let copy = &copies[0].0;
    assert!(
        copy.id != doc.id
            && copy.note.as_deref() == Some("Business")
            && copy.favorite == Some(true)
    );
    assert_eq!(scan_page_settings(&copy.pages[0]), settings);
    assert_eq!(copies[0].2, images);

    // A flipped byte in the last page fails its checksum; nothing is added.
    let mut bytes = archive.clone();
    let at = bytes.len() - 5;
    bytes[at] ^= 0xff;
    assert!(restore(&bytes).is_err());
    // Truncated: the length no longer matches the index.
    assert_eq!(
        restore(&bytes[..bytes.len() - 20]),
        Err(ScanError::InvalidDocument)
    );
}

#[test]
fn fifty_page_boundary_and_append_are_atomic() {
    let doc = create("Long", &vec![vec![1u8]; 49], &[""; 49]).unwrap();
    check_append_images(doc.pages.len() as u64, vec![1], 49).unwrap();
    let mut full = doc.clone();
    full.pages.push(page(""));
    let full = save_and_reopen(&full).unwrap();
    assert_eq!(full.pages.len(), 50);
    // A reorder made against the 49-page copy is stale.
    let ids: Vec<Uuid> = doc.pages.iter().map(|p| p.id).collect();
    assert_eq!(
        document_with_page_order(&full, ids.clone(), Some(ids)),
        Err(ScanError::InvalidDocument)
    );
    assert_eq!(
        check_append_images(full.pages.len() as u64, vec![1], 50),
        Err(ScanError::TooLarge)
    );
    let reversed: Vec<Uuid> = full.pages.iter().rev().map(|p| p.id).collect();
    let ordered =
        save_and_reopen(&document_with_page_order(&full, reversed, None).unwrap()).unwrap();
    assert_eq!(ordered.pages[0].id, full.pages[49].id);
}

#[test]
fn corrupt_document_does_not_hide_healthy_documents_or_silently_enter_backup() {
    let healthy = create("Keep", &[vec![1]], &[""]).unwrap();
    let folders = [
        (
            document_folder_name(healthy.id),
            manifest_encode(&healthy).unwrap(),
        ),
        (document_folder_name(Uuid::new_v4()), b"broken".to_vec()),
    ];
    let mut documents = vec![];
    let mut unreadable = 0;
    for (name, bytes) in &folders {
        assert!(is_document_folder(name.clone()));
        match manifest_read(bytes, name.clone()) {
            Ok(document) => documents.push(document),
            Err(_) => unreadable += 1,
        }
    }
    let result = library_listing(documents, unreadable);
    assert_eq!(result.documents, vec![healthy]);
    assert_eq!(result.unreadable_count, 1);
    // `exportBackup` refusing while anything is unreadable, and the broken file left in place: native.
}

// MARK: DocumentTests.swift

#[test]
fn library_persists_reorders_searches_and_deletes() {
    let mut doc = create("Receipt", &[vec![1], vec![2]], &["Coffee", "Total 120"]).unwrap();
    assert!(document_matches(&doc, "coffee".into()));
    doc.title = "Renamed".into();
    doc.pages.reverse();
    let reopened = save_and_reopen(&doc).unwrap();
    assert_eq!(library_listing(vec![reopened], 0).documents, vec![doc]);
    // Images following the page order and deletion to `.trash`: native.
}

#[test]
fn invalid_save_keeps_original() {
    let original = create("Keep", &[vec![1]], &[""]).unwrap();
    let mut invalid = original.clone();
    invalid.pages = vec![];
    assert_eq!(document_validate(&invalid), Err(ScanError::InvalidDocument));
    // The second half (a page whose image file does not exist → `missingPage`) checks the disk: native.
}

// `trash_restore_and_permanent_delete_preserve_pages`: folder moves only; not ported (see top).

#[test]
fn delayed_ocr_preserves_rename_order_and_existing_text() {
    let mut doc = create("Before", &[vec![1], vec![2]], &["", "Manual correction"]).unwrap();
    let original_pages = doc.pages.clone();
    doc.title = "After".into();
    doc.pages.reverse();
    let saved = save_and_reopen(&doc).unwrap();
    let updated = save_and_reopen(&document_fill_recognized_text(
        &saved,
        HashMap::from([
            (original_pages[0].id, "OCR first".to_string()),
            (original_pages[1].id, "OCR second".to_string()),
        ]),
        None,
        false,
    ))
    .unwrap();
    assert_eq!(updated.title, "After");
    assert_eq!(
        updated.pages.iter().map(|p| p.id).collect::<Vec<_>>(),
        doc.pages.iter().map(|p| p.id).collect::<Vec<_>>()
    );
    assert_eq!(
        updated
            .pages
            .iter()
            .map(|p| p.text.as_str())
            .collect::<Vec<_>>(),
        ["Manual correction", "OCR first"]
    );
    // OCR finishing after the document was deleted fails to find its manifest: native.
}

#[test]
fn export_selection_preserves_order_and_uses_portable_name() {
    let pages = vec![page(""), page(""), page("")];
    let doc = new_scan_document("../Receipts: November/2026".into(), pages.clone(), 0.0);
    let plan = pdf_export_plan(&doc, vec![pages[2].id, pages[0].id]).unwrap();
    assert_eq!(
        plan.pages.iter().map(|p| p.id).collect::<Vec<_>>(),
        [pages[0].id, pages[2].id]
    );
    assert!(!plan.filename.contains('/'));
    assert!(!plan.filename.contains(':'));
    assert!(plan.filename.ends_with(" - selected pages.pdf"));
    assert_eq!(
        pdf_export_plan(&doc, vec![]),
        Err(ScanError::InvalidDocument)
    );
    assert_eq!(
        pdf_export_plan(&doc, vec![Uuid::new_v4()]),
        Err(ScanError::InvalidDocument)
    );
}

#[test]
fn removing_a_page_deletes_its_image_only_after_the_manifest_commits() {
    let doc = create("Two pages", &[vec![1], vec![2]], &["", ""]).unwrap();
    let kept =
        save_and_reopen(&document_with_page_order(&doc, vec![doc.pages[1].id], None).unwrap())
            .unwrap();
    let folder = vec![
        "document.json".to_string(),
        page_file_name(doc.pages[0].id),
        page_file_name(doc.pages[1].id),
    ];
    assert_eq!(
        unlisted_page_files(&kept, folder),
        vec![page_file_name(doc.pages[0].id)]
    );
    assert_eq!(
        kept.pages.iter().map(|p| p.id).collect::<Vec<_>>(),
        [doc.pages[1].id]
    );
}

#[test]
fn clean_up_removes_staging_and_old_orphans_but_not_unreadable_documents() {
    let doc = create("Keep", &[vec![1]], &[""]).unwrap();
    let broken = document_folder_name(Uuid::new_v4());
    let root = [
        document_folder_name(doc.id),
        format!(".pending-{}", document_folder_name(Uuid::new_v4())),
        format!(".restore-{}", document_folder_name(Uuid::new_v4())),
        broken.clone(),
    ];
    let mut removed = root
        .iter()
        .filter(|n| is_staging_folder((*n).clone()))
        .count();
    let orphan = page_file_name(Uuid::new_v4());
    let stray = page_file_name(Uuid::new_v4());
    let folders: HashMap<String, (Vec<u8>, Vec<String>)> = HashMap::from([
        (
            document_folder_name(doc.id),
            (
                manifest_encode(&doc).unwrap(),
                vec![
                    "document.json".into(),
                    page_file_name(doc.pages[0].id),
                    orphan.clone(),
                ],
            ),
        ),
        (
            broken.clone(),
            (
                b"not json".to_vec(),
                vec!["document.json".into(), stray.clone()],
            ),
        ),
    ]);
    let mut deleted = vec![];
    for name in root.iter().filter(|n| is_document_folder((*n).clone())) {
        let (manifest, files) = &folders[name];
        // Unreadable documents are never touched.
        let Ok(document) = manifest_read(manifest, name.clone()) else {
            continue;
        };
        let gone = unlisted_page_files(&document, files.clone());
        removed += gone.len();
        deleted.extend(gone);
    }
    assert_eq!(removed, 3);
    assert_eq!(deleted, vec![orphan]);
    assert!(!deleted.contains(&stray));
}

// MARK: Rules the Swift suite exercises only through the store

#[test]
fn create_checks_images_before_the_document() {
    assert_eq!(create("", &[], &[]), Err(ScanError::TooLarge));
    assert_eq!(
        create("Title", &[vec![1]], &["a", "b"]),
        Err(ScanError::TooLarge)
    );
    assert_eq!(create("Title", &[vec![]], &[""]), Err(ScanError::TooLarge));
    assert_eq!(
        create("   \n", &[vec![1]], &[""]),
        Err(ScanError::InvalidDocument)
    );
    assert_eq!(
        check_new_document_images(vec![20_000_001], 1),
        Err(ScanError::TooLarge)
    );
    assert_eq!(check_new_document_images(vec![20_000_000; 5], 5), Ok(()));
    assert_eq!(
        check_new_document_images(
            vec![
                20_000_000, 20_000_000, 20_000_000, 20_000_000, 20_000_000, 1
            ],
            6
        ),
        Err(ScanError::TooLarge)
    );
    assert_eq!(check_append_images(1, vec![], 1), Err(ScanError::TooLarge));
    assert_eq!(check_append_images(1, vec![1], 99_999_999), Ok(()));
    assert_eq!(
        check_append_images(1, vec![2], 99_999_999),
        Err(ScanError::TooLarge)
    );
}

#[test]
fn validation_counts_characters_like_swift() {
    let mut doc = new_scan_document("é".repeat(200), vec![page("")], 0.0);
    // 200 characters, 400 scalars once decomposed.
    doc.title = "e\u{301}".repeat(200);
    assert_eq!(document_validate(&doc), Ok(()));
    doc.title.push('x');
    assert_eq!(document_validate(&doc), Err(ScanError::InvalidDocument));
    doc.title = "Fine".into();
    doc.note = Some("👍🏽".repeat(10_000));
    assert_eq!(document_validate(&doc), Ok(()));
    doc.note = Some("a".repeat(10_001));
    assert_eq!(document_validate(&doc), Err(ScanError::InvalidDocument));
    doc.note = None;
    doc.pages = (0..51).map(|_| page("")).collect();
    assert_eq!(document_validate(&doc), Err(ScanError::InvalidDocument));
    let p = page("");
    doc.pages = vec![p.clone(), p];
    assert_eq!(document_validate(&doc), Err(ScanError::InvalidDocument));
    doc.pages = vec![ScanPage {
        adjustments: Some(PageSettings {
            quarter_turns: 4,
            ..page_settings_default()
        }),
        ..page("")
    }];
    assert_eq!(document_validate(&doc), Err(ScanError::InvalidDocument));
    doc.pages[0].adjustments = Some(PageSettings {
        brightness: 0.5,
        contrast: 0.5,
        quarter_turns: 3,
        grayscale: false,
        crop: None,
    });
    assert_eq!(document_validate(&doc), Ok(()));
    doc.version = 5;
    assert_eq!(document_validate(&doc), Err(ScanError::InvalidDocument));
    doc.version = 1;
    doc.created = f64::NAN;
    assert_eq!(document_validate(&doc), Err(ScanError::InvalidDocument));
}

#[test]
fn search_ignores_case_and_accents() {
    let mut doc = new_scan_document("Café receipt".into(), vec![page("Total 120")], 0.0);
    doc.note = Some("Résumé".into());
    for query in ["", "cafe", "CAFÉ", "resume", "total 1", "receipt\nresume"] {
        assert!(document_matches(&doc, query.into()), "{query}");
    }
    assert!(!document_matches(&doc, "invoice".into()));
}

#[test]
fn page_settings_update_clears_text_and_needs_the_page() {
    let doc = create("Doc", &[vec![1]], &["Read"]).unwrap();
    let bad = PageSettings {
        contrast: 3.0,
        ..page_settings_default()
    };
    assert_eq!(
        document_with_page_settings(&doc, Uuid::new_v4(), bad),
        Err(ScanError::InvalidDocument)
    );
    assert_eq!(
        document_with_page_settings(&doc, Uuid::new_v4(), page_settings_default()),
        Err(ScanError::MissingPage)
    );
    let next = document_with_page_settings(&doc, doc.pages[0].id, page_settings_default()).unwrap();
    assert_eq!(next.pages[0].text, "");
    assert_eq!(next.pages[0].adjustments, Some(page_settings_default()));
}

#[test]
fn page_order_rules() {
    let doc = create("Doc", &[vec![1], vec![2]], &["", ""]).unwrap();
    let (a, b) = (doc.pages[0].id, doc.pages[1].id);
    assert_eq!(
        document_with_page_order(&doc, vec![], None),
        Err(ScanError::InvalidDocument)
    );
    assert_eq!(
        document_with_page_order(&doc, vec![a, a], None),
        Err(ScanError::InvalidDocument)
    );
    assert_eq!(
        document_with_page_order(&doc, vec![a, Uuid::new_v4()], None),
        Err(ScanError::InvalidDocument)
    );
    let next = document_with_page_order(&doc, vec![b, a], Some(vec![b, a])).unwrap();
    assert_eq!(next.pages.iter().map(|p| p.id).collect::<Vec<_>>(), [b, a]);
}

#[test]
fn library_order_breaks_ties_by_id() {
    let mut docs: Vec<ScanDocument> = ["Page 10", "page 2", "Apple", "Page 2"]
        .iter()
        .enumerate()
        .map(|(i, t)| new_scan_document(t.to_string(), vec![page("")], i as f64))
        .collect();
    docs[3].created = docs[2].created;
    let newest = library_sorted(LibraryOrder::Newest, docs.clone());
    assert_eq!(newest[2].title, "page 2");
    let tie: Vec<_> = newest[0..2]
        .iter()
        .map(|d| document_folder_name(d.id))
        .collect();
    assert!(tie[0] < tie[1]);
    let oldest = library_sorted(LibraryOrder::Oldest, docs.clone());
    assert_eq!(oldest[0].title, "Page 10");
    let names: Vec<_> = library_sorted(LibraryOrder::Name, docs)
        .into_iter()
        .map(|d| d.title)
        .collect();
    assert_eq!(names, ["Apple", "page 2", "Page 2", "Page 10"]);
}

#[test]
fn export_name_edge_cases() {
    let pages = vec![page("")];
    let name = |title: &str| {
        pdf_export_plan(
            &new_scan_document(title.into(), pages.clone(), 0.0),
            vec![pages[0].id],
        )
        .unwrap()
        .filename
    };
    assert_eq!(name("Receipt"), "Receipt.pdf");
    assert_eq!(name("..."), "Scan.pdf");
    assert_eq!(name(" .Tax\u{200B}return. "), "Tax return.pdf");
    assert_eq!(name("a<b>c|d\"e*f?g\\h"), "a b c d e f g h.pdf");
    assert_eq!(name(&"é".repeat(150)), format!("{}.pdf", "é".repeat(100)));
}

#[test]
fn backup_limits_and_header_checks() {
    assert_eq!(crc32(b"123456789"), 0xCBF4_3926);
    assert_eq!(crc32_continue(crc32(b"1234"), b"56789"), 0xCBF4_3926);
    let doc = create("Doc", &[vec![1]], &[""]).unwrap();
    let entry = |sizes: Vec<i64>| BackupEntry {
        document: doc.clone(),
        deleted: false,
        sizes,
    };
    assert_eq!(
        backup_header(vec![entry(vec![0])]),
        Err(ScanError::TooLarge)
    );
    assert_eq!(
        backup_header(vec![entry(vec![20_000_001])]),
        Err(ScanError::TooLarge)
    );
    assert_eq!(
        backup_header(vec![entry(vec![1]); 1_001]),
        Err(ScanError::TooLarge)
    );
    let header = backup_header(vec![entry(vec![1])]).unwrap();
    assert_eq!(&header[..9], b"SEVSCAN1\n");
    assert_eq!(
        backup_index_length(1_000_000_001, &header[..13]),
        Err(ScanError::TooLarge)
    );
    assert_eq!(
        backup_index_length(100, b"SEVSCAN2\n\0\0\0\0"),
        Err(ScanError::InvalidDocument)
    );
    assert_eq!(
        backup_index_length(100, b"SEVSCAN1\n"),
        Err(ScanError::InvalidDocument)
    );
    assert_eq!(
        backup_index_length(100, b"SEVSCAN1\n\x01\x24\xf4\x00"),
        Err(ScanError::TooLarge)
    );
    let length = backup_index_length(0, &header[..13]).unwrap() as usize;
    let body = &header[13..];
    assert_eq!(body.len(), length + 4);
    let file_length = header.len() as u64 + 1 + 4;
    assert!(read_backup_index(file_length, body).is_ok());
    assert_eq!(
        read_backup_index(file_length + 1, body),
        Err(ScanError::InvalidDocument)
    );
    let mut wrong = body.to_vec();
    *wrong.last_mut().unwrap() ^= 1;
    assert_eq!(
        read_backup_index(file_length, &wrong),
        Err(ScanError::InvalidDocument)
    );
    // Valid checksum over something that is not an index: Swift's DecodingError.
    let junk = b"{\"version\":1}";
    let mut body = junk.to_vec();
    body.extend(backup_checksum_bytes(crc32(junk)));
    assert_eq!(read_backup_index(30, &body), Err(ScanError::Unreadable));
    // Duplicate document IDs are refused.
    let twice = backup_header(vec![entry(vec![1]), entry(vec![1])]).unwrap();
    assert_eq!(
        read_backup_index(twice.len() as u64 + 10, &twice[13..]),
        Err(ScanError::InvalidDocument)
    );
}
