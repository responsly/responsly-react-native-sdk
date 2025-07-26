import Foundation

/// Condition semantics of protocol section 4.3.
enum ConditionEvaluator {
    static func evaluate(_ condition: Condition, against values: [String: StoredValue]) -> Bool {
        guard let name = condition.name else { return false }
        let actual = values[name]
        switch condition.op {
        case .exists:
            return actual != nil
        case .notExists:
            return actual == nil
        case .eq:
            return isEqual(actual, condition.value)
        case .neq:
            return !isEqual(actual, condition.value)
        case .contains:
            return contains(actual, condition.value)
        case .notContains:
            return !contains(actual, condition.value)
        case .gt:
            return order(actual, condition.value).map { $0 > 0 } ?? false
        case .gte:
            return order(actual, condition.value).map { $0 >= 0 } ?? false
        case .lt:
            return order(actual, condition.value).map { $0 < 0 } ?? false
        case .lte:
            return order(actual, condition.value).map { $0 <= 0 } ?? false
        case .unknown:
            return false
        }
    }

    // MARK: - Equality

    /// Two numbers compare numerically, two booleans as booleans, anything else as text,
    /// case-insensitively. An absent side is never equal.
    static func isEqual(_ actual: StoredValue?, _ expected: StoredValue?) -> Bool {
        guard let actual = actual, let expected = expected else { return false }
        switch (actual, expected) {
        case (.number(let lhs), .number(let rhs)):
            return lhs == rhs
        case (.bool(let lhs), .bool(let rhs)):
            return lhs == rhs
        default:
            return folded(text(of: actual)) == folded(text(of: expected))
        }
    }

    // MARK: - Contains

    static func contains(_ actual: StoredValue?, _ expected: StoredValue?) -> Bool {
        guard let actual = actual, let expected = expected else { return false }
        let haystack = folded(text(of: actual))
        let needle = folded(text(of: expected))
        if needle.isEmpty { return true }
        guard needle.count <= haystack.count else { return false }
        for start in 0...(haystack.count - needle.count) where haystack[start] == needle[0] {
            if haystack[start..<(start + needle.count)].elementsEqual(needle) {
                return true
            }
        }
        return false
    }

    // MARK: - Ordering

    /// Negative, zero or positive; `nil` when the two sides cannot be ordered.
    static func order(_ actual: StoredValue?, _ expected: StoredValue?) -> Int? {
        guard let actual = actual, let expected = expected else { return nil }
        if let lhs = numericValue(actual), let rhs = numericValue(expected) {
            return lhs < rhs ? -1 : (lhs > rhs ? 1 : 0)
        }
        if case .string(let lhs) = actual, case .string(let rhs) = expected {
            // Ordinal, case-sensitive, by UTF-16 code unit: the same order on every platform.
            let left = Array(lhs.utf16)
            let right = Array(rhs.utf16)
            if left.lexicographicallyPrecedes(right) { return -1 }
            if right.lexicographicallyPrecedes(left) { return 1 }
            return 0
        }
        return nil
    }

    static func numericValue(_ value: StoredValue) -> Double? {
        switch value {
        case .number(let number):
            return number.isFinite ? number : nil
        case .string(let string):
            return parseDecimal(string)
        case .bool:
            return nil
        }
    }

    /// Parses a string that matches `^[+-]?(\d+(\.\d*)?|\.\d+)([eE][+-]?\d+)?$` (ASCII digits only)
    /// and has a finite value. The platform's lenient parser is not consulted for the grammar.
    static func parseDecimal(_ string: String) -> Double? {
        let bytes = Array(string.utf8)
        let count = bytes.count
        var index = 0

        func isDigit(_ byte: UInt8) -> Bool { byte >= 0x30 && byte <= 0x39 }
        func isSign(_ byte: UInt8) -> Bool { byte == 0x2B || byte == 0x2D }
        func scanDigits() -> String {
            let start = index
            while index < count, isDigit(bytes[index]) { index += 1 }
            return String(decoding: bytes[start..<index], as: UTF8.self)
        }

        var negative = false
        if index < count, isSign(bytes[index]) {
            negative = bytes[index] == 0x2D
            index += 1
        }
        let integerDigits = scanDigits()
        var fractionDigits = ""
        if index < count, bytes[index] == 0x2E {
            index += 1
            fractionDigits = scanDigits()
            if integerDigits.isEmpty && fractionDigits.isEmpty { return nil }
        } else if integerDigits.isEmpty {
            return nil
        }
        var exponent = ""
        if index < count, bytes[index] == 0x65 || bytes[index] == 0x45 {
            index += 1
            var exponentNegative = false
            if index < count, isSign(bytes[index]) {
                exponentNegative = bytes[index] == 0x2D
                index += 1
            }
            let exponentDigits = scanDigits()
            if exponentDigits.isEmpty { return nil }
            exponent = "e" + (exponentNegative ? "-" : "") + exponentDigits
        }
        guard index == count else { return nil }

        // The grammar is validated above; hand the parser a canonical spelling so that forms
        // such as `5.` or `.5` do not depend on what it happens to accept.
        let canonical = (negative ? "-" : "")
            + (integerDigits.isEmpty ? "0" : integerDigits)
            + "." + (fractionDigits.isEmpty ? "0" : fractionDigits)
            + exponent
        guard let value = Double(canonical), value.isFinite else { return nil }
        return value
    }

