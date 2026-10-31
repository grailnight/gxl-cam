package io.github.grailnight.gxlcam

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.hardware.camera2.CaptureResult
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import io.github.grailnight.gxlcam.camera.CapturedPhoto
import io.github.grailnight.gxlcam.camera.Payload
import io.github.grailnight.gxlcam.codec.JxlNative
import io.github.grailnight.gxlcam.codec.RgbFrame
import io.github.grailnight.gxlcam.storage.ExifWriter
import io.github.grailnight.gxlcam.storage.PhotoStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.ByteArrayInputStream
import java.util.concurrent.Executors

/**
 * Background JPEG XL encoding queue. Lives for the whole process so a photo
 * keeps encoding even if the activity is recreated. Encodes run one at a time
 * because libjxl already uses all CPU cores for a single image.
 */
object PhotoPipeline {
    private const val TAG = "PhotoPipeline"
    const val MAX_PENDING = 3

    data class Params(val mode: CaptureMode, val quality: Int, val effort: Int)

    sealed class Event {
        data class Saved(
            val uri: Uri,
            val name: String,
            val bytes: Int,
            val encodeMillis: Long,
            val thumbnail: Bitmap?,
            val details: String,
        ) : Event()

        data class Failed(val message: String) : Event()
    }

    private val scope = CoroutineScope(SupervisorJob())
    private val encoder = Executors.newSingleThreadExecutor { r ->
        Thread(r, "JxlEncoder").apply { priority = Thread.NORM_PRIORITY - 1 }
    }.asCoroutineDispatcher()

