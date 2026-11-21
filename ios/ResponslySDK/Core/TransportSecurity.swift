import Foundation

/// Which addresses the SDK talks to and shows. Everything goes over https. Plain http is accepted
/// only for hosts on a developer's machine or local network, so that the SDK can be tried against
/// a local Responsly; such an address never comes from a production configuration.
enum TransportSecurity {
    static func isAllowed(_ url: URL) -> Bool {
        guard let scheme = url.scheme?.lowercased(), let host = url.host, !host.isEmpty else { return false }
        switch scheme {
        case "https":
            return true
        case "http":
            return isLocalDevelopmentHost(host)
        default:
            return false
        }
    }

    /// `localhost`, names under `.localhost`, `.local` and `.test`, `::1`, and the loopback and
    /// private IPv4 ranges (127/8, 10/8, 172.16/12, 192.168/16; 10.0.2.2 of the Android emulator
    /// is among them).
    static func isLocalDevelopmentHost(_ rawHost: String) -> Bool {
        var host = rawHost.lowercased()
        if host.hasPrefix("["), host.hasSuffix("]") {
            host = String(host.dropFirst().dropLast())
        }
        if host.hasSuffix(".") {
            host.removeLast()
        }
        if host == "localhost" || host == "::1" {
            return true
        }
        if [".localhost", ".local", ".test"].contains(where: { host.hasSuffix($0) }) {
            return true
        }
        let parts = host.split(separator: ".", omittingEmptySubsequences: false)
        guard parts.count == 4 else { return false }
        let octets = parts.compactMap { part -> Int? in
            guard (1...3).contains(part.count), part.allSatisfy({ ("0"..."9").contains($0) }) else { return nil }
            return Int(part).flatMap { $0 <= 255 ? $0 : nil }
        }
        guard octets.count == 4 else { return false }
        switch (octets[0], octets[1]) {
        case (127, _), (10, _), (192, 168), (172, 16...31):
            return true
        default:
            return false
        }
    }
}
