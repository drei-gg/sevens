import SwiftUI
import ScannerCore
import SevenKit

extension AnnotationStyle {
    static var choices: [AnnotationStyle] { [.highlight, .redOutline, .blueOutline] }
    var title: String { switch self { case .highlight: "Highlight"; case .redOutline: "Red box"; case .blueOutline: "Blue box" } }
    var ink: Color { switch self {
    case .highlight: Color(red: 1, green: 208.0/255, blue: 0).opacity(82.0/255)
    case .redOutline: Color(red: 200.0/255, green: 35.0/255, blue: 35.0/255)
    case .blueOutline: Color(red: 30.0/255, green: 80.0/255, blue: 210.0/255)
    } }
}

/// Rectangular marks use native Canvas; original-space geometry and validation come from Rust.
struct AnnotationEditor: View {
    @Environment(ScannerModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let documentID: UUID
    let original: ScanPage
    let url: URL
    let saved: (ScanPage) -> Void
    @State private var marks: [PageAnnotation]
    private struct Snapshot { var marks: [PageAnnotation]; var ink: [PageInk] }
    @State private var ink: [PageInk]
    @State private var inkMode = false
    @State private var inkColor = InkColor.black
    @State private var inkWidth = 4
    @State private var inking = false
    @State private var undo: [Snapshot] = []
    @State private var redo: [Snapshot] = []
    @State private var style = AnnotationStyle.highlight
    @State private var image: UIImage?
    @State private var exact = false
    @State private var discard = false
    @State private var working = false
    @State private var failure: String?
    init(documentID: UUID, original: ScanPage, url: URL, saved: @escaping (ScanPage) -> Void) {
        self.documentID = documentID; self.original = original; self.url = url; self.saved = saved
        _marks = State(initialValue: original.annotations)
        _ink = State(initialValue: original.ink)
    }
    private var dirty: Bool { marks != original.annotations || ink != original.ink }
    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: Tokens.Space.m) {
                    Text("Choose Ink to draw, or Rectangle for a highlight or box. Visual marks keep the original and searchable text; they do not redact information.").textRole(.secondary)
                    Picker("Tool", selection: $inkMode) { Text("Rectangle").tag(false); Text("Ink").tag(true) }.pickerStyle(.segmented).accessibilityIdentifier("ink-tool")
                    if inkMode {
                        Picker("Ink color", selection: $inkColor) { ForEach(InkColor.choices, id: \.self) { color in Text(color.title).tag(color) } }.accessibilityIdentifier("ink-color")
                        Picker("Width", selection: $inkWidth) { Text("Fine").tag(2); Text("Medium").tag(4); Text("Bold").tag(8) }.pickerStyle(.segmented)
                        Text("Draw with a finger or Apple Pencil. Undo removes the last change; ink does not erase or redact the page.").textRole(.caption)
                    } else { Picker("Mark", selection: $style) {
                        ForEach(AnnotationStyle.choices, id: \.self) { Text($0.title).tag($0) }
                    }.pickerStyle(.menu).accessibilityIdentifier("annotations-style") }
                    if let image { AnnotationCanvas(image: image, settings: original.settings, marks: marks, ink: ink, inkMode: inkMode, inkColor: inkColor, inkWidth: CGFloat(inkWidth), inking: $inking, addInk: addInk, inkFailure: { failure = $0 }, add: add).frame(height: 320) }
                    Text("\(marks.count) of 100 marks").accessibilityIdentifier("annotations-count")
                    Text("\(ink.count) of 100 ink strokes").accessibilityIdentifier("ink-count")
                    AdaptiveStack(spacing: Tokens.Space.s) {
                        Button("Undo") { if let value = undo.popLast() { redo.append(snapshot); restore(value) } }.disabled(undo.isEmpty).accessibilityIdentifier("annotations-undo")
                        Button("Redo") { if let value = redo.popLast() { undo.append(snapshot); restore(value) } }.disabled(redo.isEmpty).accessibilityIdentifier("annotations-redo")
                        Button("Add with bounds") { exact = true }.disabled(marks.count >= 100 || image == nil).accessibilityIdentifier("annotations-exact")
                    }.buttonStyle(.bordered)
                    if !ink.isEmpty {
                        Button("Remove last stroke") { change(marks, ink: Array(ink.dropLast())) }.accessibilityIdentifier("ink-remove")
                        Button("Clear ink") { change(marks, ink: []) }.accessibilityIdentifier("ink-clear")
                    }
                    if !marks.isEmpty {
                        Button("Remove last mark") { change(Array(marks.dropLast())) }.accessibilityIdentifier("annotations-remove")
                        Button("Clear all marks") { change([]) }.accessibilityIdentifier("annotations-clear")
                    }
                    Text("Marks follow the original page through crop and rotation. Cropping hides marks outside the visible area; resetting the crop brings them back.").textRole(.caption)
                    if let failure { Text(failure).foregroundStyle(Tokens.danger).accessibilityIdentifier("annotations-error") }
                }.padding()
            }.sevenScreenBackground().disabled(working)
            .navigationTitle("Annotate").navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Close") { if dirty || inking { discard = true } else { dismiss() } }.disabled(working) }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") { Task { await save() } }.disabled(working || !dirty || image == nil || inking).accessibilityIdentifier("annotations-save")
                }
            }
            .alert("Marks not saved", isPresented: Binding(get: { failure != nil }, set: { if !$0 { failure = nil } })) {
                Button("Keep editing", role: .cancel) { failure = nil }
            } message: { Text(failure ?? "Your earlier marks are kept.") }
            .confirmationDialog("Discard annotation changes?", isPresented: $discard) { Button("Discard", role: .destructive) { dismiss() } }
            .sheet(isPresented: $exact) { AnnotationBounds { add($0); exact = false } }
            .task {
                do {
                    let settings = original.settings
                    let bytes = try await Task.detached { try DocumentProcessing.rendered(Data(contentsOf: url), settings: settings, maxPixels: 1600) }.value
                    try Task.checkCancellation(); image = UIImage(data: bytes)
                } catch is CancellationError {} catch { failure = error.localizedDescription }
            }
        }.interactiveDismissDisabled(dirty || working || inking)
    }
    private var snapshot: Snapshot { Snapshot(marks: marks, ink: ink) }
    private func restore(_ value: Snapshot) { marks = value.marks; ink = value.ink }
    private func change(_ next: [PageAnnotation], ink nextInk: [PageInk]? = nil) {
        undo.append(snapshot); if undo.count > 100 { undo.removeFirst() }; redo.removeAll(); marks = next
        if let nextInk { ink = nextInk }
    }
    private func addInk(_ stroke: PageInk) -> Bool {
        do { try inkValidate(ink: ink + [stroke]); change(marks, ink: ink + [stroke]); return true }
        catch { failure = "This stroke exceeds the page's ink limit. Your previous marks are kept. Undo or clear ink to make room."; return false }
    }
    private func add(_ bounds: PageCrop) {
        guard marks.count < 100 else { failure = "This page already has 100 marks. Undo or remove a mark first."; return }
        do { change(marks + [try annotationFromDisplay(bounds: bounds, settings: original.settings, style: style)]) }
        catch { failure = error.localizedDescription }
    }
    private func save() async {
        guard !working else { return }; working = true; defer { working = false }
        do {
            let updated = try await model.repository.updateAnnotations(documentID: documentID, expected: original, annotations: marks, ink: ink)
            await model.reload()
            guard let page = updated.pages.first(where: { $0.id == original.id }) else { throw ScanError.MissingPage }
            saved(page); dismiss()
        } catch { failure = error.localizedDescription + " Your marks are kept here. If the page changed, close and reopen this editor." }
    }
}

