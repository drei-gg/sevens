import SwiftUI
import SevenKit
import VaultCore
import VaultStorage
import PhotosUI
import UniformTypeIdentifiers
import QuickLook

@main struct VaultApp: App {
    @UIApplicationDelegateAdaptor(BackupAppDelegate.self) private var delegate
    @State private var model = VaultModel()
    @Environment(\.scenePhase) private var phase
    var body: some Scene {
        WindowGroup {
            VaultRoot().environment(model).sevenStore(model.store, unlock: VaultGate.unlock)
                .task { PrivacyShield.install(); await model.start() }
                .onChange(of: phase) { _, phase in
                    if phase == .background { model.lock() }
                    if phase == .active { Task { await model.store.refresh(); await model.retryAutomaticBackups() } }
                }
        }
    }
}
struct VaultRoot: View {
    @Environment(VaultModel.self) private var model
    @State private var before = false
    var body: some View {
        Group {
            if model.library == nil { VaultLockView() }
            else if model.needsRecoveryReplacement { RecoveryReplacementView() }
            else if model.needsNewSecret { NewSecretView() }
            else { VaultLibraryView() }
        }.id(model.generation)
            .task(id: model.store.hasStarted) { if model.store.hasStarted && model.store.state != .unlocked && model.store.launchSheet == .beforeYouBuy { before = true } }
            .sheet(isPresented: $before) { BeforeYouBuyView(content: VaultGate.before) { _ in before = false } }
            .trialEndedSheet(content: VaultGate.expired)
            .alert("Could not complete", isPresented: Binding(get: { model.error != nil }, set: { shown in if !shown { model.error = nil } })) { Button("OK", role: .cancel) { model.error = nil } } message: { Text(model.error ?? "") }
            .sheet(item: Binding(get: { model.presentation }, set: { model.presentation = $0 }), onDismiss: { model.cleanPresentation() }) { item in
                if let batch = item.batch { BatchRecognitionScreen(queue: batch) }
                else if let range = item.pdfRange { PdfRangeScreen(draft: range) }
                else if let recognized = item.recognized { RecognizedTextScreen(draft: recognized) }
                else if item.filesExport { SevenFileExportPicker(urls: item.urls) }
                else if item.sharing { VaultShare(urls: item.urls) }
                else { NavigationStack { VaultPreview(urls: item.urls).toolbar { Button("Lock") { model.lock() }; Button("Done") { model.cleanPresentation() } } } }
            }
            .sheet(isPresented: Binding(get: { model.recoveryKey != nil }, set: { if !$0 { model.recoveryKey = nil } })) { RecoverySheet() }
    }
}
struct VaultLockView: View {
    @Environment(VaultModel.self) private var model
    @State private var credential = ""
    @State private var recovery = false
    @State private var kind = VaultCredentialKind.passphrase
    @State private var creating = false
    @State private var restoring = false
    var body: some View {
        NavigationStack {
            Form {
                if kind != .pattern || recovery { Section {
                    VStack(alignment: .leading, spacing: 12) {
                        Image(systemName: "lock.shield").font(.system(size: 44)).foregroundStyle(.tint).accessibilityHidden(true)
                        Text("Yours to keep.").font(.largeTitle.bold())
                        Text("Draw its pattern or enter its PIN or passphrase. Your private library stays encrypted when locked.").foregroundStyle(.secondary)
                    }.padding(.vertical, 16)
                }
                }
                Section("Open your vault") {
                    Toggle("Use a recovery key", isOn: $recovery).onChange(of: recovery) { credential = "" }
                    if !recovery { CredentialKindPicker(kind: $kind).onChange(of: kind) { credential = "" } }
                    CredentialInput(kind: recovery ? .passphrase : kind, value: $credential, label: recovery ? String(localized: "Recovery key") : kind.localizedTitle, identifier: "vault.credential")
                    Button("Unlock") { let secret = credential; credential = ""; Task { await model.unlock(secret, recovery: recovery, kind: kind) } }.disabled(credential.isEmpty || model.busy).accessibilityIdentifier("vault.unlock")
                    if model.hasBiometricKey { Button("Unlock with Face ID or Touch ID") { Task { await model.unlockBiometrics() } }.disabled(model.busy) }
                    if model.busy { ProgressView(model.progress) }
                }
                Section {
                    GatedButton(feature: String(localized: "Create a vault"), action: { creating = true }) { Label("Create a vault", systemImage: "plus") }.disabled(model.busy)
                    Button { restoring = true } label: { Label("Restore encrypted backup", systemImage: "arrow.clockwise.icloud") }.disabled(model.busy)
                }
                Section { Text("A recovery key opens existing encrypted data. It cannot bring back files after the app is deleted unless you kept a backup.").font(.footnote).foregroundStyle(.secondary) }
                Section { NavigationLink("About & ownership") { VaultSettings() } }
            }.navigationTitle("Vault")
                .sheet(isPresented: $creating) { CreateVaultSheet() }
                .sheet(isPresented: $restoring) { RestoreVaultSheet() }
        }
    }
}
struct CreateVaultSheet: View {
    @Environment(VaultModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var name = ""
    @State private var password = ""
    @State private var kind = VaultCredentialKind.passphrase
    @State private var repeated = ""
    @State private var acknowledged = false
    var body: some View {
        NavigationStack {
            Form {
                Section("New vault") {
                    TextField("Vault name", text: $name).accessibilityIdentifier("vault.newName")
                    CredentialKindPicker(kind: $kind).onChange(of: kind) { password = ""; repeated = "" }
                    CredentialInput(kind: kind, value: $password, label: kind.newLabel, identifier: "vault.newPassword")
                    CredentialInput(kind: kind, value: $repeated, label: kind.repeatLabel, identifier: "vault.repeatPassword")
                }
                Section {
                    Text("Use 12+ characters, an 8–32 digit PIN, or 8+ dots. Each secret opens its own vault. Longer, unpredictable secrets resist guessing better; avoid dates, letters and simple shapes.")
                    Toggle("I understand there is no account reset", isOn: $acknowledged).accessibilityIdentifier("vault.acknowledge")
                    Text("Next, save your recovery key somewhere outside this app. Keep a separate encrypted backup too.").font(.footnote).foregroundStyle(.secondary)
                }
                Button("Create encrypted vault") {
                    let secret = password, title = name; password = ""; repeated = ""
                    dismiss(); Task { await model.create(name: title, passphrase: secret, kind: kind) }
                }.disabled(name.isEmpty || (try? kind.validate(password)) == nil || password != repeated || !acknowledged || model.busy).accessibilityIdentifier("vault.create")
            }.scrollDismissesKeyboard(.interactively).navigationTitle("Create vault").toolbar { Button("Cancel") { dismiss() } }
        }
    }
}
struct RecoverySheet: View {
    @Environment(VaultModel.self) private var model
    @State private var saved = false
    var body: some View {
        NavigationStack {
            Form {
                Section("Keep this key outside Vault") {
                    if model.recoveryKeyReplacesOld {
                        Text("Vault updated this vault to its new encryption format, which also opens on Android. Your old recovery key no longer opens it; this one does.").accessibilityIdentifier("vault.migrated")
                    }
                    Text("This key can open your vault without its usual unlock secret. Store it somewhere private, such as your password manager.")
                    Text(verbatim: model.recoveryKey ?? "").font(.body.monospaced()).textSelection(.enabled).accessibilityIdentifier("vault.recoveryKey")
                    Text("We don’t keep a readable copy. If Vault locks before you confirm, you’ll be asked to make a new key. A backup is still needed if the local files are lost.").foregroundStyle(.secondary)
                    Toggle("I’ve saved my recovery key", isOn: $saved).accessibilityIdentifier("vault.recoverySaved")
                }
                if model.busy { ProgressView(model.progress) }
                Button("Continue to vault") { Task { await model.confirmRecoveryKey() } }.disabled(!saved || model.busy).accessibilityIdentifier("vault.continue")
            }.navigationTitle("Recovery key").interactiveDismissDisabled()
        }
    }
}
/// Shown instead of the library when the vault's current recovery key was never confirmed as saved
/// (Vault locked or quit while it was on screen). Nothing secret survived the lock, so the only safe
/// way on is a replacement key, through the same path as Settings.
struct RecoveryReplacementView: View {
    @Environment(VaultModel.self) private var model
    var body: some View {
        NavigationStack {
            Form {
                Section {
                    VStack(alignment: .leading, spacing: 12) {
                        Image(systemName: "key").font(.system(size: 44)).foregroundStyle(.tint).accessibilityHidden(true)
                        Text("Save a new recovery key").font(.title2.bold()).accessibilityAddTraits(.isHeader)
                        Text("Vault locked before you confirmed saving this vault’s recovery key, and it isn’t kept anywhere it can be shown again. Make a replacement now and save it before you continue.")
                    }.padding(.vertical, 8)
                }
                Section {
                    if model.busy { ProgressView(model.progress) }
                    Button("Create a new recovery key") { Task { await model.replaceRecoveryKey() } }.disabled(model.busy).accessibilityIdentifier("vault.replaceRecovery")
                } footer: { Text("Old backups still open with the recovery key they were made with.") }
            }.navigationTitle("Recovery key")
                .toolbar { Button { model.lock() } label: { Label("Lock", systemImage: "lock.fill") } }
        }
    }
}
/// Shown instead of the library after a restore authorised by the recovery key: the backup's own
/// unlock secret couldn't be checked against the other vaults here, so the person sets a new one (the
/// usual change, with its duplicate check) before continuing.
struct NewSecretView: View {
    @Environment(VaultModel.self) private var model
    @State private var kind = VaultCredentialKind.passphrase
    @State private var password = ""
    @State private var repeated = ""
    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Text("Choose a new unlock secret").font(.title2.bold()).accessibilityAddTraits(.isHeader)
                    Text("This vault was restored with its recovery key, so its old secret couldn’t be checked against your other vaults. Choose a new PIN, pattern or passphrase to open it from now on.")
                }
                Section {
                    CredentialKindPicker(kind: $kind).onChange(of: kind) { password = ""; repeated = "" }
                    CredentialInput(kind: kind, value: $password, label: kind.newLabel, identifier: "vault.newSecret")
                    CredentialInput(kind: kind, value: $repeated, label: kind.repeatLabel, identifier: "vault.repeatNewSecret")
                    if model.busy { ProgressView(model.progress) }
                    Button("Set unlock secret") {
                        let secret = password, chosen = kind; password = ""; repeated = ""
                        Task {
                            if await model.update(action: { try await model.repository.changePassphrase(secret, kind: chosen) }) {
                                model.announce(String(localized: "Unlock secret set. Older backups still open with their old one."))
                            }
                        }
                    }.disabled((try? kind.validate(password)) == nil || password != repeated || model.busy).accessibilityIdentifier("vault.setNewSecret")
                } footer: { Text("Older backups still open with the secret they were made with.") }
            }.scrollDismissesKeyboard(.interactively).navigationTitle("New unlock secret")
                .toolbar { Button { model.lock() } label: { Label("Lock", systemImage: "lock.fill") } }
        }
    }
}
struct RestoreVaultSheet: View {
    @Environment(VaultModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var choosing = false
    @State private var file: URL?
    @State private var credential = ""
    @State private var recovery = false
    @State private var kind = VaultCredentialKind.passphrase
    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Button(file?.lastPathComponent ?? String(localized: "Choose a .vault7 backup")) { choosing = true }
                    Toggle("Use recovery key", isOn: $recovery).onChange(of: recovery) { credential = "" }
                    if !recovery { CredentialKindPicker(kind: $kind).onChange(of: kind) { credential = "" } }
                    CredentialInput(kind: recovery ? .passphrase : kind, value: $credential, label: recovery ? String(localized: "Recovery key") : kind.backupLabel, identifier: "vault.restoreCredential")
                    Text("Use the pattern, PIN, passphrase or recovery key that applied when this backup was made. The whole backup is verified before it is added. Existing vaults are never overwritten.").font(.footnote).foregroundStyle(.secondary)
                }
                Button("Verify and restore") {
                    guard let file else { return }; let secret = credential; credential = ""; dismiss()
                    Task { await model.restore(file, credential: secret, recovery: recovery, kind: kind) }
                }.disabled(file == nil || credential.isEmpty || model.busy)
            }.navigationTitle("Restore vault").toolbar { Button("Cancel") { dismiss() } }
                .fileImporter(isPresented: $choosing, allowedContentTypes: [.data]) { result in
                    do { file = try result.get() } catch { model.error = error.vaultMessage }
                }
        }
    }
}
private struct VaultSearchRequest: Equatable {
    var query: String; var includeText: Bool; var trash: Bool; var favorites: Bool; var library: VaultLibrary
}
struct VaultLibraryView: View {
    @Environment(VaultModel.self) private var model
    @State private var query = ""
    @State private var includeNoteText = false
    @State private var noteResult: NoteSearchResult?
    @State private var resultRequest: VaultSearchRequest?
    @State private var searchingRequest: VaultSearchRequest?
    @State private var searchFailure: String?
    @State private var folder: UUID?
    @State private var favorites = false
    @State private var trash = false
    @State private var selected = Set<UUID>()
    @State private var selecting = false
    @State private var importing = false
    @State private var photos: [PhotosPickerItem] = []
    @State private var note = false
    @State private var newFolder = false
    @State private var folderName = ""
    @State private var renamingFolder: UUID?
    @State private var camera = false
    @State private var deleting = false
    @State private var editing: VaultItem?
    @State private var pendingItemAction: (VaultItem, ItemSheetAction)?
    private var library: VaultLibrary { model.library ?? VaultLibrary(name: "Vault") }
    private var searchRequest: VaultSearchRequest { VaultSearchRequest(query: query, includeText: includeNoteText, trash: trash, favorites: favorites, library: library) }
    private var noteIDs: Set<UUID> { resultRequest == searchRequest ? Set(noteResult?.ids ?? []) : [] }
    private var visible: [VaultItem] {
        library.sort.sorted(library.items.filter {
            (trash ? $0.deleted != nil : $0.deleted == nil) && (query.isEmpty ? $0.folder == folder : true) && (!favorites || $0.favorite) && ($0.matches(query) || noteIDs.contains($0.id))
        })
    }
    private func searchCoverage(_ result: NoteSearchResult) -> String {
        if let issue = result.issue { return issue.message }
        // Each part is its own catalog entry with plural variants; the list separator is a format too.
        var parts = [String(localized: "Searched \(Int(result.searched)) notes")]
        if result.unavailable > 0 { parts.append(String(localized: "\(Int(result.unavailable)) unreadable")) }
        if result.omitted > 0 { parts.append(String(localized: "\(Int(result.omitted)) skipped (500 notes / 32 MB per search)")) }
        return parts.joined(separator: String(localized: " · ", comment: "Separator between search coverage counts"))
    }
    var body: some View {
        NavigationStack {
            List {
                Section {
                    Toggle("Include note text", isOn: $includeNoteText).accessibilityIdentifier("vault.searchNotes")
                    if includeNoteText {
                        Text("Search stays on this device while unlocked. Photos and PDFs need saved text first.").font(.footnote).foregroundStyle(.secondary)
                        if searchingRequest == searchRequest { ProgressView("Searching note text…") }
                        if resultRequest == searchRequest, let result = noteResult {
                            Text(searchCoverage(result)).font(.footnote).accessibilityIdentifier("vault.searchCoverage")
                        }
                        if resultRequest == searchRequest, let searchFailure { Text(searchFailure).font(.footnote).foregroundStyle(Tokens.danger) }
                    }
                }
                if model.busy { Section { ProgressView(model.progress) } }
                #if DEBUG
                if ProcessInfo.processInfo.arguments.contains("-vault.ui") {
                    Button("Import sample files") { Task { await model.fixture() } }.disabled(model.busy)
                    Button("Expire trial for testing") { model.store.applyDebugPreset(.expired) }
                    Button("Enable test automatic backup") { Task { await model.enableTestBackup() } }
                    Button("Verify test automatic backup") { Task { await model.verifyTestBackup() } }
                    if let status = model.automaticStatus, status.copiedAt != nil, status.pendingRevision == nil, !model.automaticNeedsPreparation { Text("Automatic backup current").accessibilityIdentifier("vault.testBackupCurrent") }
                    if let message = model.automaticMessage { Text(message) }
                }
                #endif
                if !model.importResult.isEmpty { Text(model.importResult).font(.footnote).foregroundStyle(.secondary) }
                if !trash {
                    Section {
                        HStack {
                            GatedButton(feature: String(localized: "Import files"), action: { importing = true }) { Label("Files", systemImage: "folder.badge.plus") }
                            Spacer()
                            if model.store.paidFeaturesAvailable {
                                PhotosPicker(selection: $photos, matching: .any(of: [.images, .videos]), preferredItemEncoding: .current) { Label("Photos", systemImage: "photo.badge.plus") }
                            } else { GatedButton(feature: String(localized: "Import photos"), action: {}) { Label("Photos", systemImage: "photo.badge.plus") } }
                        }.buttonStyle(.borderless).disabled(model.busy)
                        HStack {
                            GatedButton(feature: String(localized: "Write a note"), action: { note = true }) { Label("Note", systemImage: "square.and.pencil") }
                            Spacer()
                            GatedButton(feature: String(localized: "Capture a photo"), action: { camera = true }) { Label("Camera", systemImage: "camera") }.disabled(!UIImagePickerController.isSourceTypeAvailable(.camera))
                        }.buttonStyle(.borderless).disabled(model.busy)
                    } footer: { Text("Photos imports the current rendition. Live Photos are stills here. Originals stay in Photos; this app never deletes them.") }
                }
                Section {
                    if let current = folder, let found = library.folders.first(where: { $0.id == current }) {
                        Button { folder = found.parent; selected = [] } label: { Label("Back from \(found.name)", systemImage: "arrow.up") }
                    }
                    if !trash && query.isEmpty {
                        ForEach(library.folders.filter { $0.parent == folder }) { child in
                            Button { folder = child.id; selected = [] } label: { Label(child.name, systemImage: "folder") }
                                .contextMenu { Button("Rename folder") { renamingFolder = child.id; folderName = child.name; newFolder = true } }
                        }
                    }
                }
                Section(trash ? "Recently Deleted" : "Your files") {
                    if visible.isEmpty && !query.isEmpty {
                        ContentUnavailableView(searchingRequest == searchRequest ? "Searching note text…" : "No matching files", systemImage: "magnifyingglass", description: Text(includeNoteText ? "Try another phrase. Check search coverage above for unreadable or skipped notes." : "Try another file name, or include note text."))
                    } else if visible.isEmpty {
                        ContentUnavailableView(trash ? "Nothing in the trash" : "A place for private things.", systemImage: trash ? "trash" : "lock.doc", description: Text(trash ? "Deleted items stay here until you permanently remove them." : "Add photos, documents or a note. Your files stay on this device until you choose to export or back up."))
                    }
                    if library.grid && !selecting {
                        LazyVGrid(columns: [GridItem(.adaptive(minimum: 140), spacing: 12)], spacing: 16) {
                            ForEach(visible) { item in Button { editing = item } label: { VStack(alignment: .leading, spacing: 8) { VaultThumbnail(item: item).frame(height: 100).frame(maxWidth: .infinity); Text(item.name).font(.callout).lineLimit(2); Text(ByteCountFormatter.string(fromByteCount: item.bytes, countStyle: .file)).font(.caption).foregroundStyle(.secondary) }.padding(8).background(.quaternary, in: RoundedRectangle(cornerRadius: 12)) }.buttonStyle(.plain).accessibilityIdentifier("vault.item") }
                        }.listRowSeparator(.hidden)
                    } else {
                        ForEach(visible) { item in
                            Button {
                                if selecting { if selected.contains(item.id) { selected.remove(item.id) } else { selected.insert(item.id) } }
                                else { editing = item }
                            } label: {
                                HStack(spacing: 12) {
                                    if selecting { Image(systemName: selected.contains(item.id) ? "checkmark.circle.fill" : "circle").accessibilityLabel(selected.contains(item.id) ? "Selected" : "Not selected") }
                                    VaultThumbnail(item: item).frame(width: 46, height: 46)
                                    VStack(alignment: .leading, spacing: 3) {
                                        Text(item.name).foregroundStyle(.primary)
                                        Text(ByteCountFormatter.string(fromByteCount: item.bytes, countStyle: .file)).font(.caption).foregroundStyle(.secondary)
                                    }
                                    Spacer()
                                    if item.favorite { Image(systemName: "star.fill").foregroundStyle(.secondary).accessibilityLabel("Favorite") }
                                }
                            }.buttonStyle(.plain).accessibilityIdentifier("vault.item")
                                .contextMenu { Button("Preview") { Task { await model.preview(item) } }; Button("Export original") { Task { await model.export([item.id]) } } }
                        }
                    }
                }
                if selecting {
                    Section("\(selected.count) selected") {
                        Button("Select all shown") { selected = Set(visible.map(\.id)) }
                        Button("Export selected originals") { Task { await model.export(selected) } }.disabled(selected.isEmpty)
                        if trash {
                            Button("Restore selected") { changeTrash(false) }
                            Button("Permanently delete selected", role: .destructive) { deleting = true }.disabled(selected.isEmpty)
                        } else {
                            Button("Recognize selected text") { model.prepareRecognitionQueue(selected) }.disabled(selected.isEmpty || model.busy).accessibilityIdentifier("ocr-batch-open")
                            Menu("Move selected to…") {
                                Button("Vault root") { move(nil) }
                                ForEach(library.folders) { target in Button(target.name) { move(target.id) } }
                            }.disabled(selected.isEmpty)
                            Button("Move selected to trash", role: .destructive) { changeTrash(true) }.disabled(selected.isEmpty)
                        }
                    }.disabled(model.busy)
                }
            }.navigationTitle(library.name).searchable(text: $query, prompt: includeNoteText ? "Search names and note text" : "Search file names")
                .task(id: searchRequest) {
                    let request = searchRequest
                    noteResult = nil; searchFailure = nil; resultRequest = nil
                    guard request.includeText, !request.query.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { searchingRequest = nil; return }
                    searchingRequest = request
                    defer { if searchingRequest == request { searchingRequest = nil } }
                    do {
                        try await Task.sleep(for: .milliseconds(250))
                        let result = try await model.repository.searchNotes(query: request.query, trash: request.trash, favorites: request.favorites)
                        try Task.checkCancellation()
                        noteResult = result; resultRequest = request
                    } catch is CancellationError { }
                    catch { searchFailure = String(localized: "Note text could not be searched. Unlock again and retry."); resultRequest = request }
                }
                .toolbar {
                    ToolbarItem(placement: .topBarLeading) { Button { model.lock() } label: { Label("Lock", systemImage: "lock.fill") }.accessibilityIdentifier("vault.lock") }
                    ToolbarItemGroup(placement: .topBarTrailing) {
                        Button(selecting ? "Done" : "Select") { selecting.toggle(); selected = [] }
                        Menu {
                            Picker("Sort", selection: Binding(get: { library.sort }, set: { order in Task { await model.update { try await model.repository.preferences(sort: order, grid: library.grid) } } })) { ForEach(VaultOrder.allCases, id: \.self) { Text($0.localizedTitle).tag($0) } }
                            Button(library.grid ? "List view" : "Grid view") { Task { await model.update { try await model.repository.preferences(sort: library.sort, grid: !library.grid) } } }
                            Toggle("Favorites only", isOn: $favorites)
                            Button(trash ? "Show library" : "Recently Deleted") { trash.toggle(); selected = []; folder = nil }
                            Button("New folder") { renamingFolder = nil; folderName = ""; newFolder = true }
                            Button("Prepare encrypted backup") { Task { await model.backup() } }
                        } label: { Label("Library options", systemImage: "ellipsis.circle") }.disabled(model.busy)
                        NavigationLink { VaultSettings() } label: { Label("Settings", systemImage: "gearshape") }
                    }
                }
                .onChange(of: photos) { _, items in guard !items.isEmpty else { return }; photos = []; Task { await model.importPhotos(items, folder: folder) } }
                .fileImporter(isPresented: $importing, allowedContentTypes: [.data], allowsMultipleSelection: true) { result in do { let urls = try result.get(); Task { await model.importFiles(urls, folder: folder) } } catch { model.error = error.vaultMessage } }
                .sheet(isPresented: $note) { NoteSheet(folder: folder) }
                .sheet(item: $editing, onDismiss: runItemAction) { item in
                    ItemSheet(item: item) { action in
                        pendingItemAction = (item, action)
                        editing = nil
                    }
                }
                .sheet(isPresented: $camera) { VaultCamera { url in if let url { Task { await model.importFiles([url], folder: folder); try? FileManager.default.removeItem(at: url) } } } }
                .alert(renamingFolder == nil ? "New folder" : "Rename folder", isPresented: $newFolder) { TextField("Folder name", text: $folderName); Button("Save") { let name = folderName, existing = renamingFolder; folderName = ""; Task { await model.update { if let existing { try await model.repository.renameFolder(existing, name: name) } else { try await model.repository.addFolder(name: name, parent: folder) } } } }; Button("Cancel", role: .cancel) {} }
                .confirmationDialog("Permanently delete these files? Backups and exported copies are not deleted.", isPresented: $deleting) { Button("Permanently delete", role: .destructive) { let ids = selected; Task { await model.update { try await model.repository.permanentlyDelete(ids) }; selected = [] } } }
        }
    }
    /// Present the next sheet only after File details has actually dismissed.
    private func runItemAction() {
        guard let (item, action) = pendingItemAction else { return }
        pendingItemAction = nil
        guard model.library != nil else { return }
        Task {
            switch action {
            case .preview: await model.preview(item)
            case .recognize: await model.recognizeText(item)
            case .export: await model.export([item.id])
            }
        }
    }
    private func changeTrash(_ value: Bool) { let ids = selected; Task { await model.update { for id in ids { try await model.repository.updateItem(id, trashed: value) } }; selected = [] } }
    private func move(_ target: UUID?) { let ids = selected; Task { await model.update { try await model.repository.move(ids, to: target) }; selected = [] } }
}

