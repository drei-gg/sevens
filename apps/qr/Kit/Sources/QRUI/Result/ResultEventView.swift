import SwiftUI
import QRCore
import SevenKit

/// An event: title, time (formatted per `EventInfo`'s rules), place.
struct ResultEventView: View {
    let info: EventInfo

    var body: some View {
        ResultCard {
            Text(info.summary.flatMap { $0.isEmpty ? nil : $0 } ?? String(localized: "result.event.noTitle", defaultValue: "Event with no title", bundle: .module))
                .font(.largeTitle.weight(.bold)).foregroundStyle(.primary)
                .textSelection(.enabled).fixedSize(horizontal: false, vertical: true)
                .accessibilityAddTraits(.isHeader)
            if let time = ResultEventFormat.timeText(info) {
                ResultField(label: String(localized: "result.event.when", defaultValue: "When", bundle: .module), value: time)
                if let organiser = ResultEventFormat.organiserText(info) {
                    Text(organiser).font(.subheadline).foregroundStyle(.secondary)
                }
            } else {
                ResultField(label: String(localized: "result.event.when", defaultValue: "When", bundle: .module),
                            value: String(localized: "result.event.noTime", defaultValue: "No time in the code", bundle: .module))
            }
            if let location = info.location, !location.isEmpty {
                ResultField(label: String(localized: "result.event.where", defaultValue: "Where", bundle: .module), value: location)
            }
            if let description = info.description, !description.isEmpty {
                ResultField(label: String(localized: "result.event.about", defaultValue: "Details", bundle: .module), value: description)
            }
            if let url = info.url, !url.isEmpty {
                ResultField(label: String(localized: "result.event.link", defaultValue: "Link", bundle: .module), value: url, muted: true)
            }
        }
    }
}
