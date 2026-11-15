import Foundation
import QRCore
import Vision

/// The one place that turns Vision's barcode results into the contract's `ScannedCode`.
/// Used by `ScannerView` (live camera) and `ImageScanner` (photos, paste, extensions).
enum ScanDecoding {
    /// Every `VNBarcodeSymbology` Vision knows mapped to a `Symbology`; anything else is `.other`.
    /// Variants of one family collapse to it (Code 39 with checksum or full ASCII -> `.code39`,
    /// GS1 DataBar Expanded/Limited -> `.gs1DataBar`, Code 93i -> `.code93`). Interleaved 2 of 5
    /// and MSI Plessey have no case of their own and are `.other`.
    static func symbology(_ s: VNBarcodeSymbology) -> Symbology {
        switch s {
        case .qr: .qr
        case .microQR: .microQR
        case .aztec: .aztec
        case .pdf417: .pdf417
        case .microPDF417: .microPDF417
        case .dataMatrix: .dataMatrix
        case .code128: .code128
        case .code39, .code39Checksum, .code39FullASCII, .code39FullASCIIChecksum: .code39
        case .code93, .code93i: .code93
        case .ean13: .ean13
        case .ean8: .ean8
        case .upce: .upce
        case .itf14: .itf14
        case .codabar: .codabar
        case .gs1DataBar, .gs1DataBarExpanded, .gs1DataBarLimited: .gs1DataBar
        default: .other
        }
    }

    /// Longest payload we keep, in bytes. Real codes top out near 3 KB; this bounds hostile input.
    static let maxPayloadBytes = 16 * 1024

    /// Builds a `ScannedCode`. `bytes` come from the raw payload when Vision has it, else from the
    /// text's UTF-8. `text` is the payload string, or the raw bytes decoded as strict UTF-8 when
    /// there is no string, or nil (binary payload). Latin-1 is not tried: it accepts every byte
    /// sequence, so it would turn binary payloads into mojibake text.
    static func code(payloadString: String?, payloadData: Data?, symbology: VNBarcodeSymbology) -> ScannedCode {
        var text = payloadString
        var bytes: [UInt8]
        if let payloadData, !payloadData.isEmpty {
            bytes = Array(payloadData.prefix(maxPayloadBytes))
            if text == nil { text = String(data: Data(bytes), encoding: .utf8) }
        } else if let text {
            bytes = Array(text.utf8.prefix(maxPayloadBytes))
        } else {
            bytes = []
        }
        if let t = text, t.utf8.count > maxPayloadBytes { text = nil }
        return ScannedCode(text: text, bytes: bytes, symbology: Self.symbology(symbology))
    }

    static func code(_ observation: VNBarcodeObservation) -> ScannedCode {
        code(payloadString: observation.payloadStringValue,
             payloadData: observation.payloadData,
             symbology: observation.symbology)
    }
}
