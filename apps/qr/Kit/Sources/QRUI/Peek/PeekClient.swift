import Foundation
import os
import QRCore

/// The only custom network code in the app (PLAN §4 rule 3, §6.5). One fresh ephemeral session
/// per Peek, no cookies, no cache, no credentials, system trust only.
public struct PeekClient: Sendable {
    /// iPhone Safari on iOS 26 (the OS version in a Safari UA string stays frozen at 18_6).
    public static let userAgent = "Mozilla/5.0 (iPhone; CPU iPhone OS 18_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Mobile/15E148 Safari/604.1"
    public static let accept = "text/html,application/xhtml+xml;q=0.9,*/*;q=0.8"
    public static let maxBodyBytes = 512 * 1024
    public static let maxRedirects = 10

    private static let log = Logger(subsystem: "com.sevensanddigits.qr", category: "peek")

    let protocolClasses: [AnyClass]?
    let totalTimeout: TimeInterval
    let rdapTimeout: TimeInterval
    let acceptLanguage: String

    /// - Parameters:
    ///   - protocolClasses: tests inject their `URLProtocol` stub here; the app passes nil.
    ///   - totalTimeout: the whole Peek (all hops and the body), 8 s.
    ///   - rdapTimeout: the registration lookup, 5 s.
    ///   - languages: BCP 47 tags, most preferred first; the first three are sent.
    public init(protocolClasses: [AnyClass]? = nil, totalTimeout: TimeInterval = 8,
                rdapTimeout: TimeInterval = 5, languages: [String] = Locale.preferredLanguages) {
        self.protocolClasses = protocolClasses
        self.totalTimeout = totalTimeout
        self.rdapTimeout = rdapTimeout
        self.acceptLanguage = Self.acceptLanguage(for: languages)
    }

    /// `en-US,fr;q=0.9,de;q=0.8` for the first three tags; `en` when there are none.
    static func acceptLanguage(for languages: [String]) -> String {
        let tags = languages.filter { !$0.isEmpty && $0.utf8.count <= 35 }.prefix(3)
        guard !tags.isEmpty else { return "en" }
        let q = ["", ";q=0.9", ";q=0.8"]
        return tags.enumerated().map { $0.element + q[$0.offset] }.joined(separator: ",")
    }

    // MARK: Fetch

    /// Fetches the link without ever returning an error: whatever was learned comes back with the
    /// reason the Peek stopped.
    public func capture(_ link: LinkInfo) async -> PeekCapture {
        let first = PeekHop(url: link.normalized, status: nil)
        switch link.scheme.lowercased() {
        case "https": break
        case "http": return PeekCapture(hops: [first], stoppedBecause: .httpTarget)
        default: return PeekCapture(hops: [first], stoppedBecause: .nonWebTarget)
        }
        guard let url = URL(string: link.normalized), url.scheme?.lowercased() == "https" else {
            return PeekCapture(hops: [first], stoppedBecause: .networkError)
        }
        let tracker = HopTracker(first: first, userAgent: Self.userAgent, accept: Self.accept,
                                 acceptLanguage: acceptLanguage)
        let outcome = FetchOutcome()
        let session = URLSession(configuration: configuration(timeout: totalTimeout))
        var request = URLRequest(url: url)
        tracker.applyHeaders(to: &request)
        request.timeoutInterval = totalTimeout

        let total = totalTimeout
        await withTaskGroup(of: Void.self) { group in
            let fetch = request
            group.addTask { await Self.run(fetch, session: session, tracker: tracker, outcome: outcome) }
            group.addTask {
                do { try await Task.sleep(nanoseconds: UInt64(max(total, 0.01) * 1_000_000_000)) } catch { return }
                outcome.markTimedOut()
                session.invalidateAndCancel()
            }
            await group.next()
            group.cancelAll()
            await group.waitForAll()
        }
        // URLSession delivers task metrics (the negotiated TLS version) asynchronously after the
        // task completes; give that callback a short, bounded moment before tearing down.
        if !outcome.snapshot().timedOut {
            for _ in 0..<10 where tracker.tlsVersion == nil {
                try? await Task.sleep(nanoseconds: 50_000_000)
            }
        }
        session.invalidateAndCancel()

        let snapshot = outcome.snapshot()
        let stop: StopReason
        if snapshot.timedOut {
            stop = .timeout
        } else if let reason = tracker.stopReason {
            stop = reason
        } else if let error = snapshot.failure {
            stop = Self.reason(for: error)
            Self.log.error("peek fetch failed: \(String(describing: error), privacy: .private)")
        } else {
            stop = .finished
        }
        return PeekCapture(hops: tracker.hops, stoppedBecause: stop, contentType: snapshot.contentType,
                           contentDisposition: snapshot.contentDisposition, body: snapshot.body,
                           tlsVersion: tracker.tlsVersion)
    }

