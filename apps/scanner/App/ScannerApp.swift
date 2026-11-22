import SwiftUI
import SevenKit
import ScannerCore
import VisionKit
import UniformTypeIdentifiers
import PDFKit

@main struct ScannerApp: App {
    @State private var model = ScannerModel()
    @Environment(\.scenePhase) private var phase
    @AppStorage("scanner.appearance") private var appearance = "system"
    var body: some Scene {
        WindowGroup {
            ScannerLibrary().environment(model).sevenStore(model.store, unlock: ScannerGate.unlock)
                .sevenLook()
                .preferredColorScheme(appearance == "dark" ? .dark : appearance == "light" ? .light : nil)
                .task {
                    await model.start()
                    #if DEBUG
                    let args = ProcessInfo.processInfo.arguments
                    if args.contains("-scanner.ui"), model.documents.isEmpty {
                        if args.contains("-scanner.demo") { await model.demo() } else if args.contains("-scanner.seed") { await model.fixture() }
                    }
                    #endif
                }
                .onChange(of: phase) { _, value in if value == .active { Task { await model.store.refresh() } } }
        }
    }
}

/// The library: documents as a grid of first pages, newest first, search on top, and one large
/// Scan button floating at the bottom (Import beside it). Recently Deleted and Backup sit under the
/// grid, where Files and Photos keep theirs.
struct ScannerLibrary: View {
    @Environment(ScannerModel.self) private var model
    @Environment(\.dynamicTypeSize) private var textSize
    @State private var query = ""
    @State private var camera = false
    @State private var importing = false
    @State private var before = false
    @State private var trashing: ScanDocument?
    #if DEBUG
    @State private var audit = ""
    #endif
    @AppStorage("scanner.order") private var order = LibraryOrder.newest.rawValue
    @State private var favoritesOnly = false
    private var sort: LibraryOrder { LibraryOrder(rawValue: order) ?? .newest }
    private var visible: [ScanDocument] {
        sort.sorted(model.documents.filter { $0.matches(query) && (!favoritesOnly || $0.favorite == true) })
    }
    private var cameraAvailable: Bool { VNDocumentCameraViewController.isSupported }
    private var columns: [GridItem] {
        [GridItem(.adaptive(minimum: textSize.isAccessibilitySize ? 260 : 150), spacing: Tokens.Space.m, alignment: .top)]
    }
    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: Tokens.Space.l) {
                    if model.unreadableCount > 0 {
                        StatusLine(text: scCount(model.unreadableCount, key: "unreadable"))
                            .padding(Tokens.Space.m).sevenCard()
                    }
                    if model.busy {
                        HStack(spacing: Tokens.Space.ms) {
                            ProgressView()
                            Text(model.progress).textRole(.body)
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(Tokens.Space.m).sevenCard()
                        .accessibilityElement(children: .combine)
                    }
                    if !cameraAvailable {
                        Text("Camera scanning is unavailable here. You can import documents instead.").textRole(.caption)
                    }
                    if model.documents.isEmpty {
                        ContentUnavailableView {
                            Label("No documents yet", systemImage: "doc.viewfinder")
                        } description: {
                            Text(cameraAvailable ? "Scans you save appear here, newest first. Tap Scan to start." : "Documents you import appear here, newest first.")
                        }
                        .containerRelativeFrame(.vertical) { height, _ in height * 0.6 }
                    } else {
                        header
                        if visible.isEmpty {
                            if query.isEmpty {
                                ContentUnavailableView("No favorites", systemImage: "star", description: Text("Star a document to keep it here."))
                            } else {
                                ContentUnavailableView.search(text: query)
                            }
                        } else {
                            LazyVGrid(columns: columns, alignment: .leading, spacing: Tokens.Space.l) {
                                ForEach(visible) { document in card(document) }
                            }
                        }
                    }
                    links
                    #if DEBUG
                    debugTools
                    #endif
                }
                .padding(.horizontal, Tokens.Space.m).padding(.top, Tokens.Space.s).padding(.bottom, Tokens.Space.l)
            }
            .sevenScreenBackground()
            .navigationTitle("Documents")
            .searchable(text: $query, prompt: "Search names, notes and text")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Menu {
                        Picker("Sort", selection: $order) {
                            Label("Newest first", systemImage: "calendar").tag(LibraryOrder.newest.rawValue)
                            Label("Oldest first", systemImage: "calendar").tag(LibraryOrder.oldest.rawValue)
                            Label("Name", systemImage: "textformat").tag(LibraryOrder.name.rawValue)
                        }
                    } label: { Label("Sort", systemImage: "arrow.up.arrow.down") }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    NavigationLink { ScannerSettings(before: $before) } label: { Label("Settings", systemImage: "gearshape") }
                }
            }
            .safeAreaInset(edge: .bottom) { captureBar }
        }
        .task(id: model.store.hasStarted) { if model.store.hasStarted && model.store.launchSheet == .beforeYouBuy { before = true } }
        .sheet(isPresented: $before) { BeforeYouBuyView(content: ScannerGate.before) { _ in before = false } }
        .trialEndedSheet(content: ScannerGate.expired)
        .sheet(isPresented: $camera) { DocumentCamera(finish: { images in Task { await model.create(images) } }, fail: { model.error = $0 }).ignoresSafeArea() }
        .fileImporter(isPresented: $importing, allowedContentTypes: [.image, .pdf], allowsMultipleSelection: true) { result in
            do { let urls = try result.get(); Task { await model.importFiles(urls) } } catch { model.error = error.localizedDescription }
        }
        .confirmationDialog("Move this document to Recently Deleted?", isPresented: Binding(get: { trashing != nil }, set: { if !$0 { trashing = nil } }), titleVisibility: .visible) {
            if let document = trashing { Button("Move to Recently Deleted", role: .destructive) { Task { _ = await model.delete(document); trashing = nil } } }
        }
        .alert("Could not complete", isPresented: Binding(get: { model.error != nil }, set: { shown in if !shown { model.error = nil } })) {
            if model.unsavedCapture != nil {
                // Unsaved scanned pages leave only by being saved or by an explicit discard.
                Button("Try again") { Task { await model.retryCapture() } }
                Button("Discard pages", role: .destructive) { model.discardCapture() }
            } else { Button("OK", role: .cancel) { model.error = nil } }
        } message: { Text(model.error ?? "") }
        .alert("Scan saved", isPresented: Binding(get: { model.notice != nil && model.error == nil }, set: { shown in if !shown { model.notice = nil } })) { Button("OK", role: .cancel) { model.notice = nil } } message: { Text(model.notice ?? "") }
    }

    /// "Recent · 6 documents", and an All/Favorites switch once something is starred.
    private var header: some View {
        VStack(alignment: .leading, spacing: Tokens.Space.ms) {
            let layout = textSize.isAccessibilitySize ? AnyLayout(VStackLayout(alignment: .leading, spacing: Tokens.Space.xs)) : AnyLayout(HStackLayout(alignment: .firstTextBaseline))
            layout {
                Text(sort == .newest ? "Recent" : sort == .oldest ? "Oldest first" : "By name").textRole(.section).accessibilityAddTraits(.isHeader)
                if !textSize.isAccessibilitySize { Spacer() }
                Text(documentCount(visible.count)).textRole(.secondary).numeric()
            }
            if favoritesOnly || model.documents.contains(where: { $0.favorite == true }) {
                Picker("Show", selection: $favoritesOnly) {
                    Text("All").tag(false)
                    Text("Favorites").tag(true)
                }
                .pickerStyle(.segmented)
                .sensoryFeedback(.selection, trigger: favoritesOnly)
            }
        }
    }

    private func card(_ document: ScanDocument) -> some View {
        NavigationLink { DocumentDetail(document: document) } label: { DocumentCard(document: document) }
            .buttonStyle(.plain)
            .accessibilityIdentifier("scanner.document")
            .contextMenu {
                Button(document.favorite == true ? "Remove from Favorites" : "Add to Favorites", systemImage: document.favorite == true ? "star.slash" : "star") {
                    Task { await model.setFavorite(document, document.favorite != true) }
                }
                Divider()
                Button("Move to Recently Deleted", systemImage: "trash", role: .destructive) { trashing = document }
            }
    }

    /// Recently Deleted and Backup, as rows on one card under the grid.
    private var links: some View {
        VStack(spacing: 0) {
            NavigationLink { RecentlyDeletedScreen() } label: {
                CardLinkRow(title: "Recently Deleted", systemImage: "trash", detail: model.deletedDocuments.isEmpty ? nil : model.deletedDocuments.count.formatted())
            }
            .accessibilityLabel(Text("Recently Deleted"))
            .accessibilityValue(Text(model.deletedDocuments.isEmpty ? "" : documentCount(model.deletedDocuments.count)))
            Rectangle().fill(Tokens.rule).frame(height: 1).padding(.leading, Tokens.Space.m).accessibilityHidden(true)
            NavigationLink { BackupScreen() } label: { CardLinkRow(title: "Backup & restore", systemImage: "externaldrive") }
                .accessibilityLabel(Text("Backup & restore"))
        }
        .buttonStyle(.plain)
        .sevenCard()
    }

    /// The capture bar: Scan as the one large action, Import beside it. Without a camera (the
    /// simulator, some iPads), Import takes the large button.
    private var captureBar: some View {
        AdaptiveStack(spacing: Tokens.Space.s) {
            if cameraAvailable {
                GatedButton(feature: "Import documents", action: { importing = true }) {
                    Label("Import", systemImage: "square.and.arrow.down.on.square").labelStyle(.iconOnly).frame(minWidth: 28, minHeight: 28)
                }
                .controlSize(.extraLarge).quickAddChip()
                .accessibilityLabel(Text("Import images or PDF"))
                GatedButton(feature: "Scan documents", action: { camera = true }) {
                    Label("Scan", systemImage: "doc.viewfinder").frame(maxWidth: .infinity)
                }
                .heroAction().accessibilityIdentifier("scanner.scan")
            } else {
                GatedButton(feature: "Import documents", action: { importing = true }) {
                    Label(textSize.isAccessibilitySize ? "Import" : "Import images or PDF", systemImage: "square.and.arrow.down.on.square").frame(maxWidth: .infinity)
                }
                .accessibilityLabel(Text("Import images or PDF"))
                .heroAction()
            }
        }
        .disabled(model.busy)
        .padding(.horizontal, Tokens.Space.m).padding(.bottom, Tokens.Space.s)
    }

    #if DEBUG
    @ViewBuilder private var debugTools: some View {
        if ProcessInfo.processInfo.arguments.contains("-scanner.ui") {
            VStack(alignment: .leading, spacing: Tokens.Space.s) {
                Button("Add sample document") { Task { await model.fixture() } }.disabled(model.busy)
                if ProcessInfo.processInfo.arguments.contains("-scanner.audit") {
                    Button("Run processing audit") { Task { audit = "Running…"; audit = await model.processingAudit() } }.disabled(model.busy)
                    Text(audit).textRole(.caption).accessibilityIdentifier("audit.result")
                }
            }
        }
    }
    #endif
}

