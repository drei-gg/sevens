import CryptoKit
import Foundation
import QRCore
import os

/// Why a check did not install newer lists. Each case has one plain sentence in `ListsAboutText`.
enum ListsUpdateFailure: Error, Equatable, Sendable {
    /// The connection failed, timed out or got something other than a plain 200.
    case unreachable
    /// The lists were fine but could not be saved.
    case saveFailed
    case refused(ListsBundleError)
}

enum ListsUpdateResult: Equatable, Sendable {
    case updated(ListsVersion)
    case failed(ListsUpdateFailure)
}

/// The second and last place the app does its own networking (SPEC §4.3, §12). Runs only when
/// `check()` is called, which only the Check for newer lists button does: two GETs to our public
/// repository, through a fresh ephemeral session with no cookies, cache or credentials and no
/// header beyond a standard user agent. Nothing about the person or their scans is sent.
struct ListsUpdater: Sendable {
    static let timeout: TimeInterval = 15
    /// A signature is 64 bytes; anything much larger is not one.
    static let maxSignatureBytes = 1024

    private static let log = Logger(subsystem: "com.sevensanddigits.qr", category: "lists")

    let configuration: ListsConfiguration
    let store: ListsStore
    let protocolClasses: [AnyClass]?
    /// The version in use now.
    let installedVersion: @Sendable () -> Int64
    /// Called with the accepted bytes, or nil after a reset; the app reloads `BundledData.shared`.
    let apply: @Sendable (Data?) -> Void

    /// The updater this build is configured for, or nil (the button hides) without a repository
    /// and a valid public key.
    static func configured(bundle: Bundle = .main) -> ListsUpdater? {
        SettingsInfo.listsConfiguration(bundle: bundle).map { ListsUpdater(configuration: $0) }
    }

    /// - Parameter protocolClasses: tests inject their `URLProtocol` stub here; the app passes nil.
    init(configuration: ListsConfiguration, store: ListsStore = ListsStore(),
         protocolClasses: [AnyClass]? = nil,
         installedVersion: @escaping @Sendable () -> Int64 = { BundledData.shared.lists.version },
         apply: @escaping @Sendable (Data?) -> Void = { BundledData.reload(downloaded: $0) }) {
        self.configuration = configuration
        self.store = store
        self.protocolClasses = protocolClasses
        self.installedVersion = installedVersion
        self.apply = apply
    }

    /// Fetches, checks and, only if every check passes, saves and loads the newer lists.
    func check() async -> ListsUpdateResult {
        guard let listURL = configuration.fileURL(ListsStore.fileName),
              let signatureURL = configuration.fileURL(ListsStore.fileName + ".sig") else {
            return .failed(.unreachable)
        }
        let session = URLSession(configuration: sessionConfiguration())
        defer { session.invalidateAndCancel() }
        let bytes: Data, signature: Data
        do {
            bytes = try await Self.fetch(listURL, limit: ListsBundle.maxBytes, session: session)
            signature = try await Self.fetch(signatureURL, limit: Self.maxSignatureBytes, session: session)
        } catch let failure as ListsUpdateFailure {
            return .failed(failure)
        } catch {
            Self.log.error("lists fetch failed: \(String(describing: error), privacy: .private)")
            return .failed(.unreachable)
        }
        return accept(bytes, signature: signature)
    }

    /// Every acceptance rule except the network: signature over the exact bytes here, then the
    /// core's format, schema, no-rollback and shape rules. Saves and loads the lists when all pass.
    func accept(_ bytes: Data, signature: Data) -> ListsUpdateResult {
        guard bytes.count <= ListsBundle.maxBytes else { return .failed(.refused(.TooLarge)) }
        guard verifies(signature, over: bytes) else { return .failed(.refused(.BadSignature)) }
        let header: ListsVersion
        do { header = try listsBundleCheck(bytes: bytes, installedVersion: installedVersion()) } catch let error as ListsBundleError {
            return .failed(.refused(error))
        } catch {
            return .failed(.refused(.Unreadable))
        }
        do { try store.write(bytes) } catch {
            Self.log.error("lists save failed: \(String(describing: error), privacy: .private)")
            return .failed(.saveFailed)
        }
        apply(bytes)
        return .updated(header)
    }

    /// "Use the lists that came with the app": deletes the saved download and reloads.
    func resetToBundled() {
        store.remove()
        apply(nil)
    }

    // MARK: Signature

    private func verifies(_ signature: Data, over bytes: Data) -> Bool {
        guard signature.count == 64 else { return false }
        return configuration.keys.contains { raw in
            guard let key = try? Curve25519.Signing.PublicKey(rawRepresentation: raw) else { return false }
            return key.isValidSignature(signature, for: bytes)
        }
    }

    // MARK: Network

    /// One GET. Refuses redirects, anything but 200, and anything larger than `limit`, which it
    /// notices from the length header or by reading no more than `limit` + 1 bytes.
    private static func fetch(_ url: URL, limit: Int, session: URLSession) async throws -> Data {
        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        request.timeoutInterval = timeout
        request.httpShouldHandleCookies = false
        request.allHTTPHeaderFields = [:]
        request.setValue(PeekClient.userAgent, forHTTPHeaderField: "User-Agent")
        let (stream, response) = try await session.bytes(for: request, delegate: RefuseRedirects())
        guard let http = response as? HTTPURLResponse, http.statusCode == 200 else { throw ListsUpdateFailure.unreachable }
        if http.expectedContentLength > Int64(limit) { throw ListsUpdateFailure.refused(.TooLarge) }
        var buffer = Data()
        buffer.reserveCapacity(min(max(Int(max(http.expectedContentLength, 0)), 16 * 1024), limit))
        for try await byte in stream {
            buffer.append(byte)
            if buffer.count > limit { throw ListsUpdateFailure.refused(.TooLarge) }
        }
        return buffer
    }

    private func sessionConfiguration() -> URLSessionConfiguration {
        let config = URLSessionConfiguration.ephemeral
        config.httpCookieStorage = nil
        config.httpShouldSetCookies = false
        config.httpCookieAcceptPolicy = .never
        config.urlCache = nil
        config.urlCredentialStorage = nil
        config.requestCachePolicy = .reloadIgnoringLocalAndRemoteCacheData
        config.timeoutIntervalForRequest = Self.timeout
        config.timeoutIntervalForResource = Self.timeout * 2
        config.waitsForConnectivity = false
        if let protocolClasses { config.protocolClasses = protocolClasses }
        return config
    }
}

/// The two files sit at fixed addresses; a redirect is a refusal, not a detour.
private final class RefuseRedirects: NSObject, URLSessionTaskDelegate, @unchecked Sendable {
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest, completionHandler: @escaping @Sendable (URLRequest?) -> Void) {
        completionHandler(nil)
    }
}
