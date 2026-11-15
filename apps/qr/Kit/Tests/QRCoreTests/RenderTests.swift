import Foundation
import Testing
@testable import QRCore

/// Test matrices; the patterns match what generated the golden files.
enum RenderSamples {
    static func matrix(_ rows: String) -> BitMatrix {
        let lines = rows.split(separator: "/").map { Array($0) }
        let w = lines.first?.count ?? 0
        return BitMatrix(width: w, height: lines.count, bits: lines.flatMap { $0.map { $0 == "#" } })
    }

    static let threeByThree = matrix("#.#/.#./#.#")
    static let barcode = matrix("#.##.#...#.#.##")

    /// 21×21: three finder patterns and a timing pattern.
    static var qrLike: BitMatrix {
        var bits = Array(repeating: false, count: 21 * 21)
        for (ox, oy) in [(0, 0), (14, 0), (0, 14)] {
            for y in 0..<7 {
                for x in 0..<7 {
                    let ring = max(abs(x - 3), abs(y - 3))
                    bits[(oy + y) * 21 + ox + x] = ring != 2
                }
            }
        }
        for i in 8...12 where i % 2 == 0 {
            bits[6 * 21 + i] = true
            bits[i * 21 + 6] = true
        }
        return BitMatrix(width: 21, height: 21, bits: bits)
    }

    static func golden(_ name: String) throws -> String {
        let dir = try #require(Fixtures.directory)
        return try String(contentsOf: dir.appendingPathComponent("render/\(name)"), encoding: .utf8)
    }
}

@Suite struct RenderSVGWriterTests {
    @Test(arguments: [
        ("3x3", 2), ("qr21", 4), ("bar1d", 1),
    ])
    func goldenFiles(name: String, scale: Int) throws {
        let m: BitMatrix = switch name {
        case "3x3": RenderSamples.threeByThree
        case "qr21": RenderSamples.qrLike
        default: RenderSamples.barcode
        }
        let plain = SVGWriter.svg(m, moduleSize: scale, foreground: .black, background: nil)
        #expect(plain == (try RenderSamples.golden("\(name).svg")))
        let bg = SVGWriter.svg(m, moduleSize: scale, foreground: .black, background: .white)
        #expect(bg == (try RenderSamples.golden("\(name)-bg.svg")))
    }

    @Test func emptyMatrixHasNoPath() {
        let svg = SVGWriter.svg(BitMatrix(width: 0, height: 0), moduleSize: 4, foreground: .black, background: nil)
        #expect(svg.hasPrefix("<svg "))
        #expect(!svg.contains("<path"))
        #expect(svg.hasSuffix("</svg>\n"))
    }

    @Test func allLightMatrixHasNoPath() {
        let svg = SVGWriter.svg(BitMatrix(width: 3, height: 3), moduleSize: 1, foreground: .black, background: nil)
        #expect(!svg.contains("<path"))
    }

    @Test func moduleSizeBelowOneCountsAsOne() {
        let a = SVGWriter.svg(RenderSamples.threeByThree, moduleSize: 0, foreground: .black, background: nil)
        let b = SVGWriter.svg(RenderSamples.threeByThree, moduleSize: 1, foreground: .black, background: nil)
        #expect(a == b)
    }

    @Test func exactlyOnePathAndHexColors() {
        let fg = RGBColor(red: 1, green: 0, blue: 0)
        let svg = SVGWriter.svg(RenderSamples.qrLike, moduleSize: 3, foreground: fg, background: .white)
        #expect(svg.components(separatedBy: "<path").count == 2)
        #expect(svg.contains("fill=\"#ff0000\""))
        #expect(svg.contains("viewBox=\"0 0 63 63\""))
    }

    @Test func deterministic() {
        let a = SVGWriter.svg(RenderSamples.qrLike, moduleSize: 2, foreground: .black, background: nil)
        let b = SVGWriter.svg(RenderSamples.qrLike, moduleSize: 2, foreground: .black, background: nil)
        #expect(a == b)
    }
}

@Suite struct RenderColorTests {
    @Test func hexClampsAndRounds() {
        #expect(RGBColor.black.hex == "#000000")
        #expect(RGBColor.white.hex == "#ffffff")
        #expect(RGBColor(red: -1, green: 2, blue: 0.5).hex == "#00ff80")
        #expect(RGBColor(red: .nan, green: .infinity, blue: 0.2).hex == "#00ff33")
        #expect(RGBColor(red: 10.0 / 255, green: 171.0 / 255, blue: 1.0 / 255).hex == "#0aab01")
    }
}

@Suite struct RenderContrastTests {
    @Test func extremes() {
        #expect(abs(Contrast.ratio(.black, .white) - 21) < 1e-9)
        #expect(abs(Contrast.ratio(.white, .black) - 21) < 1e-9)
        #expect(abs(Contrast.ratio(.white, .white) - 1) < 1e-9)
    }

    @Test func knownGrey() {
        // #777777 on white is the classic 4.48:1.
        let g = RGBColor(red: 0x77 / 255.0, green: 0x77 / 255.0, blue: 0x77 / 255.0)
        #expect(abs(Contrast.ratio(g, .white) - 4.48) < 0.01)
    }

    @Test func blackOnWhiteIsFine() {
        #expect(Contrast.warnings(foreground: .black, background: .white).isEmpty)
    }

