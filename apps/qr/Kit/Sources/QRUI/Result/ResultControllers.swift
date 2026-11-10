import SwiftUI
import SafariServices
import Contacts
import ContactsUI
import EventKit
import EventKitUI
import MessageUI
import QRCore

/// In-app Safari (D8). http(s) only; the caller checks.
struct ResultSafariView: UIViewControllerRepresentable {
    let url: URL
    let onFinish: () -> Void

    func makeCoordinator() -> Coordinator { Coordinator(onFinish: onFinish) }

    func makeUIViewController(context: Context) -> SFSafariViewController {
        let controller = SFSafariViewController(url: url)
        controller.delegate = context.coordinator
        return controller
    }

    func updateUIViewController(_ controller: SFSafariViewController, context: Context) {}

    @MainActor final class Coordinator: NSObject, @preconcurrency SFSafariViewControllerDelegate {
        let onFinish: () -> Void
        init(onFinish: @escaping () -> Void) { self.onFinish = onFinish }
        func safariViewControllerDidFinish(_ controller: SFSafariViewController) { onFinish() }
    }
}

/// `CNContactViewController(forNewContact:)` (C9 fallback only if it misbehaves on a device).
struct ResultContactEditor: UIViewControllerRepresentable {
    let info: ContactInfo
    let onFinish: () -> Void

    func makeCoordinator() -> Coordinator { Coordinator(onFinish: onFinish) }

    func makeUIViewController(context: Context) -> UINavigationController {
        let controller = CNContactViewController(forNewContact: ResultContactBuilder.contact(from: info))
        controller.delegate = context.coordinator
        return UINavigationController(rootViewController: controller)
    }

    func updateUIViewController(_ controller: UINavigationController, context: Context) {}

    @MainActor final class Coordinator: NSObject, @preconcurrency CNContactViewControllerDelegate {
        let onFinish: () -> Void
        init(onFinish: @escaping () -> Void) { self.onFinish = onFinish }
        func contactViewController(_ viewController: CNContactViewController, didCompleteWith contact: CNContact?) { onFinish() }
    }
}

/// `EKEventEditViewController` with a fresh store; iOS 17+ asks for no permission to add.
struct ResultEventEditor: UIViewControllerRepresentable {
    let info: EventInfo
    let onFinish: () -> Void

    func makeCoordinator() -> Coordinator { Coordinator(onFinish: onFinish) }

    func makeUIViewController(context: Context) -> EKEventEditViewController {
        let store = EKEventStore()
        let controller = EKEventEditViewController()
        controller.eventStore = store
        controller.event = ResultEventBuilder.event(from: info, store: store)
        controller.editViewDelegate = context.coordinator
        return controller
    }

    func updateUIViewController(_ controller: EKEventEditViewController, context: Context) {}

    @MainActor final class Coordinator: NSObject, @preconcurrency EKEventEditViewDelegate {
        let onFinish: () -> Void
        init(onFinish: @escaping () -> Void) { self.onFinish = onFinish }
        func eventEditViewController(_ controller: EKEventEditViewController, didCompleteWith action: EKEventEditViewAction) { onFinish() }
    }
}

/// `MFMessageComposeViewController` with the body filled in. Check `canSendText()` first.
struct ResultMessageComposer: UIViewControllerRepresentable {
    let number: String
    let body: String?
    let onFinish: () -> Void

    func makeCoordinator() -> Coordinator { Coordinator(onFinish: onFinish) }

    func makeUIViewController(context: Context) -> MFMessageComposeViewController {
        let controller = MFMessageComposeViewController()
        controller.recipients = [ResultActionURLs.dialable(number)]
        controller.body = body
        controller.messageComposeDelegate = context.coordinator
        return controller
    }

    func updateUIViewController(_ controller: MFMessageComposeViewController, context: Context) {}

    @MainActor final class Coordinator: NSObject, @preconcurrency MFMessageComposeViewControllerDelegate {
        let onFinish: () -> Void
        init(onFinish: @escaping () -> Void) { self.onFinish = onFinish }
        func messageComposeViewController(_ controller: MFMessageComposeViewController, didFinishWith result: MessageComposeResult) { onFinish() }
    }
}

/// ContactInfo → CNMutableContact. The note is left out: reading and writing it needs an
/// entitlement we don't ask for.
enum ResultContactBuilder {
    static func contact(from info: ContactInfo) -> CNMutableContact {
        let contact = CNMutableContact()
        if info.givenName != nil || info.familyName != nil {
            contact.givenName = info.givenName ?? ""
            contact.familyName = info.familyName ?? ""
        } else if let name = info.name {
            contact.givenName = name
        }
        contact.organizationName = info.organization ?? ""
        contact.jobTitle = info.title ?? ""
        contact.phoneNumbers = info.phones.map { CNLabeledValue(label: CNLabelOther, value: CNPhoneNumber(stringValue: $0)) }
        contact.emailAddresses = info.emails.map { CNLabeledValue(label: CNLabelOther, value: $0 as NSString) }
        contact.urlAddresses = info.urls.map { CNLabeledValue(label: CNLabelOther, value: $0 as NSString) }
        contact.postalAddresses = info.addresses.map { text in
            let address = CNMutablePostalAddress()
            address.street = text
            return CNLabeledValue(label: CNLabelOther, value: address as CNPostalAddress)
        }
        contact.birthday = birthday(info.birthday)
        return contact
    }

    /// `YYYY-MM-DD` or `YYYYMMDD`; anything else is dropped.
    static func birthday(_ text: String?) -> DateComponents? {
        guard let text else { return nil }
        let digits = text.filter(\.isNumber)
        guard digits.count == 8, text.allSatisfy({ $0.isNumber || $0 == "-" }),
              let year = Int(digits.prefix(4)), let month = Int(digits.dropFirst(4).prefix(2)),
              let day = Int(digits.suffix(2)), (1...12).contains(month), (1...31).contains(day) else { return nil }
        return DateComponents(year: year, month: month, day: day)
    }
}

enum ResultEventBuilder {
    static func event(from info: EventInfo, store: EKEventStore) -> EKEvent {
        let event = EKEvent(eventStore: store)
        event.title = info.summary ?? ""
        event.location = info.location
        event.notes = info.description
        if let url = info.url { event.url = URL(string: url) }
        if let dates = ResultEventFormat.calendarDates(info) {
            event.startDate = dates.start
            event.endDate = dates.end
            event.isAllDay = dates.allDay
        }
        return event
    }
}
