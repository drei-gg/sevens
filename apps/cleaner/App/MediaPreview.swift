import SwiftUI
@preconcurrency import Photos
import AVKit
import SevenKit
import CleanerCore
import CleanerPlatform

struct PhotoThumbnail: View {
    let id: String
    var pixelSize: CGFloat = 160
    @State private var image: UIImage?
    var body: some View {
        Group { if let image { Image(uiImage: image).resizable().scaledToFill() } else { Image(systemName: "photo").foregroundStyle(Tokens.muted).frame(maxWidth: .infinity,maxHeight: .infinity).background(Tokens.card) } }
            .task(id: id) {
                #if DEBUG
                if CleanerFixtures.active, let demo = CleanerFixtures.image(id: id) { image = demo; return }
                #endif
                guard let asset = PHAsset.fetchAssets(withLocalIdentifiers: [id], options: nil).firstObject else { return }
                let options = PHImageRequestOptions(); options.deliveryMode = .highQualityFormat; options.isNetworkAccessAllowed = false
                PHImageManager.default().requestImage(for: asset, targetSize: CGSize(width: pixelSize,height: pixelSize), contentMode: .aspectFill, options: options) { result, info in
                    guard (info?[PHImageResultIsDegradedKey] as? Bool) != true else { return }
                    Task { @MainActor in image = result }
                }
            }
    }
}
struct MediaDetail: View {
    @Environment(CleanerModel.self) private var model
    let item: CleanupItem
    @State private var image: UIImage?
    @State private var player: AVPlayer?
    @State private var rate: Float = 1
    @State private var status = "Loading preview…"
    /// The item as the library has it now: favorite and revision follow changes made here, so the
    /// label, the next favorite value and later copies never use the snapshot this view opened with.
    private var current: CleanupItem { model.media.first { $0.id == item.id } ?? item }
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Tokens.Space.m) {
                if item.kind == .video, let player {
                    VideoPlayer(player: player).frame(minHeight: 280)
                    Picker("Playback speed", selection: $rate) { Text("0.5×").tag(Float(0.5)); Text("1×").tag(Float(1)); Text("1.5×").tag(Float(1.5)); Text("2×").tag(Float(2)) }.pickerStyle(.segmented).onChange(of: rate) { _, value in player.rate = value }
                } else if let image { ZoomImage(image: image, label: String(format: c("Photo: %@"), item.name)).frame(height: 360) }
                else { ContentUnavailableView(status, systemImage: item.kind == .photo ? "photo" : "video") }
                Text(item.name).textRole(.section)
                Text("\(current.width.formatted()) × \(current.height.formatted()) · \(byteText(current.bytes))").textRole(.caption).numeric()
                if let date = item.date { Text(date, style: .date).textRole(.caption).numeric() }
                if item.kind == .video { Text(Duration.seconds(item.duration).formatted(.time(pattern: .minuteSecond))).textRole(.caption).numeric() }
                ViewThatFits(in: .horizontal) {
                  HStack(spacing: Tokens.Space.s) {
                    Button("Keep") { Task { await model.decide([item.id], .keep) } }.cleanerChip()
                    Button("Later") { Task { await model.decide([item.id], .later) } }.cleanerChip()
                    Button("Queue removal", role: .destructive) { Task { await model.decide([item.id], .remove) } }.cleanerChip()
                }
                  VStack(spacing: Tokens.Space.s) {
                    Button("Keep") { Task { await model.decide([item.id], .keep) } }.cleanerChip()
                    Button("Later") { Task { await model.decide([item.id], .later) } }.cleanerChip()
                    Button("Queue removal", role: .destructive) { Task { await model.decide([item.id], .remove) } }.cleanerChip()
                  }
                }
                if item.kind == .photo { Button { model.compress(current) } label: { Text("Inspect compression").frame(maxWidth: .infinity) }.cleanerPrimary().disabled(model.busy)
                    Text("Original retained. Inspect the actual output before saving a copy.").textRole(.caption)
                }
                if item.kind == .video { Button("Trim or compress") { model.openVideo(current) }.cleanerPrimary().disabled(model.busy) }
                Button(current.favorite ? "Remove favorite" : "Mark favorite") { model.setFavorite(item.id, value: !current.favorite) }.disabled(model.busy)
            }.padding(Tokens.Space.m).sevenReadableSheet()
        }.cleanerCanvas().navigationTitle("Review").navigationBarTitleDisplayMode(.inline)
            .task(id: item.id) { load() }.onDisappear { player?.pause() }
    }
    private func load() {
        #if DEBUG
        if CleanerFixtures.active {
            if item.kind == .video { player = AVPlayer(url: CleanerFixtures.videoURL) }
            else { image = CleanerFixtures.image(id: item.id) }
            return
        }
        #endif
        guard let asset = PHAsset.fetchAssets(withLocalIdentifiers: [item.id], options: nil).firstObject else { status = "Preview unavailable"; return }
        if item.kind == .video {
            let options = PHVideoRequestOptions(); options.isNetworkAccessAllowed = model.allowCloud
            PHImageManager.default().requestPlayerItem(forVideo: asset, options: options) { result, _ in
                Task { @MainActor in if let result { player = AVPlayer(playerItem: result) } else { status = "Enable cloud downloads to load unavailable media" } }
            }
        } else {
            let options = PHImageRequestOptions(); options.deliveryMode = .highQualityFormat; options.isNetworkAccessAllowed = model.allowCloud
            PHImageManager.default().requestImage(for: asset, targetSize: PHImageManagerMaximumSize, contentMode: .aspectFit, options: options) { result, info in
                guard (info?[PHImageResultIsDegradedKey] as? Bool) != true else { return }
                Task { @MainActor in image = result; if result == nil { status = "Enable cloud downloads to load unavailable media" } }
            }
        }
    }
}
struct ZoomImage: UIViewRepresentable {
    let image: UIImage
    /// What VoiceOver reads for the image; it is otherwise an unlabelled scroll view.
    let label: String
    func makeCoordinator() -> Coordinator { Coordinator() }
    func makeUIView(context: Context) -> UIScrollView {
        let scroll = UIScrollView(); scroll.delegate = context.coordinator; scroll.minimumZoomScale = 1; scroll.maximumZoomScale = 12
        let view = UIImageView(); view.contentMode = .scaleAspectFit; view.autoresizingMask = [.flexibleWidth,.flexibleHeight]; view.frame = scroll.bounds
        view.isAccessibilityElement = true; view.accessibilityTraits = .image; view.accessibilityLabel = label
        scroll.addSubview(view); context.coordinator.imageView = view; return scroll
    }
    func updateUIView(_ scroll: UIScrollView, context: Context) { context.coordinator.imageView?.image = image; context.coordinator.imageView?.accessibilityLabel = label; if scroll.zoomScale == 1 { context.coordinator.imageView?.frame = scroll.bounds } }
    final class Coordinator: NSObject, UIScrollViewDelegate {
        var imageView: UIImageView?
        func viewForZooming(in scrollView: UIScrollView) -> UIView? { imageView }
    }
}
struct CompressionScreen: View {
    @Environment(\.dynamicTypeSize) private var typeSize
    @Environment(CleanerModel.self) private var model
    @State private var quality = 96.0
    @State private var size: ImageSizeProfile = .original
    var body: some View {
        NavigationStack {
            if let candidate = model.candidate {
                ScrollViewReader { proxy in
                    ScrollView {
                        VStack(alignment: .leading, spacing: Tokens.Space.m) {
                            if let queue = model.compressionBatch { CompressionBatchProgress(queue: queue) }
                            if let error = model.error { CleanerNotice(text: c(error), symbol: "exclamationmark.triangle", caution: true) }
                            if model.createdCopyID != nil { Text("A copy exists. Retry checks and records that same copy. Closing keeps it, but leaves it unverified.").textRole(.secondary) }
                            CleanerHero(label: c("Inspect the actual files"),value: candidate.assessment.savings > 0 ? byteText(candidate.assessment.savings) : c("No reduction"),detail: c("Candidate reduction. The original stays."),symbol: "arrow.down.doc",hue: Tokens.dataWarm,valueRole: candidate.assessment.savings > 0 ? .display : .title)
                            Text("Pinch to inspect fine detail. These checks reject obvious damage; they cannot guarantee that a photograph still looks right to you.").textRole(.secondary)
                            Text("Quality: \(Int(quality))%").textRole(.section)
                            Slider(value: $quality, in: 90...100, step: 1)
                                .accessibilityLabel("Quality").accessibilityValue("\(Int(quality)) percent")
                                .accessibilityIdentifier("compression-quality").disabled(model.busy || model.createdCopyID != nil)
                            Text("Lower settings may make smaller files. Update the preview and inspect the result before saving.").textRole(.secondary)
                            Picker("Image size", selection: $size) {
                                Text("Original size").tag(ImageSizeProfile.original)
                                Text("2400 px max").tag(ImageSizeProfile.large)
                                Text("1600 px max").tag(ImageSizeProfile.medium)
                                Text("800 px max").tag(ImageSizeProfile.small)
                            }.pickerStyle(.menu).disabled(model.busy || model.createdCopyID != nil).accessibilityIdentifier("compression-size")
                            if size != .original { Text("Resizing removes fine detail and never enlarges the image. Compare the updated preview before saving.").textRole(.secondary) }
                            Button("Update preview") { model.updateCandidateQuality(quality / 100, size: size) }
                                .disabled(model.busy || model.createdCopyID != nil || (size == candidate.size && Int(quality) == Int((candidate.quality * 100).rounded())))
                                .accessibilityIdentifier("compression-update")
                            if model.busy { ProgressView(model.progress); if model.canCancel { Button("Cancel preparation") { model.cancel() } } }
                            Text("Original: \(candidate.assessment.source.width) × \(candidate.assessment.source.height). Output: \(candidate.assessment.output.width) × \(candidate.assessment.output.height). Quality setting: \(candidate.quality.formatted(.number.precision(.fractionLength(2))))").textRole(.secondary).numeric()
                            SevenAdaptiveColumns(accessibilityText: typeSize.isAccessibilitySize) {
                                comparison("Original", url: candidate.sourceURL, bytes: candidate.assessment.source.bytes)
                                comparison("Candidate", url: candidate.outputURL, bytes: candidate.assessment.output.bytes)
                            }
                            if candidate.assessment.issues.isEmpty {
                                Text("The current profile checks passed. Original retained when saving.").textRole(.body)
                            } else { ForEach(candidate.assessment.issues, id: \.self) { CleanerNotice(text: c($0),symbol: "exclamationmark.triangle",caution: true) } }
                            if candidate.size != .original { Text("Detail scores compare the encoded copy with a resized reference. They do not measure the detail discarded by resizing.").textRole(.caption) }
                            DisclosureGroup("Detail comparison") { Text("Full-resolution PSNR: \(candidate.assessment.fullResolutionPSNR.formatted(.number.precision(.fractionLength(1)))) dB\nWorst detail tile: \(candidate.assessment.worstTilePSNR.formatted(.number.precision(.fractionLength(1)))) dB\nThese are diagnostic thresholds, not a perceptual quality certificate.").textRole(.caption) }
                            Button { model.saveCandidate() } label: { Text(model.createdCopyID == nil ? "Save copy and keep original" : "Retry verification").frame(maxWidth: .infinity) }.cleanerPrimary().disabled(model.busy || (model.createdCopyID == nil && (!candidate.assessment.canOfferCopy || size != candidate.size || Int(quality) != Int((candidate.quality * 100).rounded())))).accessibilityIdentifier("compression-save")
                            if model.compressionBatch != nil { Button(model.createdCopyID == nil ? "Skip this photo" : "Leave copy unverified and continue") { model.skipBatchPhoto() }.disabled(model.busy).accessibilityIdentifier("compression-skip") }
                            Text("Saving a copy initially uses more storage. This build does not replace or automatically delete originals.").textRole(.caption)
                        }.padding(Tokens.Space.m).id("compression-top")
                    }.onChange(of: model.error) { _, error in if error != nil { proxy.scrollTo("compression-top", anchor: .top) } }
                    .cleanerCanvas().navigationTitle("Compression review").toolbar { Button(model.compressionBatch == nil ? "Close" : "Stop review") { model.stopCompressionBatch() }.disabled(model.busy) }
                        .onAppear { quality = (candidate.quality * 100).rounded(); size = candidate.size }
                        .onChange(of: candidate.outputURL) { _, _ in quality = (candidate.quality * 100).rounded(); size = candidate.size }
                }
            } else if let queue = model.compressionBatch { CompressionBatchScreen(queue: queue) }
        }.interactiveDismissDisabled(model.busy)
    }
    @ViewBuilder private func comparison(_ title: String, url: URL, bytes: Int64) -> some View {
        VStack(alignment: .leading) {
            Text(c(title)).textRole(.section)
            if let image = UIImage(contentsOfFile: url.path) { ZoomImage(image: image, label: String(format: c("%@ image"), c(title))).frame(height: 280) }
            Text(byteText(bytes)).textRole(.section).numeric()
        }
    }
}
