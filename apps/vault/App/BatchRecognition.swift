import SwiftUI
import VaultCore
import SevenKit

@Observable @MainActor final class RecognitionQueue {
    let plan: RecognitionBatchPlan
    var started = false
    var index = 0
    var saved = 0
    var skipped = 0
    var ready = false
    var failure: String?
    var text = ""
    var name = ""
    var stopped = false
    var item: VaultItem? { plan.items.indices.contains(index) ? plan.items[index] : nil }
    init(plan: RecognitionBatchPlan) { self.plan = plan }
    func next(savedNote: Bool) {
        if savedNote { saved += 1 } else { skipped += 1 }
        text = ""; name = ""; failure = nil; ready = false; index += 1
    }
}

struct BatchRecognitionScreen: View {
    @Environment(VaultModel.self) private var model
    @Bindable var queue: RecognitionQueue
    @State private var discard = false
    @State private var stopping = false
    private var runKey: String { "\(queue.started)-\(queue.index)-\(model.busy)-\(queue.stopped)" }
    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Text("\(queue.saved) saved · \(queue.skipped) skipped").accessibilityIdentifier("ocr-batch-counts")
                    ProgressView(value: Double(queue.index), total: Double(max(1, queue.plan.items.count))).accessibilityLabel("Review progress")
                }
                if !queue.started {
                    Section("Review \(queue.plan.items.count) files") {
                        Text("Recognize one file at a time, then save an encrypted note or skip it. All pages of each PDF are included, up to \(queue.plan.maximumPdfPages); longer PDFs need the individual page-range action. Originals stay unchanged.")
                        ForEach(queue.plan.items) { Text($0.name) }
                        Text("The queue stays only in this unlocked session. Closing or locking discards unsaved text; notes already saved remain.")
                        Button("Start review") { queue.started = true }.accessibilityIdentifier("ocr-batch-start")
                    }
                } else if queue.stopped || queue.item == nil {
                    Section(queue.stopped ? (model.busy ? "Stopping after current page…" : "Queue stopped") : "Review complete") {
                        Text("Saved notes remain in their source folders. Skipped or unfinished files were not changed.")
                    }
                } else if let item = queue.item {
                    Section("File \(queue.index + 1) of \(queue.plan.items.count): \(item.name)") {
                        if let failure = queue.failure {
                            Text(failure).foregroundStyle(Tokens.danger).accessibilityIdentifier("ocr-batch-error")
                            Button("Retry this file") { Task { await model.recognizeBatchItem(queue) } }.disabled(model.busy)
                        }
                        if queue.ready {
                            if queue.text.isEmpty { Text("No text was recognized. Enter text or skip this file.") }
                            LabeledContent("Note name") { TextField("Note name", text: $queue.name).accessibilityIdentifier("ocr-batch-name") }
                            TextEditor(text: $queue.text).frame(minHeight: 240).accessibilityLabel("Recognized text").accessibilityIdentifier("ocr-batch-text")
                            if !model.store.paidFeaturesAvailable { Text("Start the trial or unlock to save new notes. You can still read and copy this text.") }
                        }
                        if model.busy { ProgressView(model.progress) }
                        else { Button("Skip this file") { queue.next(savedNote: false) }.accessibilityIdentifier("ocr-batch-skip") }
                    }.disabled(model.busy)
                    Button(model.busy ? "Stop after current page" : "Stop queue") { if queue.ready && !queue.text.isEmpty { stopping = true } else { model.stopRecognitionQueue(queue) } }
                        .disabled(model.busy && queue.ready).accessibilityIdentifier("ocr-batch-stop")
                }
            }
            .navigationTitle("Queue").navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Close") { if queue.ready && !queue.text.isEmpty { discard = true } else { model.cleanPresentation() } }.disabled(model.busy)
                }
                ToolbarItem(placement: .primaryAction) { Button("Lock") { model.lock() }.accessibilityIdentifier("ocr-lock") }
                ToolbarItem(placement: .confirmationAction) {
                    if queue.ready && !queue.stopped {
                        Button("Save note") { Task { await model.saveBatchItem(queue) } }
                            .disabled(model.busy || queue.name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || queue.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || !model.store.paidFeaturesAvailable)
                            .accessibilityIdentifier("ocr-batch-save")
                    }
                }
            }
            .task(id: runKey) {
                if queue.started && !queue.stopped && !queue.ready && queue.failure == nil && queue.item != nil && !model.busy {
                    // The model owns this task; changing the busy state must not cancel native OCR.
                    Task { await model.recognizeBatchItem(queue) }
                }
            }
            .confirmationDialog("Stop this queue? Unsaved text will be discarded; saved notes remain.", isPresented: $stopping) {
                Button("Stop and discard", role: .destructive) { model.stopRecognitionQueue(queue) }
                Button("Keep reviewing", role: .cancel) {}
            }
            .confirmationDialog("Discard unsaved text and close the queue?", isPresented: $discard) {
                Button("Discard", role: .destructive) { model.cleanPresentation() }
                Button("Keep reviewing", role: .cancel) {}
            }
        }.interactiveDismissDisabled()
    }
}

enum BatchRecognitionError: LocalizedError {
    case pageLimit(UInt32)
    var errorDescription: String? { switch self { case .pageLimit(let limit): "This PDF needs the individual page-range action (up to \(limit) pages). Skip it here." } }
}