    private val _pending = MutableStateFlow(0)
    val pending: StateFlow<Int> = _pending

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 16)
    val events: SharedFlow<Event> = _events

    fun submit(context: Context, photo: CapturedPhoto, params: Params) {
        val app = context.applicationContext
        _pending.update { it + 1 }
        scope.launch(encoder) {
            try {
                _events.emit(process(app, photo, params))
            } catch (t: Throwable) {
                Log.e(TAG, "encoding failed", t)
                _events.emit(Event.Failed(t.message ?: t.javaClass.simpleName))
            } finally {
                (photo.payload as? Payload.Frame)?.frame?.close()
                _pending.update { it - 1 }
            }
        }
    }

    private fun process(context: Context, photo: CapturedPhoto, params: Params): Event.Saved {
        val start = SystemClock.elapsedRealtime()
        val jxl: ByteArray
        val thumbnail: Bitmap?
        val details: String
        when (val payload = photo.payload) {
            is Payload.Jpeg -> {
                val orientation = jpegOrientation(payload.bytes)
                thumbnail = runCatching { jpegThumbnail(payload.bytes, orientation) }.getOrNull()
                val transcoded = runCatching { JxlNative.transcodeJpeg(payload.bytes, params.effort) }
                if (transcoded.isSuccess) {
                    jxl = transcoded.getOrThrow()
                    val saved = 100.0 * (1.0 - jxl.size.toDouble() / payload.bytes.size)
                    details = "JPEG→JXL без потерь, −%.0f%%".format(saved)
                } else {
                    // Very unusual JPEG: fall back to decoding it and encoding the pixels.
                    Log.w(TAG, "lossless transcode failed, re-encoding", transcoded.exceptionOrNull())
                    jxl = reencodeJpeg(payload.bytes, orientation, params.effort)
                    details = "JPEG→JXL (перекодирован, d=1.0)"
                }
            }
            is Payload.Frame -> {
                val frame = payload.frame
                thumbnail = runCatching { frameThumbnail(frame, photo.rotationDegrees) }.getOrNull()
                val orientation = ExifWriter.orientationFromDegrees(photo.rotationDegrees)
                val distance = JxlNative.distanceForQuality(params.quality)
                val exif = ExifWriter.build(exifMetadata(photo, frame, orientation))
                jxl = JxlNative.encodeFrame(
                    frame.handle,
                    distance,
                    params.effort,
                    orientation,
                    if (photo.wideGamut) JxlNative.COLOR_DISPLAY_P3 else JxlNative.COLOR_SRGB,
                    exif,
                )
                details = buildString {
                    append(if (distance == 0f) "без потерь" else "q${params.quality} (d=%.2f)".format(distance))
                    if (photo.wideGamut) append(", Display P3")
                }
            }
        }
        val encodeMillis = SystemClock.elapsedRealtime() - start
        val name = PhotoStore.fileName(photo.time, "jxl")
        val uri = PhotoStore.save(
            context, jxl, name, PhotoStore.MIME_JXL, photo.time.toInstant().toEpochMilli(),
        )
        return Event.Saved(uri, name, jxl.size, encodeMillis, thumbnail, details)
    }

    private fun exifMetadata(photo: CapturedPhoto, frame: RgbFrame, orientation: Int): ExifWriter.Metadata {
        val r = photo.result
        val boost = r.get(CaptureResult.CONTROL_POST_RAW_SENSITIVITY_BOOST) ?: 100
        val iso = r.get(CaptureResult.SENSOR_SENSITIVITY)?.let { it * boost / 100 }
        return ExifWriter.Metadata(
            make = Build.MANUFACTURER.replaceFirstChar { it.uppercase() },
            model = Build.MODEL,
            software = "GXL Cam ${BuildConfig.VERSION_NAME} (libjxl ${JxlNative.libjxlVersion()})",
            orientation = orientation,
            time = photo.time,
            width = frame.width,
            height = frame.height,
            srgb = !photo.wideGamut,
            exposureTimeNs = r.get(CaptureResult.SENSOR_EXPOSURE_TIME),
            fNumber = r.get(CaptureResult.LENS_APERTURE),
            iso = iso,
            focalLengthMm = r.get(CaptureResult.LENS_FOCAL_LENGTH),
            flashFired = r.get(CaptureResult.FLASH_STATE)?.let { it == CaptureResult.FLASH_STATE_FIRED },
        )
    }

    private fun jpegOrientation(jpeg: ByteArray): Int = runCatching {
        ExifInterface(ByteArrayInputStream(jpeg))
            .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

    private fun degreesForOrientation(orientation: Int): Int = when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90
        ExifInterface.ORIENTATION_ROTATE_180 -> 180
        ExifInterface.ORIENTATION_ROTATE_270 -> 270
        else -> 0
    }

    private fun jpegThumbnail(jpeg: ByteArray, orientation: Int): Bitmap? {
        val options = BitmapFactory.Options().apply { inSampleSize = 8 }
        val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, options) ?: return null
        return rotate(bitmap, degreesForOrientation(orientation))
    }

    private fun frameThumbnail(frame: RgbFrame, rotation: Int): Bitmap {
        val factor = 8
        val bitmap = Bitmap.createBitmap(frame.width / factor, frame.height / factor, Bitmap.Config.ARGB_8888)
        JxlNative.frameThumbnail(frame.handle, bitmap)
        return rotate(bitmap, rotation)
    }

    private fun rotate(bitmap: Bitmap, degrees: Int): Bitmap {
        if (degrees % 360 == 0) return bitmap
        val m = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
    }

    /** Fallback for JPEGs libjxl cannot recompress losslessly. */
    private fun reencodeJpeg(jpeg: ByteArray, orientation: Int, effort: Int): ByteArray {
        val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, options)
            ?: throw IllegalStateException("Не удалось декодировать JPEG с камеры")
        val handle = JxlNative.bitmapToRgbFrame(bitmap)
        bitmap.recycle()
        return RgbFrame(handle, 0, 0).use { frame ->
            JxlNative.encodeFrame(frame.handle, 1.0f, effort, orientation, JxlNative.COLOR_SRGB, extractExif(jpeg))
        }
    }

    /** Returns the TIFF block of a JPEG's APP1 "Exif" segment, if present. */
    internal fun extractExif(jpeg: ByteArray): ByteArray? {
        if (jpeg.size < 4 || jpeg[0] != 0xFF.toByte() || jpeg[1] != 0xD8.toByte()) return null
        var pos = 2
        while (pos + 4 <= jpeg.size) {
            if (jpeg[pos] != 0xFF.toByte()) return null
            val marker = jpeg[pos + 1].toInt() and 0xFF
            if (marker == 0xDA || marker == 0xD9) return null // start of scan / end
            val length = ((jpeg[pos + 2].toInt() and 0xFF) shl 8) or (jpeg[pos + 3].toInt() and 0xFF)
            if (length < 2 || pos + 2 + length > jpeg.size) return null
            if (marker == 0xE1 && length >= 8 &&
                jpeg[pos + 4] == 'E'.code.toByte() && jpeg[pos + 5] == 'x'.code.toByte() &&
                jpeg[pos + 6] == 'i'.code.toByte() && jpeg[pos + 7] == 'f'.code.toByte() &&
                jpeg[pos + 8] == 0.toByte() && jpeg[pos + 9] == 0.toByte()
            ) {
                return jpeg.copyOfRange(pos + 10, pos + 2 + length)
            }
            pos += 2 + length
        }
        return null
    }
}
