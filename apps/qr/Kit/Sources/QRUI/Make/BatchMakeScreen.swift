import Foundation
import QRCore
import SevenKit
import SwiftUI

enum BatchExport {
    /// Where prepared batch PDFs are written: the app's temporary `qr-export` folder, as
    /// `MakeExportWriter` does for single codes.
    static var directory: URL {
        FileManager.default.temporaryDirectory.appendingPathComponent("qr-export", isDirectory: true)
    }

    static let filePrefix = "qr-batch-"

    static func pdf(items: [String], grid: SheetGrid, paper: PaperSize) throws -> Data {
        let matrices = try items.enumerated().map { index, text in
            do { return try CodeGenerator.generate(text: text, symbology: .qr, correction: .auto) }
            catch { throw Failure.code(index + 1) }
        }
        return PDFRenderer.batch(matrices: matrices, grid: grid, paper: paper)
    }

    /// Clears every batch PDF left in the folder (an earlier one, or one from a session that
    /// ended before it could clean up), then writes this one.
    static func write(_ data: Data, in directory: URL = BatchExport.directory) throws -> URL {
        removePrepared(in: directory)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let url = directory.appendingPathComponent("\(filePrefix)\(UUID().uuidString).pdf")
        try data.write(to: url, options: .atomic)
        return url
    }

    /// Deletes the batch PDFs in `directory`; the single-code exports beside them stay.
    static func removePrepared(in directory: URL = BatchExport.directory) {
        let names = (try? FileManager.default.contentsOfDirectory(atPath: directory.path)) ?? []
        for name in names where name.hasPrefix(filePrefix) && name.hasSuffix(".pdf") {
            try? FileManager.default.removeItem(at: directory.appendingPathComponent(name))
        }
    }

    /// Deletes one prepared PDF once it is no longer offered.
    static func discard(_ url: URL?) {
        guard let url else { return }
        try? FileManager.default.removeItem(at: url)
    }

    enum Failure: LocalizedError, Equatable {
        case code(Int)
        var errorDescription: String? {
            switch self {
            case .code(let index):
                String(localized: "batch.failure.code",
                       defaultValue: "Code \(index) couldn’t be generated. Shorten that line and review again.",
                       bundle: .module)
            }
        }
    }
}

/// The batch screen's words (SPEC §15). The core says what is wrong; these say it.
enum BatchText {
    static func issue(_ kind: BatchIssue) -> String {
        switch kind {
        case .tooLarge(let maxKilobytes):
            String(localized: "batch.issue.tooLarge",
                   defaultValue: "The batch is too large. Use at most \(Int(maxKilobytes)) KB of text.", bundle: .module)
        case .lineTooLong(let line):
            String(localized: "batch.issue.lineTooLong",
                   defaultValue: "Line \(Int(line)) is too long for a code.", bundle: .module)
        case .tooManyLines(let max):
            String(localized: "batch.issue.tooManyLines",
                   defaultValue: "Use at most \(Int(max)) nonblank lines per batch.", bundle: .module)
        case .empty:
            String(localized: "batch.issue.empty",
                   defaultValue: "Enter one code’s text or link per line.", bundle: .module)
        }
    }

    /// The review's problem in this app's words; the core's English only when it names none.
    static func issue(of review: BatchReview) -> String? {
        if let kind = review.issueKind { return issue(kind) }
        return review.issue
    }

