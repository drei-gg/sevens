#if canImport(AVFoundation)
import Foundation
@preconcurrency import AVFoundation
import CleanerCore

public struct VideoInspection: Sendable {
    public var facts: VideoFacts
    public var metadata: [String: String]
}
public struct VideoCandidate: Sendable {
    public var sourceURL: URL
    public var outputURL: URL
    public var before: VideoInspection
    public var after: VideoInspection
    public var plan: VideoCopyPlan
    public var issues: [String]
}

public enum VideoCompression {
    public static func inspect(_ url: URL) async throws -> VideoInspection {
        let asset = AVURLAsset(url: url)
        let tracks = try await asset.load(.tracks)
        let videos = tracks.filter { $0.mediaType == .video }, audio = tracks.filter { $0.mediaType == .audio }
        guard videos.count == 1, audio.count <= 1, tracks.count == videos.count + audio.count else {
            throw CleanerError.blocked("Multiple video/audio or auxiliary tracks need a different preservation profile.")
        }
        let video = videos[0]
        let descriptions = try await video.load(.formatDescriptions)
        guard descriptions.count == 1, let format = descriptions.first,
              CMFormatDescriptionGetMediaSubType(format) == kCMVideoCodecType_H264 else {
            throw CleanerError.blocked("This profile accepts SDR H.264 video only. HEVC and other codecs are not converted.")
        }
        let extensions = (CMFormatDescriptionGetExtensions(format) as NSDictionary?) ?? [:]
        guard extensions[kCMFormatDescriptionExtension_TransferFunction] as? String == kCMFormatDescriptionTransferFunction_ITU_R_709_2 as String,
              extensions[kCMFormatDescriptionExtension_ColorPrimaries] as? String == kCMFormatDescriptionColorPrimaries_ITU_R_709_2 as String else {
            throw CleanerError.blocked("This profile requires identified SDR BT.709 color. HDR and unidentified color are not converted.")
        }
        var parameter: UnsafePointer<UInt8>?, size = 0, count = 0, length: Int32 = 0
        guard CMVideoFormatDescriptionGetH264ParameterSetAtIndex(format, parameterSetIndex: 0,
            parameterSetPointerOut: &parameter, parameterSetSizeOut: &size,
            parameterSetCountOut: &count, nalUnitHeaderLengthOut: &length) == noErr,
              let parameter, size >= 4, [66,77,88,100].contains(Int(parameter[1])) else {
            throw CleanerError.blocked("This profile requires an identified 8-bit H.264 stream.")
        }
        let natural = try await video.load(.naturalSize), transform = try await video.load(.preferredTransform)
        let upright = natural.applying(transform)
        let scale = abs(transform.a * transform.d - transform.b * transform.c)
        guard abs(scale - 1) < 0.001, [transform.a,transform.b,transform.c,transform.d].allSatisfy({ abs($0) <= 1 && abs($0.rounded() - $0) < 0.001 }), transform.a * transform.d - transform.b * transform.c > 0 else {
            throw CleanerError.blocked("Mirrored or scaled video transforms need a different preservation profile.")
        }
        let time = try await video.load(.timeRange), frameRate = try await video.load(.nominalFrameRate)
        guard try regularTiming(asset, video, rate: Double(frameRate)) else { throw CleanerError.blocked("Variable-frame-rate or offset video needs a different timing profile. The original stays.") }
        let segments = try await video.load(.segments)
        let editedTiming = segments.contains { abs($0.timeMapping.source.duration.seconds - $0.timeMapping.target.duration.seconds) > 0.001 }
        var audioCodec: String?, channels: UInt32 = 0, sampleRate: UInt32 = 0, audioDuration: UInt64 = 0
        if let track = audio.first {
            let formats = try await track.load(.formatDescriptions)
            guard formats.count == 1, let description = formats.first,
                  let basic = CMAudioFormatDescriptionGetStreamBasicDescription(description)?.pointee else { throw CleanerError.Unreadable }
            audioCodec = basic.mFormatID == kAudioFormatMPEG4AAC ? "aac" : "unsupported"
            channels = basic.mChannelsPerFrame; sampleRate = UInt32(basic.mSampleRate.rounded())
            audioDuration = milliseconds(try await track.load(.timeRange).duration)
        }
        let bytes = try url.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
        let characteristics = try await video.load(.mediaCharacteristics)
        let facts = VideoFacts(width: UInt32(abs(upright.width).rounded()), height: UInt32(abs(upright.height).rounded()),
            durationMs: milliseconds(time.duration), frameRate: Double(frameRate), bytes: UInt64(bytes), videoCodec: "h264",
            bitDepth: 8, hdr: characteristics.contains(.containsHDRVideo), auxiliaryTracks: editedTiming,
            audioCodec: audioCodec, audioChannels: channels, audioSampleRate: sampleRate, audioDurationMs: audioDuration)
        var metadata: [String:String] = [:]
        for item in try await asset.load(.metadata) {
            guard let identifier = item.identifier?.rawValue,
                  identifier != "itsk/%A9too", // iTunes encoding-tool tag describes the new encoder.
                  !identifier.lowercased().contains("encoder"), !identifier.lowercased().contains("software"),
                  let value = try await item.load(.stringValue) else { continue }
            metadata[identifier] = value
        }
        return VideoInspection(facts: facts, metadata: metadata)
    }

