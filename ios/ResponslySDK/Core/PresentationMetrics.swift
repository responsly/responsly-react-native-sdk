import Foundation

/// Container heights (protocol section 6).
enum PresentationMetrics {
    static let minimumHeight: Double = 320
    static let sheetMaxWidth: Double = 640
    static let dialogMaxWidth: Double = 480
    static let dialogMargin: Double = 16
    static let cornerRadius: Double = 16

    /// Share of the screen height for a size.
    static func heightFraction(style: ResponslyPresentationStyle, size: PresentationSize) -> Double {
        switch (style, size) {
        case (.bottomSheet, .small): return 0.45
        case (.bottomSheet, .medium): return 0.65
        case (.bottomSheet, .large): return 0.88
        case (.dialog, .small): return 0.40
        case (.dialog, .medium): return 0.56
        case (.dialog, .large): return 0.80
        case (.fullscreen, _): return 1
        }
    }

    /// The largest share a sheet or dialog may grow to.
    static func maxHeightFraction(style: ResponslyPresentationStyle) -> Double {
        switch style {
        case .bottomSheet: return 0.88
        case .dialog: return 0.80
        case .fullscreen: return 1
        }
    }

    /// The container height for a screen.
    ///
    /// - Parameters:
    ///   - requestedContentHeight: the largest height the page asked for with `resize` (0 if none).
    ///   - chrome: container height that is not available to the page (the bottom safe-area
    ///     strip of a sheet), added to the page's request.
    static func containerHeight(
        style: ResponslyPresentationStyle,
        size: PresentationSize,
        screenHeight: Double,
        requestedContentHeight: Double,
        chrome: Double = 0
    ) -> Double {
        guard style != .fullscreen else { return screenHeight }
        // By size, never less than 320.
        let base = max(screenHeight * heightFraction(style: style, size: size), minimumHeight)
        // Grows to what the page needs, up to the style's limit; a smaller request is ignored.
        let limit = max(screenHeight * maxHeightFraction(style: style), base)
        let requested = requestedContentHeight > 0 ? requestedContentHeight + chrome : 0
        return min(max(base, requested), limit)
    }
}
