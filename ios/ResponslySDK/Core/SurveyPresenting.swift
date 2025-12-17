import Foundation

/// Everything the UI layer needs to load and present one survey.
struct SurveyPresentationRequest {
    let sessionId: UUID
    let surveyId: String
    /// `survey.url`: its origin is the only one that gets the context and may post messages, and
    /// its origin and path are the only place the main frame may navigate to.
    let surveyURL: URL
    /// The URL to load: `survey.url` + `?sdk=1` (+ `&lang=`).
    let loadURL: URL
    /// Document-start script that defines `window.__RESPONSLY_SDK__`.
    let contextScript: String
    let style: ResponslyPresentationStyle
    let size: PresentationSize
    let dismissible: Bool
    /// ` ResponslySDK/<version> (<platform>)`
    let userAgentSuffix: String
    /// The log level is `debug`: allow Web Inspector to attach.
    let inspectable: Bool
}

enum SurveyPresentationEvent {
    /// A bridge message from the main frame of the survey origin.
    case message(BridgeMessage, raw: String)
    /// The main-frame load failed, or the survey could not be put on screen.
    case loadFailed(String)
    /// The main frame tried to leave the survey (the URL was opened in the system browser when
    /// the survey was visible).
    case navigatedAway(URL)
    /// The close button or the backdrop was tapped; the container is already closing.
    case closedByUser
}

/// The UI layer, as the core sees it. Every method may be called from any thread.
protocol SurveyPresenting: AnyObject {
    /// Creates the container and the WebView, hidden, and starts loading.
    /// The handler may be called on any thread.
    func present(_ request: SurveyPresentationRequest, eventHandler: @escaping (SurveyPresentationEvent) -> Void)
    /// Makes the container visible (the page reported `ready`).
    func reveal(sessionId: UUID)
    /// Shows the close button and accepts a backdrop tap even when the survey is not dismissible
    /// (the page reported `completed`).
    func enableUserClose(sessionId: UUID)
    func dismiss(sessionId: UUID, animated: Bool)
    /// Removes the WebView's stored website data for these survey hosts.
    func clearWebsiteData(forHosts hosts: [String])
}

/// Used where there is no UIKit (macOS builds of the package): nothing can be shown.
final class UnavailableSurveyPresenter: SurveyPresenting {
    func present(_ request: SurveyPresentationRequest, eventHandler: @escaping (SurveyPresentationEvent) -> Void) {
        eventHandler(.loadFailed("surveys cannot be presented on this platform"))
    }

    func reveal(sessionId: UUID) {}
    func enableUserClose(sessionId: UUID) {}
    func dismiss(sessionId: UUID, animated: Bool) {}
    func clearWebsiteData(forHosts hosts: [String]) {}
}

/// Listeners, readable from the main thread while the core works on its own queue.
final class ListenerRegistry: @unchecked Sendable {
    private let lock = NSLock()
    private var entries: [(token: ResponslyListenerToken, listener: ResponslyListener)] = []

    func add(_ listener: ResponslyListener) -> ResponslyListenerToken {
        let token = ResponslyListenerToken()
        lock.lock()
        entries.append((token, listener))
        lock.unlock()
        return token
    }

    func remove(_ token: ResponslyListenerToken) {
        lock.lock()
        entries.removeAll { $0.token == token }
        lock.unlock()
    }

    func snapshot() -> [ResponslyListener] {
        lock.lock()
        defer { lock.unlock() }
        return entries.map { $0.listener }
    }
}
