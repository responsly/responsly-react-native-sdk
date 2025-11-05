package com.responsly.sdk.internal.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import com.responsly.sdk.PresentationStyle
import com.responsly.sdk.internal.config.PresentationSize

/**
 * The full-window content of the survey dialog: a dimmed backdrop and the container ("card") that
 * holds the WebView. Positions come from [SheetGeometry]; nothing here depends on the host theme.
 */
@SuppressLint("ViewConstructor") // Created in code only, never inflated.
internal class SurveyLayout(
    context: Context,
    private val style: PresentationStyle,
    private val size: PresentationSize,
) : ViewGroup(context) {
    val backdrop = View(context)
    val card = Card(context)

    /** Holds the WebView and the close button, inside the card's safe area. */
    val content: FrameLayout get() = card.content

    private var insets = EdgeInsets.NONE
    private var keyboard = 0
    private var cardColor = Color.WHITE

    /** The largest content height the page asked for; see [SheetGeometry.compute]. */
    var requestedContentHeight = 0
        set(value) {
            if (field != value) {
                field = value
                requestLayout()
            }
        }

    var frame: ContainerFrame? = null
        private set

    init {
        // A full-screen survey needs no dimming: the container covers the window.
        if (style != PresentationStyle.FULLSCREEN) backdrop.setBackgroundColor(BACKDROP_COLOR)
        addView(backdrop)
        addView(card)
    }

    fun setInsets(insets: EdgeInsets, keyboard: Int) {
        if (this.insets == insets && this.keyboard == keyboard) return
        this.insets = insets
        this.keyboard = keyboard
        requestLayout()
    }

    fun setCardColor(color: Int) {
        cardColor = color
        card.invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(width, height)
        backdrop.measure(exactly(width), exactly(height))
        val frame = SheetGeometry.compute(
            style,
            size,
            width,
            height,
            resources.displayMetrics.density,
            insets,
            keyboard,
            requestedContentHeight,
        )
        this.frame = frame
        card.frame = frame
        card.measure(exactly(frame.width), exactly(frame.height))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        backdrop.layout(0, 0, right - left, bottom - top)
        val frame = frame ?: return
        card.layout(frame.left, frame.top, frame.left + frame.width, frame.top + frame.height)
    }

    override fun shouldDelayChildPressedState(): Boolean = false

    private fun exactly(size: Int): Int = MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY)

    /** The container: paints its colour, clips its content to the rounded corners, swallows touches. */
    @SuppressLint("ViewConstructor")
    inner class Card(context: Context) : ViewGroup(context) {
        val content = FrameLayout(context)
        var frame: ContainerFrame? = null
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        init {
            setWillNotDraw(false)
            isClickable = true
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    val frame = frame
                    val top = frame?.topRadius ?: 0
                    val bottom = frame?.bottomRadius ?: 0
                    when {
                        top == 0 && bottom == 0 -> outline.setRect(0, 0, view.width, view.height)
                        // Only the top corners are rounded: extend the round rect below the view.
                        bottom == 0 -> outline.setRoundRect(0, 0, view.width, view.height + top, top.toFloat())
                        else -> outline.setRoundRect(0, 0, view.width, view.height, top.toFloat())
                    }
                }
            }
            addView(content)
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val width = MeasureSpec.getSize(widthMeasureSpec)
            val height = MeasureSpec.getSize(heightMeasureSpec)
            setMeasuredDimension(width, height)
            val padding = frame?.padding ?: EdgeInsets.NONE
            content.measure(
                exactly(maxOf(0, width - padding.left - padding.right)),
                exactly(maxOf(0, height - padding.top - padding.bottom)),
            )
        }

        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            val padding = frame?.padding ?: EdgeInsets.NONE
            content.layout(
                padding.left,
                padding.top,
                padding.left + content.measuredWidth,
                padding.top + content.measuredHeight,
            )
            invalidateOutline()
        }

        override fun onDraw(canvas: Canvas) {
            paint.color = cardColor
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        }
    }

    private companion object {
        const val BACKDROP_COLOR = 0x80000000.toInt()
    }
}

/** The close icon: two strokes of a 24dp cross, centred in the bounds. */
internal class CloseIconDrawable(private val density: Float) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 2f * density
        color = Color.BLACK
    }

    var color: Int
        get() = paint.color
        set(value) {
            paint.color = value
            invalidateSelf()
        }

    override fun draw(canvas: Canvas) {
        val centerX = bounds.exactCenterX()
        val centerY = bounds.exactCenterY()
        val arm = ARM_DP * density
        canvas.drawLine(centerX - arm, centerY - arm, centerX + arm, centerY + arm, paint)
        canvas.drawLine(centerX - arm, centerY + arm, centerX + arm, centerY - arm, paint)
    }

    override fun getIntrinsicWidth(): Int = Math.round(ICON_DP * density)

    override fun getIntrinsicHeight(): Int = Math.round(ICON_DP * density)

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    private companion object {
        const val ICON_DP = 24f

        // Half the diagonal extent of the cross inside the 24dp icon box.
        const val ARM_DP = 6.5f
    }
}

/** A fully transparent window background. */
internal fun transparentDrawable(): Drawable = ColorDrawable(Color.TRANSPARENT)
