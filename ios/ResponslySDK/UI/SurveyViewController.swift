#if canImport(UIKit) && canImport(WebKit)
import UIKit
import WebKit

/// The survey container: backdrop, the sheet / dialog / full-screen card, the WebView that loads
/// the survey page, and the close button (protocol sections 5 and 6).
final class SurveyViewController: UIViewController {
    /// Bridge messages, load failures and navigation away. Called on the main thread.
    var onEvent: ((SurveyPresentationEvent) -> Void)?
    /// The close button or the backdrop was tapped.
    var onUserClose: (() -> Void)?

    private let request: SurveyPresentationRequest
    private let surveyOrigin: WebOrigin?
    private weak var hostViewController: UIViewController?

    private let backdropView = UIView()
    private let containerView = UIView()
    private let closeButton = UIButton(type: .custom)
    private let messageProxy = ScriptMessageProxy()
    private var webView: WKWebView?
    private var heightConstraint: NSLayoutConstraint?

    /// The largest height the page asked for with `resize`.
    private var requestedContentHeight: CGFloat = 0
    private var pageBackground: HexColor?
    private var userCloseEnabled: Bool
    private var isRevealed = false
    private var isClosing = false
    private var hasReportedLoadFailure = false

    /// - Parameter hostViewController: the app's root view controller; consulted for the status
    ///   bar and the supported orientations so that this window does not change them.
    init(request: SurveyPresentationRequest, hostViewController: UIViewController?) {
        self.request = request
        self.surveyOrigin = WebOrigin(url: request.surveyURL)
        self.hostViewController = hostViewController
        self.userCloseEnabled = request.dismissible
        super.init(nibName: nil, bundle: nil)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) is not supported")
    }

    // MARK: - View

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .clear
        // Nothing is on screen until the page reports `ready`.
        view.accessibilityElementsHidden = true

        backdropView.backgroundColor = UIColor.black.withAlphaComponent(0.4)
        backdropView.alpha = 0
        backdropView.isHidden = request.style == .fullscreen
        backdropView.translatesAutoresizingMaskIntoConstraints = false
        backdropView.addGestureRecognizer(UITapGestureRecognizer(target: self, action: #selector(backdropTapped)))
        view.addSubview(backdropView)

        containerView.backgroundColor = .systemBackground
        containerView.alpha = 0
        containerView.clipsToBounds = true
        containerView.accessibilityViewIsModal = true
        containerView.translatesAutoresizingMaskIntoConstraints = false
        containerView.layer.cornerCurve = .continuous
        switch request.style {
        case .bottomSheet:
            containerView.layer.cornerRadius = CGFloat(PresentationMetrics.cornerRadius)
            containerView.layer.maskedCorners = [.layerMinXMinYCorner, .layerMaxXMinYCorner]
        case .dialog:
            containerView.layer.cornerRadius = CGFloat(PresentationMetrics.cornerRadius)
        case .fullscreen:
            break
        }
        view.addSubview(containerView)

        let webView = makeWebView()
        self.webView = webView
        containerView.addSubview(webView)

        closeButton.translatesAutoresizingMaskIntoConstraints = false
        closeButton.accessibilityLabel = SurveyViewController.closeLabel
        closeButton.addTarget(self, action: #selector(closeTapped), for: .touchUpInside)
        closeButton.isHidden = !userCloseEnabled
        updateCloseIcon()
        containerView.addSubview(closeButton)

        installConstraints(webView: webView)
        webView.load(URLRequest(url: request.loadURL))
    }

    private func makeWebView() -> WKWebView {
        let configuration = WKWebViewConfiguration()
        // JavaScript and DOM storage are on by default; the default store keeps cookies.
        configuration.websiteDataStore = .default()
        configuration.allowsInlineMediaPlayback = true
        let applicationName = configuration.applicationNameForUserAgent ?? "Mobile/15E148"
        configuration.applicationNameForUserAgent = applicationName + request.userAgentSuffix

        let userContentController = WKUserContentController()
        // The context, before any page script runs, in the main frame only. The script checks
        // the document's origin itself.
        userContentController.addUserScript(WKUserScript(
            source: request.contextScript,
            injectionTime: .atDocumentStart,
            forMainFrameOnly: true
        ))
        messageProxy.target = self
        userContentController.add(messageProxy, name: BridgeMessage.handlerName)
        configuration.userContentController = userContentController

        let webView = WKWebView(frame: .zero, configuration: configuration)
        webView.translatesAutoresizingMaskIntoConstraints = false
        webView.navigationDelegate = self
        webView.uiDelegate = self
        webView.allowsLinkPreview = false
        webView.isOpaque = false
        webView.backgroundColor = .clear
        webView.scrollView.backgroundColor = .clear
        // The container keeps the page inside the safe area (sheet, dialog) or the page handles
        // the safe areas itself (full screen).
        webView.scrollView.contentInsetAdjustmentBehavior = .never
        if #available(iOS 16.4, *) {
            webView.isInspectable = request.inspectable
        }
        return webView
    }

    private func installConstraints(webView: WKWebView) {
        var constraints = [
            backdropView.topAnchor.constraint(equalTo: view.topAnchor),
            backdropView.bottomAnchor.constraint(equalTo: view.bottomAnchor),
            backdropView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            backdropView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            webView.topAnchor.constraint(equalTo: containerView.topAnchor),
            webView.leadingAnchor.constraint(equalTo: containerView.leadingAnchor),
            webView.trailingAnchor.constraint(equalTo: containerView.trailingAnchor),
            closeButton.widthAnchor.constraint(equalToConstant: 44),
            closeButton.heightAnchor.constraint(equalToConstant: 44),
        ]

        switch request.style {
        case .bottomSheet:
            // Full width up to 640, centred; attached to the bottom. The page ends at the
            // keyboard (or the bottom safe area), the sheet's background continues below it.
            let fullWidth = containerView.widthAnchor.constraint(equalTo: view.widthAnchor)
            fullWidth.priority = UILayoutPriority(999)
            let height = webView.heightAnchor.constraint(equalToConstant: CGFloat(PresentationMetrics.minimumHeight))
            height.priority = UILayoutPriority(750)
            heightConstraint = height
            constraints += [
                containerView.centerXAnchor.constraint(equalTo: view.centerXAnchor),
                containerView.widthAnchor.constraint(lessThanOrEqualToConstant: CGFloat(PresentationMetrics.sheetMaxWidth)),
                fullWidth,
                containerView.bottomAnchor.constraint(equalTo: view.bottomAnchor),
                containerView.topAnchor.constraint(greaterThanOrEqualTo: view.safeAreaLayoutGuide.topAnchor, constant: 8),
                webView.bottomAnchor.constraint(equalTo: view.keyboardLayoutGuide.topAnchor),
                height,
                closeButton.topAnchor.constraint(equalTo: containerView.topAnchor),
                closeButton.trailingAnchor.constraint(equalTo: containerView.trailingAnchor),
            ]

        case .dialog:
            // Centred in the area above the keyboard; width min(screen − 32, 480).
            let area = UILayoutGuide()
            view.addLayoutGuide(area)
            let margin = CGFloat(PresentationMetrics.dialogMargin)
            let preferredWidth = containerView.widthAnchor.constraint(equalToConstant: CGFloat(PresentationMetrics.dialogMaxWidth))
            preferredWidth.priority = UILayoutPriority(999)
            let centered = containerView.centerYAnchor.constraint(equalTo: area.centerYAnchor)
            centered.priority = UILayoutPriority(900)
            let height = containerView.heightAnchor.constraint(equalToConstant: CGFloat(PresentationMetrics.minimumHeight))
            height.priority = UILayoutPriority(750)
            heightConstraint = height
            constraints += [
                area.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor),
                area.bottomAnchor.constraint(equalTo: view.keyboardLayoutGuide.topAnchor),
                containerView.centerXAnchor.constraint(equalTo: view.centerXAnchor),
                preferredWidth,
                containerView.widthAnchor.constraint(lessThanOrEqualTo: view.widthAnchor, constant: -2 * margin),
                centered,
                containerView.topAnchor.constraint(greaterThanOrEqualTo: area.topAnchor, constant: margin),
                containerView.bottomAnchor.constraint(lessThanOrEqualTo: area.bottomAnchor, constant: -margin),
                height,
                webView.bottomAnchor.constraint(equalTo: containerView.bottomAnchor),
                closeButton.topAnchor.constraint(equalTo: containerView.topAnchor),
                closeButton.trailingAnchor.constraint(equalTo: containerView.trailingAnchor),
            ]

        case .fullscreen:
            constraints += [
                containerView.topAnchor.constraint(equalTo: view.topAnchor),
                containerView.bottomAnchor.constraint(equalTo: view.bottomAnchor),
                containerView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
                containerView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
                webView.bottomAnchor.constraint(equalTo: containerView.bottomAnchor),
                closeButton.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor),
                closeButton.trailingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.trailingAnchor),
            ]
        }
        NSLayoutConstraint.activate(constraints)
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        updateHeight()
    }

    /// Height by `presentation.size`, grown to what the page asked for (protocol section 6).
    /// The constraints add the physical limits: the top safe area and the keyboard.
    private func updateHeight() {
        guard let heightConstraint = heightConstraint, view.bounds.height > 0 else { return }
        // Below the page, a sheet continues over the bottom safe area.
        let chrome = request.style == .bottomSheet ? Double(view.safeAreaInsets.bottom) : 0
        let total = PresentationMetrics.containerHeight(
            style: request.style,
            size: request.size,
            screenHeight: Double(view.bounds.height),
            requestedContentHeight: Double(requestedContentHeight),
            chrome: chrome
        )
        let constant = CGFloat(max(0, total - chrome))
        if abs(heightConstraint.constant - constant) > 0.5 {
            heightConstraint.constant = constant
        }
    }

    // MARK: - Driven by the presenter

    /// The page reported `ready`: slide the sheet in, fade the others; fade only with reduced motion.
    func reveal() {
        guard !isRevealed, !isClosing else { return }
        isRevealed = true
        view.accessibilityElementsHidden = false
        view.layoutIfNeeded()
        setNeedsStatusBarAppearanceUpdate()

        let reduceMotion = UIAccessibility.isReduceMotionEnabled
        if request.style == .bottomSheet && !reduceMotion {
            containerView.alpha = 1
            containerView.transform = CGAffineTransform(translationX: 0, y: containerView.bounds.height)
            UIView.animate(withDuration: 0.3, delay: 0, options: [.curveEaseOut, .allowUserInteraction]) {
                self.backdropView.alpha = 1
                self.containerView.transform = .identity
            }
        } else {
            UIView.animate(withDuration: reduceMotion ? 0.15 : 0.25, delay: 0, options: [.curveEaseOut, .allowUserInteraction]) {
                self.backdropView.alpha = 1
                self.containerView.alpha = 1
            }
        }
        UIAccessibility.post(notification: .screenChanged, argument: containerView)
    }

    /// After `completed` the survey can always be closed by the user.
    func enableUserClose() {
        guard !userCloseEnabled else { return }
        userCloseEnabled = true
        closeButton.alpha = 0
        closeButton.isHidden = false
        UIView.animate(withDuration: 0.2) {
            self.closeButton.alpha = 1
        }
    }

    /// Stops the page and takes the container off screen; `completion` runs when it is gone.
    func close(animated: Bool, completion: @escaping () -> Void) {
        guard !isClosing else {
            completion()
            return
        }
        isClosing = true
        view.endEditing(true)

        let finish = {
            self.tearDownWebView()
            completion()
        }
        guard animated, isRevealed else {
            finish()
            return
        }
        let reduceMotion = UIAccessibility.isReduceMotionEnabled
        let slide = request.style == .bottomSheet && !reduceMotion
        UIView.animate(
            withDuration: slide ? 0.25 : (reduceMotion ? 0.15 : 0.2),
            delay: 0,
            options: [.curveEaseIn, .beginFromCurrentState],
            animations: {
                self.backdropView.alpha = 0
                if slide {
                    self.containerView.transform = CGAffineTransform(translationX: 0, y: self.containerView.bounds.height)
                } else {
                    self.containerView.alpha = 0
                }
            },
            completion: { _ in finish() }
        )
    }

    private func tearDownWebView() {
        guard let webView = webView else { return }
        self.webView = nil
        webView.stopLoading()
        webView.navigationDelegate = nil
        webView.uiDelegate = nil
        let userContentController = webView.configuration.userContentController
        userContentController.removeScriptMessageHandler(forName: BridgeMessage.handlerName)
        userContentController.removeAllUserScripts()
        messageProxy.target = nil
        webView.removeFromSuperview()
    }

    // MARK: - Page-driven changes

    private func applyPageBackground(_ cssColor: String?) {
        guard let color = HexColor(css: cssColor) else { return }
        pageBackground = color
        containerView.backgroundColor = UIColor(
            red: CGFloat(color.red), green: CGFloat(color.green), blue: CGFloat(color.blue), alpha: CGFloat(color.alpha)
        )
        updateCloseIcon()
        setNeedsStatusBarAppearanceUpdate()
    }

    /// `resize`: the sheet or dialog grows to what the page needs; a smaller value is ignored.
    private func growContent(to height: Double) {
        let height = CGFloat(height)
        guard request.style != .fullscreen, height > requestedContentHeight else { return }
        requestedContentHeight = height
        guard isRevealed, !isClosing, !UIAccessibility.isReduceMotionEnabled else {
            view.setNeedsLayout()
            return
        }
        UIView.animate(withDuration: 0.25, delay: 0, options: [.curveEaseInOut, .allowUserInteraction, .beginFromCurrentState]) {
            self.updateHeight()
            self.view.layoutIfNeeded()
        }
    }

    private func reportLoadFailure(_ description: String) {
        guard !hasReportedLoadFailure, !isClosing else { return }
        hasReportedLoadFailure = true
        onEvent?(.loadFailed(description))
    }

    /// The main frame tried to go somewhere else, or a link asked for a new window.
    private func leaveSurvey(for url: URL) {
        guard !isClosing else { return }
        guard isRevealed else {
            // Nothing is on screen yet, so nobody asked to go anywhere: do not throw the user
            // into the browser, give up on the survey instead.
            reportLoadFailure("the survey page redirected away from the survey URL")
            return
        }
        UIApplication.shared.open(url, options: [:], completionHandler: nil)
        onEvent?(.navigatedAway(url))
    }

    // MARK: - Closing by the user

    @objc private func closeTapped() {
        guard userCloseEnabled, isRevealed, !isClosing else { return }
        onUserClose?()
    }

    @objc private func backdropTapped() {
        guard userCloseEnabled, isRevealed, !isClosing else { return }
        onUserClose?()
    }

    override func accessibilityPerformEscape() -> Bool {
        guard userCloseEnabled, isRevealed, !isClosing else { return false }
        onUserClose?()
        return true
    }

    // MARK: - Close button

    private static let closeLabel: String = {
        // UIKit's own translation of "Close", so that the label follows the device language.
        Bundle(for: UIApplication.self).localizedString(forKey: "Close", value: "Close", table: nil)
    }()

    private func updateCloseIcon() {
        let onDark = pageBackground?.isDark ?? (traitCollection.userInterfaceStyle == .dark)
        closeButton.setImage(SurveyViewController.closeIcon(onDarkBackground: onDark), for: .normal)
    }

    /// A 24-point icon; the button around it is the 44-point touch target.
    private static func closeIcon(onDarkBackground: Bool) -> UIImage {
        let size = CGSize(width: 24, height: 24)
        return UIGraphicsImageRenderer(size: size).image { _ in
            let chip = onDarkBackground ? UIColor(white: 1, alpha: 0.24) : UIColor(white: 0, alpha: 0.10)
            let cross = onDarkBackground ? UIColor(white: 1, alpha: 0.92) : UIColor(white: 0, alpha: 0.62)
            chip.setFill()
            UIBezierPath(ovalIn: CGRect(origin: .zero, size: size)).fill()
            let path = UIBezierPath()
            path.move(to: CGPoint(x: 8.25, y: 8.25))
            path.addLine(to: CGPoint(x: 15.75, y: 15.75))
            path.move(to: CGPoint(x: 15.75, y: 8.25))
            path.addLine(to: CGPoint(x: 8.25, y: 15.75))
            path.lineWidth = 1.75
            path.lineCapStyle = .round
            cross.setStroke()
            path.stroke()
        }
    }

    // MARK: - Leaving the host app's chrome alone

    /// The host view controller that decides the status bar, following its children and
    /// full-screen presentations.
    private var hostStatusBarController: UIViewController? {
        var controller = hostViewController
        while let current = controller {
            if let presented = current.presentedViewController, !presented.isBeingDismissed,
               presented.modalPresentationStyle == .fullScreen || presented.modalPresentationCapturesStatusBarAppearance {
                controller = presented
            } else if let child = current.childForStatusBarStyle {
                controller = child
            } else {
                break
            }
        }
        return controller
    }

    override var preferredStatusBarStyle: UIStatusBarStyle {
        if request.style == .fullscreen, isRevealed, let background = pageBackground {
            return background.isDark ? .lightContent : .darkContent
        }
        return hostStatusBarController?.preferredStatusBarStyle ?? .default
    }

    override var prefersStatusBarHidden: Bool {
        hostStatusBarController?.prefersStatusBarHidden ?? false
    }

    override var supportedInterfaceOrientations: UIInterfaceOrientationMask {
        hostViewController?.supportedInterfaceOrientations ?? super.supportedInterfaceOrientations
    }
}

