# Native feature queue

Updated 18 November 2026. This queue complements every owning `apps/<app>/SPEC.md`; a short status report or matrix does not replace the full baseline. No baseline feature disappears because another slice ships. The private evidence ledger records actual tests, source snapshots and artifacts; “implemented” is not release/device acceptance.

States: **implemented** = local source exists, with evidence recorded separately; **ready** = authorized next implementation candidate, still requiring native/library investigation and verification; **active** = being implemented now; **decision-blocked** = the stated semantics/custody decision must be resolved; **device-acceptance** = implementation exists but named real-device/service verification remains. Explicit candidates, exclusions and deferred ideas remain visible below. A row with several features does not imply they all share the same verification coverage.

## Fast — owner: apps/fast/SPEC.md

| Feature | State | Remaining boundary |
|---|---|---|
| Timestamp-based fasting lifecycle; open-ended/custom/preset goals; explicit ending and editable history | implemented | Existing phone/core suites; actual notification delivery separate |
| Calendar, backdated sessions, journal/mood/hunger/energy/notes and rest-day annotations | implemented | Physical accessibility and localization acceptance |
| Period-based trends, weight/waist/sleep, optional weight hiding | implemented | Health import is manual read-only weight, not general sync |
| Water quick-add/undo, custom/backdated entries, history/trends, optional goal and pauseable scheduled reminders | implemented | OEM/Focus/background notification acceptance |
| Dated plans, saved presets and weekly reminders | implemented | No automatic coaching programme |
| Native phone widget/Live Activity and timer complications | implemented | Physical widget/Lock Screen/face freshness and layout: device-acceptance |
| Paired Watch/Wear start presets, finish confirmation, water, durable pending/retry/acknowledgement | implemented | Paired transport/background delivery: device-acceptance |
| Independent offline watch mutations | decision-blocked | Conflict/reconciliation semantics against phone revisions and later sessions |
| Rich watch preset editing and journal entry | ready | Currently phone-only; assess useful small-screen scope without assuming offline mutation |
| JSON/CSV, conflict-checked import, expiry continuity and trial/unlock | implemented | Production product/signing/Family Sharing/purchase: device-acceptance |
| Foldable/Duo window transitions, actual Health and large-font/manual accessibility | device-acceptance | Build SDK and emulator coverage do not prove hardware acceptance |

## Bloom — owner: apps/bloom/SPEC.md

| Feature | State | Remaining boundary |
|---|---|---|
| Daily/timestamped observations, symptoms, temperature/tests/products, custom notes/tags and corrections | implemented | Existing source/workflow evidence retained |
| Separate private contact events; configurable Today shortcuts, pack/refill context | implemented | Privacy review remains distinct from automation |
| Calendar/search, cycle/temperature/symptom charts with explicit denominators | implemented | Physical accessibility acceptance |
| Conservative estimates, hormonal/pregnancy exclusions, postpartum/lactation/perimenopause modes | implemented | No contraception, diagnosis or clinical-validation claim |
| Pill/other schedules, taken/skipped/snoozed actions, corrections, refills, appointments | implemented | Real notification/zone/background delivery: device-acceptance |
| Manual reviewed Health flow transfer | implemented | Physical permissions/provider/partial failure: device-acceptance |
| Native watch companions with discreet snapshots and durable dose receipts | implemented | Paired transport/background delivery: device-acceptance |
| App authentication gate; encrypted local archive and encrypted backups | implemented | Physical biometrics/enrollment/revocation: device-acceptance |
| Privacy-sensitive widgets, reports, export/import and purchase gates | implemented | Physical launcher/store configuration: device-acceptance |
| Independent offline watch journal | decision-blocked | Conflict policy and private payload scope |
| Flo import | decision-blocked | Requires verified real export fixture; no inferred format |
| Live partner accounts, medical assessment/chat, community/editorial equivalent | excluded by current spec | Manual selected sharing remains implemented; no backend assumed |

## QR — owner: apps/qr/SPEC.md

