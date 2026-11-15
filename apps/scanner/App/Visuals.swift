import SwiftUI
import SevenKit
import ScannerCore

/// Scanner's own visual pieces: page thumbnails, document cards, chips and the floating action bar.
/// ChipLabel, `.quickAddChip()` and `.bareListRow()` follow Fast's (polish guide §5); they stay in the
/// app until a promotion into SevenKit is agreed.
enum ScannerMetrics {
    /// Thumbnails sit inside 14-point cards with 6 points or less around them (concentricity, §3.4).
    static let thumbnailRadius: CGFloat = 8
    /// A scanned page is portrait paper; A4 and Letter are both close to 3:4 at thumbnail size.
    static let pageAspect: CGFloat = 3.0 / 4.0
}

/// "2 pages", inflected by the system so it reads right in every count.
func pageCount(_ count: Int) -> String {
    String(AttributedString(localized: "^[\(count) page](inflect: true)").characters)
}

func documentCount(_ count: Int) -> String {
    String(AttributedString(localized: "^[\(count) document](inflect: true)").characters)
}

/// One page as a thumbnail: the adjusted image on card, with continuous corners and a hairline,
/// so white paper still has an edge on light paper.
struct PageThumbnail: View {
    @Environment(ScannerModel.self) private var model
    let document: ScanDocument
    var page: ScanPage?
    var deleted = false
    var maxPixels = 480
    @State private var url: URL?
    private var shown: ScanPage? { page ?? document.pages.first }
    var body: some View {
        let shape = RoundedRectangle(cornerRadius: ScannerMetrics.thumbnailRadius, style: .continuous)
        Color.clear
            .aspectRatio(ScannerMetrics.pageAspect, contentMode: .fit)
            .overlay {
                if let url, let shown { PageImage(url: url, settings: shown.settings, maxPixels: maxPixels, fill: true) }
            }
            .background(Tokens.card)
            .clipShape(shape)
            .overlay { shape.strokeBorder(Tokens.rule, lineWidth: 1) }
            .accessibilityHidden(true)
            .task(id: TaskKey(document: document.id, page: shown?.id, settings: shown?.settings)) { url = await model.pageURL(document, page: shown, deleted: deleted) }
    }
    private struct TaskKey: Equatable { var document: UUID; var page: UUID?; var settings: PageSettings? }
}

/// A document in the library grid: its first page, the page count on the page's corner, then the
/// name and date.
struct DocumentCard: View {
    let document: ScanDocument
    var body: some View {
        VStack(alignment: .leading, spacing: Tokens.Space.s) {
            PageThumbnail(document: document)
                .overlay(alignment: .bottomTrailing) {
                    Label("\(document.pages.count)", systemImage: "doc.on.doc")
                        .foregroundStyle(.primary).textRole(.caption, emphasized: true).numeric()
                        .padding(.horizontal, Tokens.Space.s).padding(.vertical, Tokens.Space.xs)
                        .background(Tokens.card, in: Capsule())
                        .overlay { Capsule().strokeBorder(Tokens.rule, lineWidth: 1) }
                        .padding(Tokens.Space.s)
                        .accessibilityHidden(true)
                }
            VStack(alignment: .leading, spacing: Tokens.Space.xs) {
                HStack(alignment: .firstTextBaseline, spacing: Tokens.Space.xs) {
                    if document.favorite == true {
                        Image(systemName: "star.fill").foregroundStyle(Tokens.ink).textRole(.caption).accessibilityHidden(true)
                    }
                    Text(document.title).textRole(.rowTitle, emphasized: true).lineLimit(2).multilineTextAlignment(.leading)
                }
                Text(document.created.formatted(date: .abbreviated, time: .omitted)).textRole(.caption).numeric()
            }
        }
        .frame(maxWidth: .infinity, alignment: .topLeading)
        .contentShape(Rectangle())
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text(document.title))
        .accessibilityValue(Text(cardValue))
        .accessibilityHint(Text("Opens the document."))
    }
    private var cardValue: String {
        var parts = [pageCount(document.pages.count), document.created.formatted(date: .abbreviated, time: .omitted)]
        if document.favorite == true { parts.append(String(localized: "Favorite")) }
        return parts.joined(separator: ", ")
    }
}

