# Sevens Android interaction guidelines

Use this alongside `AGENTS.md` for every Android UI iteration. iOS defines capabilities and data behavior; Material 3 Expressive and Android conventions define the interaction. A successful build or use of an Expressive component is not design acceptance.

## Authority

- [Material components](https://m3.material.io/components): choose by the task and read the component's usage/accessibility guidance.
- [Expressive research](https://design.google/library/expressive-material-design-google-research): use size, color, shape and containment to make important actions and related content easier to identify. Google's study results are not measurements of our apps.
- [Window insets](https://developer.android.com/develop/ui/compose/system/insets-ui): consume scaffold padding before applying IME insets; verify the actual short-window keyboard state.
- [Motion](https://m3.material.io/styles/motion): use the theme's motion scheme; preserve continuity, allow interruption, and avoid delaying actions for decoration.
- [Compose Material reference](https://developer.android.com/reference/kotlin/androidx/compose/material3/package-summary) and [release notes](https://developer.android.com/jetpack/androidx/releases/compose-material3): verify APIs against the pinned dependency, including experimental APIs. Use the maintained component before making an imitation.
- [Compose accessibility testing](https://developer.android.com/develop/ui/compose/accessibility/testing): run the Accessibility Test Framework on changed workflows; resolve errors without suppressing the checks. This complements manual service testing.
- [Compose accessibility defaults](https://developer.android.com/develop/ui/compose/accessibility/api-defaults): preserve component semantics, provide a single coherent action per target, and reserve at least 48dp for interactive targets.

The current baseline (2026-11-17) pins Compose BOM `2026.09.00` and Material 3 `1.5.0-rc01`. Check the locks before assuming a newer documented API is available. Experimental Expressive APIs require deliberate opt-in; do not upgrade dependencies incidentally during a visual pass.

These are primary references. Generic Android skills and sample apps are implementation aids, not substitutes for the component specification or evidence from our own screens.

## Choose the interaction first

| Intent | Default choice | Check |
| --- | --- | --- |
| Move between primary destinations | Native navigation bar/rail; drawer when enlarged labels cannot fit | Keep full destination names; do not shrink text to force five labels into a bar. |
| Commit the main task | Filled button or appropriate FAB | One clear primary action; secondary actions have lower emphasis. |
| Choose one of a few related values | Connected toggle buttons with radio semantics | Selected state is visible and announced; labels fit at large text. |
| Choose one of many values/types | Exposed dropdown or a selection sheet | Current value remains visible; do not hide a long list in sideways scrolling. |
| Choose multiple items | Checkboxes and a contextual selection toolbar | Count, scope and enabled actions reflect the actual selection. |
| Turn a setting on/off | Switch | Its label describes the resulting state; the whole row has coherent semantics. |
| Search a collection | Inline Material text field for filtering without suggestions; native search surface when suggestions/results need their own destination | Search submits dismiss the IME explicitly. Consume scaffold insets before IME padding; test real landscape keyboard bounds. Sensitive queries stay in memory and expire on lock. |
| Filter a collection | Filter chips or labelled filter controls | Active constraints and a clear/reset action remain discoverable. |
| Optional rating | Single choice plus an explicit clear action | Zero is distinct from unrecorded; do not require guessing that retapping clears it. |
| Enter date/time | Material date/time picker with text/keyboard alternative | Locale, 12/24-hour format, small-height layout and cancellation work. |
| Select a clip within a video | Native Material range slider with labelled exact start/end fields | Keep a minimum valid range, expose seconds, retain the draft, and require a matching prepared preview before Save. |
| Adjust a bounded quality setting | Slider with current value and an explicit preview when encoding is expensive | Keep Save disabled until the output matches the selected value; retry from the original and retain the last good preview on failure. |
| Select pages for recognition or processing | Labelled numeric range fields with inline validation and an explicit action | State the page count and processing limit, retain the range after errors/recreation, and keep the original unchanged. |
| Enter an exact duration/quantity | Validated numeric entry; useful presets when appropriate | Avoid dozens of increment taps. Use the fixed-action full-screen form when a short window or large text prevents the numeric dialog from fitting above the IME. Sliders fit approximate/continuous adjustments. |
| Related alternatives to one command | Split button | Use only when the default and alternatives form one coherent action. |
| Crop or manipulate an image | Maintained native canvas with visible handles, plus labelled exact controls | Keep handles visible against the image, fit the canvas to short windows, preserve drafts and originals, and use the same geometry for preview and export. |
| Reorder | Maintained drag/reorder component plus explicit move actions | Touch, keyboard and accessibility users can reach the same result. |
| Import a local asset | System document picker, an owned copy, selected filename, preview and explicit replacement/removal | Cancellation retains the prior selection. Validate format/size before publishing; define missing-file fallback and backup coverage. Preserve draft references across rotation and clean unused copies safely. |
| Secondary commands | Labelled buttons or a short overflow menu | Frequently used controls do not require long-press discovery. Separate sorting/filtering from destructive commands. |
| Temporary contextual controls | Appropriate Material toolbar/sheet | Avoid obscuring content or the keyboard; preserve safe insets. |

Custom graphics remain appropriate for domain content such as the fasting/water gauge, charts and scan previews. A custom visual does not justify replacing a standard interaction primitive.

## Visual and behavioral contract

- Start with the task, current state and main action. Group related controls; do not give every action equal weight or make every surface a competing card.
- SevenKit owns color/type/shape/motion roles. The current ink-and-paper palette is a product choice, not an M3 requirement. Preserve it while assessing hierarchy and contrast; color must not be the only state cue. Do not hard-code app-specific variants of shared controls.
- Use component pressed, checked, focused, disabled and loading states. A state-dependent task must not become visually invisible because the control is disabled (for example, a memory mission's playback highlight).
- Keep labels, selected values and validation visible. Let content wrap or scroll; do not solve large text by shrinking it. Check compact and wide windows, keyboard visibility, system insets and system-bar contrast, including dialogs with their own windows.
- Retain drafts through configuration changes. Keep sensitive drafts in memory rather than saved-state bundles where the app's security model requires it. Protect unsaved work when leaving; do not add confirmations to harmless navigation.
- Put collection-wide utilities such as backup and trash in a stable navigation location; a footer after an unbounded collection is insufficient. Show the next export action beside the action that produced it.
- Host completion feedback in the screen that starts the operation, including pushed Settings screens. Verify failure, retry and the visible success state; a callback firing is not proof that the person sees feedback.
- A review must identify what will be committed. Read the latest persisted state before opening an asynchronous editor, and detect intervening changes on save. Keep the draft on conflict; never let late recognition overwrite manual corrections. Sensitive reviews must disappear on lock and reject completion from an earlier session.
- Disable duplicate submissions while saving, publish success only after persistence, keep recoverable inputs on failure, and offer undo when supported. A selected chip must represent committed state or clearly identified draft state.
- Test TalkBack/focus explicitly before claiming accessibility acceptance. Compose semantics assertions help, but do not replace service/device testing.

## Iteration evidence

For each changed screen or interaction, record:

1. **Problem and hypothesis:** what is difficult now, the relevant guideline, and the expected user benefit. Include the tradeoff, such as one additional tap to choose a rare content type in exchange for a clearly labelled current type.
2. **Baseline:** screenshot with a deterministic fixture, viewport, font scale, theme and named state. Capture a short recording when motion or transitions change.
3. **Implementation:** the actual Material API and any justified exception; keep domain rules in Rust.
4. **Comparison:** repeat the same fixture and configuration after the change. Inspect hierarchy, labels, density and action reachability. Compare taps where useful; do not invent usability timings or user-study results.
5. **Workflow:** exercise entry, selection/input, commit, cancellation, failure/retry and recreation where applicable. Check persisted results, not just a success message.
6. **Decision:** retain, revise or revert with a concrete reason. Record remaining gaps.

Use a screen/state ledger with separate columns for source review, visual comparison, automated workflow, motion review and physical-device/accessibility review. Values are `not checked`, `passed`, `failed`, or `not applicable` with a reason. Do not turn an app green because one representative screen passed. Test counts include existing tests unless explicitly labelled new.

Name the viewport and window audited by automated accessibility checks. The default Compose adapter can report clipped lazy-list edges or obscured background windows while a modal/IME is active. Diagnose these against the actual screen: audit the focused modal with the unchanged ATF rules, and record any scroll states that were not audited. Do not claim whole-workflow accessibility from a single stable-state check.

The regression matrix includes light/dark, normal and 200% font size, compact/wide windows, keyboard shown, and relevant empty/loading/error/selected/disabled states. Run checks proportionate to the change; broader coverage must be documented rather than assumed. Build, lint and optimized startup checks are engineering gates, separate from the visual decision.

Keyboard acceptance must assert that IME insets are visible before capturing or auditing the layout. Programmatic text entry alone does not prove the soft keyboard appeared. Check the complete control bounds against the keyboard viewport; a partially visible node can satisfy a basic visibility assertion. Resolve focus and keyboard controllers inside the actual dialog content: the host activity and a Compose dialog have separate focus roots. A hide request against the host cannot establish that the editor keyboard closed. Assert the focused window’s IME visibility after dismissal. Allow system IME animations to settle before capturing their final state. Restore any keyboard or animation settings changed by a test.

## Long-running comparisons and resumable work

When a meaningful total exists, use the native M3 determinate progress indicator; keep provider inventory/unknown-duration work indeterminate. Cleaner uses `LinearWavyProgressIndicator` with checked/total text and a reachable Cancel action. A stopped job leaves a visible saved-work count and an explicit way to continue. Do not animate an invented percentage or hide completion durability behind a success label.

Persist completed work before advancing progress. Resume only after a fresh permission-aware inventory; changed, deleted or inaccessible items cannot inherit old results. Android MediaStore cache revisions must combine [database version and generation](https://developer.android.com/reference/android/provider/MediaStore.MediaColumns#GENERATION_MODIFIED), not modification timestamps alone. Where trustworthy revisions are unavailable, remeasure. A clear-progress action removes derived work only and says which user records remain. Verify interruption/reopen, changed revisions, restricted inventory, storage failure and clear/retry independently of the screen preview.

## Wear companions

Use [Wear Compose Material 3](https://developer.android.com/training/wearables/compose/migrate-to-material3), currently pinned at `1.7.1`, rather than shrinking phone controls onto a round display. Use `AppScaffold`/`ScreenScaffold` with the same `TransformingLazyColumn` state, scaffold content padding, and native surface transformations on action buttons. Apply [Wear list guidance](https://developer.android.com/training/wearables/compose/lists) and inspect the actual round viewport. Dynamic watch-face colors may fall back to the native scheme. Primary and secondary dose actions retain different emphasis; queue clearing uses the native confirmation dialog.

Watch freshness, offline pending state and acknowledgement are interaction states, not just transport details. Never show a scheduled action as saved before the phone confirms it. Disable actions on stale or disabled snapshots, preserve a durable receipt across retries, and explain that clearing a queue cannot undo a phone commit. Emulator UI tests, paired-device transport and watch-face acceptance are separate evidence.

For watch preset choices, use a native scrollable selection dialog/list with the duration and action visible. Capture the displayed revision with the chosen ID; reject a changed/deleted preset instead of silently substituting a default. Version new commands so older phones cannot ignore a meaningful field. Keep private user-written labels out of the transport unless explicitly required by the product contract. Persist a pending action before sending; a storage error must leave it visibly unconfirmed.

## Home Screen widgets

Pin the compatible WorkManager runtime with Glance (currently `2.11.0`, matching Fast/Bloom/Alarm); validate the optimized APK startup, because a debug widget test does not exercise Room constructor retention under R8.

Use [Glance Material 3](https://developer.android.com/develop/ui/compose/glance) for Android widget controls and surfaces, not phone Compose controls embedded in a fake widget. Follow [Glance update guidance](https://developer.android.com/develop/ui/compose/glance/glance-app-widget): launcher refreshes are scheduled, not an exact delivery mechanism. A running Glance composition must observe changed persisted state; merely requesting an update is insufficient if the content captured an old snapshot. Keep widget data small, bounded and excluded from backups where it is derived/private. Group each repeated entry: [Glance columns](https://developer.android.com/reference/kotlin/androidx/glance/layout/Column.composable) truncate after ten direct children. A single-item screenshot cannot verify a multi-entry layout.

Expose the saved state, timestamp/freshness and required next action honestly. An alarm receipt, scheduled time or elapsed timer never proves delivery/completion. Hide stale times after the shared expiry or time-zone change. Explain that routine names are visible before pinning a schedule widget; use the native launcher pin sheet. Android and iOS may refresh differently—[WidgetKit timelines](https://developer.apple.com/documentation/widgetkit/keeping-a-widget-up-to-date) are the iOS counterpart, not a reason to promise exact Android redraws. Widget refresh must not become the alarm scheduling mechanism.

Verify the actual launcher-hosted RemoteViews/WidgetKit surface, including empty, populated, unverified, stale and changed states, plus opening the app. Test resize and large text separately; use fewer entries instead of clipping important times. Widget host evidence, app preview evidence and physical launcher acceptance remain distinct.

## Portfolio coverage

The ledger must retain all seven apps and distinguish screen families:

| App | Families to track |
| --- | --- |
| Fast | Timer/start/end, watch preset selection/pending/retry, plans/presets, history/calendar/detail, journal, trends, water, settings/import/export/Health, widget |
| Bloom | Today/doses, calendar/day/timed observations, patterns, care/plans, report/export, settings/privacy/reminders, widget, Wear companion/pending/sync |
| QR | Scan/sources, verdict/peek/actions, history/selection/notes, nine Make forms/options/export, batch input/review/PDF, settings/known addresses |
| Alarm | Alarm/loop lists, routine/default editors, imported sound/preview/replacement/fallback, naps, mission types, history, permissions/settings, saved-schedule widget/stale/unverified |
| Scanner | Library/search, capture/import, document/page editing/reordering, crop handles/exact bounds/reset, rectangular marks/freehand ink/undo/conflicts, text correction/conflicts, export/PDF preview, trash, backup/settings |
| Cleaner | Overview, photos/videos/filter/selection/detail, duplicate/similar groups, pending removal, selected-photo review queue/preparation/skip/stop/results/same-copy retry, compression quality/size/preview, native video range/prepare/cancel/playback/save/same-copy retry, saved comparison progress/cancel/resume/clear, files, contacts/merge, activity/settings |
| Vault | Create/unlock/recovery, library/folders/filter/selection, ephemeral note-content search/coverage/lock, notes/details, image/PDF OCR range/review/encrypted-note save, file previews/export, trash, credentials/backup/settings |

Keep private fixture data, screenshots, recordings and device evidence outside public source. Small synthetic regression fixtures may live with their tests. Preserve prior iterations; a new pass does not retroactively certify older screenshots or untested device paths.

## Selected-file review queues

Use a contextual selection action followed by a bounded review form. Confirm the full file scope before processing; reject unsupported selections instead of silently skipping them. Use determinate M3 progress for completed file decisions and a separate indeterminate current-page activity; OCR time is not a known percentage. Keep saved, skipped and unfinished outcomes distinct. Advance only after persistence succeeds, retain failed-save drafts, and provide explicit retry/skip/stop. A stopped queue keeps committed outputs and says what was discarded. Protect edited unsaved text when stopping. Sensitive review state stays in memory and is cleared on lock; configuration restoration must not serialize plaintext. Whole-PDF processing must disclose its limit and reject longer PDFs rather than silently losing pages.

## Calendar filters and short-window pickers

Use the native [Material date picker](https://developer.android.com/develop/ui/compose/components/datepickers), including calendar and text-input modes. A compact-height calendar must not compress or clip its weeks. SevenKit uses a scrolling full-screen picker with fixed confirmation actions below 500dp window height, initially in native input mode; its calendar remains available. Native validation errors or incomplete dates disable confirmation. Material 1.5 visibly normalizes some end-of-month inputs (for example February 30 to February 28); retain and verify the displayed resolved date rather than claiming strict rejection of every impossible date. Verify actual calendar selection, typed invalid/valid dates, the real soft keyboard, cancellation, and configuration restoration. Converting Material’s UTC date value to a local civil date must not shift the selected day.

A filter form edits a draft until Apply. Show the committed date scope and a discoverable clear action in the results. State the calendar/time-zone interpretation and treatment of missing dates when these affect results; do not manufacture dates from scan time. A filter change must not silently discard hidden selections or rewrite review decisions. On This Day must state its reference year and leap-day behavior.

Floating selection controls must reserve their measured height in the scrolling viewport, not merely add content padding. Otherwise focus-driven scrolling can place an actionable chip behind the toolbar even when a visibility assertion passes. Exercise the actual click and resulting state with an active selection.

## Composed metadata filters

Use labelled exposed dropdowns for extension/size scopes and filter chips for independent boolean constraints; native calendar fields belong in the same draft form. Apply commits the whole draft, Cancel preserves the previous result, and the collection shows active constraints plus matched/total counts. Clear filters and clear search are distinct labelled actions. Keep applied state/query across tab transitions and configuration changes; preserve an open draft across recreation.

Describe the metadata actually available. Filename extensions are not verified MIME/content types. Unknown size is distinct from zero; a minimum-size predicate must not silently include unknown records. Dates from a chosen folder are modification dates, not camera capture dates. Use the original scan for coverage/totals, and never write provider or review state merely because a filter changed. Test composed filters, no extension, unknown size/date, cancellation and recreation against unchanged source records.

## Persisted document annotations

Use maintained native drawing APIs for document-domain visuals: [Compose Canvas](https://developer.android.com/develop/ui/compose/graphics/draw/overview) and native bitmap Canvas on Android; [SwiftUI Canvas](https://developer.apple.com/documentation/swiftui/canvas) and Core Graphics on Apple. Rectangular highlights/boxes do not need a custom freehand engine. Freehand input uses stable Jetpack Ink `1.0.0` and system PencilKit. Keep the portable committed path separate from transient predicted input; do not serialize one platform's private stroke archive as the shared document format. Use connected Material choices for the tool, labelled undo/redo/remove actions, and validated numeric bounds as an alternative to drawing gestures.

Size the complete canvas to the usable window, including short landscape windows; a passing drag test does not prove the whole page fits. Preserve the editor snapshot and undo history through configuration changes. Keep geometry portable and versioned against retained originals, apply the same transformations to previews and final output, reject stale saves, and retain failed-save drafts. Verify saved/reopened records, backup, crop/rotation, original bytes and pixels in actual exported PDFs. A preview-only overlay is incomplete. State when visual marks leave underlying searchable text intact; never imply that a highlight or box redacts information.

## Reviewed batch processing

Validate the complete selection before opening a queue. State the batch bound, keep stable item/revision snapshots, and preserve the selection and existing review decisions. Use a full-screen review for large image comparisons, labelled primary/secondary actions, and [Material progress indicators](https://developer.android.com/develop/ui/compose/components/progress): determinate progress counts completed decisions; indeterminate progress describes work whose duration is unknown. Do not present encoding time as a known percentage. Keep fixed actions within a readable width in landscape and ensure failure feedback becomes visible from a scrolled comparison.

A prepared preview is not a saved output. Distinguish saved-and-verified, skipped, and created-but-unverified outcomes. If the native provider created a copy before verification or ledger persistence failed, retain its identifier and retry verification/recording of that same copy. Freeze quality/size changes until that receipt is resolved; never turn Retry into another insert. Commit history and processed source/output guards together before publishing success. Explain exactly what Stop retains and whether a queue survives app termination. Session-only receipt recovery must not be described as crash-atomic or exactly-once. Verify real provider bytes, interrupted persistence, unchanged originals and per-item results; automated interaction passes still require rendered inspection.

## Freehand input and prepared export actions

Use [Jetpack Ink authoring](https://developer.android.com/develop/ui/views/touch-and-input/stylus-input/ink-api-draw-stroke) and [PencilKit](https://developer.apple.com/documentation/pencilkit) for native stroke input. Keep tool/color/width choices labelled; connected Material choices fit the small fixed sets. Retain rectangular exact-bounds entry, explicit Undo/Redo and whole-stroke removal. A finger/stylus canvas is domain content, not a substitute for accessible editing actions. Do not claim physical stylus latency or TalkBack/VoiceOver acceptance from injected gestures.

Persist bounded original-space geometry once in the shared core. Constant-width strokes have round caps/joins; pressure, textured brushes and partial erasing require their own portable representation and acceptance. Preserve a stroke's creation clip so input outside a cropped canvas never appears after resetting the crop. Later crops hide existing ink without deleting it. Reject oversized strokes as a whole, retain earlier work, and show native error feedback even when the form is scrolled. Commit rectangles and ink atomically against the original page snapshot; never downgrade a newer manifest when editing older mark types. Test actual native drawing, cancellation/undo, recreation, stale/failed saves, backup, every rotation and pixels in both searchable and image-only PDFs. Native drawing completion can arrive after the finger-up callback: do not clear the authoring surface before its finished stroke is available. Scroll test fixtures beside an active drawing canvas; swiping through it correctly creates another mark. Exercise new JNI-backed authoring in the optimized APK as well as debug.

After preparing an export, promote its next action into the same fixed action slot. Use the concise native action name **Share PDF**, which opens saving/sharing choices, rather than a long toolbar sentence that crowds out the screen title at enlarged text. Verify the action from the end of the content, native chooser cancellation and retained inputs; neither a generated file nor a callback proves the action is reachable.

For Compose workflow tests, use the supported [v2 test dispatcher](https://developer.android.com/develop/ui/compose/testing/migrate-v2) where the pinned library provides it. Synchronize on the completed editor state, not just a file appearing on disk: persistence can precede the library refresh and lock release. Preserve failed-run diagnostics and distinguish harness faults from app faults; do not replace a failed workflow with a build-only claim.

## Native video review

Use the maintained [Media3 Transformer](https://developer.android.com/media/media3/transformer/transformations) for native Android trimming/encoding, with an explicit supported profile. The range slider supports approximate selection; labelled decimal start/end fields support exact selection and accessibility. Preserve native range semantics, disabled/busy states and a fixed Save action. Preparation and saving are distinct: changed input invalidates the current preview, errors retain the original and last good candidate, and cancellation cleans task-owned temporary output. A native player must display the actual candidate.

Inspect the source before encoding and validate the resulting file before offering Save. Preserve supported orientation, timing, audio and capture metadata; refuse unsupported profiles explicitly. State real dimensions, duration and bytes, including when a candidate is larger. Save a separate copy, read its provider bytes back, then record completion. If insertion succeeded but verification/recording failed, retry the same receipt rather than inserting a duplicate. Do not imply crash recovery for session-only receipts or permit deletion merely because encoding succeeded.

Exercise real media, including portrait rotation, non-keyframe trim, selected frame content, audible full-stream decode, cancellation cleanup, invalid ranges, recreation, provider readback and failed-record retry. Run focused ATF on the actual entry/review route plus 200% text/dark and landscape workflows. A successful test command with zero executed cases is not evidence.
