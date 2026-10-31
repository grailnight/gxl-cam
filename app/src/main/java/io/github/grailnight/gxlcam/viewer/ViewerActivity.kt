package io.github.grailnight.gxlcam.viewer

import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import io.github.grailnight.gxlcam.MainActivity
import io.github.grailnight.gxlcam.codec.JxlNative
import io.github.grailnight.gxlcam.databinding.ActivityViewerBinding
import io.github.grailnight.gxlcam.storage.PhotoStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.time.ZonedDateTime

/** Built-in JPEG XL viewer (the system gallery cannot display .jxl yet). */
class ViewerActivity : ComponentActivity() {

    private lateinit var binding: ActivityViewerBinding
    private var uri: Uri? = null
    private var bytes: ByteArray? = null
    private var info: IntArray? = null
    private var bitmap: Bitmap? = null
    private var name: String = "image.jxl"

    private val openDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { picked ->
        if (picked != null) load(picked)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        window.colorMode = ActivityInfo.COLOR_MODE_WIDE_COLOR_GAMUT
        binding = ActivityViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            binding.info.updatePadding(top = bars.top + pad(12))
            binding.actions.updatePadding(bottom = bars.bottom + pad(4))
            insets
        }

        binding.image.setOnClickListener { toggleChrome() }
        binding.shareButton.setOnClickListener { share() }
        binding.exportButton.setOnClickListener { exportJpeg() }
        binding.openButton.setOnClickListener {
            openDocument.launch(arrayOf(PhotoStore.MIME_JXL, "application/octet-stream"))
        }

        val initial = intent?.data
        if (initial != null) load(initial) else {
            binding.progress.visibility = View.GONE
            binding.info.text = "Откройте файл .jxl"
        }
    }

    private fun pad(dp: Int) = (dp * resources.displayMetrics.density).toInt()

    private fun toggleChrome() {
        val show = binding.actions.visibility != View.VISIBLE
        binding.actions.visibility = if (show) View.VISIBLE else View.GONE
        binding.info.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun load(target: Uri) {
        uri = target
        binding.progress.visibility = View.VISIBLE
        binding.image.setImageDrawable(null)
        name = PhotoStore.displayName(this, target) ?: "image.jxl"
        lifecycleScope.launch {
            try {
                val start = SystemClock.elapsedRealtime()
                val data = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(target)?.use { it.readBytes() }
                        ?: throw IllegalStateException("Не удалось открыть файл")
                }
                val (decoded, meta) = withContext(Dispatchers.Default) { decode(data) }
                val millis = SystemClock.elapsedRealtime() - start
                bytes = data
                info = meta
                bitmap = decoded
                binding.image.setImageBitmap(decoded)
                binding.info.text = describe(data, meta, decoded, millis)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                binding.info.text = "$name\nОшибка: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                binding.progress.visibility = View.GONE
            }
        }
    }

    private fun decode(data: ByteArray): Pair<Bitmap, IntArray> {
        val meta = JxlNative.probe(data)
        val width = meta[0]
        val height = meta[1]
        // Keep the bitmap below ~64 MB so the GPU can draw it.
        var factor = 1
        while ((width / factor).toLong() * (height / factor) > MAX_PIXELS) factor++
        val bmp = Bitmap.createBitmap(
            width / factor, height / factor, Bitmap.Config.ARGB_8888, meta[3] == 1,
            ColorSpace.get(ColorSpace.Named.DISPLAY_P3),
        )
        val actual = JxlNative.decodeInto(data, bmp, JxlNative.COLOR_DISPLAY_P3)
        if (actual != JxlNative.COLOR_DISPLAY_P3) bmp.setColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
        return bmp to meta
    }

    private fun describe(data: ByteArray, meta: IntArray, bmp: Bitmap, millis: Long): String {
        val kind = when {
            meta[5] == 1 -> "JPEG, сжатый без потерь (исходный JPEG восстановим)"
            meta[4] == 1 -> "VarDCT (с потерями)"
            else -> "без потерь (Modular)"
        }
        val scaled = if (bmp.width != meta[0]) " · показан ${bmp.width}×${bmp.height}" else ""
        val mp = meta[0].toLong() * meta[1]
        return "$name\n${meta[0]}×${meta[1]} (%.1f Мп) · ${MainActivity.formatSize(data.size)} · %.2f бит/пикс\n"
            .format(mp / 1e6, data.size * 8.0 / mp) +
            "$kind · ${meta[6]} бит · декодирование $millis мс$scaled"
    }

    private fun share() {
        val target = uri ?: return
        val intent = Intent(Intent.ACTION_SEND)
            .setType(PhotoStore.MIME_JXL)
            .putExtra(Intent.EXTRA_STREAM, target)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(intent, null))
    }

    /**
     * Saves a JPEG copy for apps without JPEG XL support. Losslessly recompressed
     * files give back the camera's original JPEG bit-for-bit.
     */
    private fun exportJpeg() {
        val data = bytes ?: return
        val meta = info ?: return
        val shown = bitmap ?: return
        binding.exportButton.isEnabled = false
        lifecycleScope.launch {
            try {
                val (jpeg, exact) = withContext(Dispatchers.Default) {
                    if (meta[5] == 1) {
                        JxlNative.reconstructJpeg(data) to true
                    } else {
                        val out = ByteArrayOutputStream()
                        shown.compress(Bitmap.CompressFormat.JPEG, 95, out)
                        out.toByteArray() to false
                    }
                }
                val jpegName = name.substringBeforeLast('.') + ".jpg"
                withContext(Dispatchers.IO) {
                    PhotoStore.save(
                        this@ViewerActivity, jpeg, jpegName, "image/jpeg",
                        ZonedDateTime.now().toInstant().toEpochMilli(),
                    )
                }
                val how = if (exact) "оригинальный JPEG восстановлен бит-в-бит" else "перекодировано, q95"
                toast("Сохранено: $jpegName ($how)")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                toast("Не удалось: ${e.message}")
            } finally {
                binding.exportButton.isEnabled = true
            }
        }
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    companion object {
        private const val MAX_PIXELS = 16_000_000L
    }
}
