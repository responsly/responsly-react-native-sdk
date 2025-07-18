import Foundation

/// The small storage surface the SDK needs; `UserDefaults` in the app, memory in tests.
protocol KeyValueStorage: AnyObject {
    func data(forKey key: String) -> Data?
    func setData(_ data: Data?, forKey key: String)
}

/// `UserDefaults` suite `com.responsly.sdk` (protocol section 3).
final class UserDefaultsStorage: KeyValueStorage {
    static let suiteName = "com.responsly.sdk"

    private let defaults: UserDefaults

    init(suiteName: String = UserDefaultsStorage.suiteName) {
        // `UserDefaults(suiteName:)` is nil only when the name is the app's own bundle identifier.
        defaults = UserDefaults(suiteName: suiteName) ?? .standard
    }

    func data(forKey key: String) -> Data? {
        defaults.data(forKey: key)
    }

    func setData(_ data: Data?, forKey key: String) {
        if let data = data {
            defaults.set(data, forKey: key)
        } else {
            defaults.removeObject(forKey: key)
        }
    }
}

final class InMemoryStorage: KeyValueStorage {
    private let lock = NSLock()
    private var values: [String: Data] = [:]

    init() {}

    func data(forKey key: String) -> Data? {
        lock.lock()
        defer { lock.unlock() }
        return values[key]
    }

    func setData(_ data: Data?, forKey key: String) {
        lock.lock()
        values[key] = data
        lock.unlock()
    }
}

/// The cached configuration with its `ETag` and fetch time.
struct CachedConfig: Equatable {
    /// The response body exactly as received, so that a newer SDK can re-read fields this one ignores.
    var body: Data
    var etag: String?
    var fetchedAt: Date
    /// The API host the copy came from; a copy from another host is not used.
    var apiHost: String?
    var config: SDKConfig
}

enum TelemetryEventType: String, Codable, Equatable {
    case displayed
    case dismissed
    case completed
}

/// One telemetry event. Carries nothing but the type and the survey id.
struct TelemetryEvent: Codable, Equatable {
    let type: TelemetryEventType
    let surveyId: String

    private enum CodingKeys: String, CodingKey {
        case type
        case surveyId = "survey_id"
    }
}

/// Reads and writes the persisted state. Not thread-safe by itself: the core confines it to its queue.
final class StateStore {
    private enum Key {
        static let state = "responsly.state"
        static let config = "responsly.config"
        static let telemetry = "responsly.telemetry"
    }

    private struct CachedConfigRecord: Codable {
        var body: Data
        var etag: String?
        var fetchedAt: Date
        var apiHost: String?

        private enum CodingKeys: String, CodingKey {
            case body
            case etag
            case fetchedAt = "fetched_at"
            case apiHost = "api_host"
        }
    }

    private let storage: KeyValueStorage
    private let encoder: JSONEncoder
    private let decoder: JSONDecoder

    init(storage: KeyValueStorage) {
        self.storage = storage
        encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .secondsSince1970
        encoder.outputFormatting = [.sortedKeys]
        decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .secondsSince1970
    }

    // MARK: - State

    /// Loads the state, creating it (with a new install id) on first use.
    func loadState() -> SDKState {
        if let data = storage.data(forKey: Key.state),
           let state = try? decoder.decode(SDKState.self, from: data),
           !state.installId.isEmpty {
            return state
        }
        let state = SDKState()
        saveState(state)
        return state
    }

    func saveState(_ state: SDKState) {
        storage.setData(try? encoder.encode(state), forKey: Key.state)
    }

    // MARK: - Cached configuration

    func loadCachedConfig() -> CachedConfig? {
        guard let data = storage.data(forKey: Key.config),
              let record = try? decoder.decode(CachedConfigRecord.self, from: data),
              let config = SDKConfig.parse(record.body)
        else { return nil }
        return CachedConfig(
            body: record.body, etag: record.etag, fetchedAt: record.fetchedAt,
            apiHost: record.apiHost, config: config
        )
    }

    func saveCachedConfig(_ cached: CachedConfig) {
        let record = CachedConfigRecord(
            body: cached.body, etag: cached.etag, fetchedAt: cached.fetchedAt, apiHost: cached.apiHost
        )
        storage.setData(try? encoder.encode(record), forKey: Key.config)
    }

    /// Restarts the TTL of the cached copy (`304 Not Modified`).
    func touchCachedConfig(fetchedAt: Date) {
        guard let data = storage.data(forKey: Key.config),
              var record = try? decoder.decode(CachedConfigRecord.self, from: data)
        else { return }
        record.fetchedAt = fetchedAt
        storage.setData(try? encoder.encode(record), forKey: Key.config)
    }

    func clearCachedConfig() {
        storage.setData(nil, forKey: Key.config)
    }

    // MARK: - Telemetry queue

    func loadTelemetry() -> [TelemetryEvent] {
        guard let data = storage.data(forKey: Key.telemetry),
              let events = try? decoder.decode([TelemetryEvent].self, from: data)
        else { return [] }
        return events
    }

    func saveTelemetry(_ events: [TelemetryEvent]) {
        if events.isEmpty {
            storage.setData(nil, forKey: Key.telemetry)
        } else {
            storage.setData(try? encoder.encode(events), forKey: Key.telemetry)
        }
    }
}