    public static func makeCandidate(sourceURL: URL, directory: URL, start: UInt64, end: UInt64) async throws -> VideoCandidate {
        try Task.checkCancellation()
        let before = try await inspect(sourceURL)
        let plan = try videoCopyPlan(source: before.facts, startMs: start, endMs: end)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let free = try directory.resourceValues(forKeys: [.volumeAvailableCapacityKey]).volumeAvailableCapacity ?? 0
        guard UInt64(max(0,free)) >= plan.requiredFreeBytes else { throw CleanerError.blocked("There is not enough temporary storage for the original, candidate and verification copy.") }
        let asset = AVURLAsset(url: sourceURL)
        guard let session = AVAssetExportSession(asset: asset, presetName: AVAssetExportPreset1280x720) else {
            throw CleanerError.blocked("The native exporter cannot prepare this video profile. The original stays.")
        }
        session.timeRange = CMTimeRange(start: CMTime(value: Int64(start), timescale: 1000), duration: CMTime(value: Int64(end-start), timescale: 1000))
        session.metadata = try await asset.load(.metadata)
        // The exporter owns its scratch directory, never the retained original/candidate folder.
        let scratch = directory.appendingPathComponent("encoder-" + UUID().uuidString)
        try FileManager.default.createDirectory(at: scratch, withIntermediateDirectories: true)
        session.directoryForTemporaryFiles = scratch
        defer { try? FileManager.default.removeItem(at: scratch) }
        let output = directory.appendingPathComponent(UUID().uuidString).appendingPathExtension("mp4")
        do {
            try await session.export(to: output, as: .mp4)
            try Task.checkCancellation()
            let after = try await inspect(output)
            var issues = videoCopyIssues(source: before.facts, output: after.facts, plan: plan)
            for (key,value) in before.metadata where after.metadata[key] != value {
                issues.append("Capture metadata was not preserved: \(key).")
            }
            try await checkFrames(output, duration: after.facts.durationMs)
            try Task.checkCancellation()
            return VideoCandidate(sourceURL: sourceURL, outputURL: output, before: before, after: after, plan: plan, issues: issues)
        } catch { try? FileManager.default.removeItem(at: output); throw error }
    }

    public static func checkFrames(_ url: URL, duration: UInt64) async throws {
        let generator = AVAssetImageGenerator(asset: AVURLAsset(url: url)); generator.appliesPreferredTrackTransform = true
        for time in [UInt64(0),duration/2,duration > 100 ? duration-100 : 0] {
            try Task.checkCancellation()
            _ = try await generator.image(at: CMTime(value: Int64(time), timescale: 1000))
        }
    }
    private static func milliseconds(_ time: CMTime) -> UInt64 {
        let seconds = time.seconds
        return seconds.isFinite && seconds >= 0 && seconds < Double(UInt64.max)/1000 ? UInt64((seconds * 1000).rounded()) : UInt64.max
    }
    private static func regularTiming(_ asset: AVAsset, _ track: AVAssetTrack, rate: Double) throws -> Bool {
        guard rate.isFinite, (1...30.01).contains(rate) else { return false }
        let reader = try AVAssetReader(asset: asset)
        let output = AVAssetReaderTrackOutput(track: track, outputSettings: nil); reader.add(output)
        guard reader.startReading() else { throw CleanerError.blocked("Timing reader could not start: \(reader.error?.localizedDescription ?? "unknown error")") }
        defer { reader.cancelReading() }
        var times: [Double] = []
        while let sample = output.copyNextSampleBuffer() {
            // Compressed reads also deliver decoder-reset/end markers with no image sample.
            // Those may repeat timestamps or carry an invalid CMTime; they are not video frames.
            guard CMSampleBufferGetNumSamples(sample) > 0, CMSampleBufferGetTotalSampleSize(sample) > 0 else { continue }
            let timestamp = CMSampleBufferGetPresentationTimeStamp(sample)
            guard timestamp.isNumeric, timestamp.seconds.isFinite else { return false }
            times.append(timestamp.seconds)
            if times.count > 18_001 { return false }
        }
        guard reader.status == .completed else { throw CleanerError.blocked("Timing reader could not finish: \(reader.error?.localizedDescription ?? "unknown error")") }
        times.sort()
        let period = 1 / rate
        return times.count >= 2 && abs(times[0]) <= 0.1 && zip(times,times.dropFirst()).allSatisfy { abs(($1 - $0) - period) <= max(0.002,period * 0.03) }
    }
}
#endif