| Feature | State | Remaining boundary |
|---|---|---|
| Camera/photo/clipboard scan, offline verdict/signals, payment decoding and destination-specific actions | implemented | Real camera/payment fixtures and external intents: device-acceptance |
| User-triggered Peek, redirect/login-form/domain-age inspection | implemented | External network behavior and real destinations require bounded acceptance |
| Signed list update/reset and known addresses | implemented | Configured signing key and published signed lists remain deployment-dependent |
| Nine Make types, styling/options, image/PDF/export and share entry points | implemented | Continue screen-specific polish; external share providers: device-acceptance |
| Batch exact-input review, preserved duplicates/order, paper/grid and numbered multipage PDF | implemented | Decode-back evidence exists; physical printing/large payloads separate |
| Searchable history, notes, opt-out, deletion, JSON/CSV and expiry continuity | implemented | Existing data remains accessible after expiry |
| History multi-device sync | decision-blocked | v1 is local; conflict/privacy/network contract needed |
| Reputation “safe” guarantee or hidden background checks | excluded by spec | No feature iteration may imply these |

## Alarm — owner: apps/alarm/SPEC.md

| Feature | State | Remaining boundary |
|---|---|---|
| Weekly shared slots with per-slot weekdays, one-off dates and interval windows | implemented | Native travel/DST/folds/gaps: device-acceptance |
| System alarms/quiet reminders, reconciliation/receipts, permissions and foreground retries | implemented | Physical locked-phone/OEM/AlarmKit delivery: device-acceptance |
| Missions, optional reason, snooze/follow-up checks and cancel pending checks | implemented | iOS system Stop cannot be bypassed; physical acceptance separate |
| Groups/bulk toggles, quick/custom naps, defaults, duplicate/search/sort | implemented | Existing source/native suites retained |
| Disabled routine templates, JSON merge and CSV history | implemented | Templates are data, not automatic scheduling authorization |
| Native saved-schedule widgets, stale/unverified/privacy states | implemented | Launcher refresh/locked device/permission revoke: device-acceptance |
| Owned PCM WAV import/preview/replace/fallback | implemented | Delivered round five; physical sound delivery: device-acceptance |
| Skip-next | decision-blocked | Reliable recurring AlarmKit occurrence semantics and reconciliation |
| Date-specific overrides | ready | Native recurring interaction must be proven before enabling; no silent loss of base schedule |
| Independently moving one weekday inside a shared slot | ready | Split/rejoin editor semantics must preserve remaining days and receipts |
| Completion-relative schedules | ready | Define explicit completion events and native schedule reconciliation; never infer completion from delivery |
| Multistage timer sequences | ready | Shared state machine, interruption/restart, timing and platform scheduling feasibility |
| Watch app | ready | Native small-screen scope plus confirmed phone commands; no assumed offline scheduling |

## Scanner — owner: apps/scanner/SPEC.md

| Feature | State | Remaining boundary |
|---|---|---|
| Native multipage camera/image/PDF capture/import and append, bounded original storage | implemented | Physical camera and large-document memory/performance: device-acceptance |
| Library search/sort/favorites/theme, rename/notes/page reordering and removal | implemented | Manual accessibility/localization acceptance |
| Rotation/brightness/contrast/grayscale/reset; native crop handles and exact bounds | implemented | Original-preserving version-2 geometry reaches OCR/PDF |
| On-device OCR, retry, saved-text correction and stale-result conflicts | implemented | Correction currently affects search/text share, not freshly recognized PDF layer |
| Rectangular highlights and outline annotations | implemented | Round nine: native canvas/exact bounds, undo/redo, stale-save protection, version-3 original-space geometry, reopen/backup and actual PDF pixels on both phones. Physical stylus/accessibility/performance acceptance remains separate |
| Freehand constant-width ink | implemented | Round twelve: Jetpack Ink / PencilKit, shared v4 geometry, undo/recreation/conflict checks, reopen/backup and actual cropped/rotated PDF pixels on both phones. Physical stylus/latency/manual accessibility remain separate |
| Text labels, pressure/textured ink, partial erasing and richer annotation editing | ready | Preserve portable geometry and accessible editing; constant-width strokes do not complete this scope |
| Corrected text in searchable PDF | ready | Need honest text placement when manual text has no OCR geometry; avoid false alignment claims |
| PDF selected pages/paper/quality/searchable layer/preview/files/share, text export | implemented | Real OCR/font/PDF-reader/provider compatibility: device-acceptance |
| Trash/restore/permanent deletion, portable backup and staged validated restore | implemented | Whole-library restore is not crash-atomic; provider/device limits remain |
| Generic Android document edge/perspective detection | ready | Existing search found no shippable library; reconsider maintained alternatives, preserve CameraX fallback |
| Folders | ready | Explicitly absent today; define portable folder metadata before adding native UI |
| Automatic cloud sync | decision-blocked | Conflict, custody and provider semantics; backup is not sync |

## Cleaner — owner: apps/cleaner/SPEC.md (full baseline retained)

