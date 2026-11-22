import SwiftUI
import PencilKit
import ScannerCore

extension InkColor {
    static var choices: [InkColor] { [.black, .red, .blue] }
    var title: String { switch self { case .black: sc("Black"); case .red: sc("Red"); case .blue: sc("Blue") } }
    var uiColor: UIColor { switch self {
    case .black: UIColor(red: 23.0/255, green: 23.0/255, blue: 23.0/255, alpha: 1)
    case .red: UIColor(red: 200.0/255, green: 35.0/255, blue: 35.0/255, alpha: 1)
    case .blue: UIColor(red: 30.0/255, green: 80.0/255, blue: 210.0/255, alpha: 1)
    } }
}

/// Shared portable geometry, rendered identically for the editor and final page/PDF pixels.
nonisolated func drawPageInk(_ context: CGContext, ink: [PageInk], settings: PageSettings, size: CGSize, flipped: Bool = false) throws {
    guard size.width > 0, size.height > 0 else { return }
    context.saveGState(); defer { context.restoreGState() }
    if flipped { context.translateBy(x: 0, y: size.height); context.scaleBy(x: 1, y: -1) }
    context.clip(to: CGRect(origin: .zero, size: size))
    for stroke in ink {
        let d = try inkDisplay(ink: stroke, settings: settings, displayWidth: size.width, displayHeight: size.height)
        context.saveGState()
        context.clip(to: CGRect(x: d.clip.x, y: d.clip.y, width: d.clip.width, height: d.clip.height))
        context.setStrokeColor(stroke.color.uiColor.cgColor); context.setFillColor(stroke.color.uiColor.cgColor)
        context.setLineWidth(d.width); context.setLineCap(.round); context.setLineJoin(.round)
        if let first = d.points.first {
            if d.points.count == 1 { context.fillEllipse(in: CGRect(x: first.x-d.width/2, y: first.y-d.width/2, width: d.width, height: d.width)) }
            else { context.beginPath(); context.move(to: CGPoint(x: first.x, y: first.y)); for p in d.points.dropFirst() { context.addLine(to: CGPoint(x: p.x, y: p.y)) }; context.strokePath() }
        }
        context.restoreGState()
    }
}

struct InkCanvas: UIViewRepresentable {
    let ink: [PageInk]
    let settings: PageSettings
    let enabled: Bool
    let color: InkColor
    let width: CGFloat
    @Binding var inking: Bool
    let add: (PageInk) -> Bool
    let failure: (String) -> Void
    func makeUIView(context: Context) -> InkSurface { InkSurface() }
    func updateUIView(_ view: InkSurface, context: Context) { view.update(self) }
    static func dismantleUIView(_ view: InkSurface, coordinator: ()) { view.canvas.delegate = nil; view.configuration = nil }
}

final class InkSurface: UIView, PKCanvasViewDelegate {
    let canvas = PKCanvasView()
    fileprivate var configuration: InkCanvas?
    private var displayInk: [PageInk] = []
    private var usingTool = false
    private var handoff = false
    private var strokeColor = InkColor.black
    private var strokeWidth: CGFloat = 4
    override init(frame: CGRect) {
        super.init(frame: frame)
        isOpaque = false; backgroundColor = .clear; clipsToBounds = true
        canvas.backgroundColor = .clear; canvas.isOpaque = false; canvas.isScrollEnabled = false
        canvas.drawingPolicy = .anyInput; canvas.delegate = self
        canvas.accessibilityIdentifier = "ink-canvas"
        canvas.accessibilityLabel = sc("Ink canvas. Draw a stroke; use Undo or Clear ink to remove it.")
        addSubview(canvas)
    }
    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }
    override func layoutSubviews() { super.layoutSubviews(); canvas.frame = bounds; setNeedsDisplay() }
    func update(_ value: InkCanvas) {
        configuration = value; displayInk = value.ink; canvas.isUserInteractionEnabled = value.enabled
        if !usingTool { canvas.tool = PKInkingTool(.monoline, color: value.color.uiColor, width: value.width) }
        setNeedsDisplay()
    }
    override func draw(_ rect: CGRect) {
        guard let c = configuration, let context = UIGraphicsGetCurrentContext() else { return }
        try? drawPageInk(context, ink: displayInk, settings: c.settings, size: bounds.size)
    }
    func canvasViewDidBeginUsingTool(_ canvasView: PKCanvasView) {
        usingTool = true
        guard let c = configuration else { return }
        strokeColor = c.color; strokeWidth = c.width; c.inking = true
    }
    func canvasViewDidEndUsingTool(_ canvasView: PKCanvasView) {
        usingTool = false
        // PencilKit may publish its completed drawing immediately after the end callback.
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            self.commit()
            if self.canvas.drawingGestureRecognizer.state == .cancelled || self.canvas.drawingGestureRecognizer.state == .failed { self.configuration?.inking = false }
        }
    }
    func canvasViewDidFinishRendering(_ canvasView: PKCanvasView) {
        if !usingTool { commit(); if canvas.drawing.strokes.isEmpty { configuration?.inking = false } }
    }
    func canvasViewDrawingDidChange(_ canvasView: PKCanvasView) { if !usingTool { commit() } }
    private func commit() {
        guard !handoff, !usingTool, !canvas.drawing.strokes.isEmpty, let c = configuration, bounds.width > 0, bounds.height > 0 else { return }
        handoff = true; defer { handoff = false; c.inking = false }
        for stroke in canvas.drawing.strokes {
            var points: [InkPoint] = []
            for sample in stroke.path.interpolatedPoints(by: .distance(0.5)) {
                let p = sample.location.applying(stroke.transform)
                points.append(InkPoint(x: p.x/bounds.width, y: p.y/bounds.height))
                if points.count > 8192 { break }
            }
            // Core rejects a too-long stroke as a whole, retaining all earlier ink.
            do { let mark = try inkFromDisplay(points: points, width: strokeWidth, displayWidth: bounds.width, displayHeight: bounds.height, settings: c.settings, color: strokeColor); if c.add(mark) { displayInk.append(mark) } }
            catch { c.failure(sc("This stroke could not be added. Your earlier marks are kept; try a shorter stroke.")) }
        }
        canvas.drawing = PKDrawing()
        setNeedsDisplay()
    }
}
