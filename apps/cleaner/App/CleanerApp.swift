import SwiftUI
import SevenKit
import CleanerCore
import CleanerPlatform
import UniformTypeIdentifiers

@main struct CleanerApp: App {
    @State private var model = CleanerModel()
    @Environment(\.scenePhase) private var phase
    var body: some Scene {
        WindowGroup {
            CleanerLaunchRoot().environment(model).sevenStore(model.store,unlock: CleanerGate.unlock).sevenLook()
                .task { await model.start() }
                .onChange(of: phase) { _, value in if value == .active { Task { await model.store.refresh() } } }
        }
    }
}
struct CleanerHome: View {
    @Environment(CleanerModel.self) private var model
    @State private var before = false
    var body: some View {
        TabView {
            NavigationStack { Dashboard() }.tabItem { Label("Overview",systemImage: "square.grid.2x2") }
            NavigationStack { MediaLibrary(kind: .photo) }.tabItem { Label("Photos",systemImage: "photo.on.rectangle") }
            NavigationStack { MediaLibrary(kind: .video) }.tabItem { Label("Videos",systemImage: "video") }
            NavigationStack { FilesScreen() }.tabItem { Label("Files",systemImage: "folder") }
            NavigationStack { ContactsScreen() }.tabItem { Label("Contacts",systemImage: "person.crop.rectangle") }
        }.sevenAdaptiveTabs()
            .safeAreaInset(edge: .bottom) {
                if model.busy {
                    VStack(spacing: Tokens.Space.s) {
                        if let fraction = model.comparisonFraction { ProgressView(value: fraction).accessibilityLabel("Comparison progress") }
                        HStack(spacing: Tokens.Space.s) {
                            if model.comparisonFraction == nil { ProgressView() }
                            Text(model.progress).textRole(.caption); Spacer()
                            Button("Cancel") { model.cancel() }.disabled(!model.canCancel)
                        }
                    }.padding(Tokens.Space.ms).background(.bar)
                }
            }
            .task(id: model.store.hasStarted) { if model.store.hasStarted && model.store.launchSheet == .beforeYouBuy { before = true } }
            .sheet(isPresented: $before) { BeforeYouBuyView(content: CleanerGate.before) { _ in before = false } }
            .trialEndedSheet(content: CleanerGate.expired)
            .sheet(isPresented: Binding(get: { model.candidate != nil || model.compressionBatch != nil },set: { if !$0 { model.stopCompressionBatch() } })) { CompressionScreen() }
            .sheet(isPresented: Binding(get: { model.videoReview != nil }, set: { if !$0 { model.closeVideo() } })) { if let review = model.videoReview { VideoReviewScreen(review: review) } }
            .alert("Could not complete",isPresented: Binding(get: { model.error != nil && model.candidate == nil && model.compressionBatch == nil && model.videoReview == nil },set: { shown in if !shown { model.error = nil } })) { Button("OK",role: .cancel) { model.error = nil } } message: { Text(model.error ?? "") }
    }
}
func byteText(_ bytes: Int64?) -> String { bytes.map { ByteCountFormatter.string(fromByteCount: $0,countStyle: .file) } ?? c("Not measured") }
struct Dashboard: View {
    @Environment(CleanerModel.self) private var model
    private var summary: StorageSummary { StorageSummary(model.media) }
    private var pending: Int { model.media.filter { model.state.decisions[$0.id] == .remove }.count }
    var body: some View {
        ScrollView {
            VStack(spacing: Tokens.Space.l) {
                CleanerHero(label: model.media.isEmpty ? c("Make room for more") : c("Your accessible library"),value: model.media.isEmpty ? c("Room to breathe.") : (summary.knownBytes > 0 ? byteText(summary.knownBytes) : model.media.count.formatted()),detail: model.media.isEmpty ? c("Keep the memories. Find the clutter.") : (summary.knownBytes > 0 ? c("Measured media, ready for a closer look.") : c("Accessible items. Sizes are not measured yet.")),symbol: "square.stack.3d.up.fill",valueRole: model.media.isEmpty ? .title : .display)
                if !model.groups.isEmpty {
                    NavigationLink { GroupsScreen() } label: { Text("Review matches").frame(maxWidth: .infinity) }.cleanerPrimary().accessibilityIdentifier("cleaner.review-groups")
                } else {
                    Button { model.media.isEmpty ? model.scanPhotos() : model.analyze() } label: { Text(model.media.isEmpty ? c("Scan library") : c(model.savedMeasurements > 0 ? "Continue comparison" : "Find duplicates")).frame(maxWidth: .infinity) }.cleanerPrimary().disabled(model.busy).accessibilityIdentifier("cleaner.scan")
                }
                if model.savedMeasurements > 0 { CleanerNotice(text: "Saved measurements: \(model.savedMeasurements). Continue after a fresh library check.") }
                CleanerTiles {
                    NavigationLink { GroupsScreen() } label: { CleanerStat(title: c("Matching groups"),value: model.groups.count.formatted(),symbol: "square.on.square",detail: c("Compare before choosing")) }.buttonStyle(.plain).accessibilityIdentifier("cleaner.groups")
                    NavigationLink { PendingScreen() } label: { CleanerStat(title: c("Pending removal"),value: pending.formatted(),symbol: "tray",detail: c("Nothing deleted yet"),hue: Tokens.dataWarm) }.buttonStyle(.plain).accessibilityIdentifier("cleaner.pending").accessibilityValue(pending.formatted())
                }
                if !model.media.isEmpty { LibraryComposition(items: model.media) }
                VStack(alignment: .leading,spacing: Tokens.Space.ms) {
                    SectionRule(c("A place for everything"))
                    CleanerTiles {
                        NavigationLink { MediaLibrary(kind: .photo) } label: { CleanerStat(title: c("Photos"),value: model.media.filter { item in item.kind == .photo }.count.formatted(),symbol: "photo.on.rectangle",detail: c("Duplicates, screenshots, keeps")) }.buttonStyle(.plain)
                        NavigationLink { MediaLibrary(kind: .video) } label: { CleanerStat(title: c("Videos"),value: model.media.filter { item in item.kind == .video }.count.formatted(),symbol: "video",detail: c("Start with the largest"),hue: Tokens.dataWarm) }.buttonStyle(.plain)
                    }
                }
                ViewThatFits(in: .horizontal) {
                    HStack(spacing: Tokens.Space.s) { quickActions }
                    VStack(spacing: Tokens.Space.s) { quickActions }
                }
                VStack(alignment: .leading,spacing: Tokens.Space.ms) {
                    if model.limitedPhotos { CleanerNotice(text: c("Only the photos you allowed are included."),symbol: "photo.badge.checkmark") }
                    if summary.unknownCount > 0 { CleanerNotice(text: cCount(Int(summary.unknownCount),key: "unmeasured")) }
                    CleanerNotice(text: c("Media size is not space freed on this phone. iCloud and Recently Deleted affect local storage."))
                }
            }.padding(Tokens.Space.m).frame(maxWidth: 672)
        }.frame(maxWidth: .infinity).cleanerCanvas().navigationTitle("Cleaner")
            .toolbar { NavigationLink { CleanerSettings() } label: { Image(systemName: "gearshape").accessibilityLabel("Settings") } }
    }
    @ViewBuilder private var quickActions: some View {
        Button { model.scanPhotos() } label: { CleanerChip(title: c("Refresh"),symbol: "arrow.clockwise") }.cleanerChip().disabled(model.busy)
        NavigationLink { ActivityScreen() } label: { CleanerChip(title: c("Activity"),symbol: "clock") }.cleanerChip()
    }
}
struct CleanerSettings: View {
    @Environment(CleanerModel.self) private var model
    @State private var before = false
    var body: some View {
        Form {
            Section {
                Toggle("Allow iCloud downloads",isOn: Binding(get: { model.allowCloud },set: { model.allowCloud = $0 }))
            } header: { Text("Cloud originals").textRole(.caption) } footer: { Text("Off by default. Reading original media may require downloads and temporary storage.").textRole(.caption) }
            Section {
                Text("Saved measurements: \(model.savedMeasurements)").textRole(.body)
                Button("Clear scan progress") { model.clearScanProgress() }.disabled(model.busy).accessibilityIdentifier("clear-scan-progress")
                Text("Clearing progress keeps your media, review decisions and activity history.").textRole(.caption)
            } header: { Text("Saved scan progress").textRole(.caption) }
              footer: { Text("Completed comparisons stay on this device. Scan library checks access and changes before continuing. Closing Cleaner pauses work; it does not scan in the background.").textRole(.caption) }
            Section { Text("No Cleaner account, analytics, ads or servers. Apple Photos and your chosen file provider may fetch cloud content when you permit it.").textRole(.body) } header: { Text("Privacy").textRole(.caption) }
            Section { Text("Compression saves a separate copy. The current still-image profile excludes HDR, RAW, Live Photos, transparency and unverified formats.").textRole(.body); Text("Contact archives contain accessible fields. Notes require additional Apple permission, so archives are labelled partial and destructive merges are not enabled.").textRole(.body) } header: { Text("Preservation").textRole(.caption) }
            Section {
                Text("PhoneNumberKit 5.0.14 · MIT\nGoogle libphonenumber metadata · Apache-2.0").textRole(.secondary)
                NavigationLink("PhoneNumberKit license") { LicenseScreen(name: "PhoneNumberKit-LICENSE") }
                NavigationLink("Libphonenumber metadata license") { LicenseScreen(name: "Libphonenumber-LICENSE") }
                Text("Built with PhotoKit, Vision, Image I/O, Accelerate, CryptoKit, Contacts and SevenKit.").textRole(.caption)
            } header: { Text("Open-source work").textRole(.caption) }
            AboutSection(onShowBeforeYouBuy: { before = true })
        }.cleanerCanvas().navigationTitle("Settings").navigationBarTitleDisplayMode(.inline).sheet(isPresented: $before) { BeforeYouBuyView(content: CleanerGate.before) { _ in before = false } }
    }
}
struct MediaLibrary: View {
    @Environment(CleanerModel.self) private var model
    @Environment(\.dynamicTypeSize) private var textSize
    let kind: MediaKind
    @State private var query = ""
    @State private var dateMode = MediaDateFilter.any
    @State private var referenceDate = Date()
    @State private var dateEditor = false
    @State private var category = "All"
    @State private var order = "Newest"
    @State private var album = false
    @State private var albumName = ""
    private var library: [CleanupItem] { model.media.filter { $0.kind == kind } }
    private var visible: [CleanupItem] {
        library.filter { item in matchesDate(item.date, mode: dateMode, reference: referenceDate) && (query.isEmpty || item.name.localizedCaseInsensitiveContains(query)) && (category == "All" || item.tags.contains(category) || (category == "Keep" && model.state.decisions[item.id] == .keep) || (category == "Later" && model.state.decisions[item.id] == .later)) }.sorted {
            if order == "Largest" { return ($0.bytes ?? -1) > ($1.bytes ?? -1) }; return ($0.date ?? .distantPast) > ($1.date ?? .distantPast)
        }
    }
    var body: some View {
        ScrollView {
            VStack(spacing: Tokens.Space.l) {
                CleanerHero(label: kind == .photo ? c("Your photo library") : c("The bigger picture"),value: kind == .video && StorageSummary(library).knownBytes > 0 ? byteText(StorageSummary(library).knownBytes) : library.count.formatted(),detail: kind == .photo ? c("A little less clutter. The moments stay yours.") : c("Review the clips taking up room."),symbol: kind == .photo ? "photo.stack.fill" : "video.fill",hue: kind == .photo ? Tokens.dataCool : Tokens.dataWarm)
                Button { library.isEmpty ? model.scanPhotos() : model.analyze() } label: { Text(library.isEmpty ? c("Scan library") : c(model.savedMeasurements > 0 ? "Continue comparison" : "Find duplicates")).frame(maxWidth: .infinity) }.cleanerPrimary().disabled(model.busy)
                if !library.isEmpty {
                    ViewThatFits(in: .horizontal) {
                        HStack(spacing: Tokens.Space.s) { reviewActions }
                        VStack(spacing: Tokens.Space.s) { reviewActions }
                    }
                    CleanerTiles {
                        categoryTile(kind == .photo ? "Screenshots" : "Screen recordings",symbol: "rectangle.dashed")
                        categoryTile(kind == .photo ? "Live Photos" : "High frame rate",symbol: kind == .photo ? "livephoto" : "speedometer")
                    }
                }
                if !model.selected.isEmpty {
                    VStack(spacing: Tokens.Space.ms) {
                        Text(cCount(model.selected.count,key: "selected")).textRole(.rowTitle,emphasized: true).numeric()
                        ViewThatFits(in: .horizontal) {
                            HStack(spacing: Tokens.Space.s) { selectionActions }
                            VStack(spacing: Tokens.Space.s) { selectionActions }
                        }
                        if kind == .photo {
                            Button("Review selected photos") { model.startCompressionBatch(model.selected.intersection(Set(library.map(\.id)))) }
                                .disabled(model.busy || model.selected.intersection(Set(library.map(\.id))).isEmpty)
                        }
                        NavigationLink("Review pending removal") { PendingScreen() }
                    }.padding(Tokens.Space.m).sevenCard()
                }
                if library.isEmpty { CleanerNotice(text: c("Choose which photos Cleaner can access when you scan. Originals stay in Photos."),symbol: "photo.badge.checkmark") }
                else {
                    HStack { Text(category == "All" ? c("Browse your library") : c(category)).textRole(.section); Spacer(); Text(visible.count.formatted()).textRole(.caption).numeric().accessibilityIdentifier("cleaner-visible-count") }
                    if dateMode != .any {
                        Button { dateMode = .any } label: { Label(dateSummary(dateMode, date: referenceDate), systemImage: "xmark.circle") }
                            .accessibilityLabel("Clear date filter: " + dateSummary(dateMode, date: referenceDate)).accessibilityIdentifier("cleaner-date-active")
                    }
                    if visible.isEmpty { ContentUnavailableView.search(text: query) }
                    LazyVGrid(columns: Array(repeating: GridItem(.flexible(),spacing: Tokens.Space.ms,alignment: .top),count: textSize.isAccessibilitySize ? 1 : 2),spacing: Tokens.Space.ms) {
                        ForEach(visible) { item in MediaTile(item: item) }
                    }
                }
            }.padding(Tokens.Space.m).frame(maxWidth: 672)
        }.frame(maxWidth: .infinity).cleanerCanvas().navigationTitle(kind == .photo ? c("Photos") : c("Videos")).searchable(text: $query)
            .toolbar {
                Menu {
                    Picker("Collection",selection: $category) { ForEach(kind == .photo ? ["All","Screenshots","Live Photos","Bursts","HDR","Keep","Later"] : ["All","Screen recordings","High frame rate","Keep","Later"],id: \.self) { Text(c($0)).tag($0) } }
                    Button("Recorded date…") { dateEditor = true }.accessibilityIdentifier("cleaner-date-open")
                    Picker("Sort",selection: $order) { Text("Newest").tag("Newest"); Text("Largest").tag("Largest") }
                    Button("Read Photos library") { model.scanPhotos() }
                } label: { Image(systemName: "line.3.horizontal.decrease").accessibilityLabel("Sort and filter") }
                Menu {
                    Button("Select visible") { model.selected = Set(visible.map(\.id)) }; Button("Deselect all") { model.selected = [] }
                    Button("Invert visible selection") { model.selected.formSymmetricDifference(Set(visible.map(\.id))) }
                    Button("Keep selected") { Task { await model.decide(model.selected,.keep) } }
                    Button("Decide later") { Task { await model.decide(model.selected,.later) } }
                    Button("Queue removal") { Task { await model.decide(model.selected,.remove) } }
                    if kind == .photo {
                        Button("Review selected photos") { model.startCompressionBatch(model.selected.intersection(Set(library.map(\.id)))) }
                            .disabled(model.busy || model.selected.intersection(Set(library.map(\.id))).isEmpty)
                            .accessibilityIdentifier("compression-batch-open")
                    }
                    Button("Create album with selected") { album = true }
                } label: { Image(systemName: "checklist").accessibilityLabel("Batch actions") }
            }
            .sheet(isPresented: $dateEditor) { DateFilterEditor(mode: dateMode, reference: referenceDate) { mode, date in dateMode = mode; referenceDate = date } }
            .alert("New album",isPresented: $album) { TextField("Album name",text: $albumName); Button("Create") { let ids = model.selected,title = albumName; model.run("Creating album…",cancellable: false) { try await model.photos.addToAlbum(ids: ids,title: title) } }; Button("Cancel",role: .cancel) {} }
    }
    @ViewBuilder private var selectionActions: some View {
        Button("Keep") { Task { await model.decide(model.selected,.keep) } }.cleanerChip()
        Button("Later") { Task { await model.decide(model.selected,.later) } }.cleanerChip()
        Button("Queue removal") { Task { await model.decide(model.selected,.remove) } }.cleanerChip()
    }
    @ViewBuilder private var reviewActions: some View {
        NavigationLink { GroupsScreen() } label: { CleanerChip(title: c("Compare groups"),symbol: "square.on.square") }.cleanerChip()
        if kind == .photo { Button { model.findSimilar() } label: { CleanerChip(title: c("Similar shots"),symbol: "sparkle.magnifyingglass") }.cleanerChip().disabled(model.busy) }
        else { Button { order = "Largest" } label: { CleanerChip(title: c("Largest first"),symbol: "arrow.down") }.cleanerChip() }
    }
    private func categoryTile(_ title: String,symbol: String) -> some View {
        Button { category = category == title ? "All" : title } label: {
            CleanerStat(title: c(title),value: library.filter { $0.tags.contains(title) }.count.formatted(),symbol: symbol,detail: category == title ? c("Showing this collection") : nil,hue: kind == .photo ? Tokens.dataCool : Tokens.dataWarm)
        }.buttonStyle(.plain).accessibilityHint("Filters the library to this collection")
    }
}
struct MediaTile: View {
    @Environment(CleanerModel.self) private var model
    let item: CleanupItem
    var body: some View {
        VStack(alignment: .leading,spacing: Tokens.Space.s) {
            NavigationLink { MediaDetail(item: item) } label: {
                VStack(alignment: .leading,spacing: Tokens.Space.s) {
                    Color.clear.aspectRatio(1.2,contentMode: .fit).overlay { PhotoThumbnail(id: item.id,pixelSize: 720) }.clipped().clipShape(RoundedRectangle(cornerRadius: Tokens.Radius.card,style: .continuous))
                    Text(item.name).textRole(.rowTitle).lineLimit(2).truncationMode(.middle)
                    Text(byteText(item.bytes)).textRole(.caption).numeric()
                }
            }.buttonStyle(.plain)
            HStack {
                if item.favorite { Label("Favorite",systemImage: "heart.fill").textRole(.caption) }
                else if let decision = model.state.decisions[item.id],decision != .undecided { Text(c(decision.rawValue.capitalized)).textRole(.caption) }
                Spacer(minLength: Tokens.Space.xs)
                Button { if !model.selected.insert(item.id).inserted { model.selected.remove(item.id) } } label: { Image(systemName: model.selected.contains(item.id) ? "checkmark.circle.fill" : "circle").frame(minWidth: 44,minHeight: 44) }.buttonStyle(.borderless).accessibilityLabel(c("Select") + " " + item.name).accessibilityValue(model.selected.contains(item.id) ? c("Selected") : c("Not selected"))
            }
        }.frame(maxWidth: .infinity,maxHeight: .infinity,alignment: .topLeading).padding(Tokens.Space.ms).sevenCard()
    }
}
struct MediaRow: View {
    @Environment(CleanerModel.self) private var model
    let item: CleanupItem
    var body: some View {
        HStack(spacing: Tokens.Space.ms) {
            PhotoThumbnail(id: item.id).frame(width: 56,height: 56).clipped().clipShape(RoundedRectangle(cornerRadius: Tokens.Space.s,style: .continuous)).accessibilityHidden(true)
            VStack(alignment: .leading,spacing: Tokens.Space.xs) { Text(item.name).textRole(.rowTitle).lineLimit(2).truncationMode(.middle); Text(byteText(item.bytes)).textRole(.caption).numeric() }
            if item.favorite { Image(systemName: "heart.fill").foregroundStyle(Tokens.muted).accessibilityLabel("Favorite; protected from batch removal") }
        }
    }
}
struct GroupsScreen: View {
    @Environment(CleanerModel.self) private var model
    var body: some View {
        ScrollView {
            VStack(spacing: Tokens.Space.l) {
                CleanerHero(label: c("A closer look"),value: model.groups.count.formatted(),detail: c("Matching groups. You choose what stays."),symbol: "square.on.square")
                Button { model.smartSelect() } label: { Text("Select suggested exact copies").frame(maxWidth: .infinity) }.cleanerPrimary().disabled(model.groups.isEmpty)
                if !model.selected.isEmpty {
                    Button(cCount(model.selected.count,key: "queue")) { Task { await model.decide(model.selected,.remove) } }.cleanerChip().accessibilityIdentifier("cleaner.queue-selection")
                }
                NavigationLink("Review pending removal") { PendingScreen() }
                ForEach(model.groups) { group in
                    VStack(alignment: .leading,spacing: Tokens.Space.ms) {
                        Text(group.kind == .identicalResources ? c("Identical resources") : c("Similar moments")).textRole(.section)
                        CleanerTiles { ForEach(group.members.compactMap { id in model.media.first { $0.id == id } }) { item in MediaTile(item: item) } }
                        DisclosureGroup("Why these match") { Text(group.reason).textRole(.caption) }
                    }
                }
                CleanerNotice(text: c("Similar photos can show different moments. Matching bytes can still have different dates or album placement."))
                if model.groups.isEmpty { ContentUnavailableView("No groups yet",systemImage: "square.on.square",description: Text("Run an exact or similar scan from Photos or Videos.")) }
            }.padding(Tokens.Space.m).frame(maxWidth: 672)
        }.frame(maxWidth: .infinity).cleanerCanvas().navigationTitle("Compare groups").navigationBarTitleDisplayMode(.inline)
    }
}
struct PendingScreen: View {
    @Environment(CleanerModel.self) private var model
    @State private var confirm = false
    @State private var approved: Set<String> = []
    private var items: [CleanupItem] { model.media.filter { model.state.decisions[$0.id] == .remove } }
    var body: some View {
        List {
            CleanerHero(label: c("Nothing deleted yet"),value: items.count.formatted(),detail: c("Items queued for your final review."),symbol: "tray",hue: Tokens.dataWarm).cleanerBareRow()
            ForEach(items) { item in HStack { MediaRow(item: item); Spacer(); Button("Undo") { Task { await model.decide([item.id],.undecided) } }.frame(minHeight: 44) } }
            Section {
                CleanerNotice(text: c("Deleting from Photos also deletes through iCloud Photos. Recently Deleted may keep using storage. Favorites are protected."))
                Button("Delete reviewed items…",role: .destructive) { approved = Set(items.map(\.id)); confirm = true }.disabled(items.isEmpty || model.busy)
            }
        }.cleanerCanvas().navigationTitle("Pending removal").navigationBarTitleDisplayMode(.inline)
            .confirmationDialog(cCount(approved.count,key: "delete"),isPresented: $confirm,titleVisibility: .visible) { Button("Delete from Photos",role: .destructive) { model.deleteReviewed(approved) } }
    }
}
struct ActivityScreen: View {
    @Environment(CleanerModel.self) private var model
    var body: some View {
        List {
            if model.state.history.isEmpty { ContentUnavailableView("Your review history",systemImage: "clock",description: Text("Completed scans and actions appear here.")) }
            ForEach(model.state.history) { entry in VStack(alignment: .leading,spacing: Tokens.Space.s) { Text(entry.message).textRole(.body); Text(entry.date,style: .date).textRole(.caption).numeric() }.padding(.vertical,Tokens.Space.xs) }
        }.cleanerCanvas().navigationTitle("Activity").navigationBarTitleDisplayMode(.inline)
    }
}
struct LicenseScreen: View {
    let name: String
    var body: some View {
        ScrollView { if let url = Bundle.main.url(forResource: name,withExtension: "txt"),let text = try? String(contentsOf: url,encoding: .utf8) { Text(text).textRole(.body).padding(Tokens.Space.m).textSelection(.enabled) } else { Text("License text could not be loaded.") } }.cleanerCanvas().navigationTitle("Open-source license").navigationBarTitleDisplayMode(.inline)
    }
}
