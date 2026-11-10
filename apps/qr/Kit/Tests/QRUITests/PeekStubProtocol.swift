import Foundation

/// A scripted network for the Peek tests. Replies are keyed by exact URL, so each test uses its
/// own host names and the tests can run in parallel.
final class PeekStubProtocol: URLProtocol, @unchecked Sendable {
    enum Reply: Sendable {
        case ok(status: Int = 200, headers: [String: String] = [:], body: Data = Data(), chunk: Int = 16 * 1024)
        case redirect(status: Int = 302, location: String, headers: [String: String] = [:])
        case fail(URLError.Code)
        /// Never answers.
        case hang
    }

    private final class Registry: @unchecked Sendable {
        let lock = NSLock()
        var replies: [String: Reply] = [:]
        var seen: [String: [[String: String]]] = [:]
        var methods: [String: [String]] = [:]
    }
    private static let registry = Registry()

    static func register(_ url: String, _ reply: Reply) {
        registry.lock.withLock { registry.replies[url] = reply }
    }

    /// Headers of every request made to `url`, in order.
    static func requests(to url: String) -> [[String: String]] {
        registry.lock.withLock { registry.seen[url] ?? [] }
    }

    static func methods(of url: String) -> [String] {
        registry.lock.withLock { registry.methods[url] ?? [] }
    }

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func stopLoading() {}

    override func startLoading() {
        guard let url = request.url else { client?.urlProtocol(self, didFailWithError: URLError(.badURL)); return }
        let key = url.absoluteString
        let reply = Self.registry.lock.withLock { () -> Reply? in
            Self.registry.seen[key, default: []].append(request.allHTTPHeaderFields ?? [:])
            Self.registry.methods[key, default: []].append(request.httpMethod ?? "")
            return Self.registry.replies[key]
        }
        guard let reply else { client?.urlProtocol(self, didFailWithError: URLError(.cannotFindHost)); return }
        switch reply {
        case .ok(let status, let headers, let body, let chunk):
            guard let response = HTTPURLResponse(url: url, statusCode: status, httpVersion: "HTTP/1.1", headerFields: headers)
            else { return }
            client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
            var offset = 0
            while offset < body.count {
                let end = min(offset + chunk, body.count)
                client?.urlProtocol(self, didLoad: body.subdata(in: offset..<end))
                offset = end
            }
            client?.urlProtocolDidFinishLoading(self)
        case .redirect(let status, let location, let headers):
            var all = headers
            all["Location"] = location
            guard let response = HTTPURLResponse(url: url, statusCode: status, httpVersion: "HTTP/1.1", headerFields: all),
                  let target = URL(string: location, relativeTo: url) else { return }
            var next = URLRequest(url: target.absoluteURL)
            next.allHTTPHeaderFields = request.allHTTPHeaderFields
            client?.urlProtocol(self, wasRedirectedTo: next, redirectResponse: response)
        case .fail(let code):
            client?.urlProtocol(self, didFailWithError: URLError(code))
        case .hang:
            break
        }
    }
}
