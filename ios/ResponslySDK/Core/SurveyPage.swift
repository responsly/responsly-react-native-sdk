import Foundation

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
