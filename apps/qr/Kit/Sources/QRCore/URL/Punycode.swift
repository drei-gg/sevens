// WP 1.1 owns this file.

/// RFC 3492 for single labels (without the "xn--" prefix).
public enum Punycode {
    private static let base = 36, tMin = 1, tMax = 26, skew = 38, damp = 700
    private static let initialBias = 72, initialN = 128

    private static func adapt(_ deltaIn: Int, _ numPoints: Int, _ first: Bool) -> Int {
        var delta = first ? deltaIn / damp : deltaIn / 2
        delta += delta / numPoints
        var k = 0
        while delta > ((base - tMin) * tMax) / 2 {
            delta /= base - tMin
            k += base
        }
        return k + (base - tMin + 1) * delta / (delta + skew)
    }

    private static func digit(_ d: Int) -> Character {
        Character(Unicode.Scalar(UInt8(d < 26 ? 97 + d : 22 + d)))
    }

    /// Encodes a label's Unicode text. All-ASCII input gives `text-`, per the RFC.
    /// nil for empty input or arithmetic overflow.
    public static func encode(_ label: String) -> String? {
        let input = label.unicodeScalars.map { Int($0.value) }
        guard !input.isEmpty, input.count <= 4096 else { return nil }
        var output = ""
        for c in input where c < 128 { output.unicodeScalars.append(Unicode.Scalar(UInt8(c))) }
        let b = output.unicodeScalars.count
        var h = b
        if b > 0 { output.append("-") }
        var n = initialN, delta = 0, bias = initialBias
        while h < input.count {
            guard let m = input.filter({ $0 >= n }).min() else { return nil }
            let (mul, o1) = (m - n).multipliedReportingOverflow(by: h + 1)
            let (sum, o2) = delta.addingReportingOverflow(mul)
            if o1 || o2 { return nil }
            delta = sum
            n = m
            for c in input {
                if c < n { delta += 1 }
                if c == n {
                    var q = delta
                    var k = base
                    while true {
                        let t = k <= bias ? tMin : (k >= bias + tMax ? tMax : k - bias)
                        if q < t { break }
                        output.append(digit(t + (q - t) % (base - t)))
                        q = (q - t) / (base - t)
                        k += base
                    }
                    output.append(digit(q))
                    bias = adapt(delta, h + 1, h == b)
                    delta = 0
                    h += 1
                }
            }
            delta += 1
            n += 1
        }
        return output
    }

    /// Decodes the text after `xn--`. nil when it isn't valid Punycode (or is empty).
    public static func decode(_ label: String) -> String? {
        let bytes = Array(label.utf8)
        guard !bytes.isEmpty, bytes.count <= 4096, bytes.allSatisfy({ $0 < 128 }) else { return nil }
        var output: [Int] = []
        var pos = 0
        if let d = bytes.lastIndex(of: UInt8(ascii: "-")) {
            output = bytes[0..<d].map { Int($0) }
            pos = d + 1
        }
        var n = initialN, i = 0, bias = initialBias
        while pos < bytes.count {
            let oldI = i
            var w = 1
            var k = base
            while true {
                guard pos < bytes.count else { return nil }
                let c = bytes[pos]
                pos += 1
                let d: Int
                switch c {
                case 48...57: d = Int(c) - 22
                case 65...90: d = Int(c) - 65
                case 97...122: d = Int(c) - 97
                default: return nil
                }
                let (dw, o1) = d.multipliedReportingOverflow(by: w)
                let (ni, o2) = i.addingReportingOverflow(dw)
                if o1 || o2 { return nil }
                i = ni
                let t = k <= bias ? tMin : (k >= bias + tMax ? tMax : k - bias)
                if d < t { break }
                let (nw, o3) = w.multipliedReportingOverflow(by: base - t)
                if o3 { return nil }
                w = nw
                k += base
            }
            let count = output.count + 1
            bias = adapt(i - oldI, count, oldI == 0)
            n += i / count
            i %= count
            guard n <= 0x10FFFF, Unicode.Scalar(UInt32(n)) != nil else { return nil }
            output.insert(n, at: i)
            i += 1
            if output.count > 4096 { return nil }
        }
        guard !output.isEmpty else { return nil }
        var result = ""
        for v in output {
            guard let s = Unicode.Scalar(UInt32(v)) else { return nil }
            result.unicodeScalars.append(s)
        }
        return result
    }
}
