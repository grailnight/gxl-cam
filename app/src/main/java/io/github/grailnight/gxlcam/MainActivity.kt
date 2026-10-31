package io.github.grailnight.gxlcam

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.ImageFormat
import android.media.MediaActionSound
import android.net.Uri
import android.os.Bundle
import android.util.Range
import android.util.Size
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.OrientationEventListener
import android.view.ScaleGestureDetector
import android.view.SurfaceHolder
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.github.grailnight.gxlcam.camera.CameraController
import io.github.grailnight.gxlcam.codec.JxlNative
import io.github.grailnight.gxlcam.databinding.ActivityMainBinding
import io.github.grailnight.gxlcam.databinding.DialogSettingsBinding
import io.github.grailnight.gxlcam.viewer.ViewerActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

class MainActivity : ComponentActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var settings: Settings
    private lateinit var camera: CameraController

    private var opened: CameraController.Opened? = null
    private var openJob: Job? = null
    private var isActivityResumed = false
    private var surfaceReady = false
    private var expectedSurfaceSize: Size? = null
    private var surfaceSizeReached: CompletableDeferred<Unit>? = null
    private var permissionAsked = false

    private var deviceOrientation = 0
    private var uiRotation = 0f
    private var capturing = false
    private var zoom = 1f
    private var lastStatus = ""
    private var lastUri: Uri? = null

    private val shutterSound = MediaActionSound()

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera() else showMessage(getString(R.string.permission_needed))
        }

    private val orientationListener by lazy {
        object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                deviceOrientation = orientation
                rotateIcons(orientation)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        settings = Settings(this)
        camera = CameraController(this) { message -> runOnUiThread { onCameraLost(message) } }
        shutterSound.load(MediaActionSound.SHUTTER_CLICK)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.updatePadding(top = bars.top, bottom = bars.bottom, left = bars.left, right = bars.right)
            insets
        }

        binding.preview.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                surfaceReady = true
                startCamera()
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                val expected = expectedSurfaceSize
                if (expected != null && expected.width == width && expected.height == height) {
                    surfaceSizeReached?.complete(Unit)
                }
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                surfaceReady = false
                openJob?.cancel()
                camera.close()
                opened = null
            }
        })

        binding.shutter.setOnClickListener { takePhoto() }
        binding.switchCamera.setOnClickListener {
            settings.useFrontCamera = !settings.useFrontCamera
            zoom = 1f
            startCamera()
        }
        binding.settingsButton.setOnClickListener { showSettings() }
        binding.modeLabel.setOnClickListener { showSettings() }
        binding.thumbnail.setOnClickListener { openViewer() }
        binding.message.setOnClickListener { requestCameraPermissionAgain() }
        setupPreviewGestures()
        updateModeLabel()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { PhotoPipeline.pending.collect { updateStatus() } }
                launch { PhotoPipeline.events.collect { onPipelineEvent(it) } }
            }
        }
        loadLastThumbnail()
    }

    override fun onResume() {
        super.onResume()
        isActivityResumed = true
        orientationListener.enable()
        if (hasCameraPermission()) {
            startCamera()
        } else if (!permissionAsked) {
            permissionAsked = true
            permissionLauncher.launch(Manifest.permission.CAMERA)
        } else {
            showMessage(getString(R.string.permission_needed))
        }
    }

    override fun onPause() {
        isActivityResumed = false
        orientationListener.disable()
        openJob?.cancel()
        camera.close()
        opened = null
        super.onPause()
    }

    override fun onDestroy() {
        camera.release()
        shutterSound.release()
        super.onDestroy()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
            keyCode == KeyEvent.KEYCODE_CAMERA
        ) {
            if (event.repeatCount == 0) takePhoto()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    // ------------------------------------------------------------------------
    // Camera lifecycle

    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun requestCameraPermissionAgain() {
        if (hasCameraPermission()) return
        if (shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        } else {
            startActivity(
                Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.fromParts("package", packageName, null)),
            )
        }
    }

    private fun startCamera() {
        if (!surfaceReady || !isActivityResumed || !hasCameraPermission()) return
        openJob?.cancel()
        opened = null
        binding.shutter.isEnabled = false
        openJob = lifecycleScope.launch {
            try {
                hideMessage()
                val wantFront = settings.useFrontCamera
                val (id, front) = camera.findCamera(wantFront)?.let { it to wantFront }
                    ?: camera.findCamera(!wantFront)?.let { it to !wantFront }
                    ?: throw IllegalStateException("Камера не найдена")
                val plan = withContext(Dispatchers.Default) {
                    camera.plan(id, front, settings.mode, settings.wideGamut)
                }
                awaitPreviewSurface(plan.previewSize)
                val result = camera.open(plan, binding.preview.holder.surface)
                opened = result
                zoom = zoom.coerceIn(plan.zoomRange.lower, plan.zoomRange.upper)
                camera.setZoom(zoom)
                setupZoomBar(plan.zoomRange)
                updateModeLabel()
                binding.shutter.isEnabled = !capturing
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showMessage("Не удалось открыть камеру:\n${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    /** Sizes the SurfaceView buffer for the camera and waits until it is applied. */
    private suspend fun awaitPreviewSurface(size: Size) {
        val frame = binding.preview.holder.surfaceFrame
        if (frame.width() == size.width && frame.height() == size.height) {
            return
        }
        val reached = CompletableDeferred<Unit>()
        expectedSurfaceSize = size
        surfaceSizeReached = reached
        binding.preview.setAspectRatio(size.width, size.height)
        withTimeoutOrNull(1000) { reached.await() }
        surfaceSizeReached = null
    }

    private fun onCameraLost(message: String) {
        opened = null
        binding.shutter.isEnabled = false
        showMessage("$message\nНажмите, чтобы переподключить")
        binding.message.setOnClickListener {
            binding.message.setOnClickListener { requestCameraPermissionAgain() }
            startCamera()
        }
    }

    // ------------------------------------------------------------------------
    // Capture

    private fun takePhoto() {
        val current = opened ?: return
        if (capturing) return
        if (PhotoPipeline.pending.value >= PhotoPipeline.MAX_PENDING) {
            toast("Подождите — кодируются предыдущие снимки")
            return
        }
        capturing = true
        binding.shutter.isEnabled = false
        val params = PhotoPipeline.Params(current.plan.mode, settings.quality, settings.effort)
        shutterSound.play(MediaActionSound.SHUTTER_CLICK)
        binding.overlay.flash()
        lifecycleScope.launch {
            try {
                val photo = withTimeout(15_000) { camera.capture(settings.quality, deviceOrientation) }
                PhotoPipeline.submit(this@MainActivity, photo, params)
            } catch (e: TimeoutCancellationException) {
                toast("Камера не ответила")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast("Снимок не удался: ${e.message}")
            } finally {
                capturing = false
                binding.shutter.isEnabled = opened != null
            }
        }
    }

    private fun onPipelineEvent(event: PhotoPipeline.Event) {
        when (event) {
            is PhotoPipeline.Event.Saved -> {
                event.thumbnail?.let { binding.thumbnail.setImageBitmap(it) }
                lastUri = event.uri
                settings.lastPhotoUri = event.uri.toString()
                lastStatus = "✓ ${event.name} · ${formatSize(event.bytes)} · ${event.details} · " +
                    "%.1f с".format(event.encodeMillis / 1000.0)
            }
            is PhotoPipeline.Event.Failed -> {
                lastStatus = "Ошибка: ${event.message}"
                toast(lastStatus)
            }
        }
        updateStatus()
    }

    private fun updateStatus() {
        val pending = PhotoPipeline.pending.value
        binding.statusText.text = if (pending > 0) "Кодирование в JPEG XL… ($pending)" else lastStatus
    }

    private fun openViewer() {
        val uri = lastUri ?: settings.lastPhotoUri?.let(Uri::parse)
        if (uri == null) {
            toast(getString(R.string.no_photos))
            return
        }
        startActivity(Intent(this, ViewerActivity::class.java).setData(uri))
    }

    private fun loadLastThumbnail() {
        val uri = settings.lastPhotoUri?.let(Uri::parse) ?: return
        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.Default) {
                runCatching {
                    val bytes = contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                    val info = JxlNative.probe(bytes)
                    val factor = maxOf(1, minOf(info[0], info[1]) / 160)
                    Bitmap.createBitmap(
                        info[0] / factor, info[1] / factor, Bitmap.Config.ARGB_8888, true,
                        ColorSpace.get(ColorSpace.Named.SRGB),
                    ).also { JxlNative.decodeInto(bytes, it, JxlNative.COLOR_SRGB) }
                }.getOrNull()
            }
            if (bitmap != null && binding.thumbnail.drawable == null) {
                binding.thumbnail.setImageBitmap(bitmap)
                lastUri = uri
            }
        }
    }

    // ------------------------------------------------------------------------
    // Zoom & focus

    @SuppressLint("ClickableViewAccessibility")
    private fun setupPreviewGestures() {
        val scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val range = opened?.plan?.zoomRange ?: return false
                zoom = (zoom * detector.scaleFactor).coerceIn(range.lower, range.upper)
                camera.setZoom(zoom)
                updateZoomBar()
                return true
            }
        })
        val tapDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                val v = binding.previewFrame
                if (opened == null || v.width == 0 || v.height == 0) return false
                camera.focusAt(e.x / v.width, e.y / v.height)
                binding.overlay.showFocus(e.x, e.y)
                return true
            }
        })
        binding.previewFrame.setOnTouchListener { v, event ->
            scaleDetector.onTouchEvent(event)
            if (!scaleDetector.isInProgress) tapDetector.onTouchEvent(event)
            if (event.action == MotionEvent.ACTION_UP) v.performClick()
            true
        }
    }

    private val zoomPresets = mutableListOf<Pair<Float, TextView>>()

    private fun setupZoomBar(range: Range<Float>) {
        binding.zoomBar.removeAllViews()
        zoomPresets.clear()
        val presets = listOf(0.5f, 1f, 2f, 5f).filter { it >= range.lower - 0.05f && it <= range.upper + 0.01f }
        if (presets.size < 2) return
        val size = (40 * resources.displayMetrics.density).toInt()
        val margin = (6 * resources.displayMetrics.density).toInt()
        for (preset in presets) {
            val chip = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(size, size).apply { setMargins(margin, 0, margin, 0) }
                gravity = Gravity.CENTER
                background = ContextCompat.getDrawable(context, R.drawable.chip_bg)
                textSize = 12f
                rotation = uiRotation
                setOnClickListener {
                    zoom = preset.coerceIn(range.lower, range.upper)
                    camera.setZoom(zoom)
                    updateZoomBar()
                }
            }
            binding.zoomBar.addView(chip)
            zoomPresets += preset to chip
        }
        updateZoomBar()
    }

    private fun updateZoomBar() {
        val active = zoomPresets.lastOrNull { zoom >= it.first - 0.01f } ?: zoomPresets.firstOrNull()
        for ((preset, chip) in zoomPresets) {
            if (chip === active?.second) {
                chip.text = formatZoom(zoom) + "×"
                chip.setTextColor(ContextCompat.getColor(this, R.color.accent))
            } else {
                chip.text = formatZoom(preset)
                chip.setTextColor(0xFFFFFFFF.toInt())
            }
        }
    }

    private fun formatZoom(z: Float): String =
        if (abs(z - z.toInt()) < 0.05f) z.toInt().toString() else "%.1f".format(z)

    private fun rotateIcons(orientation: Int) {
        val rounded = ((orientation + 45) / 90 * 90) % 360
        val target = -rounded.toFloat()
        var delta = (target - uiRotation) % 360f
        if (delta > 180f) delta -= 360f
        if (delta < -180f) delta += 360f
        if (abs(delta) < 1f) return
        uiRotation += delta
        val views = listOf<View>(binding.thumbnail, binding.switchCamera, binding.settingsButton) +
            zoomPresets.map { it.second }
        views.forEach { it.animate().rotation(uiRotation).setDuration(250).start() }
    }

    // ------------------------------------------------------------------------
    // Settings

    private fun updateModeLabel() {
        val o = opened
        binding.modeLabel.text = buildString {
            append("JXL · ")
            when (settings.mode) {
                CaptureMode.DIRECT -> {
                    append(if (settings.quality >= 100) "без потерь" else "q${settings.quality}")
                }
                CaptureMode.JPEG_LOSSLESS -> append("JPEG→JXL без потерь · JPEG q${settings.quality}")
            }
            append(" · e${settings.effort}")
            if (o != null) {
                if (o.wideGamut) append(" · P3")
                append(" · %.1f Мп".format(o.captureSize.width * o.captureSize.height / 1e6))
            }
        }
    }

    private fun showSettings() {
        val view = DialogSettingsBinding.inflate(layoutInflater)
        var mode = settings.mode
        var quality = settings.quality
        var effort = settings.effort
        val plan = opened?.plan
        val p3Supported = plan != null &&
            CameraController.supportsDisplayP3(plan.characteristics, ImageFormat.YUV_420_888)

        fun refresh() {
            val direct = mode == CaptureMode.DIRECT
            view.qualityLabel.text = if (direct) "Качество JPEG XL: $quality" else "Качество JPEG камеры: $quality"
            view.qualityHint.text = when {
                !direct -> "Затем JPEG сжимается в JXL без потерь"
                quality >= 100 -> "Математически без потерь — файлы очень большие"
                else -> "distance %.2f · 90 ≈ визуально без потерь".format(JxlNative.distanceForQuality(quality))
            }
            view.effortLabel.text = "Усилие кодера (effort): $effort"
            view.effortHint.text = when {
                effort <= 3 -> "Быстро, файлы крупнее"
                effort <= 6 -> "Баланс скорости и размера"
                effort == 7 -> "Стандарт libjxl"
                else -> "Медленно (несколько секунд), файлы меньше"
            }
            view.wideGamutSwitch.isEnabled = direct && p3Supported
            view.wideGamutSwitch.text =
                getString(if (p3Supported || plan == null) R.string.wide_gamut else R.string.wide_gamut_unsupported)
        }

        view.modeGroup.check(if (mode == CaptureMode.DIRECT) R.id.modeDirect else R.id.modeJpeg)
        view.modeGroup.setOnCheckedChangeListener { _, id ->
            mode = if (id == R.id.modeDirect) CaptureMode.DIRECT else CaptureMode.JPEG_LOSSLESS
            refresh()
        }
        view.qualitySeek.progress = quality - Settings.MIN_QUALITY
        view.qualitySeek.setOnSeekBarChangeListener(seekListener { quality = Settings.MIN_QUALITY + it; refresh() })
        view.effortSeek.progress = effort - 1
        view.effortSeek.setOnSeekBarChangeListener(seekListener { effort = it + 1; refresh() })
        view.wideGamutSwitch.isChecked = settings.wideGamut
        view.aboutText.text = "GXL Cam ${BuildConfig.VERSION_NAME} · libjxl ${JxlNative.libjxlVersion()}\n" +
            "Снимки сохраняются в Pictures/GXL Cam (*.jxl)"
        refresh()

        AlertDialog.Builder(this, R.style.Theme_GxlCam_Dialog)
            .setTitle(R.string.settings_title)
            .setView(view.root)
            .setPositiveButton(R.string.ok) { _, _ ->
                val restart = mode != settings.mode || view.wideGamutSwitch.isChecked != settings.wideGamut
                settings.mode = mode
                settings.quality = quality
                settings.effort = effort
                settings.wideGamut = view.wideGamutSwitch.isChecked
                updateModeLabel()
                if (restart) startCamera()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun seekListener(onChange: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
            if (fromUser) onChange(progress)
        }

        override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
        override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
    }

    // ------------------------------------------------------------------------

    private fun showMessage(text: String) {
        binding.message.text = text
        binding.message.visibility = View.VISIBLE
    }

    private fun hideMessage() {
        binding.message.visibility = View.GONE
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    companion object {
        fun formatSize(bytes: Int): String = when {
            bytes >= 1_000_000 -> "%.1f МБ".format(bytes / 1_000_000.0)
            else -> "%d КБ".format(bytes / 1000)
        }
    }
}
