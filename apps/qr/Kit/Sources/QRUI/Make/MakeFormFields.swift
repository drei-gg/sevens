import SevenKit
import SwiftUI

/// The fields of whichever form is chosen. Pure input: `MakeScreenModel` validates.
struct MakeFormFields: View {
    @Bindable var model: MakeScreenModel

    private func row(_ title: String, _ binding: Binding<String>, _ id: String, field: MakeFormField? = nil,
                     keyboard: UIKeyboardType = .default, content: UITextContentType? = nil,
                     caps: TextInputAutocapitalization = .never, autocorrect: Bool = false,
                     multiline: Bool = false) -> some View {
        MakeFormRow(title: title, text: binding, identifier: id, issue: field.flatMap { model.issue(for: $0) },
                    keyboard: keyboard, contentType: content, capitalization: caps, autocorrect: autocorrect,
                    multiline: multiline)
    }

    var body: some View {
        switch model.kind {
        case .link:
            row(String(localized: "make.field.link", defaultValue: "Web address", bundle: .module), $model.form.link,
                QRAccessibilityID.makeLinkField, field: .link, keyboard: .URL, content: .URL)
        case .text:
            row(String(localized: "make.field.text", defaultValue: "Text", bundle: .module), $model.form.text,
                QRAccessibilityID.makeTextField, field: .text, caps: .sentences, autocorrect: true, multiline: true)
        case .wifi: wifi
        case .contact: contact
        case .event: event
        case .email:
            row(String(localized: "make.field.emailAddress", defaultValue: "Email address", bundle: .module),
                $model.form.emailAddress, "make.field.email", field: .emailAddress, keyboard: .emailAddress, content: .emailAddress)
            row(String(localized: "make.field.subject", defaultValue: "Subject (optional)", bundle: .module),
                $model.form.emailSubject, "make.field.subject", caps: .sentences, autocorrect: true)
            row(String(localized: "make.field.message", defaultValue: "Message (optional)", bundle: .module),
                $model.form.emailBody, "make.field.body", caps: .sentences, autocorrect: true, multiline: true)
        case .sms:
            row(String(localized: "make.field.smsNumber", defaultValue: "Phone number", bundle: .module),
                $model.form.smsNumber, "make.field.smsNumber", field: .smsNumber, keyboard: .phonePad, content: .telephoneNumber)
            row(String(localized: "make.field.message", defaultValue: "Message (optional)", bundle: .module),
                $model.form.smsBody, "make.field.smsBody", caps: .sentences, autocorrect: true, multiline: true)
        case .phone:
            row(String(localized: "make.field.phone", defaultValue: "Phone number", bundle: .module),
                $model.form.phone, "make.field.phone", field: .phone, keyboard: .phonePad, content: .telephoneNumber)
        case .location:
            row(String(localized: "make.field.latitude", defaultValue: "Latitude", bundle: .module),
                $model.form.latitude, "make.field.latitude", field: .latitude, keyboard: .numbersAndPunctuation)
            row(String(localized: "make.field.longitude", defaultValue: "Longitude", bundle: .module),
                $model.form.longitude, "make.field.longitude", field: .longitude, keyboard: .numbersAndPunctuation)
            row(String(localized: "make.field.place", defaultValue: "Place name (optional)", bundle: .module),
                $model.form.locationQuery, "make.field.place", caps: .words, autocorrect: true)
        }
    }

    @ViewBuilder private var wifi: some View {
        row(String(localized: "make.field.ssid", defaultValue: "Network name", bundle: .module), $model.form.ssid,
            "make.field.ssid", field: .ssid)
        Picker(selection: $model.form.wifiSecurity) {
            ForEach(MakeWiFiSecurity.allCases) { Text(verbatim: $0.title).tag($0) }
        } label: {
            Text(verbatim: String(localized: "make.field.security", defaultValue: "Security", bundle: .module))
        }
        .accessibilityIdentifier("make.field.security")
        if model.form.wifiSecurity != .none {
            row(String(localized: "make.field.wifiPassword", defaultValue: "Password", bundle: .module),
                $model.form.wifiPassword, "make.field.wifiPassword", field: .wifiPassword)
        }
        Toggle(isOn: $model.form.wifiHidden) {
            Text(verbatim: String(localized: "make.field.hidden", defaultValue: "Hidden network", bundle: .module))
        }
        .accessibilityIdentifier("make.field.hidden")
    }

