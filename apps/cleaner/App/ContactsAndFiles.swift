import SwiftUI
import Contacts
import UniformTypeIdentifiers
import CleanerCore
import CleanerPlatform
import SevenKit

struct FilesScreen: View {
    @Environment(CleanerModel.self) private var model
    @State private var importing = false
    @State private var query = ""
    @State private var duplicatesOnly = false
    private var items: [CleanupItem] {
        guard let scan = model.folderScan else { return [] }
        let duplicates = Set(scan.groups.flatMap(\.members))
        return scan.items.filter { (!duplicatesOnly || duplicates.contains($0.id)) && (query.isEmpty || $0.name.localizedCaseInsensitiveContains(query)) }
    }
    var body: some View {
        ScrollView {
            VStack(spacing: Tokens.Space.l) {
                CleanerHero(label: c("Your chosen folders"),value: model.folderScan.map { scan in let summary = StorageSummary(scan.items); return summary.knownBytes == 0 && summary.unknownCount > 0 ? c("Not measured") : byteText(summary.knownBytes) } ?? c("Find the big stuff."),detail: c("Large files and exact copies, in the places you choose."),symbol: "folder.fill",hue: Tokens.dataWarm,valueRole: model.folderScan.map { scan in StorageSummary(scan.items).knownBytes == 0 && StorageSummary(scan.items).unknownCount > 0 ? .title : .display } ?? .title)
                Button { importing = true } label: { Text("Choose a folder").frame(maxWidth: .infinity) }.cleanerPrimary().disabled(model.busy)
                if let scan = model.folderScan {
                    CleanerTiles {
                        CleanerStat(title: c("Files found"),value: scan.items.count.formatted(),symbol: "doc",hue: Tokens.dataWarm)
                        Button { duplicatesOnly.toggle() } label: { CleanerStat(title: c("Duplicate groups"),value: scan.groups.count.formatted(),symbol: "doc.on.doc",detail: duplicatesOnly ? c("Showing duplicates") : c("Tap to inspect"),hue: Tokens.dataWarm) }.buttonStyle(.plain)
                    }
                    FileSizeChart(items: scan.items)
                    HStack { Text(duplicatesOnly ? c("Exact copies") : c("All files")).textRole(.section); Spacer(); if duplicatesOnly { Button("Show all") { duplicatesOnly = false } } }
                    if items.isEmpty { ContentUnavailableView.search(text: query) }
                    VStack(spacing: Tokens.Space.ms) {
                        ForEach(items) { item in
                            HStack(alignment: .top,spacing: Tokens.Space.ms) {
                                Image(systemName: fileSymbol(item.name)).foregroundStyle(Tokens.dataWarm).textRole(.section).accessibilityHidden(true)
                                VStack(alignment: .leading,spacing: Tokens.Space.xs) {
                                    Text(item.name).textRole(.rowTitle).lineLimit(2).truncationMode(.middle)
                                    Text(byteText(item.bytes)).textRole(.caption).numeric()
                                    if scan.groups.contains(where: { $0.members.contains(item.id) }) { Label("Exact duplicate candidate",systemImage: "doc.on.doc").textRole(.caption) }
                                }; Spacer(minLength: 0)
                            }.frame(maxWidth: .infinity,alignment: .leading).padding(Tokens.Space.m).sevenCard().accessibilityElement(children: .combine)
                        }
                    }
                    if StorageSummary(scan.items).unknownCount > 0 { CleanerNotice(text: cCount(Int(StorageSummary(scan.items).unknownCount),key: "unmeasured")) }
                    if scan.skipped > 0 { CleanerNotice(text: cCount(scan.skipped,key: "unreadable")) }
                }
                CleanerNotice(text: c("Only selected folders are scanned. Other apps’ private data and System Data are outside Cleaner’s access."),symbol: "folder.badge.person.crop")
                CleanerNotice(text: c("Cloud placeholders are not downloaded for hashing. File providers may behave differently."))
            }.padding(Tokens.Space.m).frame(maxWidth: 672)
        }.frame(maxWidth: .infinity).cleanerCanvas().navigationTitle("Files").searchable(text: $query)
            .fileImporter(isPresented: $importing,allowedContentTypes: [.folder]) { result in do { model.scanFolder(try result.get()) } catch { model.error = error.cleanerMessage } }
    }
    private func fileSymbol(_ name: String) -> String { switch (name as NSString).pathExtension.lowercased() { case "mov","mp4": "film"; case "zip": "doc.zipper"; case "pdf": "doc.richtext"; default: "doc" } }
}
struct ContactsScreen: View {
    @Environment(CleanerModel.self) private var model
    @State private var region = ""
    @State private var query = ""
    @State private var incomplete = false
    @State private var comparing = false
    private var records: [ContactRecord] { model.contactSnapshot?.records ?? [] }
    var body: some View {
        List {
            VStack(spacing: Tokens.Space.l) {
                CleanerHero(label: records.isEmpty ? c("People, neatly kept") : c("Possible matches"),value: records.isEmpty ? c("Familiar faces.") : model.contactMatches.count.formatted(),detail: c("Compare the details. Keep every person distinct."),symbol: "person.crop.rectangle.stack.fill",valueRole: records.isEmpty ? .title : .display)
                if records.isEmpty {
                    Button { model.scanContacts(region: region.isEmpty ? nil : region) } label: { Text("Scan contacts").frame(maxWidth: .infinity) }.cleanerPrimary().disabled(model.busy)
                } else {
                    Button { comparing = true } label: { Text("Compare contacts").frame(maxWidth: .infinity) }.cleanerPrimary()
                    CleanerTiles {
                        CleanerStat(title: c("Contact records"),value: records.count.formatted(),symbol: "person.crop.rectangle")
                        Button { incomplete.toggle() } label: { CleanerStat(title: c("Missing details"),value: records.filter(\.incomplete).count.formatted(),symbol: "person.crop.circle.badge.questionmark",detail: incomplete ? c("Showing incomplete entries") : c("Worth a closer look")) }.buttonStyle(.plain)
                    }
                    ViewThatFits(in: .horizontal) {
                        HStack(spacing: Tokens.Space.s) { contactActions }
                        VStack(spacing: Tokens.Space.s) { contactActions }
                    }
                }
            }.cleanerBareRow()
            Section {
                Picker("Phone number country",selection: $region) {
                    Text("Not specified").tag("")
                    ForEach(Locale.Region.isoRegions.map(\.identifier).sorted { (Locale.current.localizedString(forRegionCode: $0) ?? $0) < (Locale.current.localizedString(forRegionCode: $1) ?? $1) },id: \.self) { code in Text(Locale.current.localizedString(forRegionCode: code) ?? code).tag(code) }
                }.pickerStyle(.navigationLink)
            } footer: { Text("Choose a country to compare local numbers. Without one, international numbers and email addresses are compared.").textRole(.caption) }
            if let snapshot = model.contactSnapshot {
                Section {
                    if snapshot.limited { CleanerNotice(text: c("Only the contacts you allowed are included."),symbol: "person.crop.circle.badge.checkmark") }
                    if ContactMatching.crowdedIdentifierCount(snapshot.records) > 0 { CleanerNotice(text: c("Some identifiers are shared by over 100 records. Those groups are not expanded into pairs in this build.")) }
                    if incomplete { Button("Show all contacts") { incomplete = false } }
                    ForEach(records.filter { (!incomplete || $0.incomplete) && (query.isEmpty || $0.name.localizedCaseInsensitiveContains(query)) }) { contact in
                        NavigationLink { ContactFields(record: contact) } label: {
                            HStack(spacing: Tokens.Space.ms) {
                                Image(systemName: "person.crop.circle").foregroundStyle(Tokens.dataCool).textRole(.section).accessibilityHidden(true)
                                VStack(alignment: .leading,spacing: Tokens.Space.xs) { Text(contact.name.isEmpty ? c("Unnamed contact") : contact.name).textRole(.rowTitle); Text(contact.fields.first(where: { field in field.key == "email" || field.key == "phone" })?.value ?? c("Review missing details")).textRole(.caption) }
                            }
                        }
                    }
                } header: { Text(incomplete ? c("Missing details") : c("Your contacts")).textRole(.caption) }
                Section {
                    if let url = model.exportURL { ShareLink("Share partial contact archive",item: url) }
                    CleanerNotice(text: c("Archives contain accessible fields. Notes and account relationships are not included. Original contacts are retained."),symbol: "doc.badge.clock")
                }
            } else {
                CleanerNotice(text: c("Permission is requested when you scan. Contact comparison happens on this phone."),symbol: "hand.raised").cleanerBareRow()
            }
        }.cleanerCanvas().navigationTitle("Contacts").searchable(text: $query)
            .navigationDestination(isPresented: $comparing) { ContactGroupsScreen() }
    }
    @ViewBuilder private var contactActions: some View {
        Button { model.scanContacts(region: region.isEmpty ? nil : region) } label: { CleanerChip(title: c("Scan contacts"),symbol: "arrow.clockwise") }.cleanerChip().disabled(model.busy)
        Button { model.exportContacts() } label: { CleanerChip(title: c("Export fields"),symbol: "square.and.arrow.up") }.cleanerChip().disabled(model.busy)
    }
}
struct ContactGroupsScreen: View {
    @Environment(CleanerModel.self) private var model
    var body: some View {
        List {
            CleanerHero(label: c("Possible matches"),value: model.contactMatches.count.formatted(),detail: c("A shared number is a clue, not proof."),symbol: "person.2.fill").cleanerBareRow()
            ForEach(model.contactMatches) { match in
                if let a = model.contactSnapshot?.records.first(where: { $0.id == match.left }),let b = model.contactSnapshot?.records.first(where: { $0.id == match.right }) {
                    NavigationLink { ContactComparison(a: a,b: b,reasons: match.reasons) } label: {
                        VStack(alignment: .leading,spacing: Tokens.Space.s) {
                            Label(a.name,systemImage: "person.crop.circle").textRole(.rowTitle,emphasized: true)
                            Label(b.name,systemImage: "person.crop.circle").textRole(.rowTitle)
                            Text(match.reasons.joined(separator: "; ")).textRole(.caption)
                        }.padding(.vertical,Tokens.Space.s)
                    }
                }
            }
            if model.contactMatches.isEmpty { ContentUnavailableView("No matches yet",systemImage: "person.2",description: Text("Scan contacts to compare possible duplicates.")) }
        }.cleanerCanvas().navigationTitle("Contact candidates").navigationBarTitleDisplayMode(.inline)
    }
}
struct ContactComparison: View {
    let a: ContactRecord
    let b: ContactRecord
    let reasons: [String]
    @Environment(\.dynamicTypeSize) private var typeSize
    var body: some View {
        let plan = ContactMergePlan([a,b],backupVerified: true,revisionUnchanged: true)
        ScrollView {
            VStack(alignment: .leading,spacing: Tokens.Space.l) {
                Label("Compare the details",systemImage: "person.2").textRole(.title)
                Text(reasons.joined(separator: "; ")).textRole(.secondary)
                SevenAdaptiveColumns(accessibilityText: typeSize.isAccessibilitySize) { ContactFieldList(record: a); ContactFieldList(record: b) }
                SectionRule(c("Preservation check"))
                ForEach(plan.blockers,id: \.self) { CleanerNotice(text: $0,symbol: "exclamationmark.triangle",caution: true) }
                if !plan.conflicts.isEmpty { Text(c("Conflicting fields") + ": " + plan.conflicts.map(fieldTitle).joined(separator: ", ")).textRole(.body) }
                CleanerNotice(text: c("Merging and restoration are still under validation. No source contact is removed by this build."),symbol: "person.crop.circle.badge.checkmark")
            }.padding(Tokens.Space.m).frame(maxWidth: 672)
        }.frame(maxWidth: .infinity).cleanerCanvas().navigationTitle("Compare contacts").navigationBarTitleDisplayMode(.inline)
    }
}
struct ContactFields: View {
    let record: ContactRecord
    var body: some View { ScrollView { ContactFieldList(record: record).padding(Tokens.Space.m).frame(maxWidth: 672) }.frame(maxWidth: .infinity).cleanerCanvas().navigationTitle("Contact fields").navigationBarTitleDisplayMode(.inline) }
}
private func fieldTitle(_ key: String) -> String {
    c(["givenName":"First name","familyName":"Last name","middleName":"Middle name","previousFamilyName":"Previous last name","phone":"Phone","email":"Email","organization":"Organization","birthday":"Birthday","address":"Address","url":"Website","relation":"Relationship","social":"Social profile","instantMessage":"Messaging","jobTitle":"Job title","contactType":"Record type","photo":"Contact photo" ][key] ?? key)
}
struct ContactFieldList: View {
    let record: ContactRecord
    var body: some View {
        VStack(alignment: .leading,spacing: Tokens.Space.m) {
            Image(systemName: "person.crop.circle.fill").foregroundStyle(Tokens.dataCool).textRole(.hero).accessibilityHidden(true)
            Text(record.name.isEmpty ? c("Unnamed contact") : record.name).textRole(.section).fixedSize(horizontal: false,vertical: true)
            ForEach(Array(record.fields.enumerated()),id: \.offset) { _,field in
                VStack(alignment: .leading,spacing: Tokens.Space.xs) {
                    Text(field.label.isEmpty ? fieldTitle(field.key) : fieldTitle(field.key) + " · " + CNLabeledValue<NSString>.localizedString(forLabel: field.label)).textRole(.caption)
                    Text(field.value).textRole(.body).fixedSize(horizontal: false,vertical: true).textSelection(.enabled)
                }.accessibilityElement(children: .combine)
            }
            CleanerNotice(text: c("Notes are not accessible in this build."),symbol: "note.text")
        }.frame(maxWidth: .infinity,alignment: .leading).padding(Tokens.Space.m).sevenCard()
    }
}
