import Foundation

/// A message from the survey page (protocol section 5.3).
enum BridgeMessage {
    case ready(surveyId: String?, title: String?, backgroundColor: String?, pageCount: Int?)
    /// The height the page needs, in CSS pixels.
    case resize(height: Double)
    case pageChanged(index: Int?, count: Int?)
    case questionAnswered(ResponslyAnswer)
    case completed(responseId: String, answers: [ResponslyAnswer])
    case closeRequested(reason: String?)
    case error(code: String, message: String)
    /// A type this SDK does not know. Ignored.
    case unknown(type: String)

    static let handlerName = "responsly"

    var type: String {
        switch self {
        case .ready: return "ready"
        case .resize: return "resize"
        case .pageChanged: return "page_changed"
        case .questionAnswered: return "question_answered"
        case .completed: return "completed"
        case .closeRequested: return "close_requested"
        case .error: return "error"
        case .unknown(let type): return type
        }
    }

    /// Parses `{"responsly":1,"type":"<type>","payload":{...}}`. `nil` for anything else.
    static func parse(_ raw: String) -> BridgeMessage? {
        guard let data = raw.data(using: .utf8),
              let object = (try? JSONSerialization.jsonObject(with: data, options: [])) as? [String: Any],
              JSONRead.int(object["responsly"]) == 1,
              let type = object["type"] as? String
        else { return nil }
        let payload = (object["payload"] as? [String: Any]) ?? [:]

        switch type {
        case "ready":
            return .ready(
                surveyId: JSONRead.text(payload["survey_id"]),
                title: payload["title"] as? String,
                backgroundColor: payload["background_color"] as? String,
                pageCount: JSONRead.int(payload["page_count"])
            )
        case "resize":
            guard let height = JSONRead.double(payload["height"]), height >= 0 else { return nil }
            return .resize(height: height)
        case "page_changed":
            return .pageChanged(index: JSONRead.int(payload["index"]), count: JSONRead.int(payload["count"]))
        case "question_answered":
            return .questionAnswered(answer(from: payload))
        case "completed":
            let answers = ((payload["answers"] as? [Any]) ?? []).compactMap { entry -> ResponslyAnswer? in
                (entry as? [String: Any]).map(answer(from:))
            }
            return .completed(responseId: JSONRead.text(payload["response_id"]) ?? "", answers: answers)
        case "close_requested":
            return .closeRequested(reason: payload["reason"] as? String)
        case "error":
            return .error(
                code: JSONRead.text(payload["code"]) ?? "unknown",
                message: (payload["message"] as? String) ?? ""
            )
        default:
            return .unknown(type: type)
        }
    }

    private static func answer(from payload: [String: Any]) -> ResponslyAnswer {
        ResponslyAnswer(
            questionId: JSONRead.text(payload["question_id"]) ?? "",
            type: (payload["type"] as? String) ?? "",
            title: (payload["title"] as? String) ?? "",
            value: payload["value"] ?? NSNull()
        )
    }
}

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

/// The survey URL and the context handed to the page (protocol sections 5.1 and 5.2).
enum SurveyPage {
    struct ContextInput {
        var platform: String
        var sdkVersion: String
        var appId: String
        var appVersion: String
        var osVersion: String
        var locale: String
        var surveyId: String
        var trigger: ResponslyTrigger
        var style: ResponslyPresentationStyle
        var dismissible: Bool
        var anonymous: Bool
        var userId: String?
        var traits: [String: StoredValue]
        var hiddenFields: [String: String]
    }

    /// `survey.url` + `?sdk=1`, plus `&lang=<code>` only when a locale was set explicitly.
    /// Nothing else goes into the URL.
    static func url(surveyURL: URL, lang: String?) -> URL {
        guard var components = URLComponents(url: surveyURL, resolvingAgainstBaseURL: false) else {
            return surveyURL
        }
        var items = components.queryItems ?? []
        items.append(URLQueryItem(name: "sdk", value: "1"))
        if let lang = lang, !lang.isEmpty {
            items.append(URLQueryItem(name: "lang", value: lang))
        }
        components.queryItems = items
        return components.url ?? surveyURL
    }

    static func context(_ input: ContextInput) -> [String: Any] {
        var context: [String: Any] = [
            "v": 1,
            "platform": input.platform,
            "sdk_version": input.sdkVersion,
            "app_id": input.appId,
            "app_version": input.appVersion,
            "os_version": input.osVersion,
            "locale": input.locale,
            "survey_id": input.surveyId,
            "trigger": input.trigger.toDictionary(),
            "presentation": [
                "style": input.style.rawValue,
                "dismissible": input.dismissible,
            ] as [String: Any],
        ]
        // An anonymous survey gets no user and no hidden fields.
        if !input.anonymous, let userId = input.userId {
            context["user"] = [
                "id": userId,
                "traits": input.traits.mapValues { $0.jsonObject },
            ] as [String: Any]
        } else {
            context["user"] = NSNull()
        }
        context["hidden"] = input.anonymous ? [String: String]() : input.hiddenFields
        return context
    }

    /// The document-start script. It defines `window.__RESPONSLY_SDK__` only when the document's
    /// origin is the survey's origin (the script itself is installed for the main frame only).
    static func contextScript(context: [String: Any], origin: WebOrigin) -> String {
        let contextJSON = javaScriptLiteral(context) ?? "null"
        let originJSON = javaScriptLiteral([origin.serialized]) ?? "[]"
        return """
        (function () {
          if (window.location.origin !== \(originJSON)[0]) { return; }
          window.__RESPONSLY_SDK__ = \(contextJSON);
        })();
        """
    }

    /// JSON that is safe to embed in a script.
    static func javaScriptLiteral(_ object: Any) -> String? {
        guard JSONSerialization.isValidJSONObject(object),
              let data = try? JSONSerialization.data(withJSONObject: object, options: [.sortedKeys]),
              let json = String(data: data, encoding: .utf8)
        else { return nil }
        return json
            .replacingOccurrences(of: "\u{2028}", with: "\\u2028")
            .replacingOccurrences(of: "\u{2029}", with: "\\u2029")
    }
}

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
