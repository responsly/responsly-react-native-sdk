import Foundation

/// What to do with a navigation in the survey's WebView (protocol section 5.4).
enum NavigationDecision: Equatable {
    case allow
    /// Open in the system browser and cancel in the WebView.
    case openExternally
    case cancel
}

enum NavigationPolicy {
    /// Schemes that must never be handed to the system.
    private static let inertSchemes: Set<String> = ["about", "blob", "data", "javascript", "file"]

    /// The only schemes a survey may open outside the app. Custom schemes, the host app's own
    /// deep links among them, are never opened.
    static let externalSchemes: Set<String> = ["http", "https", "mailto", "tel", "sms"]

    /// Whether a navigation that leaves the visible survey is handed to the system: only after a
    /// tap on a link, and only for `externalSchemes`. A page script, a server redirect or a
    /// `<meta http-equiv="refresh">` never opens anything by itself.
    static func mayOpenExternally(_ url: URL, userInitiated: Bool) -> Bool {
        guard userInitiated, let scheme = url.scheme?.lowercased() else { return false }
        return externalSchemes.contains(scheme)
    }

    /// - Parameters:
    ///   - isMainFrame: the navigation targets the main frame.
    ///   - opensNewWindow: the navigation asks for a new window (`target="_blank"`, `window.open`).
    static func decide(url: URL?, surveyURL: URL, isMainFrame: Bool, opensNewWindow: Bool) -> NavigationDecision {
        guard isMainFrame || opensNewWindow else { return .allow }
        guard let url = url, let scheme = url.scheme?.lowercased() else { return .cancel }
        if inertSchemes.contains(scheme) { return .cancel }
        if opensNewWindow { return .openExternally }
        guard scheme == "http" || scheme == "https" else { return .openExternally }
        return isSurveyDocument(url, surveyURL: surveyURL) ? .allow : .openExternally
    }

    /// Same origin and same path as the survey URL; query and fragment may differ.
    static func isSurveyDocument(_ url: URL, surveyURL: URL) -> Bool {
        guard let origin = WebOrigin(url: url), let surveyOrigin = WebOrigin(url: surveyURL) else { return false }
        return origin == surveyOrigin && path(of: url) == path(of: surveyURL)
    }

    private static func path(of url: URL) -> String {
        let path = URLComponents(url: url, resolvingAgainstBaseURL: true)?.percentEncodedPath ?? url.path
        return path.isEmpty ? "/" : path
    }
}
