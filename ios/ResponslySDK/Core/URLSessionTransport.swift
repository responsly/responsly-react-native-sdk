import Foundation

/// `URLSession` without a cache, cookies or credentials: the SDK does its own `ETag` handling and
/// has nothing to store between requests.
final class URLSessionTransport: HTTPTransport {
    private let session: URLSession

    init() {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
        configuration.urlCache = nil
        configuration.httpCookieStorage = nil
        configuration.httpShouldSetCookies = false
        configuration.urlCredentialStorage = nil
        configuration.timeoutIntervalForRequest = 15
        configuration.timeoutIntervalForResource = 30
        session = URLSession(configuration: configuration, delegate: RedirectBlocker(), delegateQueue: nil)
    }

    deinit {
        session.invalidateAndCancel()
    }

    func perform(_ request: URLRequest, completion: @escaping (HTTPResult) -> Void) {
        let task = session.dataTask(with: request) { data, response, error in
            guard let http = response as? HTTPURLResponse else {
                completion(.failure(error?.localizedDescription ?? "no response"))
                return
            }
            var headers: [String: String] = [:]
            for (name, value) in http.allHeaderFields {
                if let name = name as? String, let value = value as? String {
                    headers[name] = value
                }
            }
            completion(.response(status: http.statusCode, headers: headers, body: data ?? Data()))
        }
        task.resume()
    }
}

/// API endpoints are fixed. Never forward the SDK key, app metadata or an events body to a
/// redirect destination, including a different host or a less secure scheme.
private final class RedirectBlocker: NSObject, URLSessionTaskDelegate {
    func urlSession(
        _ session: URLSession,
        task: URLSessionTask,
        willPerformHTTPRedirection response: HTTPURLResponse,
        newRequest request: URLRequest,
        completionHandler: @escaping (URLRequest?) -> Void
    ) {
        completionHandler(nil)
    }
}
