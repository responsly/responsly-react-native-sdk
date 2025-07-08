import Foundation

/// A value as it is stored and compared: string, number or boolean.
/// Dates are converted to ISO 8601 UTC strings before they get here (protocol section 3).
enum StoredValue: Equatable {
    case string(String)
    case number(Double)
    case bool(Bool)

    /// `nil` for a non-finite number, which JSON cannot carry.
    init?(_ value: ResponslyValue) {
        switch value {
        case .string(let string):
            self = .string(string)
        case .number(let number):
            guard number.isFinite else { return nil }
            self = .number(number)
        case .bool(let bool):
            self = .bool(bool)
        case .date(let date):
            self = .string(ISO8601.string(from: date))
        }
    }

    /// From a `JSONSerialization` object; `nil` for null, arrays and objects.
    init?(json: Any?) {
        guard let json = json else { return nil }
        if let string = json as? String {
            self = .string(string)
        } else if let number = json as? NSNumber {
            if CFGetTypeID(number) == CFBooleanGetTypeID() {
                self = .bool(number.boolValue)
            } else {
                let double = number.doubleValue
                guard double.isFinite else { return nil }
                self = .number(double)
            }
        } else {
            return nil
        }
    }

    var jsonObject: Any {
        switch self {
        case .string(let string): return string
        case .number(let number): return NSNumber(value: number)
        case .bool(let bool): return NSNumber(value: bool)
        }
    }
}

extension StoredValue: Codable {
    init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()
        if let bool = try? container.decode(Bool.self) {
            self = .bool(bool)
        } else if let number = try? container.decode(Double.self) {
            self = .number(number)
        } else {
            self = .string(try container.decode(String.self))
        }
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.singleValueContainer()
        switch self {
        case .string(let string): try container.encode(string)
        case .number(let number): try container.encode(number)
        case .bool(let bool): try container.encode(bool)
        }
    }
}

enum ISO8601 {
    private static let lock = NSLock()

    private static let plain: ISO8601DateFormatter = {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime]
        formatter.timeZone = TimeZone(secondsFromGMT: 0)
        return formatter
    }()

    private static let fractional: ISO8601DateFormatter = {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        formatter.timeZone = TimeZone(secondsFromGMT: 0)
        return formatter
    }()

    /// UTC, seconds precision: `2026-11-10T12:00:00Z`.
    static func string(from date: Date) -> String {
        let whole = Date(timeIntervalSince1970: date.timeIntervalSince1970.rounded(.down))
        lock.lock()
        defer { lock.unlock() }
        return plain.string(from: whole)
    }

    static func date(from string: String) -> Date? {
        lock.lock()
        defer { lock.unlock() }
        return plain.date(from: string) ?? fractional.date(from: string)
    }
}
