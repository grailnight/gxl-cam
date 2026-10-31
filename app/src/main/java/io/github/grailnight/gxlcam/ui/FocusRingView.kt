package io.github.grailnight.gxlcam.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/** Draws a short-lived focus ring and a capture flash over the preview. */
class FocusRingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        color = Color.WHITE
    }
    private val flashPaint = Paint().apply { color = Color.BLACK }
    private var ringX = 0f
    private var ringY = 0f
    private var ringProgress = -1f
    private var flashAlpha = 0
    private var ringAnimator: ValueAnimator? = null

    fun showFocus(x: Float, y: Float) {
        ringX = x
        ringY = y
        ringAnimator?.cancel()
        ringAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1200
            addUpdateListener {
                ringProgress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    fun flash() {
        ValueAnimator.ofInt(200, 0).apply {
            duration = 220
            addUpdateListener {
                flashAlpha = it.animatedValue as Int
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (flashAlpha > 0) {
            flashPaint.alpha = flashAlpha
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), flashPaint)
        }
        if (ringProgress in 0f..1f) {
            val shrink = (1f - minOf(ringProgress * 4f, 1f))
            val radius = (36f + 20f * shrink) * density
            ringPaint.alpha = if (ringProgress < 0.75f) 255 else ((1f - ringProgress) * 4f * 255).toInt()
            canvas.drawCircle(ringX, ringY, radius, ringPaint)
            if (ringProgress >= 1f) ringProgress = -1f
        }
    }
}