// MARK: - Bridge

extension SurveyViewController: WKScriptMessageHandler {
    func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage) {
        guard message.name == BridgeMessage.handlerName, !isClosing else { return }
        // Only the main frame of the survey origin may talk to the SDK.
        guard message.frameInfo.isMainFrame, let surveyOrigin = surveyOrigin else { return }
        let securityOrigin = message.frameInfo.securityOrigin
        let origin = WebOrigin(scheme: securityOrigin.protocol, host: securityOrigin.host, port: securityOrigin.port)
        guard origin == surveyOrigin else { return }
        guard let raw = message.body as? String, let parsed = BridgeMessage.parse(raw) else { return }

        switch parsed {
        case .ready(_, _, let backgroundColor, _):
            applyPageBackground(backgroundColor)
        case .resize(let height):
            growContent(to: height)
        default:
            break
        }
        onEvent?(.message(parsed, raw: raw))
    }
}

/// `WKUserContentController` retains its message handlers; this keeps the view controller out of
/// that retain cycle.
private final class ScriptMessageProxy: NSObject, WKScriptMessageHandler {
    weak var target: WKScriptMessageHandler?

    func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage) {
        target?.userContentController(userContentController, didReceive: message)
    }
}

// MARK: - Navigation

extension SurveyViewController: WKNavigationDelegate {
    func webView(
        _ webView: WKWebView,
        decidePolicyFor navigationAction: WKNavigationAction,
        decisionHandler: @escaping @MainActor (WKNavigationActionPolicy) -> Void
    ) {
        let url = navigationAction.request.url
        let decision = NavigationPolicy.decide(
            url: url,
            surveyURL: request.surveyURL,
            isMainFrame: navigationAction.targetFrame?.isMainFrame ?? false,
            opensNewWindow: navigationAction.targetFrame == nil
        )
        switch decision {
        case .allow:
            decisionHandler(.allow)
        case .cancel:
            decisionHandler(.cancel)
        case .openExternally:
            decisionHandler(.cancel)
            if let url = url {
                leaveSurvey(for: url)
            }
        }
    }

