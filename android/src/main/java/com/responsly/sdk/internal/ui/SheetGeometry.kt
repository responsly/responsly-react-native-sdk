package com.responsly.sdk.internal.ui

import com.responsly.sdk.PresentationStyle
import com.responsly.sdk.internal.config.PresentationSize

/** Insets of the window edges, in pixels. */
internal data class EdgeInsets(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    companion object {
        val NONE = EdgeInsets(0, 0, 0, 0)
    }
}

/**
 * Where the container goes. The padding is the part of the container that lies under system bars
 * or the keyboard; the WebView fills the rest.
 */
internal data class ContainerFrame(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
    val padding: EdgeInsets,
    val topRadius: Int,
    val bottomRadius: Int,
) {
    val contentHeight: Int get() = height - padding.top - padding.bottom
}

/** The layout rules of section 6, as a pure function of the screen. All lengths are pixels. */
internal object SheetGeometry {
    const val SHEET_MAX_WIDTH_DP = 640
    const val DIALOG_MAX_WIDTH_DP = 480
    const val DIALOG_MARGIN_DP = 16
    const val MIN_HEIGHT_DP = 320
    const val CORNER_RADIUS_DP = 16
    const val SHEET_MAX_FRACTION = 0.88
    const val DIALOG_MAX_FRACTION = 0.80

    /**
     * @param insets system bars and display cutout.
     * @param keyboard height of the on-screen keyboard measured from the bottom edge, 0 when hidden.
     * @param requestedContent the largest height the page asked for with `resize`, 0 when none.
     */
    @Suppress("LongParameterList")
    fun compute(
        style: PresentationStyle,
        size: PresentationSize,
        screenWidth: Int,
        screenHeight: Int,
        density: Float,
        insets: EdgeInsets,
        keyboard: Int,
        requestedContent: Int,
    ): ContainerFrame = when (style) {
        PresentationStyle.BOTTOM_SHEET -> sheet(size, screenWidth, screenHeight, density, insets, keyboard, requestedContent)
        PresentationStyle.DIALOG -> dialog(size, screenWidth, screenHeight, density, insets, keyboard, requestedContent)
        PresentationStyle.FULLSCREEN -> ContainerFrame(
            left = 0,
            top = 0,
            width = screenWidth,
            height = screenHeight,
            padding = EdgeInsets(insets.left, insets.top, insets.right, maxOf(insets.bottom, keyboard)),
            topRadius = 0,
            bottomRadius = 0,
        )
    }

    @Suppress("LongParameterList")
    private fun sheet(
        size: PresentationSize,
        screenWidth: Int,
        screenHeight: Int,
        density: Float,
        insets: EdgeInsets,
        keyboard: Int,
        requestedContent: Int,
    ): ContainerFrame {
        val safeWidth = maxOf(0, screenWidth - insets.left - insets.right)
        val width = minOf(safeWidth, dp(SHEET_MAX_WIDTH_DP, density))
        val fraction = when (size) {
            PresentationSize.SMALL -> 0.45
            PresentationSize.MEDIUM -> 0.65
            PresentationSize.LARGE -> SHEET_MAX_FRACTION
        }
        val base = maxOf(fraction(fraction, screenHeight), dp(MIN_HEIGHT_DP, density))
        val limit = maxOf(fraction(SHEET_MAX_FRACTION, screenHeight), base)
        // The sheet height includes the strip under the navigation bar; the page asks for content height.
        var height = base
        if (requestedContent > 0) height = maxOf(height, minOf(requestedContent + insets.bottom, limit))
        height = minOf(height, screenHeight - insets.top)

        // Above the keyboard the WebView keeps its height when there is room and shrinks when there is not.
        val bottom = maxOf(insets.bottom, keyboard)
        val content = maxOf(0, minOf(height - insets.bottom, screenHeight - insets.top - bottom))
        val total = content + bottom
        return ContainerFrame(
            left = insets.left + (safeWidth - width) / 2,
            top = screenHeight - total,
            width = width,
            height = total,
            padding = EdgeInsets(0, 0, 0, bottom),
            topRadius = dp(CORNER_RADIUS_DP, density),
            bottomRadius = 0,
        )
    }

    @Suppress("LongParameterList")
    private fun dialog(
        size: PresentationSize,
        screenWidth: Int,
        screenHeight: Int,
        density: Float,
        insets: EdgeInsets,
        keyboard: Int,
        requestedContent: Int,
    ): ContainerFrame {
        val margin = dp(DIALOG_MARGIN_DP, density)
        val safeWidth = maxOf(0, screenWidth - insets.left - insets.right)
        val width = maxOf(0, minOf(safeWidth - 2 * margin, dp(DIALOG_MAX_WIDTH_DP, density)))
        val fraction = when (size) {
            PresentationSize.SMALL -> 0.40
            PresentationSize.MEDIUM -> 0.56
            PresentationSize.LARGE -> DIALOG_MAX_FRACTION
        }
        val base = maxOf(fraction(fraction, screenHeight), dp(MIN_HEIGHT_DP, density))
        val limit = maxOf(fraction(DIALOG_MAX_FRACTION, screenHeight), base)
        var height = base
        if (requestedContent > 0) height = maxOf(height, minOf(requestedContent, limit))

        // Centred in what is left between the status bar and the navigation bar or keyboard.
        val regionTop = insets.top + margin
        val regionBottom = screenHeight - maxOf(insets.bottom, keyboard) - margin
        val available = maxOf(0, regionBottom - regionTop)
        height = minOf(height, available)
        val radius = dp(CORNER_RADIUS_DP, density)
        return ContainerFrame(
            left = insets.left + (safeWidth - width) / 2,
            top = regionTop + (available - height) / 2,
            width = width,
            height = height,
            padding = EdgeInsets.NONE,
            topRadius = radius,
            bottomRadius = radius,
        )
    }

    private fun dp(value: Int, density: Float): Int = Math.round(value * density)

    private fun fraction(fraction: Double, total: Int): Int = Math.round(fraction * total).toInt()
}
