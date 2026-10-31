package io.github.grailnight.gxlcam.codec

import android.graphics.Bitmap
import java.nio.ByteBuffer

/** JNI bindings to libjxl (see app/src/main/cpp/jni_bridge.cc). */
object JxlNative {
    init {
        System.loadLibrary("gxljni")
    }

    const val COLOR_SRGB = 0
    const val COLOR_DISPLAY_P3 = 1

    external fun libjxlVersion(): String

    /** Lossless JPEG → JPEG XL recompression; the JPEG can be rebuilt bit-exactly. */
    external fun transcodeJpeg(jpeg: ByteArray, effort: Int): ByteArray

    /**
     * Converts a YUV_420_888 frame to a native RGB buffer and returns its handle.
     * The handle must be freed with [releaseFrame].
     */
    external fun yuvToRgbFrame(
        y: ByteBuffer, yRowStride: Int, yPixelStride: Int,
        u: ByteBuffer, v: ByteBuffer, uvRowStride: Int, uvPixelStride: Int,
        width: Int, height: Int,
    ): Long

    /** Copies an ARGB_8888 bitmap into a native RGB frame (alpha dropped). */
    external fun bitmapToRgbFrame(bitmap: Bitmap): Long

    /** Box-downscales the frame into [bitmap] (ARGB_8888, size = frame / integer factor). */
    external fun frameThumbnail(frame: Long, bitmap: Bitmap)

    /**
     * Encodes a native RGB frame to JPEG XL.
     * @param distance butteraugli distance, 0 = mathematically lossless
     * @param orientation EXIF orientation 1..8
     * @param exif raw TIFF/EXIF block or null
     */
    external fun encodeFrame(
        frame: Long, distance: Float, effort: Int, orientation: Int, colorSpace: Int, exif: ByteArray?,
    ): ByteArray

    external fun releaseFrame(frame: Long)

    /** Returns [width, height, orientation, hasAlpha, isLossy, hasJpegReconstruction, bitsPerSample]. */
    external fun probe(data: ByteArray): IntArray

    /** Decodes into an ARGB_8888 bitmap; returns the color space of the decoded pixels. */
    external fun decodeInto(data: ByteArray, bitmap: Bitmap, colorSpace: Int): Int

    /** Rebuilds the original JPEG from a losslessly recompressed JPEG XL. */
    external fun reconstructJpeg(data: ByteArray): ByteArray

    /** libjxl's mapping of a JPEG-like quality (0..100) to butteraugli distance. */
    fun distanceForQuality(quality: Int): Float {
        // Same formula as JxlEncoderDistanceFromQuality() in libjxl.
        val q = quality.toFloat()
        return when {
            q >= 100f -> 0f
            q >= 30f -> 0.1f + (100f - q) * 0.09f
            else -> 53f / 3000f * q * q - 23f / 20f * q + 25f
        }
    }
}

/** A native RGB frame handle with RAII-style release. */
class RgbFrame(val handle: Long, val width: Int, val height: Int) : AutoCloseable {
    private var released = false

    override fun close() {
        if (!released) {
            released = true
            JxlNative.releaseFrame(handle)
        }
    }
}
