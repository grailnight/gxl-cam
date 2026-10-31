package io.github.grailnight.gxlcam.storage

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.roundToLong

/**
 * Minimal EXIF (TIFF) writer for photos encoded directly from camera frames,
 * where there is no camera JPEG to take the metadata from.
 * The result is a little-endian TIFF block as stored in a JPEG XL "Exif" box.
 */
object ExifWriter {

    data class Metadata(
        val make: String,
        val model: String,
        val software: String,
        val orientation: Int,
        val time: ZonedDateTime,
        val width: Int,
        val height: Int,
        val srgb: Boolean,
        val exposureTimeNs: Long? = null,
        val fNumber: Float? = null,
        val iso: Int? = null,
        val focalLengthMm: Float? = null,
        val flashFired: Boolean? = null,
    )

    private const val ASCII = 2
    private const val SHORT = 3
    private const val LONG = 4
    private const val RATIONAL = 5
    private const val UNDEFINED = 7

    private class Entry(val tag: Int, val type: Int, val count: Int, val data: ByteArray)

    private class Ifd {
        val entries = mutableListOf<Entry>()

        fun ascii(tag: Int, value: String) {
            val bytes = (value + "\u0000").toByteArray(Charsets.US_ASCII)
            entries += Entry(tag, ASCII, bytes.size, bytes)
        }

        fun short(tag: Int, value: Int) {
            entries += Entry(tag, SHORT, 1, le(2) { putShort(value.toShort()) })
        }

        fun long(tag: Int, value: Long) {
            entries += Entry(tag, LONG, 1, le(4) { putInt(value.toInt()) })
        }

        fun rational(tag: Int, num: Long, den: Long) {
            val g = gcd(num, den).coerceAtLeast(1)
            entries += Entry(tag, RATIONAL, 1, le(8) { putInt((num / g).toInt()); putInt((den / g).toInt()) })
        }

        fun undefined(tag: Int, bytes: ByteArray) {
            entries += Entry(tag, UNDEFINED, bytes.size, bytes)
        }

        /** Size of this IFD including its out-of-line data. */
        fun size(): Int = 2 + entries.size * 12 + 4 +
            entries.sumOf { if (it.data.size > 4) (it.data.size + 1) and 1.inv() else 0 }

        fun write(out: ByteBuffer, offset: Int) {
            entries.sortBy { it.tag }
            var dataOffset = offset + 2 + entries.size * 12 + 4
            out.putShort(entries.size.toShort())
            val outOfLine = mutableListOf<ByteArray>()
            for (e in entries) {
                out.putShort(e.tag.toShort())
                out.putShort(e.type.toShort())
                out.putInt(e.count)
                if (e.data.size <= 4) {
                    out.put(e.data)
                    repeat(4 - e.data.size) { out.put(0) }
                } else {
                    out.putInt(dataOffset)
                    outOfLine += e.data
                    dataOffset += (e.data.size + 1) and 1.inv()
                }
            }
            out.putInt(0) // no next IFD
            for (d in outOfLine) {
                out.put(d)
                if (d.size and 1 == 1) out.put(0)
            }
        }
    }

    fun build(m: Metadata): ByteArray {
        val dateTime = m.time.format(DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss"))
        val offset = m.time.format(DateTimeFormatter.ofPattern("xxx"))
        val subSec = "%03d".format(m.time.nano / 1_000_000)

        val ifd0 = Ifd().apply {
            ascii(0x010F, m.make)
            ascii(0x0110, m.model)
            short(0x0112, m.orientation.coerceIn(1, 8))
            rational(0x011A, 72, 1) // XResolution
            rational(0x011B, 72, 1) // YResolution
            short(0x0128, 2)        // ResolutionUnit: inch
            ascii(0x0131, m.software)
            ascii(0x0132, dateTime)
        }
        val exif = Ifd().apply {
            m.exposureTimeNs?.takeIf { it > 0 }?.let { rational(0x829A, it / 1000, 1_000_000) }
            m.fNumber?.takeIf { it > 0 }?.let { rational(0x829D, (it * 100).roundToLong(), 100) }
            short(0x8822, 2) // ExposureProgram: normal (auto)
            m.iso?.takeIf { it > 0 }?.let { short(0x8827, it.coerceAtMost(65535)) }
            undefined(0x9000, "0232".toByteArray(Charsets.US_ASCII))
            ascii(0x9003, dateTime)
            ascii(0x9004, dateTime)
            ascii(0x9010, offset)
            ascii(0x9011, offset)
            ascii(0x9012, offset)
            m.flashFired?.let { short(0x9209, if (it) 1 else 0) }
            m.focalLengthMm?.takeIf { it > 0 }?.let { rational(0x920A, (it * 100).roundToLong(), 100) }
            ascii(0x9290, subSec)
            ascii(0x9291, subSec)
            ascii(0x9292, subSec)
            short(0xA001, if (m.srgb) 1 else 0xFFFF) // ColorSpace: sRGB / uncalibrated
            long(0xA002, m.width.toLong())
            long(0xA003, m.height.toLong())
            short(0xA402, 0) // ExposureMode: auto
            short(0xA403, 0) // WhiteBalance: auto
        }
        // ExifIFD pointer: an inline LONG, so its value does not affect IFD0's size.
        ifd0.long(0x8769, 0)
        val ifd0Size = ifd0.size()
        val exifOffset = 8 + ifd0Size
        ifd0.entries.removeAll { it.tag == 0x8769 }
        ifd0.long(0x8769, exifOffset.toLong())

        val total = 8 + ifd0Size + exif.size()
        val buf = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        buf.put('I'.code.toByte()).put('I'.code.toByte()).putShort(42).putInt(8)
        ifd0.write(buf, 8)
        exif.write(buf, exifOffset)
        check(buf.position() == total)
        return buf.array()
    }

    private inline fun le(size: Int, block: ByteBuffer.() -> Unit): ByteArray =
        ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN).apply(block).array()

    private tailrec fun gcd(a: Long, b: Long): Long = if (b == 0L) a else gcd(b, a % b)

    /** Rotation in degrees (0/90/180/270, clockwise) → EXIF orientation. */
    fun orientationFromDegrees(degrees: Int): Int = when (((degrees % 360) + 360) % 360) {
        90 -> 6
        180 -> 3
        270 -> 8
        else -> 1
    }
}
