import Foundation

public enum SVGWriter {
    /// One `<path>` of horizontal runs, `shape-rendering="crispEdges"`, colors as hex.
    /// `moduleSize` is in SVG user units (values under 1 count as 1); `background` nil leaves it transparent.
    /// The caller adds the quiet zone first. Output is deterministic and ends with a newline.
    ///
    /// Format: `<svg xmlns viewBox="0 0 W H" width="W" height="H" shape-rendering="crispEdges">`, then
    /// optionally `<rect width="100%" height="100%" fill="#bg"/>`, then (if any module is dark) exactly one
    /// `<path fill="#fg" d="…"/>`. For every row top to bottom, every maximal run of dark modules
    /// left to right becomes `M{x} {y}h{len}v{s}h-{len}z`, all in user units (module coordinate × s),
    /// run segments concatenated with no separators. Integers only.
    public static func svg(_ matrix: BitMatrix, moduleSize: Int, foreground: RGBColor, background: RGBColor?) -> String {
        let s = max(1, moduleSize)
        let w = matrix.width * s, h = matrix.height * s
        var out = "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 \(w) \(h)\" width=\"\(w)\" height=\"\(h)\" shape-rendering=\"crispEdges\">\n"
        if let background {
            out += "<rect width=\"100%\" height=\"100%\" fill=\"\(background.hex)\"/>\n"
        }
        var d = ""
        for y in 0..<matrix.height {
            var x = 0
            while x < matrix.width {
                guard matrix[x, y] else { x += 1; continue }
                var end = x + 1
                while end < matrix.width, matrix[end, y] { end += 1 }
                let len = (end - x) * s
                d += "M\(x * s) \(y * s)h\(len)v\(s)h-\(len)z"
                x = end
            }
        }
        if !d.isEmpty {
            out += "<path fill=\"\(foreground.hex)\" d=\"\(d)\"/>\n"
        }
        out += "</svg>\n"
        return out
    }
}
