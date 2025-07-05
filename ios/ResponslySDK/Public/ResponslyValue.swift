import Foundation

/// A trait or event-property value: string, number, boolean or date.
///
/// Dates are stored and compared as ISO 8601 UTC strings with seconds precision
/// (for example `2026-11-10T12:00:00Z`).
public enum ResponslyValue: Hashable, Sendable {
    case string(String)
    case number(Double)
    case bool(Bool)
    case date(Date)

    /// Converts a loosely typed value, as it arrives from a cross-platform bridge
    /// (React Native, Flutter, Unity), into a `ResponslyValue`.
    ///
    /// - `String` stays a string (an ISO 8601 string is not turned into a date),
    /// - `NSNumber` becomes `.bool` when it wraps a boolean, otherwise `.number`,
    /// - `Date` becomes `.date`,
    /// - `nil`, `NSNull`, non-finite numbers and every other type give `nil`.
    public static func from(_ any: Any?) -> ResponslyValue? {
        guard let any = any else { return nil }
        if let value = any as? ResponslyValue { return value }
        if any is NSNull { return nil }
        if let string = any as? String { return .string(string) }
        if let date = any as? Date { return .date(date) }
        if let number = any as? NSNumber {
            if CFGetTypeID(number) == CFBooleanGetTypeID() {
                return .bool(number.boolValue)
            }
            let double = number.doubleValue
            return double.isFinite ? .number(double) : nil
        }
        return nil
    }

    /// Converts a loosely typed trait map. `NSNull` (or a nil value) is kept as `nil`,
    /// which removes the trait; values of unsupported types are skipped.
    public static func traits(from dictionary: [String: Any?]) -> [String: ResponslyValue?] {
        var result: [String: ResponslyValue?] = [:]
        for (key, raw) in dictionary {
            guard let raw = raw, !(raw is NSNull) else {
                result.updateValue(nil, forKey: key)
                continue
            }
            if let value = from(raw) {
                result.updateValue(value, forKey: key)
            }
        }
        return result
    }

    /// Converts a loosely typed event-property map; values of unsupported types are skipped.
    public static func properties(from dictionary: [String: Any?]) -> [String: ResponslyValue] {
        var result: [String: ResponslyValue] = [:]
        for (key, raw) in dictionary {
            if let value = from(raw) {
                result[key] = value
            }
        }
        return result
    }
}

extension ResponslyValue: ExpressibleByStringLiteral {
    public init(stringLiteral value: String) {
        self = .string(value)
    }
}

extension ResponslyValue: ExpressibleByIntegerLiteral {
    public init(integerLiteral value: Int) {
        self = .number(Double(value))
    }
}

extension ResponslyValue: ExpressibleByFloatLiteral {
    public init(floatLiteral value: Double) {
        self = .number(value)
    }
}

extension ResponslyValue: ExpressibleByBooleanLiteral {
    public init(booleanLiteral value: Bool) {
        self = .bool(value)
    }
}
