import SwiftUI
import ScannerCore

struct ExportScreen: View {
    @Environment(ScannerModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let document: ScanDocument
    @State private var selected: Set<UUID>
    @State private var quality = PDFQuality.standard
    @State private var paper = PaperSize.a4
    @State private var searchable = true
    @State private var saving = false
    @State private var working = false
    @State private var exported: ShareFile?
    @State private var sharing = false
    @State private var previewing = false
    @State private var bytes = 0
    @State private var failure: String?
    init(document: ScanDocument) {
        self.document = document
        _selected = State(initialValue: Set(document.pages.map(\.id)))
    }
    var body: some View {
        NavigationStack {
            Form {
                Section("Pages to export") {
                    Button(selected.count == document.pages.count ? "Clear selection" : "Select all") {
                        selected = selected.count == document.pages.count ? [] : Set(document.pages.map(\.id))
                    }
                    ForEach(Array(document.pages.enumerated()), id: \.element.id) { index, page in
                        Toggle("Page \(index + 1)", isOn: Binding(get: { selected.contains(page.id) }, set: { include in
                            if include { selected.insert(page.id) } else { selected.remove(page.id) }
                        })).accessibilityIdentifier("export.page.\(index + 1)")
                    }
                }
                Section("PDF options") {
                    Picker("Paper size", selection: $paper) { Text("A4").tag(PaperSize.a4); Text("US Letter").tag(PaperSize.letter) }
                    Toggle("Searchable text", isOn: $searchable).accessibilityIdentifier("export.searchable")
                    Text("Text recognition runs on your device. Check important text for recognition errors.").font(.footnote).foregroundStyle(.secondary)
                }
                Section("File size") {
                    Picker("Quality", selection: $quality) {
                        Text("Standard").tag(PDFQuality.standard)
                        Text("Smaller file").tag(PDFQuality.compact)
                    }.pickerStyle(.segmented)
                    Text("Smaller files reduce image detail in this export. Your saved pages stay unchanged.").font(.footnote).foregroundStyle(.secondary)
                }
                Section {
                    Button("Prepare PDF") { Task { await prepare() } }.disabled(selected.isEmpty || working).accessibilityIdentifier("export.prepare")
                    if working { ProgressView("Preparing PDF…") }
                    if let exported {
                        Text(exported.url.lastPathComponent).font(.subheadline)
                        Text(ByteCountFormatter.string(fromByteCount: Int64(bytes), countStyle: .file)).foregroundStyle(.secondary).accessibilityIdentifier("export.size")
                        Button("Preview") { previewing = true }
                        Button("Save to Files") { saving = true }
                        Button("Share PDF") { sharing = true }.accessibilityIdentifier("export.share")
                    }
                    if let failure { Text(failure).foregroundStyle(.red) }
                }
            }.navigationTitle("Export PDF").navigationBarTitleDisplayMode(.inline)
                .toolbar { Button("Done") { dismiss() }.disabled(working) }
                .disabled(working)
                .interactiveDismissDisabled(working)
                .onChange(of: selected) { _, _ in exported = nil }
                .onChange(of: quality) { _, _ in exported = nil }
                .onChange(of: paper) { _, _ in exported = nil }
                .onChange(of: searchable) { _, _ in exported = nil }
                .sheet(isPresented: $saving) { if let exported { FileExportPicker(url: exported.url) } }
                .sheet(isPresented: $sharing) { if let exported { ShareSheet(url: exported.url) } }
                .sheet(isPresented: $previewing) { if let exported { NavigationStack { PDFPreview(url: exported.url).navigationTitle("PDF preview").toolbar { Button("Done") { previewing = false } } } } }
        }
        // Files live in unique export directories and are cleaned on the next app launch after 24 hours.
        // Keeping them beyond sheet dismissal lets an external share extension finish reading.
    }
    private func prepare() async {
        working = true; failure = nil; exported = nil
        defer { working = false }
        do {
            let plan = try PDFExportPlan(document: document, selected: selected)
            var subset = document; subset.pages = plan.pages
            let urls = try await model.repository.pageURLs(subset)
            let chosen = quality; let paperSize = paper; let includeText = searchable; let pages = plan.pages
            let url = try DocumentProcessing.exportURL(filename: plan.filename)
            try await Task.detached { try DocumentProcessing.pdf(urls: urls, pages: pages, to: url, quality: chosen, paper: paperSize, searchable: includeText) }.value
            bytes = try url.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0; exported = ShareFile(url: url)
            #if DEBUG
            if ProcessInfo.processInfo.arguments.contains("-scanner.ui") {
                let root = await model.repository.root
                let evidence = root.appendingPathComponent("export-\(chosen.rawValue).pdf")
                try? FileManager.default.removeItem(at: evidence)
                try FileManager.default.copyItem(at: url, to: evidence)
            }
            #endif
        } catch { failure = error.localizedDescription }
    }
}

struct RecentlyDeletedScreen: View {
    @Environment(ScannerModel.self) private var model
    @State private var permanent: ScanDocument?
    @State private var working = false
    var body: some View {
        List {
            Section {
                Text("Deleted documents stay on this device until you restore or permanently delete them. They are not a backup; export important copies to Files.").font(.footnote).foregroundStyle(.secondary)
            }
            if model.deletedDocuments.isEmpty { ContentUnavailableView("Nothing in Recently Deleted", systemImage: "trash") }
            ForEach(model.deletedDocuments) { document in
                VStack(alignment: .leading, spacing: 12) {
                    Text(document.title).font(.headline)
                    Text("\(document.pages.count) pages").font(.caption).foregroundStyle(.secondary)
                    Button("Restore") { working = true; Task { await model.restore(document); working = false } }.accessibilityIdentifier("trash.restore")
                    Button("Delete permanently", role: .destructive) { permanent = document }
                }.padding(.vertical, 6)
            }
        }.navigationTitle("Recently Deleted").disabled(working)
            .confirmationDialog("Permanently delete this document? This cannot be undone.", isPresented: Binding(get: { permanent != nil }, set: { if !$0 { permanent = nil } })) {
                if let document = permanent { Button("Delete permanently", role: .destructive) { working = true; Task { await model.permanentlyDelete(document); permanent = nil; working = false } } }
            }
    }
}
