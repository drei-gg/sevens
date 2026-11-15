import SwiftUI
import SevenKit
import ScannerCore

/// Export: pick pages on their thumbnails, set the PDF options, then one large button prepares the
/// file and becomes Share once it's ready. The ready file's size is the hero.
struct ExportScreen: View {
    @Environment(ScannerModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @Environment(\.dynamicTypeSize) private var textSize
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
    private var allSelected: Bool { selected.count == document.pages.count }
    var body: some View {
        NavigationStack {
            List {
                Section {
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: textSize.isAccessibilitySize ? 140 : 76), spacing: Tokens.Space.ms, alignment: .top)],
                              spacing: Tokens.Space.ms) {
                        ForEach(Array(document.pages.enumerated()), id: \.element.id) { index, page in
                            pageToggle(index: index, page: page)
                        }
                    }
                    .padding(.vertical, Tokens.Space.s)
                } header: {
                    let layout = textSize.isAccessibilitySize ? AnyLayout(VStackLayout(alignment: .leading, spacing: Tokens.Space.xs)) : AnyLayout(HStackLayout(alignment: .firstTextBaseline))
                    layout {
                        Text("\(selected.count) of \(pageCount(document.pages.count))").numeric()
                        if !textSize.isAccessibilitySize { Spacer() }
                        Button(allSelected ? "Clear selection" : "Select all") {
                            selected = allSelected ? [] : Set(document.pages.map(\.id))
                        }.textCase(nil)
                    }
                }
                Section {
                    Picker("Paper size", selection: $paper) { Text("A4").tag(PaperSize.a4); Text("US Letter").tag(PaperSize.letter) }
                    Toggle("Searchable text", isOn: $searchable).accessibilityIdentifier("export.searchable")
                    Picker("Quality", selection: $quality) {
                        Text("Standard").tag(PDFQuality.standard)
                        Text("Smaller file").tag(PDFQuality.compact)
                    }.pickerStyle(.segmented)
                } header: { Text("PDF") } footer: {
                    Text("Text recognition runs on your device. Check important text for recognition errors. Smaller files reduce image detail in this export; your saved pages stay unchanged.").textRole(.caption)
                }
                if let exported {
                    Section {
                        VStack(spacing: Tokens.Space.xs) {
                            Text("PDF ready").textRole(.caption, emphasized: true).textCase(.uppercase)
                            Text(ByteCountFormatter.string(fromByteCount: Int64(bytes), countStyle: .file))
                                .textRole(.hero).numeric().accessibilityIdentifier("export.size")
                            Text(exported.url.lastPathComponent).textRole(.secondary).lineLimit(2).truncationMode(.middle)
                        }
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, Tokens.Space.m)
                        AdaptiveStack(spacing: Tokens.Space.s) {
                            Button { previewing = true } label: { ChipLabel(title: "Preview", systemImage: "eye") }.quickAddChip().accessibilityIdentifier("export.preview")
                            Button { saving = true } label: { ChipLabel(title: "Save to Files", systemImage: "folder") }.quickAddChip()
                        }
                        .padding(.horizontal, Tokens.Space.m)
                        .bareListRow()
                    }
                    .listRowBackground(Tokens.card)
                }
                if let failure { Section { StatusLine(text: failure, color: Tokens.danger) } }
            }
            .sevenScreenBackground()
            .navigationTitle("Export PDF").navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() }.disabled(working) } }
            .safeAreaInset(edge: .bottom) { primaryAction.padding(.horizontal, Tokens.Space.m).padding(.bottom, Tokens.Space.s) }
            .interactiveDismissDisabled(working)
            .onChange(of: selected) { _, _ in exported = nil }
            .onChange(of: quality) { _, _ in exported = nil }
            .onChange(of: paper) { _, _ in exported = nil }
            .onChange(of: searchable) { _, _ in exported = nil }
            .sensoryFeedback(.success, trigger: exported?.id) { _, new in new != nil }
            .sheet(isPresented: $saving) { if let exported { FileExportPicker(url: exported.url) } }
            .sheet(isPresented: $sharing) { if let exported { ShareSheet(url: exported.url) } }
            .sheet(isPresented: $previewing) { if let exported { PDFPreviewSheet(url: exported.url) { previewing = false } } }
        }
        // Files live in unique export directories and are cleaned on the next app launch after 24 hours.
        // Keeping them beyond sheet dismissal lets an external share extension finish reading.
    }

    @ViewBuilder private var primaryAction: some View {
        if exported != nil {
            Button { sharing = true } label: { Label("Share PDF", systemImage: "square.and.arrow.up").frame(maxWidth: .infinity) }
                .heroAction().accessibilityIdentifier("export.share")
        } else {
            Button { Task { await prepare() } } label: {
                Label(working ? "Preparing PDF…" : "Prepare PDF", systemImage: "doc.richtext").frame(maxWidth: .infinity)
            }
            .heroAction().disabled(selected.isEmpty || working).accessibilityIdentifier("export.prepare")
        }
    }

    private func pageToggle(index: Int, page: ScanPage) -> some View {
        let isOn = selected.contains(page.id)
        return Button {
            if isOn { selected.remove(page.id) } else { selected.insert(page.id) }
        } label: {
            VStack(spacing: Tokens.Space.xs) {
                PageThumbnail(document: document, page: page, maxPixels: 240)
                    .opacity(isOn ? 1 : 0.45)
                    .overlay(alignment: .topTrailing) {
                        Image(systemName: isOn ? "checkmark.circle.fill" : "circle")
                            .foregroundStyle(isOn ? Tokens.ink : Tokens.muted).textRole(.section)
                            .background(Circle().fill(Tokens.card).padding(Tokens.Space.xs))
                            .padding(Tokens.Space.xs)
                    }
                Text("\(index + 1)").textRole(.caption).numeric()
            }
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text("Page \(index + 1)"))
        .accessibilityAddTraits(isOn ? [.isButton, .isSelected] : .isButton)
        .accessibilityIdentifier("export.page.\(index + 1)")
        .sensoryFeedback(.selection, trigger: isOn)
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

/// Documents moved here wait until they're restored or deleted for good: a thumbnail per document,
/// Restore on the row, delete permanently by swipe or the context menu.
struct RecentlyDeletedScreen: View {
    @Environment(ScannerModel.self) private var model
    @State private var permanent: ScanDocument?
    @State private var working = false
    var body: some View {
        List {
            if model.deletedDocuments.isEmpty {
                ContentUnavailableView {
                    Label("Nothing in Recently Deleted", systemImage: "trash")
                } description: {
                    Text("Documents you delete wait here until you restore them or delete them permanently.")
                }
                .bareListRow()
            } else {
                Section {
                    ForEach(model.deletedDocuments) { document in
                        HStack(spacing: Tokens.Space.ms) {
                            PageThumbnail(document: document, deleted: true, maxPixels: 160).frame(width: 44)
                            VStack(alignment: .leading, spacing: Tokens.Space.xs) {
                                Text(document.title).textRole(.rowTitle).lineLimit(2)
                                Text(pageCount(document.pages.count)).textRole(.caption).numeric()
                            }
                            .accessibilityElement(children: .combine)
                            Spacer(minLength: Tokens.Space.s)
                            Button("Restore") { restore(document) }
                                .modifier(SevenButtonStyle(kind: .bordered)).buttonBorderShape(.capsule)
                                .accessibilityIdentifier("trash.restore")
                        }
                        .swipeActions(edge: .trailing) {
                            Button("Delete permanently", systemImage: "trash", role: .destructive) { permanent = document }
                        }
                        .contextMenu {
                            Button("Restore", systemImage: "arrow.uturn.backward") { restore(document) }
                            Divider()
                            Button("Delete permanently", systemImage: "trash", role: .destructive) { permanent = document }
                        }
                    }
                } footer: {
                    Text("Deleted documents stay on this iPhone until you restore or permanently delete them. They are not a backup; export important copies to Files.").textRole(.caption)
                }
            }
        }
        .sevenScreenBackground()
        .navigationTitle("Recently Deleted").disabled(working)
        .confirmationDialog("Permanently delete this document? This cannot be undone.", isPresented: Binding(get: { permanent != nil }, set: { if !$0 { permanent = nil } }), titleVisibility: .visible) {
            if let document = permanent { Button("Delete permanently", role: .destructive) { working = true; Task { await model.permanentlyDelete(document); permanent = nil; working = false } } }
        }
    }
    private func restore(_ document: ScanDocument) { working = true; Task { await model.restore(document); working = false } }
}