    static var title: String { String(localized: "batch.title", defaultValue: "Batch codes", bundle: .module) }
    static func reviewTitle(_ count: Int) -> String {
        String(localized: "batch.title.review", defaultValue: "Review \(count) codes", bundle: .module)
    }
    static var paper: String { String(localized: "batch.paper", defaultValue: "Paper", bundle: .module) }
    static var grid: String { String(localized: "batch.grid", defaultValue: "Labels per page", bundle: .module) }
    static var readyHeader: String { String(localized: "batch.ready.header", defaultValue: "PDF ready", bundle: .module) }
    static var readyBody: String {
        String(localized: "batch.ready.body", defaultValue: "Your inputs remain available below.", bundle: .module)
    }
    static var edit: String { String(localized: "batch.edit", defaultValue: "Edit inputs", bundle: .module) }
    static var reviewFooter: String {
        String(localized: "batch.review.footer",
               defaultValue: "Review the exact text encoded below. The PDF prints numbered QR labels in this order, in black on white. Nothing is added to History.",
               bundle: .module)
    }
    static var inputHeader: String { String(localized: "batch.input.header", defaultValue: "Code contents", bundle: .module) }
    static var inputFooter: String {
        String(localized: "batch.input.footer",
               defaultValue: "One text or link per line, up to 100 codes. Blank lines are skipped; other characters and duplicate lines stay unchanged.",
               bundle: .module)
    }
    static var close: String { String(localized: "batch.close", defaultValue: "Close", bundle: .module) }
    static var share: String { String(localized: "batch.share", defaultValue: "Share PDF", bundle: .module) }
    static var review: String { String(localized: "batch.review", defaultValue: "Review", bundle: .module) }
    static var create: String { String(localized: "batch.create", defaultValue: "Create PDF", bundle: .module) }
    static var creating: String { String(localized: "batch.creating", defaultValue: "Creating PDF…", bundle: .module) }
    static var discardTitle: String {
        String(localized: "batch.discard.title", defaultValue: "Discard batch inputs?", bundle: .module)
    }
    static var discard: String { String(localized: "batch.discard", defaultValue: "Discard", bundle: .module) }
    static func rowHeader(_ number: Int) -> String {
        String(localized: "batch.row.header", defaultValue: "Code \(number)", bundle: .module)
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
                if let issue {
                    // A problem with the input, not a verdict: ink, not a status colour (SPEC "Look").
                    Label(issue, systemImage: "exclamationmark.circle")
                        .textRole(.body).foregroundStyle(Tokens.ink)
                        .accessibilityIdentifier("batch-issue")
                }
                if let items {
                    Section {
                        Picker(BatchText.paper, selection: $paper) {
                            ForEach(PaperSize.allCases, id: \.self) { Text(verbatim: $0.title).tag($0) }
                        }
                        Picker(BatchText.grid, selection: $grid) {
                            ForEach(SheetGrid.allCases, id: \.self) { Text(verbatim: $0.title).tag($0) }
                        }
                    }.disabled(busy || prepared != nil)
                    if prepared != nil { Section(BatchText.readyHeader) { Text(BatchText.readyBody) } }
                    Section {
                        Button(BatchText.edit) { self.items = nil; clearPrepared(); issue = nil }.disabled(busy)
                    } footer: {
                        Text(BatchText.reviewFooter)
                    }
                    ForEach(items.indices, id: \.self) { index in
                        BatchPayloadRow(number: index + 1, payload: items[index])
                    }
                } else {
                    Section {
                        TextEditor(text: $input).frame(minHeight: 180)
                            .textInputAutocapitalization(.never).autocorrectionDisabled()
                            .accessibilityIdentifier("batch-input")
                    } header: { Text(BatchText.inputHeader) } footer: {
                        Text(BatchText.inputFooter)
                    }
                }
            }
            .sevenScreenBackground()
            .navigationTitle(items.map { BatchText.reviewTitle($0.count) } ?? BatchText.title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(BatchText.close) {
                        if !input.isEmpty && prepared == nil { discard = true } else { clearPrepared(); dismiss() }
                    }.disabled(busy)
                }
                ToolbarItem(placement: .confirmationAction) {
                    if let prepared {
                        ShareLink(item: prepared) { Label(BatchText.share, systemImage: "square.and.arrow.up") }
                            .accessibilityIdentifier("batch-share")
                    } else {
                        Button(items == nil ? BatchText.review : BatchText.create) {
                            if let items { Task { await export(items) } }
                            else {
                                let review = batchReview(text: input)
                                issue = BatchText.issue(of: review)
                                if issue == nil { self.items = review.items }
                            }
                        }.disabled(busy).accessibilityIdentifier("batch-review-export")
                    }
                }
            }
            .overlay {
                if busy {
                    ProgressView(BatchText.creating).padding()
                        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: Tokens.Radius.card))
                }
            }
            .confirmationDialog(BatchText.discardTitle, isPresented: $discard) {
                Button(BatchText.discard, role: .destructive) { dismiss() }
            }
        }
        .interactiveDismissDisabled(busy || (!input.isEmpty && prepared == nil))
        // ShareLink reports neither a finished nor a cancelled share, so the PDF goes when it stops
        // being offered: on Edit inputs, on Close or Discard, on the next prepare, and whatever an
        // earlier screen left (a swipe down) when this one opens.
        .onAppear { BatchExport.removePrepared() }
    }

    private func clearPrepared() {
        BatchExport.discard(prepared)
        prepared = nil
    }

    @MainActor private func export(_ items: [String]) async {
        guard !busy else { return }
        busy = true; issue = nil
        defer { busy = false }
        let grid = grid, paper = paper
        do {
            prepared = try await Task.detached(priority: .userInitiated) {
                try BatchExport.write(try BatchExport.pdf(items: items, grid: grid, paper: paper))
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
        } header: { Text(BatchText.rowHeader(number)) }
    }
}
