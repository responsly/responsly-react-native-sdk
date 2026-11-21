import Foundation

/// Matches a survey host against the name of a website-data record, which is the host's
/// registrable domain (`acme.responsly.com` is stored under `responsly.com`).
enum WebsiteDataMatcher {
    static func host(_ host: String, belongsToRecordNamed recordName: String) -> Bool {
        let host = host.lowercased()
        let record = recordName.lowercased()
        guard !host.isEmpty, !record.isEmpty else { return false }
        return host == record || host.hasSuffix("." + record)
    }
}
