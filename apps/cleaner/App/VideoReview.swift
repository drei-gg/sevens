import SwiftUI
import AVKit
import CleanerCore
import CleanerPlatform
import SevenKit

@Observable @MainActor final class VideoReview {
    let item: CleanupItem
    let source: URL
    let inspection: VideoInspection
    var start = "0.000"
    var end: String
    var candidate: VideoCandidate?
    var createdID: String?
    var savedID: String?
    init(item: CleanupItem, source: URL, inspection: VideoInspection) {
        self.item = item; self.source = source; self.inspection = inspection; self.end = Self.seconds(inspection.facts.durationMs)
    }
    var plan: VideoCopyPlan? {
        guard let a = Double(start.replacingOccurrences(of: ",", with: ".")), let b = Double(end.replacingOccurrences(of: ",", with: ".")), a.isFinite, b.isFinite, a >= 0, b >= 0,
              a <= Double(inspection.facts.durationMs)/1000, b <= Double(inspection.facts.durationMs)/1000 else { return nil }
        return try? videoCopyPlan(source: inspection.facts, startMs: UInt64((a * 1000).rounded()), endMs: UInt64((b * 1000).rounded()))
    }
    static func seconds(_ ms: UInt64) -> String { String(format: "%.3f", locale: Locale(identifier: "en_US_POSIX"), Double(ms)/1000) }
}

struct VideoReviewScreen: View {
    @Environment(CleanerModel.self) private var model
    @Bindable var review: VideoReview
    @State private var discard = false
    private var finished: Bool { review.savedID != nil }
    private var locked: Bool { model.busy || review.createdID != nil }
    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: Tokens.Space.m) {
                    Text(finished ? "The saved copy was read back and recorded. Your original remains." : "Choose a range, prepare the preview, then inspect it before saving a separate copy.").textRole(.body)
                    Text("SDR H.264 · up to 720p · original frame rate and AAC audio. No automatic removal.").textRole(.caption)
                    if !finished {
                        Text("Start, seconds").textRole(.secondary)
                        TextField("Start, seconds", text: $review.start).keyboardType(.decimalPad).textFieldStyle(.roundedBorder).disabled(locked).accessibilityIdentifier("video-start")
                        Text("End, seconds").textRole(.secondary)
                        TextField("End, seconds", text: $review.end).keyboardType(.decimalPad).textFieldStyle(.roundedBorder).disabled(locked).accessibilityIdentifier("video-end")
                        Text("At least 0.5 second, within \(VideoReview.seconds(review.inspection.facts.durationMs)) seconds.").textRole(.caption)
                        if review.plan == nil { Text("Choose a valid range inside the original.").textRole(.secondary).foregroundStyle(Tokens.danger) }
                        Button(review.candidate == nil ? "Prepare preview" : "Update preview") { model.prepareVideo() }.cleanerPrimary()
                            .disabled(locked || review.plan == nil || review.plan == review.candidate?.plan).accessibilityIdentifier("video-prepare")
                    }
                    if model.busy { ProgressView(model.progress); if model.canCancel { Button("Cancel preparation") { model.cancel() } } }
                    if review.createdID != nil && !finished { Text("A copy exists. Retry verifies that same copy. Closing leaves it unverified.").textRole(.secondary) }
                    if let candidate = review.candidate {
                        Text("Original: \(byteText(Int64(candidate.before.facts.bytes))). Preview: \(byteText(Int64(candidate.after.facts.bytes))).").textRole(.section)
                        if candidate.after.facts.bytes >= candidate.before.facts.bytes { Text("This copy is not smaller. Saving it uses additional storage.").textRole(.secondary) }
                        Text("\(candidate.after.facts.width) × \(candidate.after.facts.height) · \(VideoReview.seconds(candidate.after.facts.durationMs)) seconds").textRole(.secondary).accessibilityIdentifier("video-result")
                        VideoCandidatePlayer(url: candidate.outputURL).frame(height: 280)
                        ForEach(candidate.issues, id: \.self) { issue in Text(issue).textRole(.secondary).foregroundStyle(Tokens.danger) }
                    }
                    Text("Capture metadata is checked. Photos date, location, favorites and supported albums are copied. Drafts and unfinished verification last for this session only.").textRole(.caption)
                }.padding(Tokens.Space.m).sevenReadableSheet()
            }.cleanerCanvas().navigationTitle(finished ? "Video copy saved" : "Video review").navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) { Button("Close") {
                        if !finished && review.candidate != nil && review.createdID == nil { discard = true } else { model.closeVideo() }
                    }.disabled(model.busy) }
                    ToolbarItem(placement: .confirmationAction) { Button(finished ? "Done" : review.createdID != nil ? "Retry" : "Save copy") {
                        if finished { model.closeVideo() } else { model.saveVideo() }
                    }.disabled(model.busy || (!finished && (review.candidate == nil || review.plan != review.candidate?.plan || review.candidate?.issues.isEmpty != true))).accessibilityIdentifier("video-save") }
                }
                .confirmationDialog("Discard this preview?", isPresented: $discard, titleVisibility: .visible) { Button("Discard", role: .destructive) { model.closeVideo() } }
                .alert("Video not saved", isPresented: Binding(get: { model.error != nil }, set: { shown in if !shown { model.error = nil } })) { Button("Keep editing") { model.error = nil } } message: { Text(model.error ?? "") }
        }.interactiveDismissDisabled(model.busy || (!finished && review.candidate != nil && review.createdID == nil))
    }
}

private struct VideoCandidatePlayer: View {
    let url: URL
    @State private var player: AVPlayer?
    var body: some View { VideoPlayer(player: player).task(id: url) { player?.pause(); player = AVPlayer(url: url) }.onDisappear { player?.pause() } }
}