    @ViewBuilder private var contact: some View {
        row(String(localized: "make.field.givenName", defaultValue: "First name", bundle: .module), $model.form.givenName,
            "make.field.givenName", field: .contactName, content: .givenName, caps: .words, autocorrect: true)
        row(String(localized: "make.field.familyName", defaultValue: "Last name", bundle: .module), $model.form.familyName,
            "make.field.familyName", content: .familyName, caps: .words, autocorrect: true)
        row(String(localized: "make.field.organization", defaultValue: "Company", bundle: .module), $model.form.organization,
            "make.field.organization", content: .organizationName, caps: .words, autocorrect: true)
        row(String(localized: "make.field.jobTitle", defaultValue: "Job title", bundle: .module), $model.form.jobTitle,
            "make.field.jobTitle", content: .jobTitle, caps: .words, autocorrect: true)
        row(String(localized: "make.field.contactPhone", defaultValue: "Phone", bundle: .module), $model.form.contactPhone,
            "make.field.contactPhone", field: .contactPhone, keyboard: .phonePad, content: .telephoneNumber)
        row(String(localized: "make.field.contactEmail", defaultValue: "Email", bundle: .module), $model.form.contactEmail,
            "make.field.contactEmail", field: .contactEmail, keyboard: .emailAddress, content: .emailAddress)
        row(String(localized: "make.field.contactURL", defaultValue: "Website", bundle: .module), $model.form.contactURL,
            "make.field.contactURL", keyboard: .URL, content: .URL)
        row(String(localized: "make.field.address", defaultValue: "Address", bundle: .module), $model.form.contactAddress,
            "make.field.address", content: .fullStreetAddress, caps: .words, autocorrect: true, multiline: true)
        row(String(localized: "make.field.note", defaultValue: "Note", bundle: .module), $model.form.contactNote,
            "make.field.note", caps: .sentences, autocorrect: true, multiline: true)
    }

    @ViewBuilder private var event: some View {
        row(String(localized: "make.field.eventTitle", defaultValue: "Title", bundle: .module), $model.form.eventTitle,
            "make.field.eventTitle", field: .eventTitle, caps: .sentences, autocorrect: true)
        Toggle(isOn: $model.form.eventAllDay) {
            Text(verbatim: String(localized: "make.field.allDay", defaultValue: "All day", bundle: .module))
        }
        .accessibilityIdentifier("make.field.allDay")
        let components: DatePickerComponents = model.form.eventAllDay ? [.date] : [.date, .hourAndMinute]
        DatePicker(selection: $model.form.eventStart, displayedComponents: components) {
            Text(verbatim: String(localized: "make.field.starts", defaultValue: "Starts", bundle: .module))
        }
        .accessibilityIdentifier("make.field.starts")
        DatePicker(selection: $model.form.eventEnd, displayedComponents: components) {
            Text(verbatim: String(localized: "make.field.ends", defaultValue: "Ends", bundle: .module))
        }
        .accessibilityIdentifier("make.field.ends")
        if let issue = model.issue(for: .eventEnd) { MakeInlineMessage(text: issue.message) }
        row(String(localized: "make.field.eventLocation", defaultValue: "Location", bundle: .module), $model.form.eventLocation,
            "make.field.eventLocation", caps: .words, autocorrect: true)
        row(String(localized: "make.field.eventURL", defaultValue: "Web address", bundle: .module), $model.form.eventURL,
            "make.field.eventURL", keyboard: .URL, content: .URL)
        row(String(localized: "make.field.eventNotes", defaultValue: "Notes", bundle: .module), $model.form.eventDescription,
            "make.field.eventNotes", caps: .sentences, autocorrect: true, multiline: true)
    }
}