    @Test func lowContrastWarns() {
        let g = RGBColor(red: 0.6, green: 0.6, blue: 0.6)
        let w = Contrast.warnings(foreground: g, background: .white)
        #expect(w.count == 1)
        if case .lowContrast(let r)? = w.first { #expect(r < 4.0) } else { Issue.record("expected lowContrast") }
    }

    @Test func invertedWarnsAlone() {
        #expect(Contrast.warnings(foreground: .white, background: .black) == [.inverted])
    }

    @Test func bothApplyLowContrastFirst() {
        let light = RGBColor(red: 0.9, green: 0.9, blue: 0.9)
        let w = Contrast.warnings(foreground: .white, background: light)
        #expect(w.count == 2)
        if case .lowContrast? = w.first {} else { Issue.record("lowContrast should come first") }
        #expect(w.last == .inverted)
    }
}

@Suite struct RenderQuietZoneTests {
    @Test func moduleCounts() {
        #expect(QuietZone.modules(for: .qr) == (4, 4))
        #expect(QuietZone.modules(for: .microQR) == (2, 2))
        #expect(QuietZone.modules(for: .aztec) == (1, 1))
        #expect(QuietZone.modules(for: .pdf417) == (2, 2))
        #expect(QuietZone.modules(for: .microPDF417) == (2, 2))
        #expect(QuietZone.modules(for: .dataMatrix) == (1, 1))
        #expect(QuietZone.modules(for: .code128) == (10, 0))
        #expect(QuietZone.modules(for: .ean13) == (10, 0))
        #expect(QuietZone.modules(for: .other) == (4, 4))
    }

    @Test func everySymbologyCovered() {
        for s in Symbology.allCases {
            let q = QuietZone.modules(for: s)
            #expect(q.horizontal >= 0 && q.vertical >= 0)
        }
    }

    @Test func addPadsWithLightModules() {
        let m = RenderSamples.threeByThree
        let p = QuietZone.add(to: m, horizontal: 2, vertical: 1)
        #expect(p.width == 7 && p.height == 5)
        for y in 0..<5 {
            for x in 0..<7 {
                let inside = x >= 2 && x < 5 && y >= 1 && y < 4
                #expect(p[x, y] == (inside ? m[x - 2, y - 1] : false))
            }
        }
    }

    @Test func zeroAndNegativeAndEmpty() {
        let m = RenderSamples.threeByThree
        #expect(QuietZone.add(to: m, horizontal: 0, vertical: 0) == m)
        #expect(QuietZone.add(to: m, horizontal: -3, vertical: -1) == m)
        let e = BitMatrix(width: 0, height: 0)
        #expect(QuietZone.add(to: e, horizontal: 4, vertical: 4) == e)
    }
}

@Suite struct RenderSheetLayoutTests {
    @Test func paperSizes() {
        #expect(PaperSize.a4.size.width == 595.28 && PaperSize.a4.size.height == 841.89)
        #expect(PaperSize.letter.size.width == 612 && PaperSize.letter.size.height == 792)
    }

    @Test func regions() {
        for r in ["US", "CA", "MX", "PH", "CL", "CO", "VE", "CR", "GT", "PA", "PR", "SV", "NI", "BZ", "us"] {
            #expect(PaperSize.forRegion(r) == .letter, "\(r)")
        }
        for r in ["GB", "DE", "FR", "JP", "AU", "ZZ", ""] as [String?] + [nil] {
            #expect(PaperSize.forRegion(r) == .a4, "\(r ?? "nil")")
        }
    }

    @Test func gridDimensions() {
        #expect(SheetGrid.grid2x3.columns == 2 && SheetGrid.grid2x3.rows == 3)
        #expect(SheetGrid.grid3x4.columns == 3 && SheetGrid.grid3x4.rows == 4)
        #expect(SheetGrid.grid4x6.columns == 4 && SheetGrid.grid4x6.rows == 6)
    }

    @Test(arguments: SheetGrid.allCases, PaperSize.allCases)
    func layoutInvariants(grid: SheetGrid, paper: PaperSize) {
        let cells = SheetLayout.cells(grid: grid, paper: paper)
        let page = paper.size
        let eps = 1e-9
        #expect(cells.count == grid.columns * grid.rows)
        guard let first = cells.first else { return }
        for c in cells {
            #expect(abs(c.width - first.width) < eps && abs(c.height - first.height) < eps)
            #expect(c.width > 0 && c.height > 0)
            #expect(c.x >= 36 - eps && c.y >= 36 - eps)
            #expect(c.x + c.width <= page.width - 36 + eps)
            #expect(c.y + c.height <= page.height - 36 + eps)
        }
        for i in cells.indices {
            for j in cells.indices where j > i {
                let a = cells[i], b = cells[j]
                let overlap = a.x < b.x + b.width - eps && b.x < a.x + a.width - eps
                    && a.y < b.y + b.height - eps && b.y < a.y + a.height - eps
                #expect(!overlap)
            }
        }
        // Order: left to right, top to bottom; 12 pt gutters; fills the margins exactly.
        for (i, c) in cells.enumerated() {
            let col = i % grid.columns, row = i / grid.columns
            #expect(abs(c.x - (36 + Double(col) * (first.width + 12))) < eps)
            #expect(abs(c.y - (36 + Double(row) * (first.height + 12))) < eps)
        }
        if let last = cells.last {
            #expect(abs(last.x + last.width - (page.width - 36)) < 1e-6)
            #expect(abs(last.y + last.height - (page.height - 36)) < 1e-6)
        }
    }
}
