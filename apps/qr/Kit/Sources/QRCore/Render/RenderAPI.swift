// WP 1.7 owns this file (and may split it). The signatures below are what QRUI (WP 2.3, 3.3)
// calls; keep them.

/// sRGB, components 0…1.
public struct RGBColor: Sendable, Hashable {
    public var red: Double, green: Double, blue: Double
    public init(red: Double, green: Double, blue: Double) {
        self.red = red
        self.green = green
        self.blue = blue
    }
    public static let black = RGBColor(red: 0, green: 0, blue: 0)
    public static let white = RGBColor(red: 1, green: 1, blue: 1)
    /// "#rrggbb", lowercase.
    public var hex: String { fatalError("WP 1.7") }
}

public enum QuietZone {
    /// PLAN §6.8: QR 4 all round, Aztec 1, PDF417 2, Code 128 10 left and right (0 top and bottom).
    public static func modules(for symbology: Symbology) -> (horizontal: Int, vertical: Int) { fatalError("WP 1.7") }
    public static func add(to matrix: BitMatrix, horizontal: Int, vertical: Int) -> BitMatrix { fatalError("WP 1.7") }
}

public enum SVGWriter {
    /// One `<path>` of horizontal runs, `shape-rendering="crispEdges"`, colors as hex.
    /// `moduleSize` is in SVG user units; `background` nil leaves it transparent.
    public static func svg(_ matrix: BitMatrix, moduleSize: Int, foreground: RGBColor, background: RGBColor?) -> String {
        fatalError("WP 1.7")
    }
}

public enum ContrastWarning: Sendable, Hashable {
    /// WCAG ratio under 4.0.
    case lowContrast(ratio: Double)
    /// Foreground lighter than background.
    case inverted
}

public enum Contrast {
    /// WCAG 2 contrast ratio, 1…21.
    public static func ratio(_ a: RGBColor, _ b: RGBColor) -> Double { fatalError("WP 1.7") }
    /// Warnings in display order; empty when fine. Warns, never blocks.
    public static func warnings(foreground: RGBColor, background: RGBColor) -> [ContrastWarning] { fatalError("WP 1.7") }
}

/// D14: generic label grids on A4 or US Letter.
public enum PaperSize: String, Sendable, CaseIterable {
    case a4, letter
    /// Points (1/72 in).
    public var size: (width: Double, height: Double) { fatalError("WP 1.7") }
    /// US Letter for regions that use it (US, CA, MX, PH, …), A4 otherwise.
    public static func forRegion(_ regionCode: String?) -> PaperSize { fatalError("WP 1.7") }
}

public enum SheetGrid: String, Sendable, CaseIterable {
    case grid2x3, grid3x4, grid4x6
    public var columns: Int { fatalError("WP 1.7") }
    public var rows: Int { fatalError("WP 1.7") }
}

public struct SheetCell: Sendable, Hashable {
    /// Points from the top-left of the page.
    public var x: Double, y: Double, width: Double, height: Double
    public init(x: Double, y: Double, width: Double, height: Double) {
        self.x = x
        self.y = y
        self.width = width
        self.height = height
    }
}

public enum SheetLayout {
    /// Cells left to right, top to bottom, inside fixed page margins.
    public static func cells(grid: SheetGrid, paper: PaperSize) -> [SheetCell] { fatalError("WP 1.7") }
}
