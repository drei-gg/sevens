#if canImport(Contacts)
import Foundation
import Contacts
import CleanerCore

public struct ContactSnapshot: Sendable {
    public var records: [ContactRecord]
    public var archives: [String: Data]
    public var limited: Bool
    public init(records: [ContactRecord], archives: [String: Data], limited: Bool) { self.records = records; self.archives = archives; self.limited = limited }
}
public enum ContactArchive {
    public static func encode(_ contact: CNContact) throws -> Data {
        try NSKeyedArchiver.archivedData(withRootObject: contact, requiringSecureCoding: true)
    }
    public static func decode(_ data: Data) throws -> CNContact {
        guard let contact = try NSKeyedUnarchiver.unarchivedObject(ofClass: CNContact.self, from: data) else { throw CleanerError.blocked("Contact archive cannot be read.") }; return contact
    }
    public static func verified(_ contact: CNContact) throws -> Data {
        let data = try encode(contact), restored = try decode(data)
        // CNContact equality includes available fields; explicitly compare common multivalues too.
        guard contact.isEqual(restored), contact.phoneNumbers == restored.phoneNumbers,
              contact.emailAddresses == restored.emailAddresses, contact.postalAddresses == restored.postalAddresses else { throw CleanerError.blocked("Contact archive failed its round-trip check.") }
        return data
    }
}
public actor ContactsService {
    public init() {}
    private static var keys: [CNKeyDescriptor] { [
        CNContactIdentifierKey, CNContactTypeKey, CNContactNamePrefixKey, CNContactGivenNameKey,
        CNContactMiddleNameKey, CNContactFamilyNameKey, CNContactPreviousFamilyNameKey, CNContactNameSuffixKey,
        CNContactNicknameKey, CNContactOrganizationNameKey, CNContactDepartmentNameKey, CNContactJobTitleKey,
        CNContactPhoneticGivenNameKey, CNContactPhoneticMiddleNameKey, CNContactPhoneticFamilyNameKey,
        CNContactPhoneticOrganizationNameKey, CNContactPhoneNumbersKey, CNContactEmailAddressesKey,
        CNContactPostalAddressesKey, CNContactUrlAddressesKey, CNContactBirthdayKey, CNContactNonGregorianBirthdayKey,
        CNContactDatesKey, CNContactRelationsKey, CNContactSocialProfilesKey, CNContactInstantMessageAddressesKey,
        CNContactImageDataKey, CNContactImageDataAvailableKey
    ].map { $0 as CNKeyDescriptor } }
    public func scan(region: String?) async throws -> ContactSnapshot {
        let store = CNContactStore()
        guard try await store.requestAccess(for: .contacts) else { throw CleanerError.blocked("Contacts access was not granted. You can change it in Settings.") }
        var records: [ContactRecord] = []; var archives: [String: Data] = [:]
        let normalizer = PhoneNormalizer()
        for container in try store.containers(matching: nil) {
            let request = CNContactFetchRequest(keysToFetch: Self.keys)
            request.unifyResults = false
            request.predicate = CNContact.predicateForContactsInContainer(withIdentifier: container.identifier)
            try store.enumerateContacts(with: request) { contact, stop in
                if Task.isCancelled { stop.pointee = true; return }
                let record = Self.record(contact, container: container.identifier, region: region, normalizer: normalizer)
                records.append(record)
                // No silent omission: archive failure fails the entire scan's export readiness.
                do { archives[contact.identifier] = try ContactArchive.verified(contact) }
                catch { stop.pointee = true }
            }
        }
        try Task.checkCancellation()
        guard records.count == archives.count else { throw CleanerError.blocked("Some contacts could not be archived and verified. No contacts were changed.") }
        return ContactSnapshot(records: records, archives: archives, limited: CNContactStore.authorizationStatus(for: .contacts) == .limited)
    }
    public static func record(_ c: CNContact, container: String, region: String?, normalizer: PhoneNormalizer = PhoneNormalizer()) -> ContactRecord {
        var fields: [ContactField] = []
        let singles = ["prefix": c.namePrefix, "givenName": c.givenName, "middleName": c.middleName, "familyName": c.familyName,
            "previousFamilyName": c.previousFamilyName, "suffix": c.nameSuffix, "nickname": c.nickname,
            "organization": c.organizationName, "department": c.departmentName, "jobTitle": c.jobTitle,
            "phoneticGiven": c.phoneticGivenName, "phoneticMiddle": c.phoneticMiddleName, "phoneticFamily": c.phoneticFamilyName,
            "phoneticOrganization": c.phoneticOrganizationName]
        for (key, value) in singles where !value.isEmpty { fields.append(ContactField(key, value)) }
        fields += c.phoneNumbers.map { ContactField("phone", $0.value.stringValue, label: $0.label ?? "") }
        fields += c.emailAddresses.map { ContactField("email", $0.value as String, label: $0.label ?? "") }
        fields += c.urlAddresses.map { ContactField("url", $0.value as String, label: $0.label ?? "") }
        fields += c.postalAddresses.map { ContactField("address", CNPostalAddressFormatter.string(from: $0.value, style: .mailingAddress), label: $0.label ?? "") }
        fields += c.contactRelations.map { ContactField("relation", $0.value.name, label: $0.label ?? "") }
        fields += c.socialProfiles.map { ContactField("social", "\($0.value.service): \($0.value.username) \($0.value.urlString) \($0.value.userIdentifier)", label: $0.label ?? "") }
        fields += c.instantMessageAddresses.map { ContactField("instantMessage", "\($0.value.service): \($0.value.username)", label: $0.label ?? "") }
        fields += c.dates.map { ContactField("date", String(describing: $0.value), label: $0.label ?? "") }
        if let value = c.birthday { fields.append(ContactField("birthday", String(describing: value))) }
        if let value = c.nonGregorianBirthday { fields.append(ContactField("nonGregorianBirthday", String(describing: value))) }
        if let image = c.imageData { fields.append(ContactField("photo", "\(image.count) bytes retained in archive")) }
        fields.append(ContactField("contactType", String(c.contactType.rawValue)))
        let phones = Set(c.phoneNumbers.compactMap { normalizer.normalized($0.value.stringValue, region: region) })
        let emails = Set(c.emailAddresses.compactMap { emailKey(raw: $0.value as String) })
        return ContactRecord(id: c.identifier, name: contactDisplayName(given: c.givenName, middle: c.middleName, family: c.familyName, organization: c.organizationName),
                             container: container, fields: sortContactFields(fields: fields), phoneKeys: phones, emailKeys: emails)
    }
    /// This package is explicitly a partial archive, not a complete address-book backup.
    public func export(_ snapshot: ContactSnapshot, to url: URL) throws {
        let package = newContactPackage(records: snapshot.records, archives: snapshot.archives)
        try contactPackageEncode(package: package).write(to: url, options: [.atomic, .completeFileProtection])
        try contactPackageVerify(written: package, readBack: Data(contentsOf: url))
    }
}
#endif
