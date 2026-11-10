import PhotosUI
import QRCore
import SevenKit
import SwiftUI

/// The home screen (PLAN §6.6): the live scanner, a torch, photo and paste, and the way to
/// Make, History and Settings. Showing a result is not acting (D7): a scan only opens the
/// result sheet; nothing is opened, joined or paid without a tap there.
public struct ScanScreen: View {
    @Environment(AppModel.self) private var model
    @Environment(\.openURL) private var openURL
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.dynamicTypeSize) private var typeSize

    @State private var torch = TorchController()
    @State private var availability = ScannerView.availability
    @State private var result: ScanScreenResult?
    @State private var candidates: [ScanScreenCandidate]?
    @State private var chosen: ScanScreenCandidate?
    @State private var multipleInView = false
    @State private var photoItem: PhotosPickerItem?
    @State private var message: String?
    @State private var working = false

    public init() {}

    private var sheetIsUp: Bool { result != nil || candidates != nil }

    public var body: some View {
        content
            .background(Tokens.paper.ignoresSafeArea())
            .navigationTitle(String(localized: "scan.title", defaultValue: "Scan", bundle: .module))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { toolbar }
            .sheet(item: $result) { shown in
                ResultSheet(analysis: shown.analysis)
                    // At accessibility sizes the half-height sheet shows only the verdict; open it full.
                    .presentationDetents(typeSize.isAccessibilitySize ? [.large] : [.medium, .large])
            }
            .sheet(item: Binding(get: { candidates.map(CandidateList.init) }, set: { if $0 == nil { candidates = nil } }),
                   onDismiss: showChosen) { list in
                ScanScreenPickList(candidates: list.items, onPick: { pick in
                    chosen = pick
                    candidates = nil
                }, onCancel: { candidates = nil })
            }
            .onChange(of: model.pendingCode, initial: true) { _, _ in
                guard model.pendingCode != nil, let code = model.takePendingCode() else { return }
                Task { await present(code) }
            }
            .onChange(of: photoItem) { _, item in
                guard let item else { return }
                Task { await scanPhoto(item) }
            }
            .onChange(of: sheetIsUp) { _, up in if up { torch.turnOff() } }
            .onChange(of: scenePhase) { _, phase in
                if phase == .active {
                    availability = ScannerView.availability
                    torch.refresh()
                } else {
                    torch.turnOff()
                }
            }
            .onChange(of: message) { _, text in
                if let text { AccessibilityNotification.Announcement(text).post() }
            }
            .onDisappear { torch.turnOff() }
    }

    // MARK: Layout

    @ViewBuilder
    private var content: some View {
        if availability == .available {
            VStack(spacing: Tokens.Space.m) {
                camera
                statusLine
                actions
            }
            .padding(Tokens.Space.m)
        } else {
            ScrollView {
                VStack(spacing: Tokens.Space.m) {
                    fallbackHeader
                    statusLine
                    actions
                }
                .padding(Tokens.Space.m)
            }
        }
    }

    private var camera: some View {
        ScannerView(
            isActive: !sheetIsUp && scenePhase == .active,
            onCode: { code in Task { await present(code) } },
            onMultipleChange: { many in
                multipleInView = many
                if many {
                    AccessibilityNotification.Announcement(Self.tapACodeText).post()
                }
            },
            onStartFailure: { _ in availability = .unsupported }
        )
        .clipShape(RoundedRectangle(cornerRadius: Tokens.Radius.card))
        .frame(minHeight: 220)
        .accessibilityLabel(String(localized: "scan.camera.a11y", defaultValue: "Camera viewfinder for scanning codes", bundle: .module))
        .overlay(alignment: .top) {
            if multipleInView {
                Text(Self.tapACodeText)
                    .font(.headline)
                    .foregroundStyle(Tokens.ink)
                    .padding(.horizontal, Tokens.Space.m)
                    .padding(.vertical, Tokens.Space.s)
                    .background(Tokens.card, in: Capsule())
                    .padding(Tokens.Space.m)
                    .transition(reduceMotion ? .identity : .opacity)
            }
        }
        .overlay(alignment: .bottomTrailing) {
            if torch.isAvailable { torchButton.padding(Tokens.Space.m) }
        }
        .animation(reduceMotion ? nil : .default, value: multipleInView)
    }

    private var torchButton: some View {
        Button { torch.toggle() } label: {
            Image(systemName: torch.isOn ? "flashlight.on.fill" : "flashlight.off.fill")
                .font(.title2)
                .foregroundStyle(Tokens.ink)
                .frame(minWidth: 44, minHeight: 44)
                .background(Tokens.card, in: Circle())
        }
        .accessibilityLabel(String(localized: "scan.torch", defaultValue: "Torch", bundle: .module))
        .accessibilityValue(torch.isOn
            ? String(localized: "scan.torch.on", defaultValue: "On", bundle: .module)
            : String(localized: "scan.torch.off", defaultValue: "Off", bundle: .module))
    }

    private var fallbackHeader: some View {
        VStack(spacing: Tokens.Space.s) {
            Image(systemName: availability == .unsupported ? "photo.on.rectangle" : "camera.fill")
                .font(.largeTitle)
                .foregroundStyle(Tokens.muted)
                .accessibilityHidden(true)
            Text(fallbackText)
                .font(.body)
                .foregroundStyle(Tokens.ink)
                .multilineTextAlignment(.center)
            if availability == .denied {
                Button(String(localized: "scan.denied.settings", defaultValue: "Open Settings", bundle: .module)) {
                    if let url = URL(string: UIApplication.openSettingsURLString) { openURL(url) }
                }
                .buttonStyle(.bordered)
                .controlSize(.large)
                .tint(Tokens.ink)
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, Tokens.Space.l)
    }

    private var fallbackText: String {
        switch availability {
        case .denied:
            return String(localized: "scan.denied.text",
                          defaultValue: "Camera access is off. You can still scan a photo or what you copied, or turn the camera on in Settings.",
                          bundle: .module)
        case .restricted:
            return String(localized: "scan.restricted.text",
                          defaultValue: "The camera is turned off on this device. You can still scan a photo or what you copied.",
                          bundle: .module)
        default:
            return String(localized: "scan.unsupported.text",
                          defaultValue: "This device can't scan with the camera. You can still scan a photo or what you copied.",
                          bundle: .module)
        }
    }

    @ViewBuilder
    private var statusLine: some View {
        if let message {
            Text(message)
                .font(.callout)
                .foregroundStyle(Tokens.ink)
                .multilineTextAlignment(.center)
                .frame(maxWidth: .infinity)
        } else if working {
            ProgressView().accessibilityLabel(String(localized: "scan.working", defaultValue: "Reading", bundle: .module))
        }
    }

    private var actions: some View {
        ViewThatFits(in: .horizontal) {
            HStack(spacing: Tokens.Space.m) { photoButton; pasteButton }
            VStack(spacing: Tokens.Space.m) { photoButton; pasteButton }
        }
    }

    private var photoButton: some View {
        PhotosPicker(selection: $photoItem, matching: .images, photoLibrary: .shared()) {
            Label(String(localized: "scan.photo", defaultValue: "Photo", bundle: .module), systemImage: "photo")
                .font(.headline)
                .frame(maxWidth: .infinity, minHeight: 28)
        }
        .buttonStyle(.bordered)
        .controlSize(.large)
        .tint(Tokens.ink)
        .accessibilityLabel(String(localized: "scan.photo.a11y", defaultValue: "Scan a photo", bundle: .module))
        .accessibilityIdentifier(QRAccessibilityID.scanPhoto)
    }

    private var pasteButton: some View {
        PasteScanButton { codes in
            Task { await handle(codes, empty: Self.nothingToPasteText) }
        }
        .controlSize(.large)
        .accessibilityIdentifier(QRAccessibilityID.scanPaste)
    }

    @ToolbarContentBuilder
    private var toolbar: some ToolbarContent {
        ToolbarItem(placement: .topBarLeading) {
            NavigationLink(value: RootRoute.settings) {
                Image(systemName: "gearshape")
            }
            .accessibilityLabel(String(localized: "scan.settings", defaultValue: "Settings", bundle: .module))
            .accessibilityIdentifier(QRAccessibilityID.scanSettings)
        }
        ToolbarItemGroup(placement: .topBarTrailing) {
            NavigationLink(value: RootRoute.history) {
                Image(systemName: "clock.arrow.circlepath")
            }
            .accessibilityLabel(String(localized: "scan.history", defaultValue: "History", bundle: .module))
            .accessibilityIdentifier(QRAccessibilityID.scanHistory)
            NavigationLink(value: RootRoute.make) {
                Image(systemName: "qrcode")
            }
            .accessibilityLabel(String(localized: "scan.make", defaultValue: "Make a code", bundle: .module))
            .accessibilityIdentifier(QRAccessibilityID.scanMake)
        }
    }

    // MARK: Text

    private static var tapACodeText: String {
        String(localized: "scan.multiple", defaultValue: "Tap a code", bundle: .module)
    }
    private static var nothingToPasteText: String {
        String(localized: "scan.paste.empty", defaultValue: "Nothing to scan on the clipboard", bundle: .module)
    }
    private static var noCodeText: String {
        String(localized: "scan.photo.none", defaultValue: "No code found in this image", bundle: .module)
    }
    private static var photoFailedText: String {
        String(localized: "scan.photo.failed", defaultValue: "That photo couldn't be read", bundle: .module)
    }

    // MARK: Actions

    /// Analyzes, saves to History (when it is on and allowed), and shows the sheet.
    private func present(_ code: ScannedCode) async {
        guard !sheetIsUp else { return }
        message = nil
        let analysis = await model.analysis(of: code)
        await model.record(analysis, kind: .scanned)
        guard !sheetIsUp else { return }
        result = ScanScreenResult(analysis: analysis)
    }

    private func showChosen() {
        guard let pick = chosen else { return }
        chosen = nil
        Task {
            await model.record(pick.analysis, kind: .scanned)
            result = ScanScreenResult(analysis: pick.analysis)
        }
    }

    private func scanPhoto(_ item: PhotosPickerItem) async {
        working = true
        message = nil
        defer { working = false; photoItem = nil }
        do {
            let codes = try await ImageScanner().scan(item)
            await handle(codes, empty: Self.noCodeText)
        } catch {
            message = Self.photoFailedText
        }
    }

    private func handle(_ codes: [ScannedCode], empty: String) async {
        message = nil
        switch codes.count {
        case 0:
            message = empty
        case 1:
            await present(codes[0])
        default:
            var list: [ScanScreenCandidate] = []
            for (index, code) in codes.enumerated() {
                list.append(ScanScreenCandidate(index: index, analysis: await model.analysis(of: code)))
            }
            candidates = list
        }
    }

    private struct CandidateList: Identifiable {
        let id = "candidates"
        let items: [ScanScreenCandidate]
        init(_ items: [ScanScreenCandidate]) { self.items = items }
    }
}

#Preview("Scan, light") {
    NavigationStack { ScanScreen() }.qrStore(AppModel.preview(state: .unlocked, history: []))
}
#Preview("Scan, dark") {
    NavigationStack { ScanScreen() }.qrStore(AppModel.preview(state: .unlocked, history: [])).preferredColorScheme(.dark)
}
#Preview("Scan, largest text") {
    NavigationStack { ScanScreen() }.qrStore(AppModel.preview(state: .unlocked, history: [])).dynamicTypeSize(.accessibility5)
}