| Feature | State | Remaining boundary |
|---|---|---|
| Accessible photo/video inventory, collections and streamed full-resource exact hashes/bytes | implemented | Permissions, cloud originals, large libraries: device-acceptance |
| Provisional nearby-still similarity | implemented | Threshold/category tuning and real-library perceptual quality: device-acceptance |
| Recompressed-equivalent detection; burst/blur/exposure/content categories | ready | Incremental coverage and false positives must remain explicit. Keeper reasons and connected similar-shot groups are implemented (SPEC, 2026-11-22) |
| Video similar/overlapping clips and recording collections | ready | No identity claims based solely on thumbnails/duration |
| Album/type filters | ready | Size, dimension, video length and Large filters are implemented on both phones (SPEC, 2026-11-22); Large lists largest first from an adjustable minimum (10 MB photos / 100 MB videos by default); unknown values stay unknown, never zero |
| Full-frame zoom/native playback and durable keep/later/removal decisions | implemented | Real-library preservation and revocation: device-acceptance |
| Select visible, clear/deselect and invert visible selection | implemented | Native source on both phones; hidden selection semantics remain explicit |
| Swipe sessions, side-by-side review, protected groups/albums and richer keeper rules | ready | Existing batch selection does not complete these baseline review actions |
| Previewed deletion with source/revision/favorite guards | implemented | Physical PhotoKit/MediaStore mutation and recovery: device-acceptance |
| Bounded sRGB JPEG/PNG still recompression and explicit Original/2400/1600/800 resize | implemented | Separate verified copy; original removal not authorized by encoder success |
| HEIC still recompression (HEIC→HEIC, up to 50 MP) | implemented | iPhone: sRGB/Display P3, copied metadata, every ImageIO auxiliary image (depth, disparity, mattes, HDR/ISO gain maps) read back; Android: HeifWriter, sRGB, capture tags and rotation, refuses P3/mirrored/auxiliary HEICs. Real iPhone/Samsung HEIC libraries and maker-note round trips: device-acceptance |
| Selected-photo compression review queue (1–10) | implemented | Both phones: per-photo preparation/quality/size/inspection/save/skip/stop/results; same-copy verification/record retry; one temporary pair; originals retained |
| Durable/background photo batches and stronger temporary-space budgeting | ready | Session queue does not resume after app termination; provider mutation and ledger are not one crash-atomic transaction; bounded source streaming, disk preflight and journal recovery remain |
| Bounded native video trim/compression review and verified separate copy | implemented | Both phones: SDR H.264/AAC, constant <=30 fps, <=1080p/10 min/512 MiB input, <=720p output, exact range/preview/cancel/save/same-copy retry; originals retained. Physical codecs/providers and quality: device-acceptance |
| Broader video/HDR/HEVC/auxiliary profiles, durable video batches and Live-to-still | ready | Profile-specific audio/color/metadata/timing proof and restartable mutation receipts; current bounded profile is not final scope |
| Additional image formats/conversion/alpha/auxiliary/RAW profiles | ready | Profile-specific preservation; HEIC is implemented (row above). Format conversion, alpha, RAW, Live-to-still and Android auxiliary/P3 HEIC remain |
| Source replacement after compression | decision-blocked | Restartable mutation journal, saved-output/metadata/feature proof; deletion never implicit |
| Album creation/filing and favorites | implemented | iOS PhotoKit albums; Android folder albums (move after the system confirmation; Android 11 minimum). Provider/account/limited-access acceptance |
| Day/month/year and On This Day sessions | implemented | Round seven: native phone filters with shared civil-date rules, explicit reference, preserved selection/decisions and unknown dates; persistent review sessions remain separate |
| Search/sort filing and unfiled collections | ready | Availability differs by provider; do not invent absent dates/albums |
| Review collections/tags | candidate | Feasibility/value review, retained in baseline |
| Selected-folder recursive size/hash discovery | implemented | Physical provider/permission behavior: device-acceptance |
| Filename extension, size and modification-date filters with search | implemented | Round eight: native draft/apply/cancel/clear and duplicate composition on both phones; Android applied/query/draft recreation. Unknown metadata and original scan preserved; real-provider/large-library acceptance remains separate |
| Exact-file duplicates and folder totals | implemented | Current scan coverage/provider restrictions remain explicit; cloud files are not implicitly downloaded |
| File previews and richer provider presentation | ready | Preserve permissions, original files and unknown metadata |
| File batch delete/move/export, conflicts and resumable provider mutations | ready | Native provider capability checks and durable per-item outcomes before mutations |
| Original/multipart export with manifests and verified destination readback | ready | All resources for Live/RAW; partial JPEG is not complete backup |
| Saved export destinations and export history | ready | Revocation/retry and independent readback |
| Dashboard logical size/unknowns/pending/completed work and iCloud/Recently Deleted explanations | implemented | Actual freed capacity differs from logical bytes; physical acceptance |
| Optional reminders/widgets | ready | Retained baseline scope; choose useful private summary without exact-refresh promises |
| Native SQLite resumable foreground exact-comparison checkpoints | implemented | Durable provider mutations/background operation are separate |
| Rich persistent/incremental scan index and background scans | ready | Platform background grants, fresh revisions, limited access and explicit coverage |
| Contact inventory/candidate reasons/field comparison and verified partial archives | implemented | Partial archives cannot authorize destructive mutation |
| Incomplete-contact editing, field-by-field merge, bulk deletion and restore-to-store | decision-blocked | Inaccessible fields/notes entitlement/account and linked-record preservation; source proof required |
| Adaptive tablet layouts, difficult-content quality and memory/storage pressure | device-acceptance | Emulator/build does not prove preservation/perceptual quality |
| Email cleanup and protected collection | candidate | Separate value/feasibility evidence; no backend/custody assumption |
| Android string resources (English) and community translations | implemented / candidate | Every Android string is in `res/values/strings.xml`; core-supplied reasons (profile gates, keeper and match reasons, merge blockers) stay English until the core returns codes; translations arrive by community PRs |
| Treemap / photo enhancement | deferred / excluded | Explicit baseline decisions; not silently omitted |

