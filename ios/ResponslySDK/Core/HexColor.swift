import Foundation

/// A CSS hex colour: `#RGB`, `#RGBA`, `#RRGGBB` or `#RRGGBBAA`.
struct HexColor: Equatable {
    let red: Double
    let green: Double
    let blue: Double
    let alpha: Double

    init?(css: String?) {
        guard let css = css?.trimmingCharacters(in: .whitespacesAndNewlines), css.hasPrefix("#") else { return nil }
        var digits = Array(css.dropFirst())
        guard digits.allSatisfy({ $0.isASCII && $0.isHexDigit }) else { return nil }
        if digits.count == 3 || digits.count == 4 {
            digits = digits.flatMap { [$0, $0] }
        }
        guard digits.count == 6 || digits.count == 8 else { return nil }
        var components: [Double] = []
        for index in stride(from: 0, to: digits.count, by: 2) {
            guard let byte = UInt8(String(digits[index...index + 1]), radix: 16) else { return nil }
            components.append(Double(byte) / 255)
        }
        red = components[0]
        green = components[1]
        blue = components[2]
        alpha = components.count == 4 ? components[3] : 1
    }

    /// Relative luminance (WCAG), 0 = black, 1 = white.
    var luminance: Double {
        func linear(_ component: Double) -> Double {
            component <= 0.03928 ? component / 12.92 : pow((component + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * linear(red) + 0.7152 * linear(green) + 0.0722 * linear(blue)
    }

    var isDark: Bool {
        luminance < 0.4
    }
}