    private static func run(_ request: URLRequest, session: URLSession, tracker: HopTracker,
                            outcome: FetchOutcome) async {
        var buffer: [UInt8] = []
        do {
            let (bytes, response) = try await session.bytes(for: request, delegate: tracker)
            if let http = response as? HTTPURLResponse {
                tracker.setFinalStatus(http.statusCode)
                outcome.setHeaders(contentType: http.value(forHTTPHeaderField: "Content-Type"),
                                   disposition: http.value(forHTTPHeaderField: "Content-Disposition"))
            }
            if tracker.stopReason == nil {
                buffer.reserveCapacity(16 * 1024)
                for try await byte in bytes {
                    buffer.append(byte)
                    if buffer.count >= maxBodyBytes { break }   // our own cap: success
                }
            }
        } catch {
            outcome.fail(error)
        }
        outcome.setBody(buffer)
    }

    /// Certificate and TLS problems stop the Peek as `tlsFailure`; everything else is `networkError`.
    static func reason(for error: Error) -> StopReason {
        guard let urlError = error as? URLError else { return .networkError }
        switch urlError.code {
        case .serverCertificateHasBadDate, .serverCertificateUntrusted, .serverCertificateHasUnknownRoot,
             .serverCertificateNotYetValid, .secureConnectionFailed, .clientCertificateRejected,
             .clientCertificateRequired:
            return .tlsFailure
        default:
            return .networkError
        }
    }

    // MARK: Domain age

    /// The domain RDAP is asked about: the registrable domain in ASCII, or for a private suffix
    /// such as github.io the ICANN-level domain. Nil for IP addresses and unknown suffixes.
    static func rdapDomain(for link: LinkInfo, data: BundledData) -> String? {
        guard link.registrableDomain != nil, !link.isIPAddress, !link.hostASCII.isEmpty else { return nil }
        let match = data.publicSuffixes.match(hostASCII: link.hostASCII)
        return link.suffixIsPrivate ? match.icannRegistrableDomain : match.registrableDomain
    }

    /// Whole days since registration, or nil when unknown (no service, plain-http service,
    /// non-200, no registration event, timeout).
    public func registrationAge(for link: LinkInfo, data: BundledData, now: Date) async -> Int? {
        guard let domain = Self.rdapDomain(for: link, data: data),
              let query = data.rdap.queryURL(forDomain: domain),
              query.lowercased().hasPrefix("https://"),
              let url = URL(string: query), url.scheme?.lowercased() == "https" else { return nil }
        let session = URLSession(configuration: configuration(timeout: rdapTimeout))
        defer { session.invalidateAndCancel() }
        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        request.timeoutInterval = rdapTimeout
        request.setValue("application/rdap+json", forHTTPHeaderField: "Accept")
        request.setValue(Self.userAgent, forHTTPHeaderField: "User-Agent")
        do {
            let (body, response) = try await session.data(for: request)
            guard (response as? HTTPURLResponse)?.statusCode == 200,
                  let registered = RDAPResponse.registrationDate(json: body) else { return nil }
            return DomainAge.days(registeredAt: registered, now: now)
        } catch {
            Self.log.error("rdap lookup failed: \(String(describing: error), privacy: .private)")
            return nil
        }
    }

    // MARK: Session

