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
