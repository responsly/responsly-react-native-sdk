import Foundation

enum HTTPResult {
    case response(status: Int, headers: [String: String], body: Data)
    case failure(String)
}

/// Performs one HTTP request. The completion may be called on any thread.
protocol HTTPTransport {
    func perform(_ request: URLRequest, completion: @escaping (HTTPResult) -> Void)
}

enum ConfigFetchResult: Equatable {
    /// `200`
    case fetched(config: SDKConfig, body: Data, etag: String?)
    /// `304`: keep the cached copy and restart its TTL.
    case notModified
    /// `401`: drop the cache, show nothing, do not retry until the next launch.
    case invalidKey
    /// Another `3xx`: the API host redirects. Redirects are not followed, so that the SDK key never
    /// reaches another address; keep the cached copy.
    case redirected(status: Int)
    /// Any other status, a network error or an unreadable body: keep the cached copy.
    case failed(String)
}

enum TelemetrySendResult: Equatable {
    case sent
    /// The server refused the batch for good (a 4xx): retrying would not help.
    case rejected
    /// Network error or a temporary status: keep the events and retry later.
    case retry
}

/// The two endpoints of protocol section 2. Makes no other network calls.
final class APIClient {
    let apiHost: URL
    private let sdkKey: String
    private let platform: String
    private let sdkVersion: String
    private let appId: String
    private let transport: HTTPTransport

    init(apiHost: URL, sdkKey: String, platform: String, sdkVersion: String, appId: String, transport: HTTPTransport) {
        self.apiHost = apiHost
        self.sdkKey = sdkKey
        self.platform = platform
        self.sdkVersion = sdkVersion
        self.appId = appId
        self.transport = transport
    }

    func makeRequest(path: String, method: String) -> URLRequest {
        var request = URLRequest(url: apiHost.appendingPathComponent(path))
        request.httpMethod = method
        request.cachePolicy = .reloadIgnoringLocalCacheData
        request.setValue(sdkKey, forHTTPHeaderField: "X-Responsly-Sdk-Key")
        request.setValue(platform, forHTTPHeaderField: "X-Responsly-Platform")
        request.setValue(sdkVersion, forHTTPHeaderField: "X-Responsly-Sdk-Version")
        request.setValue(appId, forHTTPHeaderField: "X-Responsly-App-Id")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        return request
    }

    /// `GET /sdk/v1/config`
    func fetchConfig(etag: String?, completion: @escaping (ConfigFetchResult) -> Void) {
        var request = makeRequest(path: "sdk/v1/config", method: "GET")
        if let etag = etag, !etag.isEmpty {
            request.setValue(etag, forHTTPHeaderField: "If-None-Match")
        }
        transport.perform(request) { result in
            switch result {
            case .failure(let message):
                completion(.failed(message))
            case .response(let status, let headers, let body):
                switch status {
                case 200:
                    guard let config = SDKConfig.parse(body) else {
                        completion(.failed("the configuration is not a JSON object"))
                        return
                    }
                    completion(.fetched(config: config, body: body, etag: APIClient.header("ETag", in: headers)))
                case 304:
                    completion(.notModified)
                case 401:
                    completion(.invalidKey)
                case 300..<400:
                    completion(.redirected(status: status))
                default:
                    completion(.failed("HTTP \(status)"))
                }
            }
        }
    }

    /// `POST /sdk/v1/events`
    func sendEvents(_ events: [TelemetryEvent], completion: @escaping (TelemetrySendResult) -> Void) {
        var request = makeRequest(path: "sdk/v1/events", method: "POST")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        guard let body = try? encoder.encode(["events": events]) else {
            completion(.rejected)
            return
        }
        request.httpBody = body
        transport.perform(request) { result in
            switch result {
            case .failure:
                completion(.retry)
            case .response(let status, _, _):
                switch status {
                case 200..<300:
                    completion(.sent)
                case 408, 429:
                    completion(.retry)
                case 400..<500:
                    completion(.rejected)
                default:
                    completion(.retry)
                }
            }
        }
    }

    static func header(_ name: String, in headers: [String: String]) -> String? {
        headers.first { $0.key.caseInsensitiveCompare(name) == .orderedSame }?.value
    }
}