struct NoteSheet: View {
    @Environment(VaultModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let folder: UUID?
    @State private var name = ""
    @State private var text = ""
    var body: some View {
        NavigationStack { Form {
            TextField("Note name", text: $name).accessibilityIdentifier("vault.noteName")
            TextEditor(text: $text).frame(minHeight: 240).accessibilityLabel("Note text").accessibilityIdentifier("vault.noteText")
            Button("Save encrypted note") {
                guard model.store.paidFeaturesAvailable else { return }; let title = name, content = text
                Task { await model.update { try await model.repository.addNote(name: title, text: content, folder: folder) }; if model.error == nil { dismiss() } }
            }.disabled(name.isEmpty || model.busy).accessibilityIdentifier("vault.saveNote")
        }.navigationTitle("New note").toolbar { Button("Lock") { model.lock() }; Button("Cancel") { dismiss() } } }
    }
}
enum ItemSheetAction { case preview, recognize, export }

struct ItemSheet: View {
    @Environment(VaultModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let item: VaultItem
    let onAction: (ItemSheetAction) -> Void
    @State private var name = ""
    @State private var favorite = false
    @State private var noteText = ""
    @State private var noteLoaded = false
    var body: some View {
        NavigationStack { Form {
            Section { TextField("Name", text: $name); Toggle("Favorite", isOn: $favorite)
                Button("Save details") { Task { await model.update { try await model.repository.updateItem(item.id, name: name, favorite: favorite) }; if model.error == nil { dismiss() } } }
            }
            Section {
                Text(ByteCountFormatter.string(fromByteCount: item.bytes, countStyle: .file))
                Text(item.created.formatted(date: .abbreviated, time: .shortened))
                Button("Preview") { onAction(.preview) }
                if UTType(item.type)?.conforms(to: .image) == true || UTType(item.type)?.conforms(to: .pdf) == true {
                    Button("Recognize text") { onAction(.recognize) }
                }
                Button("Export original") { onAction(.export) }
                Text("Previews use a temporary decrypted copy on this device. Exported copies are no longer protected by Vault.").font(.footnote).foregroundStyle(.secondary)
            }
            if item.type == "public.plain-text" && item.bytes <= 5_000_000 {
                Section("Edit note") {
                    if noteLoaded {
                        TextEditor(text: $noteText).frame(minHeight: 160).accessibilityLabel("Note text")
                        Button("Save note changes") { let text = noteText; Task { await model.update { try await model.repository.editNote(item.id, text: text) }; if model.error == nil { dismiss() } } }
                    } else { ProgressView() }
                }
            }
            Section("Manual order") {
                Button("Move earlier") { shift(-1) }
                Button("Move later") { shift(1) }
            }
            Section { Button(item.deleted == nil ? "Move to Recently Deleted" : "Restore file", role: item.deleted == nil ? .destructive : nil) { Task { await model.update { try await model.repository.updateItem(item.id, trashed: item.deleted == nil) }; if model.error == nil { dismiss() } } } }
        }.disabled(model.busy).navigationTitle("File details").toolbar { Button("Lock") { model.lock() }; Button("Done") { dismiss() } }.onAppear { name = item.name; favorite = item.favorite }
                .task { if item.type == "public.plain-text" && item.bytes <= 5_000_000 { do { noteText = try await model.repository.readNote(item.id); noteLoaded = true } catch { model.error = error.vaultMessage } } }
        }
    }
    private func shift(_ delta: Int) {
        guard let library = model.library else { return }
        var ids = library.sort.sorted(library.items.filter { $0.folder == item.folder && $0.deleted == nil }).map(\.id)
        guard let index = ids.firstIndex(of: item.id), ids.indices.contains(index + delta) else { return }
        ids.swapAt(index, index + delta)
        Task { await model.update { try await model.repository.reorder(ids) } }
    }
}

struct VaultSettings: View {
    @Environment(VaultModel.self) private var model
    @State private var before = false
    @State private var password = ""
    @State private var kind = VaultCredentialKind.passphrase
    @State private var repeated = ""
    @State private var restore = false
    @State private var changed = false
    var body: some View {
        Form {
            if let library = model.library {
                AutomaticBackupSection()
                Section("Backup & recovery") {
                    Button("Prepare encrypted backup") { Task { await model.backup() } }
                    if let date = library.backupPrepared { Text("Last prepared: \(date.formatted()). This does not confirm that a provider uploaded it.").font(.footnote) }
                    else { Text("No backup prepared yet.").foregroundStyle(.secondary) }
                    Text("Save the .vault7 file outside this app, then test a restore on another device. Uninstalling Vault deletes local files. Your iCloud device backup is not a substitute for this export.").font(.footnote).foregroundStyle(.secondary)
                    Button("Restore another vault") { restore = true }
                    Button("Generate a replacement recovery key") { Task { await model.replaceRecoveryKey() } }
                    Text("Replacing a recovery key affects this device. Old backups keep the previous key until you replace those backups.").font(.footnote).foregroundStyle(.secondary)
                }
                Section("Biometric convenience") {
                    if model.biometricVaultID == library.id {
                        Label("Face ID or Touch ID unlock is on for this vault.", systemImage: "checkmark.circle.fill").accessibilityIdentifier("vault.biometricOn")
                    } else {
                        Button("Enable Face ID / Touch ID for this vault") { Task { await model.enableBiometrics() } }
                        if model.hasBiometricKey { Text("It’s on for another vault now. Turning it on here moves it to this one.").font(.footnote).foregroundStyle(.secondary) }
                    }
                    if model.hasBiometricKey { Button("Remove biometric unlock", role: .destructive) { model.disableBiometrics() } }
                    Text("Enabling this stores a key protected by your current biometric enrollment on this device, for one vault at a time. Keep the independent secret and recovery key.").font(.footnote).foregroundStyle(.secondary)
                }
                Section("Change unlock method") {
                    CredentialKindPicker(kind: $kind).onChange(of: kind) { password = ""; repeated = ""; changed = false }
                    CredentialInput(kind: kind, value: $password, label: kind.newLabel, identifier: "vault.changeCredential").onChange(of: password) { if !password.isEmpty { changed = false } }
                    CredentialInput(kind: kind, value: $repeated, label: kind.repeatLabel, identifier: "vault.repeatChangeCredential")
                    Button("Change unlock secret") {
                        let secret = password, chosen = kind; password = ""; repeated = ""
                        Task {
                            if await model.update(action: { try await model.repository.changePassphrase(secret, kind: chosen) }) {
                                changed = true; model.announce(String(localized: "Unlock secret changed. Older backups still open with the old one."))
                            }
                        }
                    }.disabled((try? kind.validate(password)) == nil || password != repeated)
                    if changed { Label("Unlock secret changed. Older backups still open with the old one.", systemImage: "checkmark.circle.fill").accessibilityIdentifier("vault.secretChanged") }
                    Text("Older backups still use their old credentials. Make a new backup after changing them.").font(.footnote).foregroundStyle(.secondary)
                }
            }
            Section("Privacy") {
                Text("No Vault account, analytics, ads or storage servers. Automatic backups copy encrypted files to the folder you select. Your Files provider handles cloud storage; App Store purchases contact Apple.")
                Text("Vault hides its contents when inactive and locks in the background. It does not prevent screenshots or protect an open session on a compromised device.")
            }
            Section("Built on") { Text("age encryption (rage, the Rust implementation of age-encryption.org/v1) in Vault’s shared core, the Keychain, PhotosUI, Vision, PDFKit and Quick Look. Vaults made by earlier versions are read with Apple CryptoKit and Apple Encrypted Archive until their first unlock updates them. Original files are preserved without recompression by Vault.") }
            AboutSection(onShowBeforeYouBuy: { before = true })
        }.navigationTitle("Settings").disabled(model.busy)
            .toolbar { Button("Lock") { model.lock() } }
            .sheet(isPresented: $before) { BeforeYouBuyView(content: VaultGate.before) { _ in before = false } }
            .sheet(isPresented: $restore) { RestoreVaultSheet() }
    }
}

struct VaultShare: UIViewControllerRepresentable {
    let urls: [URL]
    func makeUIViewController(context: Context) -> UIActivityViewController { UIActivityViewController(activityItems: urls, applicationActivities: nil) }
    func updateUIViewController(_ controller: UIActivityViewController, context: Context) {}
}
struct VaultPreview: UIViewControllerRepresentable {
    let urls: [URL]
    func makeCoordinator() -> Coordinator { Coordinator(urls) }
    func makeUIViewController(context: Context) -> QLPreviewController { let controller = QLPreviewController(); controller.dataSource = context.coordinator; return controller }
    func updateUIViewController(_ controller: QLPreviewController, context: Context) {}
    final class Coordinator: NSObject, QLPreviewControllerDataSource {
        let urls: [URL]; init(_ urls: [URL]) { self.urls = urls }
        func numberOfPreviewItems(in controller: QLPreviewController) -> Int { urls.count }
        func previewController(_ controller: QLPreviewController, previewItemAt index: Int) -> any QLPreviewItem { urls[index] as NSURL }
    }
}
