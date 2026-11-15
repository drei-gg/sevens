import SwiftUI
import Charts
import SevenKit
import CleanerCore

/// App-local compositions, following Fast's StatGrid/StatTile/ChipLabel patterns.
struct CleanerTiles<Content: View>: View {
    @Environment(\.dynamicTypeSize) private var size
    @ViewBuilder var content: Content
    var body: some View { LazyVGrid(columns: Array(repeating: GridItem(.flexible(),spacing: Tokens.Space.ms,alignment: .top),count: size.isAccessibilitySize ? 1 : 2),spacing: Tokens.Space.ms) { content } }
}
struct CleanerStat: View {
    let title: String
    let value: String
    let symbol: String
    var detail: String? = nil
    var hue: Color = Tokens.dataCool
    var body: some View {
        VStack(alignment: .leading,spacing: Tokens.Space.s) {
            Image(systemName: symbol).foregroundStyle(hue).textRole(.section).accessibilityHidden(true)
            Text(value).textRole(.section).numeric().fixedSize(horizontal: false,vertical: true)
            Text(title).textRole(.rowTitle).lineLimit(nil).fixedSize(horizontal: false,vertical: true)
            if let detail { Text(detail).textRole(.caption) }
        }.frame(maxWidth: .infinity,maxHeight: .infinity,alignment: .topLeading).padding(Tokens.Space.m).sevenCard()
            .accessibilityElement(children: .combine)
    }
}
struct CleanerHero: View {
    let label: String
    let value: String
    let detail: String
    let symbol: String
    var hue: Color = Tokens.dataCool
    var valueRole: TextRole = .display
    @Environment(\.dynamicTypeSize) private var size
    var body: some View {
        VStack(spacing: Tokens.Space.ms) {
            Image(systemName: symbol).foregroundStyle(hue).textRole(size.isAccessibilitySize ? .section : .hero).accessibilityHidden(true)
            Text(label).textRole(.caption,emphasized: true).textCase(.uppercase).multilineTextAlignment(.center).lineLimit(nil).fixedSize(horizontal: false,vertical: true)
            Text(value).textRole(valueRole).numeric().multilineTextAlignment(.center).fixedSize(horizontal: false,vertical: true)
            Text(detail).textRole(.secondary).multilineTextAlignment(.center).fixedSize(horizontal: false,vertical: true)
        }.frame(maxWidth: .infinity).padding(.vertical,Tokens.Space.l)
            .accessibilityElement(children: .combine)
    }
}
struct CleanerChip: View {
    let title: String
    let symbol: String
    var body: some View { Label(title,systemImage: symbol).textRole(.rowTitle).numeric().frame(minHeight: Tokens.Space.l).padding(.horizontal,Tokens.Space.xs) }
}
extension View {
    func cleanerChip() -> some View { modifier(SevenButtonStyle(kind: .bordered)).controlSize(.regular).buttonBorderShape(.capsule) }
    func cleanerPrimary() -> some View { sevenProminentButton().controlSize(.extraLarge).buttonBorderShape(.capsule) }
    func cleanerCanvas() -> some View { sevenScreenBackground() }
    func cleanerBareRow() -> some View { listRowBackground(Color.clear).listRowInsets(EdgeInsets()).listRowSeparator(.hidden) }
}
struct CleanerNotice: View {
    let text: String
    var symbol = "info.circle"
    var caution = false
    var body: some View {
        HStack(alignment: .top,spacing: Tokens.Space.s) {
            Image(systemName: symbol).foregroundStyle(caution ? Tokens.caution : Tokens.muted).accessibilityHidden(true)
            Text(text).textRole(.caption).fixedSize(horizontal: false,vertical: true)
        }.frame(maxWidth: .infinity,alignment: .leading).accessibilityElement(children: .combine)
    }
}
struct LibraryComposition: View {
    let items: [CleanupItem]
    private var photos: Int64 { items.filter { $0.kind == .photo }.compactMap(\.bytes).reduce(0,+) }
    private var videos: Int64 { items.filter { $0.kind == .video }.compactMap(\.bytes).reduce(0,+) }
    var body: some View {
        VStack(alignment: .leading,spacing: Tokens.Space.ms) {
            Text("Measured media").textRole(.rowTitle,emphasized: true)
            if photos + videos > 0 {
                Chart {
                    BarMark(x: .value("Bytes",photos)).foregroundStyle(Tokens.dataCool)
                    BarMark(x: .value("Bytes",videos)).foregroundStyle(Tokens.dataWarm)
                }.chartXAxis(.hidden).chartYAxis(.hidden).frame(height: Tokens.Space.m).clipShape(Capsule()).accessibilityHidden(true)
                CleanerTiles {
                    Label { Text("Photos · \(byteText(photos))").textRole(.caption).numeric() } icon: { Image(systemName: "photo").foregroundStyle(Tokens.dataCool) }
                    Label { Text("Videos · \(byteText(videos))").textRole(.caption).numeric() } icon: { Image(systemName: "video").foregroundStyle(Tokens.dataWarm) }
                }
            } else { Text("Measure your library to see the photo and video breakdown.").textRole(.secondary) }
        }.padding(Tokens.Space.m).sevenCard().accessibilityElement(children: .combine)
    }
}
struct FileSizeChart: View {
    let items: [CleanupItem]
    var body: some View {
        VStack(alignment: .leading,spacing: Tokens.Space.ms) {
            Text("Largest files").textRole(.rowTitle,emphasized: true)
            ForEach(Array(items.filter { $0.bytes != nil }.prefix(3))) { item in
                VStack(alignment: .leading,spacing: Tokens.Space.xs) {
                    ViewThatFits(in: .horizontal) {
                        HStack { Text(item.name).lineLimit(1).truncationMode(.middle); Spacer(); Text(byteText(item.bytes)).numeric() }
                        VStack(alignment: .leading) { Text(item.name); Text(byteText(item.bytes)).numeric() }
                    }.textRole(.caption)
                    GeometryReader { geometry in
                        Capsule().fill(Tokens.rule)
                        Capsule().fill(Tokens.dataWarm).frame(width: geometry.size.width * CGFloat(item.bytes ?? 0) / CGFloat(max(1, items.compactMap(\.bytes).max() ?? 1)))
                    }.frame(height: Tokens.Space.s).accessibilityHidden(true)
                }.accessibilityElement(children: .combine)
            }
        }.padding(Tokens.Space.m).sevenCard()
    }
}
