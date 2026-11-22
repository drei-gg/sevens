import SwiftUI
import QRCore
import SevenKit

/// The reasons behind a verdict, one plain sentence each. No scores. Info signals are shown
/// quieter; they never change the verdict.
struct ResultSignalList: View {
    let rows: [ResultReason]

    /// Already-worded reasons (`ResultReasons`), shown as given.
    init(rows: [ResultReason]) {
        self.rows = rows
    }

    /// Signals worded by `SignalText`; identical sentences appear once.
    init(signals: [Signal]) {
        var seen = Set<String>()
        self.rows = signals.compactMap { signal in
            let sentence = SignalText.sentence(for: signal)
            return seen.insert(sentence).inserted ? ResultReason(sentence: sentence, isInfo: signal.severity == .info) : nil
        }
    }

    var body: some View {
        if !rows.isEmpty {
            VStack(alignment: .leading, spacing: Tokens.Space.s) {
                ForEach(rows, id: \.self) { row in
                    HStack(alignment: .firstTextBaseline, spacing: Tokens.Space.s) {
                        Image(systemName: row.isInfo ? "info.circle" : "exclamationmark.circle")
                            .foregroundStyle(Tokens.muted)
                            .accessibilityHidden(true)
                        Text(row.sentence)
                            .textRole(.body)
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