/// The label of a capsule chip for a one-tap action: "Preview", "Arrange". Style the button with
/// `.quickAddChip()`.
struct ChipLabel: View {
    let title: LocalizedStringKey
    let systemImage: String
    var body: some View {
        Label(title, systemImage: systemImage).textRole(.rowTitle, emphasized: true)
            .lineLimit(1).minimumScaleFactor(0.8).frame(maxWidth: .infinity, minHeight: 28)
    }
}

/// A square-ish action button with its icon above a short label, three to a row: the document's
/// quick actions, which don't fit as capsules side by side.
struct ActionTileLabel: View {
    let title: LocalizedStringKey
    let systemImage: String
    var body: some View {
        VStack(spacing: Tokens.Space.xs) {
            Image(systemName: systemImage).imageScale(.large)
            Text(title).textRole(.caption, emphasized: true).foregroundStyle(Tokens.ink).lineLimit(2).multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity, minHeight: 52)
    }
}

extension View {
    func actionTile() -> some View {
        modifier(SevenButtonStyle(kind: .bordered)).controlSize(.large).buttonBorderShape(.roundedRectangle(radius: Tokens.Radius.card))
    }
    func quickAddChip() -> some View {
        modifier(SevenButtonStyle(kind: .bordered)).controlSize(.large).buttonBorderShape(.capsule)
    }
    /// The one large action of a screen (polish guide §6): ink capsule, extra large.
    func heroAction() -> some View {
        sevenProminentButton().controlSize(.extraLarge).buttonBorderShape(.capsule)
    }
    /// A list row that shows our own layout straight on paper, without the row's card.
    func bareListRow() -> some View {
        listRowBackground(Color.clear).listRowInsets(EdgeInsets()).listRowSeparator(.hidden)
    }
}

/// Side by side, or stacked at accessibility text sizes.
struct AdaptiveStack<Content: View>: View {
    @Environment(\.dynamicTypeSize) private var textSize
    var spacing = Tokens.Space.ms
    @ViewBuilder var content: Content
    var body: some View {
        let layout = textSize.isAccessibilitySize ? AnyLayout(VStackLayout(spacing: spacing)) : AnyLayout(HStackLayout(spacing: spacing))
        layout { content }
    }
}

/// A labelled number on a card (Fast's tile): Backup's summary.
struct StatTile: View {
    let title: LocalizedStringKey
    let value: String
    var body: some View {
        VStack(alignment: .leading, spacing: Tokens.Space.xs) {
            Text(title).textRole(.caption, emphasized: true).textCase(.uppercase)
            Text(value).textRole(.section).numeric().lineLimit(2).minimumScaleFactor(0.7)
        }
        .frame(maxWidth: .infinity, minHeight: 64, alignment: .topLeading)
        .padding(Tokens.Space.ms)
        .sevenCard()
        .accessibilityElement(children: .combine)
    }
}

/// A navigation row on our own card, for the few places outside a `List`.
struct CardLinkRow: View {
    let title: LocalizedStringKey
    let systemImage: String
    var detail: String?
    var body: some View {
        HStack(spacing: Tokens.Space.ms) {
            Image(systemName: systemImage).foregroundStyle(Tokens.ink).frame(minWidth: 28).accessibilityHidden(true)
            Text(title).textRole(.rowTitle).foregroundStyle(.primary)
            Spacer(minLength: Tokens.Space.s)
            if let detail { Text(detail).textRole(.secondary).numeric() }
            Image(systemName: "chevron.right").textRole(.caption, emphasized: true).accessibilityHidden(true)
        }
        .padding(.horizontal, Tokens.Space.m).padding(.vertical, Tokens.Space.ms)
        .frame(minHeight: 44)
        .contentShape(Rectangle())
    }
}

/// A status line: the colour goes on the icon, the words stay primary (polish guide §3.1).
struct StatusLine: View {
    let text: String
    var systemImage = "exclamationmark.triangle.fill"
    var color = Tokens.caution
    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: Tokens.Space.s) {
            Image(systemName: systemImage).foregroundStyle(color).accessibilityHidden(true)
            Text(text).textRole(.body).foregroundStyle(.primary).frame(maxWidth: .infinity, alignment: .leading)
        }
        .accessibilityElement(children: .combine)
    }
}
