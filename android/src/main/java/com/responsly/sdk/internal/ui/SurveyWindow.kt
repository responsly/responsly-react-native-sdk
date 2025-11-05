package com.responsly.sdk.internal.ui

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import androidx.annotation.RequiresApi
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.responsly.sdk.PresentationStyle
import com.responsly.sdk.internal.Logger
import com.responsly.sdk.internal.bridge.BridgeMessage
import com.responsly.sdk.internal.bridge.CssColor
import com.responsly.sdk.internal.bridge.SurveyUrl
import com.responsly.sdk.internal.core.PresentationRequest
import com.responsly.sdk.internal.core.PresenterEvents
import com.responsly.sdk.internal.numberOrNull
import com.responsly.sdk.internal.stringOrNull
import java.util.Locale

/**
 * One attempt to show a survey: a transparent, full-window dialog on [activity] with the container
 * and its WebView. The page loads while the container is invisible and the window lets touches
 * through; [reveal] makes it visible. Main thread only.
 */
internal class SurveyWindow(
    private val activity: Activity,
    val request: PresentationRequest,
    private val events: PresenterEvents,
    private val logger: Logger,
    private val webData: WebsiteData,
    private val isHostInFront: () -> Boolean,
    private val onGone: (SurveyWindow) -> Unit,
) {
    val token: Long get() = request.token

    private val handler = Handler(Looper.getMainLooper())
    private val uploads = UploadGrants(activity.applicationContext)
    private val popups = ArrayList<WebView>()

    private var dialog: Dialog? = null
    private var layout: SurveyLayout? = null
    private var webView: WebView? = null
    private var closeButton: ImageButton? = null
    private var closeIcon: CloseIconDrawable? = null
    private var heightAnimator: ValueAnimator? = null

    private var revealed = false
    private var closing = false
    private var destroyed = false
    private var dismissible = request.dismissible

    /** `ready` arrived; later copies are ignored. */
    private var readySeen = false

    /** `ready` arrived while the host activity was not in front; delivered when it is. */
    private var heldReady: BridgeMessage? = null
    private var backgroundColor = Color.WHITE
    private var title: String? = null

    /** The largest height the page asked for, in pixels. The container never shrinks. */
    private var requestedContent = 0

    /** Creates the hidden container and starts loading. Throws when no WebView is available. */
    fun start() {
        val dialog = SurveyDialog(activity, ::onUserClose)
        this.dialog = dialog
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setCanceledOnTouchOutside(false)
        val window = checkNotNull(dialog.window) { "The dialog has no window" }
        configureWindow(window)

        val context = dialog.context
        val layout = SurveyLayout(context, request.style, request.size)
        this.layout = layout
        val web = createWebView(context)
        layout.content.addView(
            web,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        val button = createCloseButton(context)
        val touchTarget = dp(CLOSE_TOUCH_TARGET_DP)
        layout.content.addView(button, FrameLayout.LayoutParams(touchTarget, touchTarget, Gravity.TOP or Gravity.END))
        layout.backdrop.setOnClickListener { onUserClose() }
        // Screen readers use the close button; the backdrop would be an unnamed duplicate.
        layout.backdrop.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        layout.backdrop.alpha = 0f
        layout.card.alpha = 0f
        // Nothing of the loading page may be reachable by a screen reader.
        layout.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        applyColors()
        updateCloseButton()
        // The layout deals with system bars and the keyboard itself, so the insets stop here and
        // the WebView below never applies them a second time.
        layout.setOnApplyWindowInsetsListener { _, insets -> applyInsets(insets) }

        dialog.setContentView(
            layout,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        dialog.setOnDismissListener { onDialogDismissed() }
        dialog.show()
        web.loadUrl(request.url)
    }

    /** Makes the loaded survey visible: colours, input, animation. */
    fun reveal() {
        if (destroyed || revealed) return
        val layout = layout ?: return
        revealed = true
        layout.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        applyColors()
        updateCloseButton()
        dialog?.window?.let { window ->
            window.clearFlags(
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            )
            title?.takeIf { it.isNotBlank() }?.let { window.setTitle(it) }
            if (request.style == PresentationStyle.FULLSCREEN) matchSystemBarsTo(window, backgroundColor)
        }

        val card = layout.card
        if (!motionEnabled()) {
            layout.backdrop.alpha = 1f
            card.alpha = 1f
        } else {
            layout.backdrop.animate().alpha(1f).setDuration(FADE_MILLIS).start()
            if (request.style == PresentationStyle.BOTTOM_SHEET && card.height > 0) {
                card.alpha = 1f
                card.translationY = card.height.toFloat()
                card.animate()
                    .translationY(0f)
                    .setDuration(SLIDE_IN_MILLIS)
                    .setInterpolator(DecelerateInterpolator(1.5f))
                    .start()
            } else {
                card.animate().alpha(1f).setDuration(FADE_MILLIS).start()
            }
        }
        webView?.requestFocus()
    }

    /** Shows or hides the close button and enables or disables backdrop tap and system back. */
    fun setDismissible(value: Boolean) {
        if (destroyed || dismissible == value) return
        dismissible = value
        updateCloseButton()
    }

    /** Closes the container; with [animated] a visible survey slides or fades out first. */
    fun dismiss(animated: Boolean) {
        if (destroyed) return
        val layout = layout
        if (!animated || !revealed || layout == null || !motionEnabled()) {
            destroy()
            return
        }
        if (closing) return
        closing = true
        dialog?.window?.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        layout.backdrop.animate().alpha(0f).setDuration(FADE_MILLIS).start()
        val card = layout.card
        if (request.style == PresentationStyle.BOTTOM_SHEET) {
            card.animate()
                .translationY(card.height.toFloat())
                .setDuration(SLIDE_OUT_MILLIS)
                .setInterpolator(AccelerateInterpolator())
                .start()
        } else {
            card.animate().alpha(0f).setDuration(FADE_MILLIS).start()
        }
        // Not an animation end action: those do not run when the window is detached first.
        handler.postDelayed({ destroy() }, maxOf(SLIDE_OUT_MILLIS, FADE_MILLIS))
    }

    /** Removes the dialog and the WebView immediately. Safe to call more than once. */
    fun destroy() {
        if (destroyed) return
        destroyed = true
        handler.removeCallbacksAndMessages(null)
        heightAnimator?.cancel()
        layout?.let {
            it.backdrop.animate().cancel()
            it.card.animate().cancel()
        }
        FileChooserBroker.cancel(this)
        uploads.releaseAll()
        dialog?.let { dialog ->
            dialog.setOnDismissListener(null)
            try {
                if (dialog.isShowing) dialog.dismiss()
            } catch (e: Exception) {
                // The window is already gone with its activity.
            }
        }
        popups.forEach(::destroyWebView)
        popups.clear()
        webView?.let(::destroyWebView)
        webView = null
        onGone(this)
    }

    /** An activity of the app came to the front. */
    fun onActivityResumed(resumed: Activity) {
        if (destroyed || revealed) return
        if (resumed === activity) {
            val ready = heldReady ?: return
            heldReady = null
            events.onBridgeMessage(token, ready)
        } else {
            // Another screen is in front now; the survey has to be loaded on that one.
            events.onHostLost(token)
            destroy()
        }
    }

    fun onActivityDestroyed(gone: Activity) {
        if (destroyed || gone !== activity) return
        if (revealed) events.onHostDestroyed(token) else events.onHostLost(token)
        destroy()
    }

    // region Window

    private fun configureWindow(window: Window) {
        window.setBackgroundDrawable(transparentDrawable())
        window.setFormat(PixelFormat.TRANSLUCENT)
        window.setWindowAnimations(0)
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        // Until the survey is revealed the window must not take focus or touches from the app.
        window.addFlags(
            WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
        )
        @Suppress("DEPRECATION")
        run {
            window.statusBarColor = Color.TRANSPARENT
            window.navigationBarColor = Color.TRANSPARENT
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        if (Build.VERSION.SDK_INT >= 29) {
            @Suppress("DEPRECATION")
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
        if (Build.VERSION.SDK_INT >= 28) {
            val attributes = window.attributes
            attributes.layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= 30) {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            window.attributes = attributes
        }
        // The container is laid out edge to edge; SurveyLayout applies the insets itself.
        if (Build.VERSION.SDK_INT >= 30) {
            @Suppress("DEPRECATION")
            window.setDecorFitsSystemWindows(false)
            mirrorSystemBars(window)
        } else {
            mirrorSystemBarsLegacy(window)
        }
    }

    /** Keeps the host's system bar look (light icons, hidden bars) while the dialog is on top. */
    @RequiresApi(30)
    private fun mirrorSystemBars(window: Window) {
        // A window has an insets controller only once its decor view exists. Ours is created
        // here; the host's is only looked at, never created, or the host could no longer
        // request window features.
        val hostDecor = activity.window?.peekDecorView() ?: return
        val hostController = activity.window?.insetsController ?: return
        window.decorView
        val controller = window.insetsController ?: return
        val mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
            WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        controller.setSystemBarsAppearance(hostController.systemBarsAppearance and mask, mask)
        val insets = hostDecor.rootWindowInsets ?: return
        var hidden = 0
        if (!insets.isVisible(WindowInsets.Type.statusBars())) hidden = hidden or WindowInsets.Type.statusBars()
        if (!insets.isVisible(WindowInsets.Type.navigationBars())) {
            hidden = hidden or WindowInsets.Type.navigationBars()
        }
        if (hidden != 0) {
            controller.systemBarsBehavior = hostController.systemBarsBehavior
            controller.hide(hidden)
        }
    }

    @Suppress("DEPRECATION")
    private fun mirrorSystemBarsLegacy(window: Window) {
        var inherited = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        if (Build.VERSION.SDK_INT >= 26) inherited = inherited or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        // peekDecorView: never create the host's decor view on its behalf.
        val hostFlags = activity.window?.peekDecorView()?.systemUiVisibility ?: 0
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            (hostFlags and inherited)
    }

    /** A full-screen survey paints behind the system bars, so their icons follow its colour. */
    private fun matchSystemBarsTo(window: Window, color: Int) {
        val light = CssColor.isLight(color)
        if (Build.VERSION.SDK_INT >= 30) {
            val mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(if (light) mask else 0, mask)
        } else {
            @Suppress("DEPRECATION")
            run {
                var mask = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                if (Build.VERSION.SDK_INT >= 26) mask = mask or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                val flags = window.decorView.systemUiVisibility
                window.decorView.systemUiVisibility = if (light) flags or mask else flags and mask.inv()
            }
        }
    }

    /** Hands the insets to the layout and returns them consumed. */
    private fun applyInsets(insets: WindowInsets): WindowInsets {
        val layout = layout ?: return insets
        if (Build.VERSION.SDK_INT >= 30) {
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val keyboard = insets.getInsets(WindowInsets.Type.ime())
            layout.setInsets(EdgeInsets(bars.left, bars.top, bars.right, bars.bottom), keyboard.bottom)
            return WindowInsets.CONSUMED
        }
        // With adjustResize the keyboard is reported as part of the bottom system inset.
        @Suppress("DEPRECATION")
        run {
            val bottom = insets.systemWindowInsetBottom
            val navigation = minOf(bottom, insets.stableInsetBottom)
            layout.setInsets(
                EdgeInsets(
                    insets.systemWindowInsetLeft,
                    insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight,
                    navigation,
                ),
                if (bottom > navigation) bottom else 0,
            )
            return insets.consumeSystemWindowInsets()
        }
    }

    private fun onDialogDismissed() {
        if (destroyed) return
        // Dismissed by the system, not by us: the activity is going away.
        if (revealed) events.onHostDestroyed(token) else events.onHostLost(token)
        destroy()
    }

    // endregion

    // region Container

    private fun createCloseButton(context: Context): ImageButton {
        val icon = CloseIconDrawable(context.resources.displayMetrics.density)
        closeIcon = icon
        val button = ImageButton(context)
        button.setImageDrawable(icon)
        button.scaleType = ImageView.ScaleType.CENTER
        button.setPadding(0, 0, 0, 0)
        button.background = RippleDrawable(ColorStateList.valueOf(RIPPLE_COLOR), null, null)
        button.contentDescription = closeLabel(context)
        button.setOnClickListener { onUserClose() }
        closeButton = button
        return button
    }

    /** Apps can translate the label with a string resource named `responsly_close`. */
    @SuppressLint("DiscouragedApi")
    private fun closeLabel(context: Context): String {
        val id = context.resources.getIdentifier("responsly_close", "string", context.packageName)
        return if (id != 0) context.getString(id) else "Close"
    }

    private fun updateCloseButton() {
        closeButton?.visibility = if (dismissible) View.VISIBLE else View.GONE
    }

    private fun applyColors() {
        layout?.setCardColor(backgroundColor)
        webView?.setBackgroundColor(backgroundColor)
        closeIcon?.color = if (CssColor.isLight(backgroundColor)) ICON_ON_LIGHT else ICON_ON_DARK
    }

    /** `resize`: the container grows to what the page needs, within its limits, and never shrinks. */
    private fun onResize(cssHeight: Double) {
        val layout = layout ?: return
        if (cssHeight <= 0.0 || cssHeight > MAX_CSS_HEIGHT) return
        val target = Math.round(cssHeight * activity.resources.displayMetrics.density).toInt()
        if (target <= requestedContent) return
        requestedContent = target
        val current = maxOf(layout.requestedContentHeight, layout.frame?.contentHeight ?: 0)
        if (!revealed || !motionEnabled() || target <= current) {
            layout.requestedContentHeight = target
            return
        }
        heightAnimator?.cancel()
        heightAnimator = ValueAnimator.ofInt(current, target).apply {
            duration = RESIZE_MILLIS
            interpolator = DecelerateInterpolator()
            addUpdateListener { layout.requestedContentHeight = it.animatedValue as Int }
            start()
        }
    }

    private fun onUserClose() {
        if (destroyed || closing || !revealed || !dismissible) return
        events.onUserClosed(token)
    }

    /** Reduced motion: the system's animator duration scale is 0. */
    private fun motionEnabled(): Boolean = try {
        Settings.Global.getFloat(activity.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
    } catch (e: Exception) {
        true
    }

    private fun dp(value: Int): Int = Math.round(value * activity.resources.displayMetrics.density)

    // endregion

    // region WebView

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(context: Context): WebView {
        val web = WebView(context)
        webView = web
        // Surveys keep their cookies and storage apart from the app's own web content.
        val cookies = webData.useSurveyProfile(web) ?: CookieManager.getInstance()
        cookies.setAcceptCookie(true)

        val settings = web.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.setSupportMultipleWindows(true)
        settings.allowFileAccess = false
        settings.userAgentString = settings.userAgentString + request.userAgentSuffix
        web.setBackgroundColor(backgroundColor)
        web.isFocusable = true
        web.isFocusableInTouchMode = true

        // Both features were checked before this window was created (see isBridgeSupported);
        // without either of them there is no survey to show.
        val origins = setOf(request.origin)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(web, request.contextScript, origins)
        } else {
            throw IllegalStateException("The WebView does not support document-start scripts")
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(
                web,
                BRIDGE_NAME,
                origins,
                WebViewCompat.WebMessageListener { _, message, sourceOrigin, isMainFrame, _ ->
                    onPostMessage(message, sourceOrigin, isMainFrame)
                },
            )
        } else {
            throw IllegalStateException("The WebView does not support web message listeners")
        }
        web.webViewClient = Client()
        web.webChromeClient = Chrome()
        return web
    }

    private fun onPostMessage(message: WebMessageCompat, sourceOrigin: Uri, isMainFrame: Boolean) {
        if (destroyed || !isMainFrame) return
        if (SurveyUrl.origin(sourceOrigin.toString()) != request.origin) return
        if (message.type != WebMessageCompat.TYPE_STRING) return
        val parsed = BridgeMessage.parse(message.data)
        if (parsed == null) {
            logger.debug { "bridge: ignored a message that is not a Responsly message" }
            return
        }
        when (parsed.type) {
            BridgeMessage.READY -> {
                if (readySeen) return
                readySeen = true
                // The container is opaque whatever alpha the page reports.
                CssColor.parse(parsed.payload.stringOrNull("background_color"))?.let {
                    backgroundColor = it or OPAQUE
                }
                title = parsed.payload.stringOrNull("title")
                if (!isHostInFront()) {
                    logger.debug { "bridge: ready while the app is not in front; waiting for it" }
                    heldReady = parsed
                    return
                }
            }
            BridgeMessage.RESIZE -> parsed.payload.numberOrNull("height")?.let(::onResize)
        }
        events.onBridgeMessage(token, parsed)
    }

    /** Section 5.4. Returns true when the navigation was cancelled. */
    private fun onMainFrameNavigation(url: String): Boolean {
        if (SurveyUrl.isSurveyPage(request.surveyUrl, url)) return false
        if (!revealed) {
            // Nobody tapped a link in a page nobody sees: this is a redirect, not a survey.
            fail("navigation", "The page left the survey address before it was ready")
        } else {
            openExternally(url)
            events.onNavigatedAway(token)
        }
        return true
    }

    private fun openExternally(url: String) {
        val uri = Uri.parse(url)
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        if (scheme !in EXTERNAL_SCHEMES) {
            logger.debug { "navigation: blocked a \"$scheme\" link" }
            return
        }
        logger.debug { "navigation: opening a \"$scheme\" link outside the survey" }
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))
        } catch (e: Exception) {
            logger.info { "navigation: no app can open the link" }
        }
    }

    private fun fail(code: String, detail: String) {
        if (!destroyed) events.onFailure(token, code, detail)
    }

    private fun destroyWebView(web: WebView) {
        try {
            (web.parent as? ViewGroup)?.removeView(web)
            web.stopLoading()
            web.webChromeClient = null
            web.webViewClient = WebViewClient()
            web.destroy()
        } catch (e: Exception) {
            logger.debug { "Could not destroy a WebView: $e" }
        }
    }

    private inner class Client : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            if (!request.isForMainFrame) return false
            return onMainFrameNavigation(request.url.toString())
        }

        // API 23 only knows this variant.
        @Deprecated("Deprecated in Java")
        override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean = onMainFrameNavigation(url)

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame) fail("load_error", "${error.errorCode} ${error.description}")
        }

        override fun onReceivedHttpError(
            view: WebView,
            request: WebResourceRequest,
            errorResponse: WebResourceResponse,
        ) {
            val status = errorResponse.statusCode
            if (!request.isForMainFrame || status < 400) return
            if (readySeen) {
                fail("http_error", "HTTP $status")
            } else {
                // An error page may still tell us why with an `error` message; give it a moment.
                handler.postDelayed({ if (!readySeen) fail("http_error", "HTTP $status") }, HTTP_ERROR_GRACE_MILLIS)
            }
        }

        @SuppressLint("WebViewClientOnReceivedSslError")
        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
            handler.cancel()
            if (SurveyUrl.isSurveyPage(request.surveyUrl, error.url.orEmpty())) {
                fail("ssl_error", "Certificate error ${error.primaryError}")
            }
        }

        @RequiresApi(26)
        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            if (view === webView) {
                val reason = if (detail.didCrash()) "crashed" else "was killed by the system"
                fail("webview_process_gone", "The WebView process $reason")
            }
            // Handled: the WebView is destroyed with the container instead of crashing the app.
            return true
        }
    }

    private inner class Chrome : WebChromeClient() {
        /** A link that asks for a new window leaves the survey like any other navigation. */
        override fun onCreateWindow(
            view: WebView,
            isDialog: Boolean,
            isUserGesture: Boolean,
            resultMsg: Message,
        ): Boolean {
            val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
            if (destroyed || !revealed || !isUserGesture) return false
            val popup = WebView(view.context)
            popups.add(popup)
            popup.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(popupView: WebView, request: WebResourceRequest): Boolean {
                    redirect(request.url.toString())
                    return true
                }

                @Deprecated("Deprecated in Java")
                override fun shouldOverrideUrlLoading(popupView: WebView, url: String): Boolean {
                    redirect(url)
                    return true
                }

                private fun redirect(url: String) {
                    if (destroyed) return
                    openExternally(url)
                    events.onNavigatedAway(token)
                    handler.post {
                        if (popups.remove(popup)) destroyWebView(popup)
                    }
                }
            }
            transport.webView = popup
            resultMsg.sendToTarget()
            return true
        }

        override fun onShowFileChooser(
            view: WebView,
            filePathCallback: ValueCallback<Array<Uri>>,
            fileChooserParams: FileChooserParams,
        ): Boolean {
            if (destroyed || !revealed) return false
            return FileChooserBroker.open(activity, this@SurveyWindow, fileChooserParams, filePathCallback, uploads, logger)
        }
    }

    // endregion

    /** The dialog itself: no title, no theme of the host, back handled by the survey. */
    private class SurveyDialog(context: Context, private val onBack: () -> Unit) :
        Dialog(context, android.R.style.Theme_DeviceDefault_Light_NoActionBar) {
        @Deprecated("Deprecated in Java")
        override fun onBackPressed() {
            // Deliberately not calling super: the survey decides whether back closes it.
            onBack()
        }
    }

    companion object {
        /** `window.ResponslyAndroid.postMessage(string)`. */
        const val BRIDGE_NAME = "ResponslyAndroid"

        private const val CLOSE_TOUCH_TARGET_DP = 44
        private const val FADE_MILLIS = 200L
        private const val SLIDE_IN_MILLIS = 280L
        private const val SLIDE_OUT_MILLIS = 200L
        private const val RESIZE_MILLIS = 200L
        private const val HTTP_ERROR_GRACE_MILLIS = 3_000L
        private const val MAX_CSS_HEIGHT = 100_000.0
        private const val OPAQUE = 0xFF000000.toInt()
        private const val ICON_ON_LIGHT = 0xFF3C4043.toInt()
        private const val ICON_ON_DARK = 0xFFFFFFFF.toInt()
        private const val RIPPLE_COLOR = 0x33808080
        private val EXTERNAL_SCHEMES = setOf("http", "https", "mailto", "tel", "sms")

        /** Whether this device's WebView can run the bridge at all. */
        fun isBridgeSupported(): Boolean =
            WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) &&
                WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
    }
}
