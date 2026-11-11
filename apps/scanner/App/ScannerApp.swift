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
                .preferredColorScheme(appearance == "dark" ? .dark : appearance == "light" ? .light : nil)
                .task {
                    await model.start()
                    #if DEBUG
                    if ProcessInfo.processInfo.arguments.contains("-scanner.ui"), ProcessInfo.processInfo.arguments.contains("-scanner.seed"), model.documents.isEmpty { await model.fixture() }
                    #endif
                }
                .onChange(of: phase) { _, value in if value == .active { Task { await model.store.refresh() } } }
        }
    }
}
struct ScannerLibrary: View {
    @Environment(ScannerModel.self) private var model
    @State private var query = ""
    @State private var camera = false
    @State private var importing = false
    @State private var before = false
    #if DEBUG
    @State private var audit = ""
    #endif
    @AppStorage("scanner.order") private var order = LibraryOrder.newest.rawValue
    @State private var favoritesOnly = false
    private var visible: [ScanDocument] {
        (LibraryOrder(rawValue: order) ?? .newest).sorted(model.documents.filter { $0.matches(query) && (!favoritesOnly || $0.favorite == true) })
    }
    var body: some View {
        NavigationStack {
            List {
                Section {
                    GatedButton(feature: "Scan documents", action: { camera = true }) { Label("Scan paper", systemImage: "doc.viewfinder") }.disabled(!VNDocumentCameraViewController.isSupported || model.busy)
                    GatedButton(feature: "Import documents", action: { importing = true }) { Label("Import images or PDF", systemImage: "doc.badge.plus") }.disabled(model.busy)
                    if !VNDocumentCameraViewController.isSupported { Text("Camera scanning is unavailable here. You can import documents instead.").font(.footnote).foregroundStyle(.secondary) }
                    if model.busy { ProgressView(model.progress) }
                    #if DEBUG
                    if ProcessInfo.processInfo.arguments.contains("-scanner.ui") {
                        Button("Add sample document") { Task { await model.fixture() } }.disabled(model.busy)
                        if ProcessInfo.processInfo.arguments.contains("-scanner.audit") {
                            Button("Run processing audit") { Task { audit = "Running…"; audit = await model.processingAudit() } }.disabled(model.busy)
                            Text(audit).accessibilityIdentifier("audit.result")
                        }
                    }
                    #endif
                }
                if model.unreadableCount > 0 {
                    Section { Text("\(model.unreadableCount) document folders could not be read. Their files have been preserved. Backup is paused until they can be recovered.").foregroundStyle(.orange) }
                }
                Section {
                    NavigationLink { RecentlyDeletedScreen() } label: { Label("Recently Deleted", systemImage: "trash") }
                    NavigationLink { BackupScreen() } label: { Label("Backup & restore", systemImage: "externaldrive") }
                }
                Section("Your documents") {
                    if visible.isEmpty { ContentUnavailableView(model.documents.isEmpty ? "A little less paper." : "No matching documents", systemImage: "doc.text", description: Text(model.documents.isEmpty ? "Scan or import a document to get started." : "Try another search or turn off Favorites.")) }
                    ForEach(visible) { document in
                        NavigationLink { DocumentDetail(document: document) } label: {
                            Label { VStack(alignment: .leading, spacing: 5) {
                                Text(document.title).font(.headline)
                                Text("\(document.pages.count) pages · \(document.created.formatted(date: .abbreviated, time: .omitted))").font(.caption).foregroundStyle(.secondary)
                            } } icon: { Image(systemName: document.favorite == true ? "star.fill" : "doc.text") }
                        }.accessibilityIdentifier("scanner.document")
                    }
                }
            }.navigationTitle("Documents").searchable(text: $query, prompt: "Search names, notes and recognized text")
                .toolbar {
                    Menu {
                        Picker("Sort", selection: $order) { Text("Newest first").tag("newest"); Text("Oldest first").tag("oldest"); Text("Name").tag("name") }
                        Toggle("Favorites only", isOn: $favoritesOnly)
                    } label: { Image(systemName: "line.3.horizontal.decrease").accessibilityLabel("Sort and filter") }
                    NavigationLink { ScannerSettings(before: $before) } label: { Image(systemName: "gearshape").accessibilityLabel("Settings") }
                }
        }
        .task(id: model.store.hasStarted) { if model.store.hasStarted && model.store.launchSheet == .beforeYouBuy { before = true } }
        .sheet(isPresented: $before) { BeforeYouBuyView(content: ScannerGate.before) { _ in before = false } }
        .trialEndedSheet(content: ScannerGate.expired)
        .sheet(isPresented: $camera) { DocumentCamera(finish: { images in Task { await model.create(images) } }, fail: { model.error = $0 }) }
        .fileImporter(isPresented: $importing, allowedContentTypes: [.image, .pdf], allowsMultipleSelection: true) { result in
            do { let urls = try result.get(); Task { await model.importFiles(urls) } } catch { model.error = error.localizedDescription }
        }
        .alert("Could not complete", isPresented: Binding(get: { model.error != nil }, set: { shown in if !shown { model.error = nil } })) { Button("OK", role: .cancel) { model.error = nil } } message: { Text(model.error ?? "") }
    }
}
struct ScannerSettings: View {
    @Binding var before: Bool
    @AppStorage("scanner.appearance") private var appearance = "system"
    var body: some View {
        Form {
            Section("Appearance") { Picker("Theme", selection: $appearance) { Text("System").tag("system"); Text("Light").tag("light"); Text("Dark").tag("dark") } }
            Section { Text("Scans and text recognition are processed on your device. Files you export or back up go only where you choose. No Scanner account or servers."); Text("Searchable PDF exports include recognized text. Review important text for recognition errors.") }
            AboutSection(onShowBeforeYouBuy: { before = true })
        }.navigationTitle("Settings")
    }
}
struct DocumentDetail: View {
    @Environment(ScannerModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State var document: ScanDocument
    @State private var title = ""
    @State private var note = ""
    @State private var favorite = false
    @State private var urls: [UUID: URL] = [:]
    @State private var exportOptions = false
    @State private var preview: ShareFile?
    @State private var working = false
    @State private var deleting = false
    @State private var importing = false
    @State private var camera = false
    var body: some View {
        List {
            Section("Document name") {
                TextField("Name", text: $title).accessibilityIdentifier("scanner.title")
                Button("Save name") { saveMetadata() }
            }
            Section("Pages") {
                ForEach(Array(document.pages.enumerated()), id: \.element.id) { index, page in
                    NavigationLink {
                        if let url = urls[page.id] { PageEditor(document: document, page: page, url: url) }
                    } label: {
                        HStack {
                            if let url = urls[page.id] { PageImage(url: url, settings: page.settings, maxPixels: 160).frame(width: 44, height: 56).accessibilityHidden(true) }
                            VStack(alignment: .leading) { Text("Page \(index + 1)"); Text(page.text.isEmpty ? "No recognized text" : page.text).font(.caption).foregroundStyle(.secondary).lineLimit(2) }
                        }
                    }.accessibilityIdentifier("scanner.page.\(index + 1)")
                }.onMove { from, to in var pages = document.pages; pages.move(fromOffsets: from, toOffset: to); orderPages(pages.map(\.id)) }
                    .onDelete { offsets in var pages = document.pages; pages.remove(atOffsets: offsets); orderPages(pages.map(\.id)) }
            }
            Section {
                Button("Preview PDF") { Task { await makePreview() } }
                Button("Export PDF") { exportOptions = true }.accessibilityIdentifier("scanner.export")
                Button("Recognize text again") { Task { working = true; await model.recognize(document); working = false; adoptCurrent() } }
                ShareLink("Share all text", item: document.pages.map(\.text).joined(separator: "\n\n")).disabled(document.pages.allSatisfy { $0.text.isEmpty })
                Text("Tap a page to rotate or adjust it. Use Edit to reorder or remove pages.").font(.footnote).foregroundStyle(.secondary)
            }
            Section("Add pages") {
                GatedButton(feature: "Scan documents", action: { camera = true }) { Text("Scan more pages") }.disabled(!VNDocumentCameraViewController.isSupported || model.busy)
                GatedButton(feature: "Import documents", action: { importing = true }) { Text("Append images or PDF") }.disabled(model.busy)
                if model.busy { ProgressView(model.progress) }
            }
            Section("Notes") {
                TextField("Optional notes", text: $note, axis: .vertical).lineLimit(3...8).accessibilityIdentifier("scanner.note")
                Toggle("Favorite", isOn: $favorite)
                Button("Save notes and favorite") { saveMetadata() }
            }
            Section { Button("Delete document", role: .destructive) { deleting = true } }
        }.disabled(working).navigationTitle(document.title).navigationBarTitleDisplayMode(.inline)
            .toolbar { EditButton() }
            .overlay { if working { ProgressView().padding().background(.regularMaterial, in: RoundedRectangle(cornerRadius: 12)) } }
            .onChange(of: model.documents) { _, _ in if !working { adoptCurrent() } }
            .onAppear { title = document.title; note = document.note ?? ""; favorite = document.favorite ?? false }
            .task(id: document.pages.map(\.id)) {
                do { let data = try await model.repository.pageURLs(document); urls = Dictionary(uniqueKeysWithValues: zip(document.pages.map(\.id), data)) }
                catch { model.error = error.localizedDescription }
            }
            .confirmationDialog("Move this document to Recently Deleted?", isPresented: $deleting) { Button("Move to Recently Deleted", role: .destructive) { Task { if await model.delete(document) { dismiss() } } } }
            .sheet(isPresented: $exportOptions) { ExportScreen(document: document) }
            .sheet(item: $preview) { file in NavigationStack { PDFPreview(url: file.url).navigationTitle("PDF preview").toolbar { Button("Done") { preview = nil } } } }
            .sheet(isPresented: $camera) { DocumentCamera(finish: { images in Task { await model.create(images, appendTo: document.id) } }, fail: { model.error = $0 }) }
            .fileImporter(isPresented: $importing, allowedContentTypes: [.image, .pdf], allowsMultipleSelection: true) { result in
                do { let urls = try result.get(); Task { await model.importFiles(urls, appendTo: document.id) } } catch { model.error = error.localizedDescription }
            }
    }
    private func adoptCurrent() { if let value = model.documents.first(where: { $0.id == document.id }) { document = value } }
    private func saveMetadata() {
        working = true
        Task {
            do { document = try await model.repository.updateMetadata(documentID: document.id, title: title, note: note, favorite: favorite); await model.reload() }
            catch { model.error = error.localizedDescription }; working = false
        }
    }
    private func orderPages(_ ids: [UUID]) {
        guard !ids.isEmpty else { model.error = "Keep at least one page, or delete the document."; return }
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
struct ShareFile: Identifiable { let id = UUID(); let url: URL }
struct ShareSheet: UIViewControllerRepresentable {
    let url: URL
    func makeUIViewController(context: Context) -> UIActivityViewController { UIActivityViewController(activityItems: [url], applicationActivities: nil) }
    func updateUIViewController(_ controller: UIActivityViewController, context: Context) {}
}
struct PDFPreview: UIViewRepresentable {
    let url: URL
    func makeUIView(context: Context) -> PDFView { let view = PDFView(); view.autoScales = true; view.displayMode = .singlePageContinuous; view.displayDirection = .vertical; view.document = PDFDocument(url: url); return view }
    func updateUIView(_ view: PDFView, context: Context) {}
}