private struct AnnotationCanvas: View {
    let image: UIImage
    let settings: PageSettings
    let marks: [PageAnnotation]
    let ink: [PageInk]
    let inkMode: Bool
    let inkColor: InkColor
    let inkWidth: CGFloat
    @Binding var inking: Bool
    let addInk: (PageInk) -> Bool
    let inkFailure: (String) -> Void
    let add: (PageCrop) -> Void
    @State private var drag: PageCrop?
    var body: some View {
        GeometryReader { proxy in
            let ratio = image.size.width / image.size.height
            let width = min(proxy.size.width, proxy.size.height * ratio)
            let height = width / ratio
            ZStack {
                Image(uiImage: image).resizable()
                Canvas { context, size in
                    context.clip(to: Path(CGRect(origin: .zero, size: size)))
                    for mark in marks {
                        if let r = try? annotationDisplayBounds(bounds: mark.bounds, settings: settings) {
                            let box = Path(CGRect(x: r.x * size.width, y: r.y * size.height, width: r.width * size.width, height: r.height * size.height))
                            if mark.style == .highlight { context.fill(box, with: .color(mark.style.ink)) }
                            else { context.stroke(box, with: .color(mark.style.ink), lineWidth: min(size.width, size.height) * 0.004) }
                        }
                    }
                    if let drag { context.stroke(Path(CGRect(x: drag.left * size.width, y: drag.top * size.height, width: (drag.right-drag.left)*size.width, height: (drag.bottom-drag.top)*size.height)), with: .color(.black), lineWidth: 2) }
                }
                .contentShape(Rectangle())
                .allowsHitTesting(!inkMode)
                .highPriorityGesture(DragGesture(minimumDistance: 2)
                    .onChanged { value in drag = bounds(value, width: width, height: height) }
                    .onEnded { value in
                        if let rect = bounds(value, width: width, height: height) { add(rect) }; drag = nil
                    })
                InkCanvas(ink: ink, settings: settings, enabled: inkMode, color: inkColor, width: inkWidth, inking: $inking, add: addInk, failure: inkFailure)
                    .allowsHitTesting(inkMode)
            }.frame(width: width, height: height).contentShape(Rectangle())
                .accessibilityLabel("Page annotation canvas. Use Add with bounds as an alternative to dragging.")
                .accessibilityIdentifier("annotations-canvas")
                .position(x: proxy.size.width/2, y: proxy.size.height/2)
        }
    }
    private func bounds(_ value: DragGesture.Value, width: CGFloat, height: CGFloat) -> PageCrop? {
        let x1 = min(1, max(0, value.startLocation.x/width)), y1 = min(1, max(0, value.startLocation.y/height))
        let x2 = min(1, max(0, value.location.x/width)), y2 = min(1, max(0, value.location.y/height))
        guard abs(x2-x1) >= 0.005, abs(y2-y1) >= 0.005 else { return nil }
        return PageCrop(left: min(x1,x2), top: min(y1,y2), right: max(x1,x2), bottom: max(y1,y2))
    }
}