## Vault — owner: apps/vault/SPEC.md

| Feature | State | Remaining boundary |
|---|---|---|
| Independent vaults/credentials/recovery/biometrics, mixed encrypted originals | implemented | Physical enrollment, large-video, disk pressure and independent security: device-acceptance |
| Native Files/Photos/camera imports and per-item failures | implemented | Current rendition is not full Live Photo; physical camera/provider acceptance |
| Folders, notes, favorites, sorting/grid, selection, trash/restore and permanent delete | implemented | Existing originals and saved records remain usable after expiry |
| Native previews/original exports with owned plaintext cleanup | implemented | External copies cannot be erased by Vault; physical player/provider acceptance |
| Encrypted portable backup/restore and opt-in verified provider-copy queue | implemented | Cloud upload is not proven by local copy; large backup/background delivery acceptance |
| Single-image and bounded selected-page PDF OCR, editable encrypted-note save | implemented | Recognition is on-device; accuracy/performance: device-acceptance |
| Bounded private note-content search with explicit coverage and lock expiry | implemented | No persistent plaintext index; raw photos/PDFs need saved text |
| Selected-file OCR review queue | implemented | Round six: ten files, one in-memory review, explicit saves/skips, partial progress and lock cleanup; background jobs remain separate |
| Broader bulk OCR (background scheduling/resume/large jobs) | ready | Current session queue does not claim this; encrypted persistent drafts/job semantics needed |
| Integrated scalable full-text index | ready | Encrypted index design, invalidation/lock/key lifetime and bounded resource use; plaintext persistence prohibited |
| Conflict-safe multi-device sync | decision-blocked | Conflict resolution, transport and key custody |
| Recipient-controlled sharing | decision-blocked | Recipient keys, revocation limits and custody model |
| Alternate icons | ready | Native platform support and approved assets |
| Break-in camera | decision-blocked | Explicit consent/privacy/retention semantics; do not silently photograph people |
| Direct Wi-Fi transfer | ready | Authenticated local pairing and encrypted transport; no implicit peer trust |
| Full Live Photo paired playback | ready | Preserve/identify all paired resources on import/export, cross-platform fallback |
| Streaming video seek | ready | Authenticated bounded decryption/seek and player lifecycle; do not weaken original format integrity |
| Browser and desktop tooling | ready | Select explicit platform scope, reuse shared core and secure key lifetime |
| Destructive duress behavior | excluded by spec | Never introduced as an incidental privacy feature |

## Queue maintenance and continuation

Each slice updates this queue, its owning spec, the cumulative evidence matrix and exact local artifacts. Split partially delivered rows rather than deleting the remainder. Record why a decision blocks an item and which native/device evidence would unblock it. Keep authorization for release/publishing separate from local implementation. The round-six `owning-specs-before.md` snapshot preserves the complete baseline text used for this reconciliation; subsequent spec edits must not erase unfinished capabilities.
