import SwiftUI
import SevenKit

// Layout pieces from Fast's polish pass (fast/polish `FastUI/Shared/Visuals.swift`), copied
// rather than moved into SevenKit so parallel passes don't edit the kit at once.

/// A labelled number on a card: History's summary tiles.
struct StatTile: View {
    let title: String
    let value: String
    var systemImage: String?

    var body: some View {
        VStack(alignment: .leading, spacing: Tokens.Space.xs) {
            Label {
                Text(title).lineLimit(1).minimumScaleFactor(0.8)
            } icon: {
                if let systemImage { Image(systemName: systemImage) }
            }
            .labelStyle(.titleAndIcon)
            .textRole(.caption, emphasized: true)
            Text(value).textRole(.section).foregroundStyle(.primary).numeric()
                .lineLimit(2).minimumScaleFactor(0.7)
                .contentTransition(.numericText())
        }
        .frame(maxWidth: .infinity, minHeight: 64, alignment: .topLeading)
        .padding(Tokens.Space.ms)
        .background(Tokens.card, in: RoundedRectangle(cornerRadius: Tokens.Radius.card, style: .continuous))
        .accessibilityElement(children: .combine)
    }
}

/// Tiles side by side, one a row at accessibility sizes.
struct StatGrid<Content: View>: View {
    @Environment(\.dynamicTypeSize) private var textSize
    var columns = 3
    @ViewBuilder var content: Content

    var body: some View {
        LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: Tokens.Space.s),
                                 count: textSize.isAccessibilitySize ? 1 : columns),
                  spacing: Tokens.Space.s) { content }
    }
}

extension View {
    /// A list row that shows our own layout straight on paper, without the row's card.
    func bareListRow() -> some View {
        listRowBackground(Color.clear).listRowInsets(EdgeInsets()).listRowSeparator(.hidden)
    }

    /// A capsule chip for the one-tap secondary actions (Copy, Share, Make a code).
    func quickAddChip() -> some View {
        modifier(SevenButtonStyle(kind: .bordered)).controlSize(.large).buttonBorderShape(.capsule)
    }
}

/// The label of a chip button. Style the button with `.quickAddChip()`.
struct ChipLabel: View {
    let title: String
    let systemImage: String

    var body: some View {
        Label(title, systemImage: systemImage).textRole(.rowTitle, emphasized: true)
            .lineLimit(1).minimumScaleFactor(0.8).frame(maxWidth: .infinity, minHeight: 28)
    }
}

/// A rounded square holding a type's symbol: the leading tile of a History row and the
/// type chips in Make. Fixed 44 pt box so row text lines up (polish guide §5.1).
struct TypeTile: View {
    let systemImage: String
    @ScaledMetric(relativeTo: .body) private var side: CGFloat = 44

    var body: some View {
        Image(systemName: systemImage)
            .textRole(.rowTitle)
            .imageScale(.large)
            .foregroundStyle(.primary)
            .frame(width: side, height: side)
            .background(Tokens.paper, in: RoundedRectangle(cornerRadius: Tokens.Radius.button, style: .continuous))
            .accessibilityHidden(true)
    }
}

/// The scanner's frame: four rounded corner brackets, the shape every scanner app uses to say
/// "put the code here". Over the camera they are white with a dark keyline, so they read on any
/// picture; on paper (no camera) they are ink.
struct ViewfinderFrame: View {
    enum Surface { case camera, paper }
    let surface: Surface

    var body: some View {
        GeometryReader { proxy in
            let side = min(proxy.size.width, proxy.size.height)
            let corners = ViewfinderCorners(length: side * 0.2, radius: side * 0.08)
            ZStack {
                if surface == .camera {
                    corners.stroke(Color.black.opacity(0.45), style: StrokeStyle(lineWidth: 9, lineCap: .round, lineJoin: .round))
                    corners.stroke(Color.white, style: StrokeStyle(lineWidth: 5, lineCap: .round, lineJoin: .round))
                } else {
                    corners.stroke(Tokens.ink, style: StrokeStyle(lineWidth: 5, lineCap: .round, lineJoin: .round))
                }
            }
            .frame(width: side, height: side)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .aspectRatio(1, contentMode: .fit)
        .accessibilityHidden(true)
    }
}

/// Four corner brackets of a square, each with a rounded corner.
struct ViewfinderCorners: Shape {
    let length: CGFloat
    let radius: CGFloat

    func path(in rect: CGRect) -> Path {
        var path = Path()
        let r = min(radius, length)
        // Top left
        path.move(to: CGPoint(x: rect.minX, y: rect.minY + length))
        path.addLine(to: CGPoint(x: rect.minX, y: rect.minY + r))
        path.addQuadCurve(to: CGPoint(x: rect.minX + r, y: rect.minY), control: CGPoint(x: rect.minX, y: rect.minY))
        path.addLine(to: CGPoint(x: rect.minX + length, y: rect.minY))
        // Top right
        path.move(to: CGPoint(x: rect.maxX - length, y: rect.minY))
        path.addLine(to: CGPoint(x: rect.maxX - r, y: rect.minY))
        path.addQuadCurve(to: CGPoint(x: rect.maxX, y: rect.minY + r), control: CGPoint(x: rect.maxX, y: rect.minY))
        path.addLine(to: CGPoint(x: rect.maxX, y: rect.minY + length))
        // Bottom right
        path.move(to: CGPoint(x: rect.maxX, y: rect.maxY - length))
        path.addLine(to: CGPoint(x: rect.maxX, y: rect.maxY - r))
        path.addQuadCurve(to: CGPoint(x: rect.maxX - r, y: rect.maxY), control: CGPoint(x: rect.maxX, y: rect.maxY))
        path.addLine(to: CGPoint(x: rect.maxX - length, y: rect.maxY))
        // Bottom left
        path.move(to: CGPoint(x: rect.minX + length, y: rect.maxY))
        path.addLine(to: CGPoint(x: rect.minX + r, y: rect.maxY))
        path.addQuadCurve(to: CGPoint(x: rect.minX, y: rect.maxY - r), control: CGPoint(x: rect.minX, y: rect.maxY))
        path.addLine(to: CGPoint(x: rect.minX, y: rect.maxY - length))
        return path
    }
}
