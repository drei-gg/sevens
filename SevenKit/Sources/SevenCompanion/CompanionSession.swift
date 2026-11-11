import Foundation
import Observation
#if canImport(WatchConnectivity)
@preconcurrency import WatchConnectivity

/// One session owner per process. Latest-state delivery plus acknowledged, reachable-only commands.
/// Apps own their payload schema, persistence, authorization and replay protection.
@Observable @MainActor
public final class CompanionSession: NSObject, WCSessionDelegate {
    public private(set) var reachable = false
    public private(set) var activated = false
    public private(set) var lastError: String?
    @ObservationIgnored public var receiveSnapshot: (@MainActor (Data) -> Void)?
    @ObservationIgnored public var receiveRequest: (@MainActor (Data) async -> Data)?
    @ObservationIgnored private var latestSnapshot: Data?
    @ObservationIgnored private let session: WCSession?
    public override init() {
        session = WCSession.isSupported() ? .default : nil
        super.init()
    }
    public func activate() {
        guard let session, session.delegate == nil || session.delegate === self else { return }
        session.delegate = self
        session.activate()
    }
    public func publish(_ data: Data) {
        latestSnapshot = data
        flushSnapshot()
    }
    private func flushSnapshot() {
        guard let session, session.activationState == .activated, let latestSnapshot else { return }
        #if os(iOS)
        guard session.isPaired, session.isWatchAppInstalled else { return }
        #endif
        do { try session.updateApplicationContext(["snapshot": latestSnapshot]); lastError = nil }
        catch { lastError = error.localizedDescription }
    }
    public func request(_ data: Data) async throws -> Data {
        guard let session, session.activationState == .activated, session.isReachable else {
            throw CompanionError.unreachable
        }
        return try await withCheckedThrowingContinuation { continuation in
            session.sendMessageData(data, replyHandler: { continuation.resume(returning: $0) },
                                    errorHandler: { continuation.resume(throwing: $0) })
        }
    }
    private func updateState() {
        activated = session?.activationState == .activated
        reachable = session?.isReachable == true
        if let data = session?.receivedApplicationContext["snapshot"] as? Data { receiveSnapshot?(data) }
        flushSnapshot()
    }
    nonisolated public func session(_ session: WCSession, activationDidCompleteWith activationState: WCSessionActivationState, error: (any Error)?) {
        Task { @MainActor [weak self] in self?.updateState() }
    }
    nonisolated public func sessionReachabilityDidChange(_ session: WCSession) {
        Task { @MainActor [weak self] in self?.updateState() }
    }
    nonisolated public func session(_ session: WCSession, didReceiveApplicationContext applicationContext: [String: Any]) {
        guard let data = applicationContext["snapshot"] as? Data else { return }
        Task { @MainActor [weak self] in self?.receiveSnapshot?(data) }
    }
    nonisolated public func session(_ session: WCSession, didReceiveMessageData messageData: Data, replyHandler: @escaping (Data) -> Void) {
        let reply = ReplyOnce(replyHandler)
        Task { @MainActor [weak self] in
            guard let handler = self?.receiveRequest else { reply.call(Data()); return }
            reply.call(await handler(messageData))
        }
    }
    #if os(iOS)
    nonisolated public func sessionDidBecomeInactive(_ session: WCSession) {}
    nonisolated public func sessionDidDeactivate(_ session: WCSession) { session.activate() }
    nonisolated public func sessionWatchStateDidChange(_ session: WCSession) {
        Task { @MainActor [weak self] in self?.updateState() }
    }
    #endif
}
public enum CompanionError: Error { case unreachable }

/// WCSession supplies a reply callback that may be called asynchronously on any queue.
/// Transfer its ownership into exactly one MainActor task; never share or invoke it twice.
private final class ReplyOnce: @unchecked Sendable {
    private let body: (Data) -> Void
    init(_ body: @escaping (Data) -> Void) { self.body = body }
    func call(_ data: Data) { body(data) }
}
#endif
