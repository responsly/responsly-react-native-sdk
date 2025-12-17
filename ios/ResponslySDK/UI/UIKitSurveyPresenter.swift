#if canImport(UIKit) && canImport(WebKit)
import UIKit
import WebKit

/// Presents surveys from a dedicated window above the app's key window scene, so the host app
/// passes no view controller (protocol section 6).
final class UIKitSurveyPresenter: SurveyPresenting, @unchecked Sendable {
    @MainActor
    private final class Presentation {
        let sessionId: UUID
        let window: UIWindow
        let controller: SurveyViewController
        /// The app's key window, made key again when the survey closes.
        weak var hostWindow: UIWindow?

        init(sessionId: UUID, window: UIWindow, controller: SurveyViewController, hostWindow: UIWindow?) {
            self.sessionId = sessionId
            self.window = window
            self.controller = controller
            self.hostWindow = hostWindow
        }
    }

    /// Main thread only.
    private var current: Presentation?

    // MARK: - SurveyPresenting (any thread)

    func present(_ request: SurveyPresentationRequest, eventHandler: @escaping (SurveyPresentationEvent) -> Void) {
        onMain { $0.presentOnMain(request, eventHandler: eventHandler) }
    }

    func reveal(sessionId: UUID) {
        onMain { $0.revealOnMain(sessionId: sessionId) }
    }

    func enableUserClose(sessionId: UUID) {
        onMain { presenter in
            guard let presentation = presenter.current, presentation.sessionId == sessionId else { return }
            presentation.controller.enableUserClose()
        }
    }

    func dismiss(sessionId: UUID, animated: Bool) {
        onMain { $0.dismissOnMain(sessionId: sessionId, animated: animated) }
    }

    func clearWebsiteData(forHosts hosts: [String]) {
        guard !hosts.isEmpty else { return }
        onMain { _ in
            let store = WKWebsiteDataStore.default()
            let types = WKWebsiteDataStore.allWebsiteDataTypes()
            store.fetchDataRecords(ofTypes: types) { records in
                // Records are named after the registrable domain of the hosts they hold.
                let matching = records.filter { record in
                    hosts.contains { WebsiteDataMatcher.host($0, belongsToRecordNamed: record.displayName) }
                }
                guard !matching.isEmpty else { return }
                store.removeData(ofTypes: types, for: matching) {}
            }
        }
    }

    private func onMain(_ body: @escaping @MainActor (UIKitSurveyPresenter) -> Void) {
        DispatchQueue.main.async {
            MainActor.assumeIsolated {
                body(self)
            }
        }
    }

    // MARK: - Main thread

    @MainActor
    private func presentOnMain(_ request: SurveyPresentationRequest, eventHandler: @escaping (SurveyPresentationEvent) -> Void) {
        if let stale = current {
            dismissOnMain(sessionId: stale.sessionId, animated: false)
        }
        guard let scene = UIKitSurveyPresenter.activeWindowScene() else {
            eventHandler(.loadFailed("there is no foreground window scene to present in"))
            return
        }
        let hostWindow = UIKitSurveyPresenter.hostWindow(in: scene)
        let controller = SurveyViewController(request: request, hostViewController: hostWindow?.rootViewController)
        let sessionId = request.sessionId
        controller.onEvent = eventHandler
        controller.onUserClose = { [weak self] in
            self?.userClosed(sessionId: sessionId, eventHandler: eventHandler)
        }

        let window = SurveyWindow(windowScene: scene)
        // Above the app's content and its alerts-in-windows, below the keyboard.
        window.windowLevel = UIWindow.Level(rawValue: UIWindow.Level.alert.rawValue - 1)
        window.backgroundColor = .clear
        // The page loads while the container is invisible: the window is on screen so that the
        // page runs normally, but touches fall through to the app until `ready`.
        window.isUserInteractionEnabled = false
        window.rootViewController = controller
        window.isHidden = false

        current = Presentation(sessionId: sessionId, window: window, controller: controller, hostWindow: hostWindow)
    }

    @MainActor
    private func revealOnMain(sessionId: UUID) {
        guard let presentation = current, presentation.sessionId == sessionId else { return }
        presentation.window.isUserInteractionEnabled = true
        presentation.window.makeKey()
        presentation.controller.reveal()
    }

    @MainActor
    private func dismissOnMain(sessionId: UUID, animated: Bool) {
        guard let presentation = current, presentation.sessionId == sessionId else { return }
        current = nil
        let window = presentation.window
        window.isUserInteractionEnabled = false
        // Hand the keyboard focus back to the app right away, not after the animation.
        if window.isKeyWindow {
            window.endEditing(true)
            presentation.hostWindow?.makeKey()
        }
        // The closure keeps the presentation alive until the container is off screen.
        presentation.controller.close(animated: animated) {
            window.isHidden = true
            window.rootViewController = nil
            window.windowScene = nil
            _ = presentation
        }
    }

    @MainActor
    private func userClosed(sessionId: UUID, eventHandler: (SurveyPresentationEvent) -> Void) {
        guard let presentation = current, presentation.sessionId == sessionId else { return }
        eventHandler(.closedByUser)
        dismissOnMain(sessionId: sessionId, animated: true)
    }

    /// The scene the user is looking at: the foreground scene that holds the key window.
    @MainActor
    private static func activeWindowScene() -> UIWindowScene? {
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        let active = scenes.filter { $0.activationState == .foregroundActive }
        return active.first { $0.windows.contains { $0.isKeyWindow } }
            ?? active.first
            ?? scenes.first { $0.activationState == .foregroundInactive }
    }

    /// The app's own window in the scene: never a survey window that is still closing.
    @MainActor
    private static func hostWindow(in scene: UIWindowScene) -> UIWindow? {
        let windows = scene.windows.filter { !($0 is SurveyWindow) }
        return windows.first { $0.isKeyWindow }
            ?? windows.first { $0.windowLevel == .normal && !$0.isHidden }
    }
}

/// The window a survey is presented in.
private final class SurveyWindow: UIWindow {}

/// Tells the core when the app comes to the foreground (refresh a stale configuration) and goes
/// to the background (flush telemetry).
final class AppLifecycleObserver {
    private var observers: [NSObjectProtocol] = []

    init(core: ResponslyCore) {
        let center = NotificationCenter.default
        observers.append(center.addObserver(
            forName: UIApplication.willEnterForegroundNotification, object: nil, queue: .main
        ) { [weak core] _ in
            core?.applicationWillEnterForeground()
        })
        observers.append(center.addObserver(
            forName: UIApplication.didEnterBackgroundNotification, object: nil, queue: .main
        ) { [weak core] _ in
            guard let core = core else { return }
            MainActor.assumeIsolated {
                // Ask for a little time so that the telemetry request can finish.
                let task = BackgroundTask(name: "com.responsly.sdk.telemetry")
                core.applicationDidEnterBackground {
                    DispatchQueue.main.async {
                        MainActor.assumeIsolated {
                            task.end()
                        }
                    }
                }
            }
        })
    }

    deinit {
        observers.forEach(NotificationCenter.default.removeObserver)
    }
}

@MainActor
private final class BackgroundTask {
    private var identifier: UIBackgroundTaskIdentifier = .invalid

    init(name: String) {
        identifier = UIApplication.shared.beginBackgroundTask(withName: name) { [weak self] in
            self?.end()
        }
    }

    func end() {
        guard identifier != .invalid else { return }
        UIApplication.shared.endBackgroundTask(identifier)
        identifier = .invalid
    }
}
#endif
