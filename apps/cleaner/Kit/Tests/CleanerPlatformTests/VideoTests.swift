#if canImport(AVFoundation)
import XCTest
import AVFoundation
@testable import CleanerPlatform

final class VideoTests: XCTestCase {
    func testNativeTrimKeepsAudioFramesMetadataAndOriginal() async throws {
        let source = try XCTUnwrap(Bundle.module.url(forResource: "video-profile", withExtension: "mp4", subdirectory: "Fixtures"))
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { if FileManager.default.fileExists(atPath: directory.path) { try? FileManager.default.removeItem(at: directory) } }
        let original = try Data(contentsOf: source)
        let candidate = try await VideoCompression.makeCandidate(sourceURL: source, directory: directory, start: 1250, end: 4750)
        XCTAssertTrue(candidate.issues.isEmpty, candidate.issues.joined(separator: "; "))
        XCTAssertEqual(candidate.after.facts.width, 720)
        XCTAssertEqual(candidate.after.facts.height, 1280)
        XCTAssertEqual(candidate.after.facts.audioChannels, 2)
        XCTAssertEqual(candidate.after.facts.audioSampleRate, 48000)
        let originalFrames = AVAssetImageGenerator(asset: AVURLAsset(url: source))
        let resultFrames = AVAssetImageGenerator(asset: AVURLAsset(url: candidate.outputURL))
        for generator in [originalFrames,resultFrames] {
            generator.appliesPreferredTrackTransform = true; generator.maximumSize = CGSize(width: 90, height: 160)
            generator.requestedTimeToleranceBefore = CMTime(value: 1, timescale: 20)
            generator.requestedTimeToleranceAfter = CMTime(value: 1, timescale: 20)
        }
        for offset in [0,1700,3300] {
            let a = try await originalFrames.image(at: CMTime(value: Int64(1250 + offset), timescale: 1000)).image
            let b = try await resultFrames.image(at: CMTime(value: Int64(offset), timescale: 1000)).image
            let before = pixels(a), after = pixels(b)
            let difference = zip(before,after).enumerated().filter { $0.offset % 4 != 3 }.reduce(0) { $0 + abs(Int($1.element.0) - Int($1.element.1)) }
            XCTAssertLessThan(Double(difference)/(90*160*3), 18, "Selected upright frame at \(offset) ms")
        }
        let asset = AVURLAsset(url: candidate.outputURL)
        let audio = try await asset.loadTracks(withMediaType: .audio)
        let track = try XCTUnwrap(audio.first)
        let reader = try AVAssetReader(asset: asset)
        let output = AVAssetReaderTrackOutput(track: track, outputSettings: [AVFormatIDKey: kAudioFormatLinearPCM])
        reader.add(output); XCTAssertTrue(reader.startReading())
        var samples = 0, audible = false
        while let sample = output.copyNextSampleBuffer() {
            samples += CMSampleBufferGetNumSamples(sample)
            if let buffer = CMSampleBufferGetDataBuffer(sample) {
                var bytes = [UInt8](repeating: 0, count: CMBlockBufferGetDataLength(buffer))
                let status = bytes.withUnsafeMutableBytes { CMBlockBufferCopyDataBytes(buffer, atOffset: 0, dataLength: $0.count, destination: $0.baseAddress!) }
                XCTAssertEqual(status, noErr); audible = audible || bytes.contains(where: { $0 != 0 })
            }
        }
        XCTAssertEqual(reader.status, .completed); XCTAssertGreaterThan(samples, 100_000); XCTAssertTrue(audible)
        XCTAssertTrue((3400...3600).contains(candidate.after.facts.durationMs))
        XCTAssertLessThan(candidate.after.facts.bytes, candidate.before.facts.bytes)
        XCTAssertEqual(try Data(contentsOf: source), original)
    }
    func testCancellationRemovesTemporaryOutput() async throws {
        let source = try XCTUnwrap(Bundle.module.url(forResource: "video-profile", withExtension: "mp4", subdirectory: "Fixtures"))
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let task = Task { try await VideoCompression.makeCandidate(sourceURL: source, directory: directory, start: 0, end: 6000) }
        try await Task.sleep(for: .milliseconds(100)); task.cancel()
        do { _ = try await task.value; XCTFail("Cancelled export completed") } catch {}
        let files = (try? FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: nil)) ?? []
        XCTAssertTrue(files.filter { $0.pathExtension == "mp4" }.isEmpty)
    }
    private func pixels(_ image: CGImage) -> [UInt8] {
        var bytes = [UInt8](repeating: 0, count: 90*160*4)
        bytes.withUnsafeMutableBytes { buffer in
            let context = CGContext(data: buffer.baseAddress, width: 90, height: 160, bitsPerComponent: 8, bytesPerRow: 90*4,
                space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue)!
            context.draw(image, in: CGRect(x: 0,y: 0,width: 90,height: 160))
        }
        return bytes
    }
}
#endif
