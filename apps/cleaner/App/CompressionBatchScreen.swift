import SwiftUI
import SevenKit
import CleanerCore

struct CompressionBatchProgress: View {
    let queue: CompressionBatch
    var body: some View {
        Text(queue.complete ? "Review complete" : "Photo \(queue.index + 1) of \(queue.items.count)").textRole(.section).accessibilityIdentifier("compression-batch-position")
        ProgressView(value: Double(queue.index), total: Double(queue.items.count))
        Text("\(queue.saved.count) saved · \(queue.skipped.count) skipped · \(queue.unverified.count) unverified").textRole(.secondary).accessibilityIdentifier("compression-batch-summary")
        if let item = queue.current { Text(item.name).textRole(.rowTitle) }
    }
}

struct CompressionBatchScreen: View {
    @Environment(CleanerModel.self) private var model
    let queue: CompressionBatch
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Tokens.Space.m) {
                CompressionBatchProgress(queue: queue)
                if let error = model.error { CleanerNotice(text: c(error), symbol: "exclamationmark.triangle", caution: true) }
                if model.busy { ProgressView(model.progress) }
                Text(queue.complete ? "Your originals and review decisions are unchanged. Saved copies remain in Photos." : "Inspect each photo, then save a copy or skip. Only one preview is prepared at a time. Originals stay in Photos.").textRole(.body)
                if !queue.complete { Text("Stopping keeps saved copies and clears the current preview. This queue does not resume after the app closes.").textRole(.secondary) }
                if !queue.unverified.isEmpty { CleanerNotice(text: c("Unverified copies may exist in Photos. Check them before starting another queue; they were not counted as saved."), symbol: "exclamationmark.triangle", caution: true) }
                if queue.index > 0 {
                    Divider(); Text("Results").textRole(.section)
                    ForEach(Array(queue.items.prefix(queue.index))) { item in
                        VStack(alignment: .leading) {
                            Text(item.name).textRole(.rowTitle)
                            Text(queue.saved.contains(item.id) ? "Copy saved and verified" : queue.unverified.contains(item.id) ? "Copy created; verification unfinished" : "Skipped; original retained").textRole(.secondary)
                        }
                    }
                }
            }.padding(Tokens.Space.m).sevenReadableSheet()
        }.cleanerCanvas().navigationTitle("Photo review").navigationBarTitleDisplayMode(.inline)
        .toolbar { Button(queue.complete ? "Close" : "Stop review") { model.stopCompressionBatch() }.disabled(model.busy).accessibilityIdentifier("compression-batch-close") }
        .safeAreaInset(edge: .bottom) {
            VStack(spacing: Tokens.Space.s) {
                if queue.complete {
                    Button { model.stopCompressionBatch() } label: { Text("Done").frame(maxWidth: .infinity) }.cleanerPrimary().accessibilityIdentifier("compression-batch-done")
                } else {
                    Button { model.prepareBatchPhoto() } label: { Text(model.error == nil ? "Prepare preview" : "Retry preparation").frame(maxWidth: .infinity) }.cleanerPrimary().disabled(model.busy).accessibilityIdentifier("compression-batch-prepare")
                    if model.busy && model.canCancel { Button("Cancel preparation") { model.cancel() } }
                    else { Button("Skip this photo") { model.skipBatchPhoto() }.disabled(model.busy).accessibilityIdentifier("compression-skip") }
                }
            }.padding(Tokens.Space.m).background(Tokens.paper)
        }
    }
}
