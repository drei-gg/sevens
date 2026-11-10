import SwiftUI
import QRCore
import SevenKit

/// One History row: verdict icon and word at the time, type, short payload, note, date.
struct HistoryRow: View {
    let item: HistoryItem

    var body: some View {
        let style = VerdictText.style(for: item.level)
        let typeName = VerdictText.typeName(for: item.typeID)
        let payload = HistoryRowSummary.shortPayload(for: item)
        let note = HistoryRowSummary.notePreview(item.note)
        VStack(alignment: .leading, spacing: Tokens.Space.xs) {
            Label {
                Text(VerdictText.title(for: item.level)).font(.subheadline.weight(.semibold))
            } icon: {
                Image(systemName: style.symbolName)
            }
            .foregroundStyle(style.color)
            Text(payload)
                .font(.body)
                .foregroundStyle(Tokens.ink)
                .lineLimit(2)
            if let note {
                Text(note).font(.callout).foregroundStyle(Tokens.muted).lineLimit(2)
            }
            Text(verbatim: "\(typeName) · \(HistoryLabels.kind(item.kind)) · \(item.date.formatted(date: .abbreviated, time: .shortened))")
                .font(.footnote)
                .foregroundStyle(Tokens.muted)
        }
        .padding(.vertical, Tokens.Space.xs)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(label(typeName: typeName, payload: payload, note: note))
        .accessibilityIdentifier(QRAccessibilityID.historyRow)
    }

    private func label(typeName: String, payload: String, note: String?) -> String {
        var parts = [VerdictText.title(for: item.level), typeName, payload]
        if let note {
            parts.append(String(localized: "history.row.noteLabel", defaultValue: "Note: \(note)", bundle: .module))
        }
        parts.append(HistoryLabels.kind(item.kind))
        parts.append(item.date.formatted(date: .complete, time: .shortened))
        return parts.joined(separator: ". ")
    }
}

enum HistoryLabels {
    static func kind(_ kind: HistoryItem.Kind) -> String {
        switch kind {
        case .scanned: return String(localized: "history.kind.scanned", defaultValue: "Scanned", bundle: .module)
        case .made: return String(localized: "history.kind.made", defaultValue: "Made", bundle: .module)
        }
    }

    static func symbology(_ symbology: Symbology) -> String {
        switch symbology {
        case .qr: return String(localized: "history.symbology.qr", defaultValue: "QR code", bundle: .module)
        case .microQR: return String(localized: "history.symbology.microQR", defaultValue: "Micro QR code", bundle: .module)
        case .aztec: return "Aztec"
        case .pdf417: return "PDF417"
        case .microPDF417: return "MicroPDF417"
        case .dataMatrix: return "Data Matrix"
        case .code128: return "Code 128"
        case .code39: return "Code 39"
        case .code93: return "Code 93"
        case .ean13: return "EAN-13"
        case .ean8: return "EAN-8"
        case .upce: return "UPC-E"
        case .itf14: return "ITF-14"
        case .codabar: return "Codabar"
        case .gs1DataBar: return "GS1 DataBar"
        case .other: return String(localized: "history.symbology.other", defaultValue: "Other barcode", bundle: .module)
        }
    }
}
