package io.github.grailnight.gxlcam

import android.content.Context
import androidx.core.content.edit

/** How a photo becomes a JPEG XL file. */
enum class CaptureMode {
    /**
     * Camera produces a JPEG which is recompressed losslessly into JPEG XL
     * (≈20 % smaller, the original JPEG can be restored bit-exactly).
     */
    JPEG_LOSSLESS,

    /**
     * Uncompressed YUV frame from the camera is encoded straight to JPEG XL,
     * with no intermediate JPEG — better quality per byte, optional lossless.
     */
    DIRECT,
}

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var mode: CaptureMode
        get() = runCatching { CaptureMode.valueOf(prefs.getString(KEY_MODE, null)!!) }
            .getOrDefault(CaptureMode.DIRECT)
        set(value) = prefs.edit { putString(KEY_MODE, value.name) }

    /**
     * Quality 50..100. In [CaptureMode.DIRECT] it is the JPEG XL quality
     * (100 = mathematically lossless, 90 = visually lossless, d=1.0).
     * In [CaptureMode.JPEG_LOSSLESS] it is the quality of the camera JPEG.
     */
    var quality: Int
        get() = prefs.getInt(KEY_QUALITY, 90).coerceIn(MIN_QUALITY, 100)
        set(value) = prefs.edit { putInt(KEY_QUALITY, value.coerceIn(MIN_QUALITY, 100)) }

    /** libjxl effort 1..9: higher = smaller files, slower encoding. */
    var effort: Int
        get() = prefs.getInt(KEY_EFFORT, 7).coerceIn(1, 9)
        set(value) = prefs.edit { putInt(KEY_EFFORT, value.coerceIn(1, 9)) }

    /** Capture in Display P3 wide gamut (DIRECT mode, if the camera supports it). */
    var wideGamut: Boolean
        get() = prefs.getBoolean(KEY_P3, true)
        set(value) = prefs.edit { putBoolean(KEY_P3, value) }

    var useFrontCamera: Boolean
        get() = prefs.getBoolean(KEY_FRONT, false)
        set(value) = prefs.edit { putBoolean(KEY_FRONT, value) }

    var lastPhotoUri: String?
        get() = prefs.getString(KEY_LAST, null)
        set(value) = prefs.edit { putString(KEY_LAST, value) }

    companion object {
        const val MIN_QUALITY = 50
        private const val KEY_MODE = "mode"
        private const val KEY_QUALITY = "quality"
        private const val KEY_EFFORT = "effort"
        private const val KEY_P3 = "wide_gamut"
        private const val KEY_FRONT = "front"
        private const val KEY_LAST = "last_uri"
    }
}
