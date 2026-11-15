import SwiftUI
import VaultCore

struct CredentialInput: View {
    let kind: VaultCredentialKind
    @Binding var value: String
    let label: String
    let identifier: String
    var body: some View {
        if kind == .pattern { PatternPad(value: $value, label: label, identifier: identifier) }
        else {
            SecureField(label, text: $value).textInputAutocapitalization(.never).autocorrectionDisabled()
                .keyboardType(kind == .pin ? .numberPad : .default).accessibilityIdentifier(identifier)
        }
    }
}
struct PatternPad: View {
    @Binding var value: String
    let label: String
    let identifier: String
    @State private var drawing = false
    private var path: [Int] { PatternPath.decode(value) ?? [] }
    var body: some View {
        VStack(alignment: .leading) {
            HStack { Text(label); Spacer(); Button("Clear") { value = "" }.accessibilityIdentifier(identifier + ".clear") }
            GeometryReader { geometry in
                let step = geometry.size.width / 5
                ZStack {
                    Path { line in
                        for (i, dot) in path.enumerated() {
                            let point = CGPoint(x: (CGFloat(dot % 5) + 0.5) * step, y: (CGFloat(dot / 5) + 0.5) * step)
                            if i == 0 { line.move(to: point) } else { line.addLine(to: point) }
                        }
                    }.stroke(.tint.opacity(0.55), style: StrokeStyle(lineWidth: 4, lineCap: .round, lineJoin: .round)).accessibilityHidden(true)
                    ForEach(0..<25, id: \.self) { dot in
                        Button { append(dot) } label: {
                            Circle().fill(path.contains(dot) ? Color.accentColor : Color.secondary.opacity(0.35)).frame(width: 15, height: 15)
                                .frame(width: step, height: step).contentShape(Rectangle())
                        }.buttonStyle(.plain).position(x: (CGFloat(dot % 5) + 0.5) * step, y: (CGFloat(dot / 5) + 0.5) * step)
                            .accessibilityLabel("Row \(dot / 5 + 1), column \(dot % 5 + 1)")
                            .accessibilityIdentifier(identifier + ".dot.\(dot)")
                    }
                }.contentShape(Rectangle()).highPriorityGesture(DragGesture(minimumDistance: 4).onChanged { gesture in
                    if !drawing { value = ""; drawing = true; hit(gesture.startLocation, step: step) }
                    hit(gesture.location, step: step)
                }.onEnded { _ in drawing = false })
            }.aspectRatio(1, contentMode: .fit)
            Text("\(path.count) dots · draw a path or tap dots in order").font(.caption).foregroundStyle(.secondary)
        }
    }
    private func append(_ dot: Int) { var result = path; PatternPath.append(dot, to: &result); value = PatternPath.encode(result) }
    private func hit(_ point: CGPoint, step: CGFloat) {
        guard point.x >= 0, point.y >= 0, point.x < step * 5, point.y < step * 5 else { return }
        let x = Int(point.x / step), y = Int(point.y / step)
        let dx = point.x - (CGFloat(x) + 0.5) * step, dy = point.y - (CGFloat(y) + 0.5) * step
        if dx * dx + dy * dy <= pow(step * 0.42, 2) { append(y * 5 + x) }
    }
}
struct CredentialKindPicker: View {
    @Binding var kind: VaultCredentialKind
    var body: some View {
        Picker("Unlock method", selection: $kind) { ForEach(VaultCredentialKind.allCases, id: \.self) { Text($0.title).tag($0) } }.pickerStyle(.segmented)
    }
}
