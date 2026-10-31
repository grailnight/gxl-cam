package io.github.grailnight.gxlcam.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.ImageView

/** ImageView with pinch-zoom, panning and double-tap zoom (fit ↔ 100 %). */
class ZoomImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ImageView(context, attrs) {

    private val matrixValues = FloatArray(9)
    private val current = Matrix()
    private var fitScale = 1f

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val scale = currentScale()
            val target = (scale * detector.scaleFactor).coerceIn(fitScale, maxOf(fitScale * 8f, 4f))
            val factor = target / scale
            current.postScale(factor, factor, detector.focusX, detector.focusY)
            constrain()
            return true
        }
    })

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            current.postTranslate(-dx, -dy)
            constrain()
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean = performClick()

        override fun onDoubleTap(e: MotionEvent): Boolean {
            val scale = currentScale()
            val target = if (scale > fitScale * 1.05f) fitScale else maxOf(1f, fitScale * 2.5f)
            val factor = target / scale
            current.postScale(factor, factor, e.x, e.y)
            constrain()
            return true
        }
    })

    init {
        scaleType = ScaleType.MATRIX
    }

    override fun setImageDrawable(drawable: Drawable?) {
        super.setImageDrawable(drawable)
        resetZoom()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        resetZoom()
    }

    private fun resetZoom() {
        val d = drawable ?: return
        if (width == 0 || height == 0 || d.intrinsicWidth <= 0) return
        fitScale = minOf(width.toFloat() / d.intrinsicWidth, height.toFloat() / d.intrinsicHeight)
        current.reset()
        current.postScale(fitScale, fitScale)
        current.postTranslate(
            (width - d.intrinsicWidth * fitScale) / 2f,
            (height - d.intrinsicHeight * fitScale) / 2f,
        )
        imageMatrix = current
    }

    private fun currentScale(): Float {
        current.getValues(matrixValues)
        return matrixValues[Matrix.MSCALE_X]
    }

    /** Keeps the image covering the view (or centered when smaller). */
    private fun constrain() {
        val d = drawable ?: return
        val rect = RectF(0f, 0f, d.intrinsicWidth.toFloat(), d.intrinsicHeight.toFloat())
        current.mapRect(rect)
        var dx = 0f
        var dy = 0f
        if (rect.width() <= width) dx = (width - rect.width()) / 2f - rect.left
        else if (rect.left > 0) dx = -rect.left
        else if (rect.right < width) dx = width - rect.right
        if (rect.height() <= height) dy = (height - rect.height()) / 2f - rect.top
        else if (rect.top > 0) dy = -rect.top
        else if (rect.bottom < height) dy = height - rect.bottom
        current.postTranslate(dx, dy)
        imageMatrix = current
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        if (!scaleDetector.isInProgress) gestureDetector.onTouchEvent(event)
        return true
    }
}