struct ScannerSettings: View {
    @Binding var before: Bool
    @AppStorage("scanner.appearance") private var appearance = "system"
    var body: some View {
        Form {
            Section("Appearance") { Picker("Theme", selection: $appearance) { Text("System").tag("system"); Text("Light").tag("light"); Text("Dark").tag("dark") } }
            Section {
                Label("Scans and text recognition are processed on your iPhone. Files you export or back up go only where you choose. No Scanner account or servers.", systemImage: "lock")
                Label("Searchable PDF exports include recognized text. Review important text for recognition errors.", systemImage: "text.viewfinder")
            } header: { Text("Privacy") }
            AboutSection(onShowBeforeYouBuy: { before = true })
            Section { AboutNoticesRow(notices: ["CropViewController 3.3.0 by Tim Oliver. " + (Bundle.main.url(forResource: "TOCropViewController-LICENSE", withExtension: "txt").flatMap { licenseURL in try? String(contentsOf: licenseURL, encoding: .utf8) } ?? "MIT license")]) }
        }
        .sevenScreenBackground()
        .navigationTitle("Settings")
    }
}

/// A document, page first: its pages as a grid (tap to adjust, the last tile adds more), quick
/// actions as chips, the recognized text and details below, and Export PDF as the one large action.
struct DocumentDetail: View {
    @Environment(ScannerModel.self) private var model
    @Environment(Store.self) private var store
    @Environment(\.dismiss) private var dismiss
    @Environment(\.dynamicTypeSize) private var textSize
    @State var document: ScanDocument
    @State private var title = ""
    @State private var note = ""
    @State private var urls: [UUID: URL] = [:]
    @State private var exportOptions = false
    @State private var preview: ShareFile?
    @State private var working = false
    @State private var deleting = false
    @State private var importing = false
    @State private var camera = false
    @State private var arranging = false
    @State private var removing: ScanPage?
    @State private var unlock = false
    @State private var leaving = false
    private var allText: String { document.pages.map(\.text).filter { !$0.isEmpty }.joined(separator: "\n\n") }
    private var edited: Bool { title != document.title || note != (document.note ?? "") }
    private var savable: Bool { edited && !title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
    private var columns: [GridItem] {
        let count = textSize.isAccessibilitySize ? 1 : 2
        return Array(repeating: GridItem(.flexible(), spacing: Tokens.Space.m, alignment: .top), count: count)
    }
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Tokens.Space.l) {
                Text("\(pageCount(document.pages.count)) · \(document.createdDate.formatted(date: .abbreviated, time: .shortened))")
                    .textRole(.secondary).numeric()
                LazyVGrid(columns: columns, spacing: Tokens.Space.m) {
                    ForEach(Array(document.pages.enumerated()), id: \.element.id) { index, page in pageTile(index: index, page: page) }
                    addTile
                }
                AdaptiveStack(spacing: Tokens.Space.s) {
                    Button { Task { await makePreview() } } label: { ActionTileLabel(title: "Preview", systemImage: "eye") }
                        .actionTile().accessibilityIdentifier("scanner.preview")
                    Button { arranging = true } label: { ActionTileLabel(title: "Arrange", systemImage: "square.grid.2x2") }
                        .actionTile().disabled(document.pages.count < 2).accessibilityIdentifier("scanner.arrange")
                    ShareLink(item: allText) { ActionTileLabel(title: "Share text", systemImage: "text.quote") }
                        .actionTile().disabled(allText.isEmpty)
                }
                GroupBox {
                    if allText.isEmpty {
                        Text("No recognized text yet.").textRole(.secondary)
                    } else {
                        Text(allText).textRole(.body).lineLimit(6).textSelection(.enabled)
                    }
                    Button("Recognize text again") { Task { working = true; await model.recognize(document); working = false; adoptCurrent() } }
                } label: { Text("Recognized text") }
                GroupBox {
                    VStack(alignment: .leading, spacing: Tokens.Space.xs) {
                        Text("Name").textRole(.caption)
                        TextField("Name", text: $title).textRole(.body).submitLabel(.done).onSubmit { if savable { saveMetadata() } }
                            .accessibilityIdentifier("scanner.title")
                    }
                    VStack(alignment: .leading, spacing: Tokens.Space.xs) {
                        Text("Notes").textRole(.caption)
                        TextField("Optional notes", text: $note, axis: .vertical).textRole(.body).lineLimit(3...8).accessibilityIdentifier("scanner.note")
                    }
                    Button("Save details") { saveMetadata() }.disabled(!savable).accessibilityIdentifier("scanner.saveDetails")
                } label: { Text("Details") }
                Button("Delete document", role: .destructive) { deleting = true }
                    .frame(maxWidth: .infinity, minHeight: 44)
            }
            .padding(.horizontal, Tokens.Space.m).padding(.top, Tokens.Space.s).padding(.bottom, Tokens.Space.l)
            .frame(maxWidth: 672).frame(maxWidth: .infinity)
        }
        .scrollDismissesKeyboard(.interactively)
        .sevenScreenBackground()
        .disabled(working)
        .navigationTitle(document.title).navigationBarTitleDisplayMode(.inline)
        // Unsaved name or notes: a back button that asks first, and no swipe back past it.
        .navigationBarBackButtonHidden(edited)
        .toolbar {
            if edited {
                ToolbarItem(placement: .topBarLeading) {
                    Button { leaving = true } label: { Label("Documents", systemImage: "chevron.backward").labelStyle(.titleAndIcon) }
                        .accessibilityIdentifier("scanner.back")
                }
            }
            ToolbarItem(placement: .topBarTrailing) {
                Button { toggleFavorite() } label: {
                    Label(document.favorite == true ? "Remove from Favorites" : "Add to Favorites", systemImage: document.favorite == true ? "star.fill" : "star")
                }
                .accessibilityIdentifier("scanner.favorite")
                .sensoryFeedback(.selection, trigger: document.favorite)
            }
        }
        .safeAreaInset(edge: .bottom) {
            Button { exportOptions = true } label: { Label("Export PDF", systemImage: "square.and.arrow.up").frame(maxWidth: .infinity) }
                .heroAction().accessibilityIdentifier("scanner.export")
                .padding(.horizontal, Tokens.Space.m).padding(.bottom, Tokens.Space.s)
        }
        .overlay { if working { ProgressView().controlSize(.large).padding(Tokens.Space.l).sevenCard() } }
        .onChange(of: model.documents) { _, _ in if !working { adoptCurrent() } }
        .onAppear { title = document.title; note = document.note ?? "" }
        .task(id: document.pages.map(\.id)) {
            do { let data = try await model.repository.pageURLs(document); urls = Dictionary(uniqueKeysWithValues: zip(document.pages.map(\.id), data)) }
            catch { model.error = error.localizedDescription }
        }
        .confirmationDialog("Discard changes to the name and notes?", isPresented: $leaving, titleVisibility: .visible) {
            Button("Discard changes", role: .destructive) { dismiss() }
            Button("Keep editing", role: .cancel) {}
        }
        .confirmationDialog("Move this document to Recently Deleted?", isPresented: $deleting, titleVisibility: .visible) { Button("Move to Recently Deleted", role: .destructive) { Task { if await model.delete(document) { dismiss() } } } }
        .confirmationDialog("Remove this page? Its image is deleted when the document is saved.", isPresented: Binding(get: { removing != nil }, set: { if !$0 { removing = nil } }), titleVisibility: .visible) {
            if let page = removing { Button("Remove page", role: .destructive) { orderPages(document.pages.filter { $0.id != page.id }.map(\.id)); removing = nil } }
        }
        .sheet(isPresented: $exportOptions) { ExportScreen(document: document) }
        .sheet(isPresented: $arranging) { ArrangePages(document: document, urls: urls) { ids in orderPages(ids) } }
        .sheet(item: $preview) { file in PDFPreviewSheet(url: file.url) { preview = nil } }
        .sheet(isPresented: $camera) { DocumentCamera(finish: { images in Task { await model.create(images, appendTo: document.id) } }, fail: { model.error = $0 }).ignoresSafeArea() }
        .fileImporter(isPresented: $importing, allowedContentTypes: [.image, .pdf], allowsMultipleSelection: true) { result in
            do { let urls = try result.get(); Task { await model.importFiles(urls, appendTo: document.id) } } catch { model.error = error.localizedDescription }
        }
        .unlockSheet(isPresented: $unlock, feature: "Scan documents")
    }

    private func pageTile(index: Int, page: ScanPage) -> some View {
        NavigationLink {
            if let url = urls[page.id] { PageEditor(document: document, page: page, url: url, number: index + 1) }
        } label: {
            VStack(spacing: Tokens.Space.xs) {
                PageThumbnail(document: document, page: page, maxPixels: 700)
                Text("\(index + 1)").textRole(.caption).numeric()
            }
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text("Page \(index + 1)"))
        .accessibilityValue(Text(page.text.isEmpty ? String(localized: "No recognized text") : page.text))
        .accessibilityHint(Text("Opens the page to rotate or adjust it."))
        .accessibilityAddTraits(.isButton)
        .accessibilityIdentifier("scanner.page.\(index + 1)")
        .contextMenu {
            if !page.text.isEmpty { ShareLink(item: page.text) { Label("Share text", systemImage: "text.quote") } }
            if document.pages.count > 1 {
                Divider()
                Button("Remove page", systemImage: "trash", role: .destructive) { removing = page }
            }
        }
    }

    /// The last tile: add pages by camera or from Files. Adding pages needs access, like a new document.
    private var addTile: some View {
        Menu {
            if VNDocumentCameraViewController.isSupported {
                Button("Scan more pages", systemImage: "doc.viewfinder") { if !store.paidFeaturesAvailable { unlock = true } else if model.canAddPages(to: document) { camera = true } }
            }
            Button("Append images or PDF", systemImage: "square.and.arrow.down.on.square") { if !store.paidFeaturesAvailable { unlock = true } else if model.canAddPages(to: document) { importing = true } }
        } label: {
            VStack(spacing: Tokens.Space.xs) {
                RoundedRectangle(cornerRadius: ScannerMetrics.thumbnailRadius, style: .continuous)
                    .strokeBorder(Tokens.rule, style: StrokeStyle(lineWidth: 1.5, dash: [6, 4]))
                    .aspectRatio(ScannerMetrics.pageAspect, contentMode: .fit)
                    .overlay {
                        VStack(spacing: Tokens.Space.s) {
                            Image(systemName: "plus").textRole(.section)
                            Text("Add pages").textRole(.rowTitle, emphasized: true)
                        }
                        .foregroundStyle(Tokens.ink)
                    }
                Text(" ").textRole(.caption).accessibilityHidden(true)
            }
        }
        .buttonStyle(.plain)
        .disabled(model.busy)
        .accessibilityLabel(Text("Add pages"))
        .accessibilityIdentifier("scanner.addPages")
    }

    private func adoptCurrent() {
        if let value = model.documents.first(where: { $0.id == document.id }) {
            let wasTitle = document.title, wasNote = document.note ?? ""
            document = value
            if title == wasTitle { title = value.title }
            if note == wasNote { note = value.note ?? "" }
        }
    }
    private func toggleFavorite() {
        Task { if let updated = await model.setFavorite(document, document.favorite != true) { document = updated } }
    }
    private func saveMetadata() {
        working = true
        Task {
            do { document = try await model.repository.updateMetadata(documentID: document.id, title: title, note: note, favorite: document.favorite ?? false); await model.reload() }
            catch { model.error = error.localizedDescription }; working = false
        }
    }
    private func orderPages(_ ids: [UUID]) {
        guard !ids.isEmpty else { model.error = String(localized: "Keep at least one page, or delete the document."); return }
        let expected = document.pages.map(\.id)
        working = true
        Task {
            do { document = try await model.repository.setPageOrder(documentID: document.id, pageIDs: ids, expectedPageIDs: expected); await model.reload() }
            catch { model.error = error.localizedDescription }; working = false
        }
    }
    private func makePreview() async {
        working = true; defer { working = false }
        do {
            let urls = try await model.repository.pageURLs(document); let pages = document.pages
            let url = try DocumentProcessing.exportURL(filename: PDFExportPlan(document: document, selected: Set(pages.map(\.id))).filename)
            try await Task.detached { try DocumentProcessing.pdf(urls: urls, pages: pages, to: url, searchable: false) }.value
            preview = ShareFile(url: url)
        } catch { model.error = error.localizedDescription }
    }
}