    // MARK: - Text

    /// A string as is; a boolean as `true` / `false`; a number without a fractional part as an
    /// integer (`5`, not `5.0`), otherwise its shortest decimal form.
    static func text(of value: StoredValue) -> String {
        switch value {
        case .string(let string):
            return string
        case .bool(let bool):
            return bool ? "true" : "false"
        case .number(let number):
            return text(ofNumber: number)
        }
    }

    static func text(ofNumber number: Double) -> String {
        guard number.isFinite else { return "\(number)" }
        if number == number.rounded(.towardZero) {
            if abs(number) < 9.0e18 {
                return String(Int64(number))
            }
            return String(format: "%.0f", locale: Locale(identifier: "en_US_POSIX"), number)
        }
        // Swift prints the shortest representation that round-trips, with an exponent for
        // very small magnitudes; expand that into plain decimal notation.
        return expandExponent("\(number)")
    }

    private static func expandExponent(_ description: String) -> String {
        guard let exponentIndex = description.firstIndex(where: { $0 == "e" || $0 == "E" }) else {
            return description
        }
        var mantissa = String(description[..<exponentIndex])
        guard let exponent = Int(description[description.index(after: exponentIndex)...]) else {
            return description
        }
        var sign = ""
        if mantissa.hasPrefix("-") {
            sign = "-"
            mantissa.removeFirst()
        }
        let parts = mantissa.split(separator: ".", omittingEmptySubsequences: false)
        let integerPart = String(parts.first ?? "")
        let fractionPart = parts.count > 1 ? String(parts[1]) : ""
        var digits = integerPart + fractionPart
        var pointPosition = integerPart.count + exponent

        if pointPosition <= 0 {
            digits = String(repeating: "0", count: -pointPosition) + digits
            return sign + "0." + digits
        }
        if pointPosition >= digits.count {
            digits += String(repeating: "0", count: pointPosition - digits.count)
            pointPosition = digits.count
            return sign + digits
        }
        let split = digits.index(digits.startIndex, offsetBy: pointPosition)
        return sign + String(digits[..<split]) + "." + String(digits[split...])
    }

    /// Locale-independent lower-casing, as UTF-16 code units so that comparison is ordinal.
    private static func folded(_ string: String) -> [UInt16] {
        Array(string.lowercased().utf16)
    }
}

/// FNV-1a, 32 bit.
enum FNV1a {
    static func hash32(_ string: String) -> UInt32 {
        var hash: UInt32 = 0x811c_9dc5
        for byte in string.utf8 {
            hash ^= UInt32(byte)
            hash = hash &* 0x0100_0193
        }
        return hash
    }
}

enum LocaleMatcher {
    /// Case-insensitive, `_` treated as `-`.
    static func normalize(_ tag: String) -> String {
        tag.trimmingCharacters(in: .whitespacesAndNewlines)
            .replacingOccurrences(of: "_", with: "-")
            .lowercased()
    }

    /// An entry matches when it equals the tag or the tag starts with the entry followed by `-`
    /// (`pt` matches `pt-BR`; `pt-BR` does not match `pt-PT`).
    static func matches(entry: String, tag: String) -> Bool {
        let entry = normalize(entry)
        let tag = normalize(tag)
        guard !entry.isEmpty else { return false }
        return tag == entry || tag.hasPrefix(entry + "-")
    }

    static func matches(entries: [String], tag: String) -> Bool {
        entries.contains { matches(entry: $0, tag: tag) }
    }
}
