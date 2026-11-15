import Foundation
import QRCore
import Testing
import Vision
@testable import QRUI

@Suite struct ScanSymbologyTests {
    @Test func scanSymbologyMapping() {
        let expected: [(VNBarcodeSymbology, Symbology)] = [
            (.qr, .qr), (.microQR, .microQr), (.aztec, .aztec), (.pdf417, .pdf417),
            (.microPDF417, .microPdf417), (.dataMatrix, .dataMatrix), (.code128, .code128),
            (.code39, .code39), (.code39Checksum, .code39), (.code39FullASCII, .code39),
            (.code39FullASCIIChecksum, .code39), (.code93, .code93), (.code93i, .code93),
            (.ean13, .ean13), (.ean8, .ean8), (.upce, .upce), (.itf14, .itf14),
            (.codabar, .codabar), (.gs1DataBar, .gs1DataBar), (.gs1DataBarExpanded, .gs1DataBar),
            (.gs1DataBarLimited, .gs1DataBar), (.i2of5, .other), (.i2of5Checksum, .other),
            (.msiPlessey, .other),
        ]
        for (vision, ours) in expected {
            #expect(ScanDecoding.symbology(vision) == ours, "\(vision.rawValue)")
        }
        #expect(ScanDecoding.symbology(VNBarcodeSymbology(rawValue: "VNBarcodeSymbologyFuture")) == .other)
    }

    @Test func scanCodeFromStringAndData() {
        let a = ScanDecoding.code(payloadString: "héllo", payloadData: Data("héllo".utf8), symbology: .qr)
        #expect(a == ScannedCode(text: "héllo", bytes: Array("héllo".utf8), symbology: .qr))
        // Raw bytes win over the string's UTF-8 when Vision has them.
        let b = ScanDecoding.code(payloadString: "h\u{e9}", payloadData: Data([0x68, 0xE9]), symbology: .pdf417)
        #expect(b.text == "h\u{e9}" && b.bytes == Data([0x68, 0xE9]) && b.symbology == .pdf417)
        // No data: UTF-8 of the text.
        let c = ScanDecoding.code(payloadString: "abc", payloadData: nil, symbology: .code128)
        #expect(c.bytes == Data([0x61, 0x62, 0x63]))
    }

    @Test func scanCodeWithoutStringDecodesUTF8OrIsNil() {
        let ok = ScanDecoding.code(payloadString: nil, payloadData: Data("plain".utf8), symbology: .qr)
        #expect(ok.text == "plain")
        let binary = ScanDecoding.code(payloadString: nil, payloadData: Data([0xFF, 0xFE, 0x00, 0x80]), symbology: .dataMatrix)
        #expect(binary.text == nil && binary.bytes == Data([0xFF, 0xFE, 0x00, 0x80]))
        let empty = ScanDecoding.code(payloadString: nil, payloadData: nil, symbology: .qr)
        #expect(empty.text == nil && empty.bytes.isEmpty)
    }

    @Test func scanCodeCapsHugePayloads() {
        let big = Data(repeating: 0x41, count: ScanDecoding.maxPayloadBytes * 2)
        let code = ScanDecoding.code(payloadString: String(decoding: big, as: UTF8.self), payloadData: big, symbology: .qr)
        #expect(code.bytes.count == ScanDecoding.maxPayloadBytes)
        #expect(code.text == nil)
    }
}
