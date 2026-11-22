import AVFoundation
import PhotosUI
import QRCore
import SevenKit
import SwiftUI

/// The home screen (SPEC §2): the live scanner, a torch, photo and paste, and the way to
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
    @State private var onScreen = false
    @State private var requestingCamera = false

    /// True while a launch sheet (Before you buy, the trial-ended sheet) is up or due: the
    /// camera stays off and nobody is asked for it (SPEC §1.3).
    private let holdsCamera: Bool

    public init(holdsCamera: Bool = false) {
        self.holdsCamera = holdsCamera
    }

    private var sheetIsUp: Bool { result != nil || candidates != nil }

    /// The scanner is open: only then does the camera run, or is its permission asked.
    private var scannerOpen: Bool {
        ScanCameraGate.scannerOpen(onScreen: onScreen, sheetUp: sheetIsUp, held: holdsCamera, sceneActive: scenePhase == .active)
    }

    public var body: some View {
        content
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
            .onChange(of: scannerOpen, initial: true) { _, _ in requestCameraIfNeeded() }
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
            .onAppear { onScreen = true }
            .onDisappear {
                onScreen = false
                torch.turnOff()
            }
    }

    // MARK: Layout

    /// Debug builds only: the camera layout over a black stand-in, for the simulator's tour.
    private static var cameraStandIn: Bool {
        #if DEBUG
        ProcessInfo.processInfo.arguments.contains(QRLaunchArgument.cameraStandIn)
        #else
        false
        #endif
    }

    private var showsCamera: Bool { ScanCameraGate.showsCameraLayout(availability) || Self.cameraStandIn }

    /// The torch only once the camera is granted (or in the tour's stand-in).
    private var showsTorch: Bool { (availability == .available || Self.cameraStandIn) && torch.isAvailable }

    /// A tool screen (polish guide §5.1): the camera fills the screen under the bar, the frame
    /// says where the code goes, and the controls sit on one glass platter within thumb reach.
    @ViewBuilder
    private var content: some View {
        if showsCamera {
            ZStack {
                camera.ignoresSafeArea(edges: [.bottom, .horizontal])
                VStack(spacing: Tokens.Space.l) {
                    Spacer(minLength: Tokens.Space.m)
                    ViewfinderFrame(surface: .camera)
                        .frame(maxWidth: 280, maxHeight: 280)
                    hint
                    Spacer(minLength: Tokens.Space.m)
                    statusLine
                    controls
                }
                .padding(.horizontal, Tokens.Space.m)
                .padding(.bottom, Tokens.Space.m)
            }
            .toolbarBackground(.visible, for: .navigationBar)
            .toolbarBackground(Tokens.paper, for: .navigationBar)
        } else {
            ScrollView {
                VStack(spacing: Tokens.Space.l) {
                    fallbackHeader
                    statusLine
                    controls
                }
                .padding(Tokens.Space.m)
            }
            .sevenScreenBackground()
        }
    }

    @ViewBuilder
    private var camera: some View {
        // Until the camera is granted (or in the simulator's tour) the frame sits on black.
        if availability != .available {
            Rectangle().fill(Color.black)
                .accessibilityLabel(String(localized: "scan.camera.a11y", defaultValue: "Camera viewfinder for scanning codes", bundle: .module))
        } else {
            ScannerView(
                isActive: scannerOpen,
                onCode: { code in Task { await present(code) } },
                onMultipleChange: { many in
                    multipleInView = many
                    if many {
                        AccessibilityNotification.Announcement(Self.tapACodeText).post()
                    }
                },
                onStartFailure: { _ in availability = .unsupported }
            )
            .accessibilityLabel(String(localized: "scan.camera.a11y", defaultValue: "Camera viewfinder for scanning codes", bundle: .module))
        }
    }

    /// One short line under the frame: what to do, or "Tap a code" when several are in view.
    private var hint: some View {
        Text(multipleInView ? Self.tapACodeText : Self.pointText)
            .textRole(.rowTitle, emphasized: true)
            .foregroundStyle(.primary)
            .multilineTextAlignment(.center)
            .padding(.horizontal, Tokens.Space.m)
            .padding(.vertical, Tokens.Space.s)
            .background(.regularMaterial, in: Capsule())
            .contentTransition(.opacity)
            .animation(reduceMotion ? nil : .easeOut(duration: 0.2), value: multipleInView)
            .accessibilityHidden(!multipleInView)
    }

    /// Photo and Paste flank the torch, the one control people reach for while aiming.
    private var controls: some View {
        ViewThatFits(in: .horizontal) {
            HStack(spacing: Tokens.Space.m) {
                photoButton
                if showsTorch { Spacer(minLength: 0); torchButton; Spacer(minLength: 0) }
                pasteButton
            }
            VStack(spacing: Tokens.Space.ms) {
                if showsTorch { torchButton }
                photoButton
                pasteButton
            }
        }
        .padding(Tokens.Space.ms)
        .frame(maxWidth: 480)
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: Tokens.Radius.card * 2, style: .continuous))
    }

    private var torchButton: some View {
        Button { torch.toggle() } label: {
            Image(systemName: torch.isOn ? "flashlight.on.fill" : "flashlight.off.fill")
                .textRole(.title)
                .foregroundStyle(torch.isOn ? Tokens.onInk : Color.primary)
                .frame(width: 64, height: 64)
                .background(torch.isOn ? Tokens.ink : Tokens.card, in: Circle())
                .contentShape(Circle())
        }
        .buttonStyle(.plain)
        .sensoryFeedback(.selection, trigger: torch.isOn)
        .accessibilityLabel(String(localized: "scan.torch", defaultValue: "Torch", bundle: .module))
        .accessibilityValue(torch.isOn
            ? String(localized: "scan.torch.on", defaultValue: "On", bundle: .module)
            : String(localized: "scan.torch.off", defaultValue: "Off", bundle: .module))
    }

    /// No camera: the same frame on paper, holding the reason, so the screen still reads as a scanner.
    private var fallbackHeader: some View {
        VStack(spacing: Tokens.Space.l) {
            ZStack {
                ViewfinderFrame(surface: .paper)
                Image(systemName: availability == .denied || availability == .restricted ? "camera.fill" : "photo.on.rectangle")
                    .glyph()
            }
            .frame(maxWidth: 220, maxHeight: 220)
            .padding(.top, Tokens.Space.l)
            Text(fallbackText)
                .textRole(.body)
                .foregroundStyle(.primary)
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
            if availability == .denied {
                Button(String(localized: "scan.denied.settings", defaultValue: "Open Settings", bundle: .module)) {
                    if let url = URL(string: UIApplication.openSettingsURLString) { openURL(url) }
                }
                .sevenProminentButton()
                .controlSize(.extraLarge)
                .buttonBorderShape(.capsule)
            }
        }
        .frame(maxWidth: .infinity)
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
                          defaultValue: "This device can’t scan with the camera. You can still scan a photo or what you copied.",
                          bundle: .module)
        }
    }

    @ViewBuilder
    private var statusLine: some View {
        if let message {
            Text(message)
                .foregroundStyle(.primary)
                .textRole(.secondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, Tokens.Space.m)
                .padding(.vertical, Tokens.Space.s)
                .background(.regularMaterial, in: Capsule())
        } else if working {
            ProgressView()
                .padding(Tokens.Space.s)
                .background(.regularMaterial, in: Circle())
                .accessibilityLabel(String(localized: "scan.working", defaultValue: "Reading", bundle: .module))
        }
    }

    private var photoButton: some View {
        PhotosPicker(selection: $photoItem, matching: .images, photoLibrary: .shared()) {
            Label(String(localized: "scan.photo", defaultValue: "Photo", bundle: .module), systemImage: "photo")
        }
        .modifier(SevenButtonStyle(kind: .bordered))
        .buttonBorderShape(.capsule)
        .labelStyle(.titleAndIcon)
        .controlSize(.regular)
        .accessibilityLabel(String(localized: "scan.photo.a11y", defaultValue: "Scan a photo", bundle: .module))
        .accessibilityIdentifier(QRAccessibilityID.scanPhoto)
    }

    private var pasteButton: some View {
        PasteScanButton { codes in
            Task { await handle(codes, empty: Self.nothingToPasteText) }
        }
        .controlSize(.regular)
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

    private static var pointText: String {
        String(localized: "scan.point", defaultValue: "Point at a code", bundle: .module)
    }
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
        String(localized: "scan.photo.failed", defaultValue: "That photo couldn’t be read", bundle: .module)
    }

    // MARK: Actions

    /// SPEC §1.3: the first time the scanner opens, iOS asks for the camera; then the screen
    /// shows the scanner, or why it can't (denied: a button to Settings; unsupported).
    private func requestCameraIfNeeded() {
        guard ScanCameraGate.shouldRequestAccess(availability: availability, scannerOpen: scannerOpen,
                                                 requesting: requestingCamera) else { return }
        requestingCamera = true
        Task {
            _ = await AVCaptureDevice.requestAccess(for: .video)
            availability = ScannerView.availability
            requestingCamera = false
        }
    }

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
