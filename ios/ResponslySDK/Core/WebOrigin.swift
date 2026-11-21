import Foundation

/// Scheme, host and port of a URL, compared the way a browser compares origins.
struct WebOrigin: Equatable {
    let scheme: String
    let host: String
    let port: Int

    /// - Parameter port: `nil` or 0 means the scheme's default port.
    init(scheme: String, host: String, port: Int?) {
        let scheme = scheme.lowercased()
        self.scheme = scheme
        var host = host.lowercased()
        if host.hasPrefix("["), host.hasSuffix("]") {
            host = String(host.dropFirst().dropLast())
        }
        self.host = host
        if let port = port, port > 0 {
            self.port = port
        } else {
            self.port = WebOrigin.defaultPort(for: scheme)
        }
    }

    init?(url: URL) {
        guard let scheme = url.scheme, let host = url.host, !host.isEmpty else { return nil }
        self.init(scheme: scheme, host: host, port: url.port)
    }

    static func defaultPort(for scheme: String) -> Int {
        switch scheme {
        case "https": return 443
        case "http": return 80
        default: return 0
        }
    }

    /// The value of `window.location.origin` for this origin.
    var serialized: String {
        var result = scheme + "://" + (host.contains(":") ? "[" + host + "]" : host)
        if port != WebOrigin.defaultPort(for: scheme) {
            result += ":\(port)"
        }
        return result
    }
}