    func webView(
        _ webView: WKWebView,
        decidePolicyFor navigationResponse: WKNavigationResponse,
        decisionHandler: @escaping @MainActor (WKNavigationResponsePolicy) -> Void
    ) {
        // An error page is not a survey: fail right away instead of waiting for `ready`.
        if !isRevealed, navigationResponse.isForMainFrame,
           let response = navigationResponse.response as? HTTPURLResponse, response.statusCode >= 400 {
            decisionHandler(.cancel)
            reportLoadFailure("HTTP \(response.statusCode)")
            return
        }
        decisionHandler(.allow)
    }

    func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
        if !isRevealed {
            reportLoadFailure(error.localizedDescription)
        }
    }

    func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
        if !isRevealed {
            reportLoadFailure(error.localizedDescription)
        }
    }

    func webViewWebContentProcessDidTerminate(_ webView: WKWebView) {
        reportLoadFailure("the web content process terminated")
    }
}

extension SurveyViewController: WKUIDelegate {
    /// Links that ask for a new window open in the system browser.
    func webView(
        _ webView: WKWebView,
        createWebViewWith configuration: WKWebViewConfiguration,
        for navigationAction: WKNavigationAction,
        windowFeatures: WKWindowFeatures
    ) -> WKWebView? {
        let url = navigationAction.request.url
        let decision = NavigationPolicy.decide(
            url: url, surveyURL: request.surveyURL, isMainFrame: false, opensNewWindow: true
        )
        if decision == .openExternally, let url = url {
            leaveSurvey(for: url)
        }
        return nil
    }
}
#endif
