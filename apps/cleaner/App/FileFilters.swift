import SwiftUI
import CleanerCore
import SevenKit

extension FileSizeFilter {
    static let choices: [Self] = [.any, .atLeast1Mb, .atLeast10Mb, .atLeast100Mb, .atLeast1Gb, .unknown]
    var title: String {
        switch self {
        case .any: "Any size"
        case .atLeast1Mb: "At least 1 MB"
        case .atLeast10Mb: "At least 10 MB"
        case .atLeast100Mb: "At least 100 MB"
        case .atLeast1Gb: "At least 1 GB"
        case .unknown: "Size unknown"
        }
    }
}
func extensionTitle(_ value: String?) -> String {
    guard let value else { return "Any extension" }
    return value.isEmpty ? "No extension" : ".\(value)"
}
struct FolderFilters {
    var fileExtension: String?
    var size: FileSizeFilter = .any
    var dateMode: MediaDateFilter = .any
    var date = Date()
    var duplicatesOnly = false
    var active: Bool { fileExtension != nil || size != .any || dateMode != .any || duplicatesOnly }
    mutating func clear() { fileExtension = nil; size = .any; dateMode = .any; duplicatesOnly = false }
}
struct FileFilterEditor: View {
    @Environment(\.dismiss) private var dismiss
    @State private var draft: FolderFilters
    let extensions: [String]
    let apply: (FolderFilters) -> Void
    init(filters: FolderFilters, extensions: [String], apply: @escaping (FolderFilters) -> Void) {
        _draft = State(initialValue: filters); self.extensions = extensions; self.apply = apply
    }
    private var choices: [String] { Array(Set(extensions + (draft.fileExtension.map { [$0] } ?? []))).sorted() }
    var body: some View {
        NavigationStack {
            Form {
                Picker("Filename extension", selection: $draft.fileExtension) {
                    Text("Any extension").tag(String?.none)
                    ForEach(choices, id: \.self) { item in Text(extensionTitle(item)).tag(Optional(item)) }
                }.accessibilityIdentifier("files-extension")
                Picker("Measured size", selection: $draft.size) {
                    ForEach(FileSizeFilter.choices, id: \.self) { item in Text(item.title).tag(item) }
                }.accessibilityIdentifier("files-size")
                Toggle("Exact duplicates only", isOn: $draft.duplicatesOnly).accessibilityIdentifier("files-duplicates")
                DateFilterFields(mode: $draft.dateMode, reference: $draft.date, showReviewNote: false, dateMeaning: "Uses the modification date supplied by the chosen folder, in this device’s current time zone. Files without a date appear only with Any date.")
                Text("Extensions come from filenames, not content inspection. Unknown sizes are kept separate; 1 MB is 1,000,000 bytes.").textRole(.secondary)
                Button("Clear all filters") { draft.clear() }.accessibilityIdentifier("files-clear-draft")
            }
            .navigationTitle("File filters").navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) { Button("Apply") { apply(draft); dismiss() }.accessibilityIdentifier("files-apply") }
            }
        }
    }
}
