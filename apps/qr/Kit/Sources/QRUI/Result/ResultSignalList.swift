import SwiftUI
import QRCore
import SevenKit

/// The reasons behind a verdict, one plain sentence each. No scores. Info signals are shown
/// quieter; they never change the verdict.
struct ResultSignalList: View {
    let signals: [Signal]

    private struct Row: Hashable { let sentence: String; let isInfo: Bool }

    /// Identical sentences appear once.
    private var rows: [Row] {
        var seen = Set<String>()
        return signals.compactMap { signal in
            let sentence = SignalText.sentence(for: signal)
            return seen.insert(sentence).inserted ? Row(sentence: sentence, isInfo: signal.severity == .info) : nil
        }
    }

    var body: some View {
        if !signals.isEmpty {
            VStack(alignment: .leading, spacing: Tokens.Space.s) {
                ForEach(rows, id: \.self) { row in
                    HStack(alignment: .firstTextBaseline, spacing: Tokens.Space.s) {
                        Image(systemName: row.isInfo ? "info.circle" : "exclamationmark.circle")
                            .foregroundStyle(Tokens.muted)
                            .accessibilityHidden(true)
                        Text(row.sentence)
                            .font(.body)
                            .foregroundStyle(row.isInfo ? Tokens.muted : Color.primary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .accessibilityElement(children: .combine)
                }
            }
            .padding(.horizontal, Tokens.Space.xs)
        }
    }
}
