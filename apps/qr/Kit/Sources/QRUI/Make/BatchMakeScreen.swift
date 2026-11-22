import Foundation
import QRCore
import SevenKit
import SwiftUI

enum BatchExport {
    static func pdf(items: [String], grid: SheetGrid, paper: PaperSize) throws -> Data {
        let matrices = try items.enumerated().map { index, text in
            do { return try CodeGenerator.generate(text: text, symbology: .qr, correction: .auto) }
            catch { throw Failure.code(index + 1) }
        }
        return PDFRenderer.batch(matrices: matrices, grid: grid, paper: paper)
    }
    enum Failure: LocalizedError {
        case code(Int)
        var errorDescription: String? {
            switch self {
            case .code(let index): "Code \(index) could not be generated. Shorten that line and review again."
            }
        }
    }
}

struct BatchMakeScreen: View {
    @Environment(\.dismiss) private var dismiss
    @State private var input = ""
    @State private var items: [String]?
    @State private var issue: String?
    @State private var grid = SheetGrid.grid2x3
    @State private var paper = PaperSize.forRegion(Locale.current.region?.identifier ?? "")
    @State private var prepared: URL?
    @State private var busy = false
    @State private var discard = false

    var body: some View {
        NavigationStack {
            Form {
                if let issue { Text(issue).foregroundStyle(Tokens.danger) }
                if let items {
                    Section {
                        Picker("Paper", selection: $paper) {
                            ForEach(PaperSize.allCases, id: \.self) { Text(verbatim: $0.title).tag($0) }
                        }
                        Picker("Labels per page", selection: $grid) {
                            ForEach(SheetGrid.allCases, id: \.self) { Text(verbatim: $0.title).tag($0) }
                        }
                    }.disabled(busy || prepared != nil)
                    if prepared != nil { Section("PDF ready") { Text("Your inputs remain available below.") } }
                    Section {
                        Button("Edit inputs") { self.items = nil; prepared = nil; issue = nil }.disabled(busy)
                    } footer: {
                        Text("Review the exact text encoded below. The PDF prints numbered QR labels in this order, in black on white. Nothing is added to History.")
                    }
                    ForEach(items.indices, id: \.self) { index in
                        BatchPayloadRow(number: index + 1, payload: items[index])
                    }
                } else {
                    Section {
                        TextEditor(text: $input).frame(minHeight: 180)
                            .textInputAutocapitalization(.never).autocorrectionDisabled()
                            .accessibilityIdentifier("batch-input")
                    } header: { Text("Code contents") } footer: {
                        Text("One text or link per line, up to 100 codes. Blank lines are skipped; other characters and duplicate lines stay unchanged.")
                    }
                }
            }
            .sevenScreenBackground()
            .navigationTitle(items.map { values in "Review \(values.count) codes" } ?? "Batch codes")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Close") {
                        if !input.isEmpty && prepared == nil { discard = true } else { dismiss() }
                    }.disabled(busy)
                }
                ToolbarItem(placement: .confirmationAction) {
                    if let prepared {
                        ShareLink(item: prepared) { Label("Share PDF", systemImage: "square.and.arrow.up") }
                            .accessibilityIdentifier("batch-share")
                    } else {
                    Button(items == nil ? "Review" : "Create PDF") {
                        if let items { Task { await export(items) } }
                        else {
                            let review = batchReview(text: input)
                            issue = review.issue
                            if issue == nil { self.items = review.items }
                        }
                    }.disabled(busy).accessibilityIdentifier("batch-review-export")
                    }
                }
            }
            .overlay { if busy { ProgressView("Creating PDF…").padding().background(.regularMaterial, in: RoundedRectangle(cornerRadius: 16)) } }
            .confirmationDialog("Discard batch inputs?", isPresented: $discard) {
                Button("Discard", role: .destructive) { dismiss() }
            }
        }
        .interactiveDismissDisabled(busy || (!input.isEmpty && prepared == nil))
    }

    @MainActor private func export(_ items: [String]) async {
        guard !busy else { return }
        busy = true; issue = nil
        defer { busy = false }
        let grid = grid, paper = paper
        do {
            prepared = try await Task.detached(priority: .userInitiated) {
                let data = try BatchExport.pdf(items: items, grid: grid, paper: paper)
                return try MakeExportWriter.write(data, named: "qr-batch-\(UUID().uuidString).pdf")
            }.value
        } catch { issue = error.localizedDescription }
    }
}

private struct BatchPayloadRow: View {
    let number: Int
    let payload: String
    var body: some View {
        Section {
            Text(verbatim: payload).textSelection(.enabled)
        } header: { Text("Code \(number)") }
    }
}
