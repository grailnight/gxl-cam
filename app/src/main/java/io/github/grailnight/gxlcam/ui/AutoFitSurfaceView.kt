package io.github.grailnight.gxlcam.ui

import android.content.Context
import android.util.AttributeSet
import android.view.SurfaceView
import kotlin.math.roundToInt

/**
 * SurfaceView that keeps the camera aspect ratio and fits inside its parent
 * (letterboxed, so the preview shows exactly what will be captured).
 */
class AutoFitSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : SurfaceView(context, attrs, defStyle) {

    private var aspectRatio = 0f

    /** Sets the camera buffer size (sensor orientation, usually landscape). */
    fun setAspectRatio(width: Int, height: Int) {
        require(width > 0 && height > 0)
        aspectRatio = width.toFloat() / height.toFloat()
        holder.setFixedSize(width, height)
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        if (aspectRatio == 0f || width == 0 || height == 0) {
            setMeasuredDimension(width, height)
            return
        }
        // The view is portrait while camera buffers are landscape.
        val ratio = if (width > height) aspectRatio else 1f / aspectRatio
        if (width < height * ratio) {
            setMeasuredDimension(width, (width / ratio).roundToInt())
        } else {
            setMeasuredDimension((height * ratio).roundToInt(), height)
        }
    }
}