private struct AnnotationBounds: View {
    @Environment(\.dismiss) private var dismiss
    let apply: (PageCrop) -> Void
    @State private var left = "10"
    @State private var top = "10"
    @State private var right = "90"
    @State private var bottom = "30"
    private var value: PageCrop? {
        let values = [left,top,right,bottom].compactMap { Double($0.replacingOccurrences(of: ",", with: ".")) }
        guard values.count == 4 else { return nil }
        let bounds = PageCrop(left: values[0]/100, top: values[1]/100, right: values[2]/100, bottom: values[3]/100)
        return (try? pageCropPixels(crop: bounds, width: 1, height: 1)) == nil ? nil : bounds
    }
    var body: some View {
        NavigationStack {
            Form {
                Text("Percent of the displayed page, from its top-left corner. Right must exceed left; bottom must exceed top.")
                edge("Left", $left); edge("Top", $top); edge("Right", $right); edge("Bottom", $bottom)
                if value == nil { Text("Enter valid bounds between 0 and 100.").foregroundStyle(Tokens.danger) }
            }.navigationTitle("Bounds").navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                    ToolbarItem(placement: .confirmationAction) { Button("Add mark") { if let value { apply(value) } }.disabled(value == nil).accessibilityIdentifier("annotations-add") }
                }
        }
    }
    private func edge(_ title: String, _ binding: Binding<String>) -> some View {
        LabeledContent(title) { TextField("Percent", text: binding).keyboardType(.decimalPad).multilineTextAlignment(.trailing).accessibilityLabel(title + " percent").accessibilityIdentifier("annotations-" + title) }
    }
}