/// Reorder or remove pages in one list, always in edit mode.
struct ArrangePages: View {
    @Environment(\.dismiss) private var dismiss
    let document: ScanDocument
    let urls: [UUID: URL]
    let apply: ([UUID]) -> Void
    @State private var pages: [ScanPage]
    init(document: ScanDocument, urls: [UUID: URL], apply: @escaping ([UUID]) -> Void) {
        self.document = document; self.urls = urls; self.apply = apply
        _pages = State(initialValue: document.pages)
    }
    var body: some View {
        NavigationStack {
            List {
                Section {
                    ForEach(pages) { page in
                        HStack(spacing: Tokens.Space.ms) {
                            PageThumbnail(document: document, page: page, maxPixels: 160).frame(width: 44)
                            VStack(alignment: .leading, spacing: Tokens.Space.xs) {
                                Text("Page \((document.pages.firstIndex(of: page) ?? 0) + 1)").textRole(.rowTitle)
                                Text(page.text.isEmpty ? String(localized: "No recognized text") : page.text).textRole(.caption).lineLimit(2)
                            }
                        }
                        .accessibilityElement(children: .combine)
                    }
                    .onMove { pages.move(fromOffsets: $0, toOffset: $1) }
                    .onDelete { offsets in if pages.count > offsets.count { pages.remove(atOffsets: offsets) } }
                } footer: {
                    Text("Drag to reorder. Removed pages are deleted when you tap Save. Keep at least one page, or delete the document.").textRole(.caption)
                }
            }
            .environment(\.editMode, .constant(.active))
            .sevenScreenBackground()
            .navigationTitle("Arrange pages").navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") { apply(pages.map(\.id)); dismiss() }.disabled(pages.map(\.id) == document.pages.map(\.id))
                }
            }
        }
        .interactiveDismissDisabled(pages.map(\.id) != document.pages.map(\.id))
    }
}

struct ShareFile: Identifiable { let id = UUID(); let url: URL }
struct ShareSheet: UIViewControllerRepresentable {
    let url: URL
    func makeUIViewController(context: Context) -> UIActivityViewController { UIActivityViewController(activityItems: [url], applicationActivities: nil) }
    func updateUIViewController(_ controller: UIActivityViewController, context: Context) {}
}
/// The PDF as it will look, scrolling continuously, with Share beside Done.
struct PDFPreviewSheet: View {
    let url: URL
    let done: () -> Void
    var body: some View {
        NavigationStack {
            PDFPreview(url: url).ignoresSafeArea(edges: .bottom)
                .navigationTitle("PDF preview").navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .topBarLeading) { ShareLink(item: url) { Label("Share PDF", systemImage: "square.and.arrow.up") } }
                    ToolbarItem(placement: .confirmationAction) { Button("Done", action: done) }
                }
        }
    }
}
struct PDFPreview: UIViewRepresentable {
    let url: URL
    func makeUIView(context: Context) -> PDFView { let view = PDFView(); view.autoScales = true; view.displayMode = .singlePageContinuous; view.displayDirection = .vertical; view.document = PDFDocument(url: url); return view }
    func updateUIView(_ view: PDFView, context: Context) {}
}
