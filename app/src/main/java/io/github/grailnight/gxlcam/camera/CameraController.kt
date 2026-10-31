package io.github.grailnight.gxlcam.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ColorSpace
import android.graphics.ImageFormat
import android.graphics.Rect
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import android.view.SurfaceHolder
import io.github.grailnight.gxlcam.CaptureMode
import io.github.grailnight.gxlcam.codec.JxlNative
import io.github.grailnight.gxlcam.codec.RgbFrame
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.time.ZonedDateTime
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** What the camera delivered for one shot. */
sealed class Payload {
    class Jpeg(val bytes: ByteArray) : Payload()
    class Frame(val frame: RgbFrame) : Payload()
}

class CapturedPhoto(
    val payload: Payload,
    val result: TotalCaptureResult,
    /** Clockwise rotation needed to show the image upright. */
    val rotationDegrees: Int,
    val wideGamut: Boolean,
    val time: ZonedDateTime,
)

/**
 * Camera2 wrapper: full-resolution still capture in either JPEG or YUV_420_888
 * plus a SurfaceView preview. All camera state lives on a dedicated thread.
 */
class CameraController(context: Context, private val onFatalError: (String) -> Unit) {

    data class Plan(
        val cameraId: String,
        val characteristics: CameraCharacteristics,
        val front: Boolean,
        val mode: CaptureMode,
        val format: Int,
        /** Candidate still sizes, largest first (all with the preview's aspect ratio). */
        val captureSizes: List<Size>,
        val previewSize: Size,
        val wideGamutRequested: Boolean,
    ) {
        val sensorOrientation: Int = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        val zoomRange: Range<Float> =
            characteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) ?: Range(1f, 1f)
    }

    data class Opened(val plan: Plan, val captureSize: Size, val wideGamut: Boolean)

    private val manager = context.getSystemService(CameraManager::class.java)
    private val thread = HandlerThread("Camera").apply { start() }
    private val handler = Handler(thread.looper)
    private val dispatcher = handler.asCoroutineDispatcher()
    private val executor = Executor { handler.post(it) }

    // --- state, only touched on the camera thread ---
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var previewBuilder: CaptureRequest.Builder? = null
    private var opened: Opened? = null
    private var zoomRatio = 1f
    private var focusRegions: Array<MeteringRectangle>? = null
    private var defaultAfRegions: Array<MeteringRectangle>? = null
    private var defaultAeRegions: Array<MeteringRectangle>? = null
    private val resetFocus = Runnable { clearFocusPoint() }

    // ------------------------------------------------------------------------
    // Planning (no camera opened yet)

    fun findCamera(front: Boolean): String? {
        val wanted = if (front) CameraMetadata.LENS_FACING_FRONT else CameraMetadata.LENS_FACING_BACK
        val ids = manager.cameraIdList.filter {
            manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == wanted
        }
        // Prefer a logical multi-camera (on Pixel it switches lenses with zoom).
        return ids.firstOrNull { id ->
            manager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                ?.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA) == true
        } ?: ids.firstOrNull()
    }

    fun plan(cameraId: String, front: Boolean, mode: CaptureMode, wideGamut: Boolean): Plan {
        val chars = manager.getCameraCharacteristics(cameraId)
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: throw IllegalStateException("No stream configuration map")
        val format = if (mode == CaptureMode.DIRECT) ImageFormat.YUV_420_888 else ImageFormat.JPEG
        val all = map.getOutputSizes(format)?.sortedByDescending { it.width.toLong() * it.height }
            ?: throw IllegalStateException("Format $format not supported")
        val largest = all.first()
        val aspect = largest.width.toFloat() / largest.height
        val sameAspect = all.filter { abs(it.width.toFloat() / it.height - aspect) < 0.01f }

        val previewCandidates = map.getOutputSizes(SurfaceHolder::class.java).toList()
        val preview = previewCandidates
            .filter { abs(it.width.toFloat() / it.height - aspect) < 0.01f }
            .filter { max(it.width, it.height) <= 1920 && min(it.width, it.height) <= 1440 }
            .maxByOrNull { it.width.toLong() * it.height }
            ?: previewCandidates.filter { it.width <= 1920 && it.height <= 1080 }
                .maxByOrNull { it.width.toLong() * it.height }
            ?: previewCandidates.first()

        return Plan(
            cameraId = cameraId,
            characteristics = chars,
            front = front,
            mode = mode,
            format = format,
            captureSizes = sameAspect.take(4),
            previewSize = preview,
            wideGamutRequested = wideGamut && mode == CaptureMode.DIRECT && supportsDisplayP3(chars, format),
        )
    }

    // ------------------------------------------------------------------------
    // Open / close

    suspend fun open(plan: Plan, previewSurface: Surface): Opened = withContext(dispatcher) {
        closeInternal()
        val camera = openDevice(plan.cameraId)
        device = camera
        try {
            val gamutOptions = if (plan.wideGamutRequested) listOf(true, false) else listOf(false)
            var chosen: Triple<Size, Boolean, ImageReader>? = null
            loop@ for (size in plan.captureSizes) {
                for (p3 in gamutOptions) {
                    val r = ImageReader.newInstance(size.width, size.height, plan.format, 2)
                    val config = sessionConfig(previewSurface, r.surface, p3, null)
                    if (isSupported(camera, config)) {
                        chosen = Triple(size, p3, r)
                        break@loop
                    }
                    r.close()
                }
            }
            val (size, p3, imageReader) = chosen ?: run {
                // Nothing reported as supported; try the smallest candidate anyway.
                val s = plan.captureSizes.last()
                Triple(s, false, ImageReader.newInstance(s.width, s.height, plan.format, 2))
            }
            reader = imageReader
            session = createSession(camera, previewSurface, imageReader.surface, p3)

            zoomRatio = zoomRatio.coerceIn(plan.zoomRange.lower, plan.zoomRange.upper)
            focusRegions = null
            previewBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(previewSurface)
                defaultAfRegions = get(CaptureRequest.CONTROL_AF_REGIONS)
                defaultAeRegions = get(CaptureRequest.CONTROL_AE_REGIONS)
                applyCommon(this, plan, isPreview = true)
            }
            session?.setRepeatingRequest(previewBuilder!!.build(), null, handler)
            Opened(plan, size, p3).also { opened = it }
        } catch (e: Exception) {
            closeInternal()
            throw e
        }
    }

    fun close() {
        handler.post { closeInternal() }
    }

    fun release() {
        handler.post {
            closeInternal()
            thread.quitSafely()
        }
    }

    private fun closeInternal() {
        handler.removeCallbacks(resetFocus)
        runCatching { session?.close() }
        runCatching { device?.close() }
        runCatching { reader?.close() }
        session = null
        device = null
        reader = null
        previewBuilder = null
        opened = null
    }

    @SuppressLint("MissingPermission")
    private suspend fun openDevice(id: String): CameraDevice = suspendCancellableCoroutine { cont ->
        try {
            manager.openCamera(id, executor, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (cont.isActive) cont.resume(camera) else camera.close()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    if (cont.isActive) {
                        cont.resumeWithException(CameraAccessException(CameraAccessException.CAMERA_DISCONNECTED))
                    } else if (device == camera) {
                        closeInternal()
                        onFatalError("Камера отключена (её занял другой процесс)")
                    }
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    val message = "Ошибка камеры: $error"
                    if (cont.isActive) {
                        cont.resumeWithException(RuntimeException(message))
                    } else if (device == camera) {
                        closeInternal()
                        onFatalError(message)
                    }
                }
            })
        } catch (e: Exception) {
            cont.resumeWithException(e)
        }
    }

    private fun sessionConfig(
        preview: Surface,
        still: Surface,
        p3: Boolean,
        callback: CameraCaptureSession.StateCallback?,
    ): SessionConfiguration {
        val outputs = listOf(OutputConfiguration(preview), OutputConfiguration(still))
        val cb = callback ?: object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) = Unit
            override fun onConfigureFailed(session: CameraCaptureSession) = Unit
        }
        return SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outputs, executor, cb).apply {
            if (p3 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                setColorSpace(ColorSpace.Named.DISPLAY_P3)
            }
        }
    }

    private fun isSupported(camera: CameraDevice, config: SessionConfiguration): Boolean = try {
        camera.isSessionConfigurationSupported(config)
    } catch (e: UnsupportedOperationException) {
        true // HAL cannot tell; just try it
    } catch (e: Exception) {
        Log.w(TAG, "isSessionConfigurationSupported failed", e)
        false
    }

    private suspend fun createSession(
        camera: CameraDevice,
        preview: Surface,
        still: Surface,
        p3: Boolean,
    ): CameraCaptureSession = suspendCancellableCoroutine { cont ->
        val callback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                if (cont.isActive) cont.resume(session) else session.close()
            }

            override fun onConfigureFailed(session: CameraCaptureSession) {
                if (cont.isActive) cont.resumeWithException(RuntimeException("Не удалось настроить сессию камеры"))
            }
        }
        camera.createCaptureSession(sessionConfig(preview, still, p3, callback))
    }

    // ------------------------------------------------------------------------
    // Requests

    private fun applyCommon(builder: CaptureRequest.Builder, plan: Plan, isPreview: Boolean) {
        val afModes = plan.characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf()
        val regions = focusRegions
        if (regions != null && CameraMetadata.CONTROL_AF_MODE_AUTO in afModes) {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO)
        } else if (CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE in afModes) {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        }
        val maxAf = plan.characteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0
        val maxAe = plan.characteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0
        // Without a focus point a still request keeps its template defaults, the
        // preview request gets the defaults it was created with back.
        if (regions != null || isPreview) {
            if (maxAf > 0) builder.set(CaptureRequest.CONTROL_AF_REGIONS, regions ?: defaultAfRegions)
            if (maxAe > 0) builder.set(CaptureRequest.CONTROL_AE_REGIONS, regions ?: defaultAeRegions)
        }
        builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
        builder.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
        builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoomRatio)
    }

    private fun updatePreview() {
        val s = session ?: return
        val b = previewBuilder ?: return
        val plan = opened?.plan ?: return
        applyCommon(b, plan, isPreview = true)
        runCatching { s.setRepeatingRequest(b.build(), null, handler) }
            .onFailure { Log.w(TAG, "setRepeatingRequest failed", it) }
    }

    fun setZoom(ratio: Float) {
        handler.post {
            val plan = opened?.plan ?: return@post
            zoomRatio = ratio.coerceIn(plan.zoomRange.lower, plan.zoomRange.upper)
            updatePreview()
        }
    }

    /**
     * Focuses and meters at a point given in normalized preview-view coordinates
     * (0..1, portrait as displayed). Reverts to continuous AF after a while.
     */
    fun focusAt(viewX: Float, viewY: Float) {
        handler.post {
            val s = session ?: return@post
            val b = previewBuilder ?: return@post
            val plan = opened?.plan ?: return@post
            val active: Rect = plan.characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                ?: return@post
            val (sx, sy) = viewToSensor(viewX, viewY, plan.sensorOrientation, plan.front)
            val half = (min(active.width(), active.height()) * 0.06f).roundToInt()
            val cx = (sx * active.width()).roundToInt().coerceIn(half, active.width() - half)
            val cy = (sy * active.height()).roundToInt().coerceIn(half, active.height() - half)
            focusRegions = arrayOf(
                MeteringRectangle(cx - half, cy - half, 2 * half, 2 * half, MeteringRectangle.METERING_WEIGHT_MAX - 1),
            )
            try {
                b.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL)
                s.capture(b.build(), null, handler)
                b.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE)
                applyCommon(b, plan, isPreview = true)
                s.setRepeatingRequest(b.build(), null, handler)
                b.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
                s.capture(b.build(), null, handler)
                b.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE)
            } catch (e: Exception) {
                Log.w(TAG, "focus failed", e)
            }
            handler.removeCallbacks(resetFocus)
            handler.postDelayed(resetFocus, FOCUS_HOLD_MS)
        }
    }

    private fun clearFocusPoint() {
        val s = session ?: return
        val b = previewBuilder ?: return
        if (focusRegions == null) return
        focusRegions = null
        runCatching {
            b.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL)
            s.capture(b.build(), null, handler)
            b.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE)
        }
        updatePreview()
    }

    /** Clockwise rotation for an upright photo given the device orientation (degrees). */
    fun captureRotation(plan: Plan, deviceOrientation: Int): Int {
        if (deviceOrientation < 0) return plan.sensorOrientation
        var rounded = ((deviceOrientation + 45) / 90 * 90) % 360
        if (plan.front) rounded = -rounded
        return (plan.sensorOrientation + rounded + 360) % 360
    }

    /** Takes one full-resolution still picture. */
    suspend fun capture(jpegQuality: Int, deviceOrientation: Int): CapturedPhoto = withContext(dispatcher) {
        val s = session ?: throw IllegalStateException("Камера не готова")
        val r = reader ?: throw IllegalStateException("Камера не готова")
        val o = opened ?: throw IllegalStateException("Камера не готова")
        val plan = o.plan
        val rotation = captureRotation(plan, deviceOrientation)
        val time = ZonedDateTime.now()

        // Drop stale frames, if any.
        while (true) {
            val stale = runCatching { r.acquireNextImage() }.getOrNull() ?: break
            stale.close()
        }

        suspendCancellableCoroutine { cont ->
            var payload: Payload? = null
            var result: TotalCaptureResult? = null
            var finished = false

            fun finish(error: Throwable?) {
                if (finished) return
                if (error == null && (payload == null || result == null)) return
                finished = true
                r.setOnImageAvailableListener(null, null)
                val p = payload
                when {
                    error != null -> {
                        (p as? Payload.Frame)?.frame?.close()
                        if (cont.isActive) cont.resumeWithException(error)
                    }
                    !cont.isActive -> (p as? Payload.Frame)?.frame?.close()
                    else -> cont.resume(CapturedPhoto(p!!, result!!, rotation, o.wideGamut, time))
                }
            }
            cont.invokeOnCancellation { handler.post { finish(kotlinx.coroutines.CancellationException("cancelled")) } }

            r.setOnImageAvailableListener({ rd ->
                val image = runCatching { rd.acquireNextImage() }.getOrNull() ?: return@setOnImageAvailableListener
                try {
                    if (payload == null && !finished) payload = toPayload(image)
                } catch (e: Throwable) {
                    finish(e)
                } finally {
                    image.close()
                }
                finish(null)
            }, handler)

            try {
                val request = s.device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    addTarget(r.surface)
                    applyCommon(this, plan, isPreview = false)
                    if (plan.format == ImageFormat.JPEG) {
                        set(CaptureRequest.JPEG_ORIENTATION, rotation)
                        set(CaptureRequest.JPEG_QUALITY, jpegQuality.coerceIn(1, 100).toByte())
                    } else {
                        setHighQuality(this, plan.characteristics)
                    }
                }.build()
                s.capture(request, object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        res: TotalCaptureResult,
                    ) {
                        result = res
                        finish(null)
                    }

                    override fun onCaptureFailed(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        failure: CaptureFailure,
                    ) {
                        finish(RuntimeException("Снимок не удался (причина ${failure.reason})"))
                    }
                }, handler)
            } catch (e: Exception) {
                finish(e)
            }
        }
    }

    private fun toPayload(image: Image): Payload = when (image.format) {
        ImageFormat.JPEG -> {
            val buffer = image.planes[0].buffer
            Payload.Jpeg(ByteArray(buffer.remaining()).also { buffer.get(it) })
        }
        ImageFormat.YUV_420_888 -> {
            val (y, u, v) = image.planes
            val handle = JxlNative.yuvToRgbFrame(
                y.buffer, y.rowStride, y.pixelStride,
                u.buffer, v.buffer, u.rowStride, u.pixelStride,
                image.width, image.height,
            )
            Payload.Frame(RgbFrame(handle, image.width, image.height))
        }
        else -> throw IllegalStateException("Unexpected image format ${image.format}")
    }

    private fun setHighQuality(builder: CaptureRequest.Builder, chars: CameraCharacteristics) {
        fun pick(key: CameraCharacteristics.Key<IntArray>, wanted: Int) =
            chars.get(key)?.contains(wanted) == true
        if (pick(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES,
                CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY)) {
            builder.set(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY)
        }
        if (pick(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES, CameraMetadata.EDGE_MODE_HIGH_QUALITY)) {
            builder.set(CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_HIGH_QUALITY)
        }
        if (pick(CameraCharacteristics.COLOR_CORRECTION_AVAILABLE_ABERRATION_MODES,
                CameraMetadata.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY)) {
            builder.set(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE,
                CameraMetadata.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY)
        }
        if (pick(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES, CameraMetadata.TONEMAP_MODE_HIGH_QUALITY)) {
            builder.set(CaptureRequest.TONEMAP_MODE, CameraMetadata.TONEMAP_MODE_HIGH_QUALITY)
        }
        if (pick(CameraCharacteristics.SHADING_AVAILABLE_MODES, CameraMetadata.SHADING_MODE_HIGH_QUALITY)) {
            builder.set(CaptureRequest.SHADING_MODE, CameraMetadata.SHADING_MODE_HIGH_QUALITY)
        }
    }

    companion object {
        private const val TAG = "CameraController"
        private const val FOCUS_HOLD_MS = 8000L

        /** Whether the camera can deliver Display P3 for `format` and the preview. */
        fun supportsDisplayP3(chars: CameraCharacteristics, format: Int): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return false
            val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: return false
            if (CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_COLOR_SPACE_PROFILES !in caps) return false
            val profiles = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_COLOR_SPACE_PROFILES) ?: return false
            val p3 = ColorSpace.Named.DISPLAY_P3
            return p3 in profiles.getSupportedColorSpaces(format) &&
                p3 in profiles.getSupportedColorSpaces(ImageFormat.PRIVATE)
        }

        /**
         * Maps a point on the (portrait, possibly mirrored) preview to normalized
         * sensor coordinates by undoing the display rotation.
         */
        fun viewToSensor(viewX: Float, viewY: Float, sensorOrientation: Int, mirrored: Boolean): Pair<Float, Float> {
            var x = if (mirrored) 1f - viewX else viewX
            var y = viewY
            repeat(((sensorOrientation / 90) % 4 + 4) % 4) {
                // Undo one 90° clockwise rotation: view(x, y) = (1 - sy, sx).
                val nx = y
                val ny = 1f - x
                x = nx
                y = ny
            }
            return x.coerceIn(0f, 1f) to y.coerceIn(0f, 1f)
        }
    }
}