    private func configuration(timeout: TimeInterval) -> URLSessionConfiguration {
        let config = URLSessionConfiguration.ephemeral
        config.httpCookieStorage = nil
        config.httpShouldSetCookies = false
        config.httpCookieAcceptPolicy = .never
        config.urlCache = nil
        config.urlCredentialStorage = nil
        config.requestCachePolicy = .reloadIgnoringLocalAndRemoteCacheData
        config.timeoutIntervalForRequest = timeout
        config.timeoutIntervalForResource = timeout
        config.waitsForConnectivity = false
        if let protocolClasses { config.protocolClasses = protocolClasses }
        return config
    }
}

/// Records hops and decides, for each redirect, whether to follow it. Never touches trust:
/// the authentication-challenge callback is not implemented, so the system default applies.
private final class HopTracker: NSObject, URLSessionTaskDelegate, @unchecked Sendable {
    private let lock = NSLock()
    private var recorded: [PeekHop]
    private var stop: StopReason?
    private var followed = 0
    private var tls: String?
    private let userAgent: String, accept: String, acceptLanguage: String

    init(first: PeekHop, userAgent: String, accept: String, acceptLanguage: String) {
        recorded = [first]
        self.userAgent = userAgent
        self.accept = accept
        self.acceptLanguage = acceptLanguage
    }

    var hops: [PeekHop] { lock.withLock { recorded } }
    var stopReason: StopReason? { lock.withLock { stop } }
    var tlsVersion: String? { lock.withLock { tls } }

    func applyHeaders(to request: inout URLRequest) {
        request.allHTTPHeaderFields = [:]
        request.setValue(userAgent, forHTTPHeaderField: "User-Agent")
        request.setValue(accept, forHTTPHeaderField: "Accept")
        request.setValue(acceptLanguage, forHTTPHeaderField: "Accept-Language")
        request.httpShouldHandleCookies = false
    }

    func setFinalStatus(_ status: Int) {
        lock.withLock { if !recorded.isEmpty { recorded[recorded.count - 1].status = status } }
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest, completionHandler: @escaping @Sendable (URLRequest?) -> Void) {
        let target = request.url
        let scheme = target?.scheme?.lowercased()
        var follow = false
        lock.withLock {
            if !recorded.isEmpty { recorded[recorded.count - 1].status = response.statusCode }
            let text = target?.absoluteString ?? ""
            if scheme == "https" {
                if followed >= PeekClient.maxRedirects {
                    stop = .tooManyRedirects
                } else {
                    followed += 1
                    recorded.append(PeekHop(url: text, status: nil))
                    follow = true
                }
            } else if scheme == "http" {
                recorded.append(PeekHop(url: text, status: nil))
                stop = .httpTarget
            } else {
                recorded.append(PeekHop(url: text, status: nil))
                stop = .nonWebTarget
            }
        }
        guard follow else {
            // Not followed: end the task now rather than wait for the redirect's own body.
            task.cancel()
            completionHandler(nil)
            return
        }
        var next = request
        applyHeaders(to: &next)
        completionHandler(next)
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didFinishCollecting metrics: URLSessionTaskMetrics) {
        guard let version = metrics.transactionMetrics.last?.negotiatedTLSProtocolVersion else { return }
        let name: String?
        switch version.rawValue {
        case 0x0301: name = "TLS 1.0"
        case 0x0302: name = "TLS 1.1"
        case 0x0303: name = "TLS 1.2"
        case 0x0304: name = "TLS 1.3"
        default: name = nil
        }
        lock.withLock { tls = name }
    }
}

/// What the fetch task hands to the code that assembles the capture.
private final class FetchOutcome: @unchecked Sendable {
    struct Snapshot {
        var body: [UInt8], contentType: String?, contentDisposition: String?
        var failure: Error?, timedOut: Bool
    }
    private let lock = NSLock()
    private var state = Snapshot(body: [], contentType: nil, contentDisposition: nil, failure: nil, timedOut: false)

    func setHeaders(contentType: String?, disposition: String?) {
        lock.withLock { state.contentType = contentType; state.contentDisposition = disposition }
    }
    func setBody(_ body: [UInt8]) { lock.withLock { state.body = body } }
    func fail(_ error: Error) { lock.withLock { state.failure = error } }
    func markTimedOut() { lock.withLock { state.timedOut = true } }
    func snapshot() -> Snapshot { lock.withLock { state } }
}
