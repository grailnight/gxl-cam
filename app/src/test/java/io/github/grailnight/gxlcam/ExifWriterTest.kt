package io.github.grailnight.gxlcam

import io.github.grailnight.gxlcam.storage.ExifWriter
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.ZoneOffset
import java.time.ZonedDateTime

class ExifWriterTest {

    private class Entry(val tag: Int, val type: Int, val count: Int, val valueOrOffset: Int, val raw: ByteArray)

    /** Tiny TIFF reader used to validate the writer's output. */
    private fun readIfd(tiff: ByteArray, offset: Int): List<Entry> {
        val b = ByteBuffer.wrap(tiff).order(ByteOrder.LITTLE_ENDIAN)
        val n = b.getShort(offset).toInt()
        return (0 until n).map { i ->
            val p = offset + 2 + i * 12
            Entry(
                tag = b.getShort(p).toInt() and 0xFFFF,
                type = b.getShort(p + 2).toInt(),
                count = b.getInt(p + 4),
                valueOrOffset = b.getInt(p + 8),
                raw = tiff.copyOfRange(p + 8, p + 12),
            )
        }
    }

    private fun ascii(tiff: ByteArray, e: Entry): String {
        val bytes = if (e.count <= 4) e.raw.copyOf(e.count) else tiff.copyOfRange(e.valueOrOffset, e.valueOrOffset + e.count)
        return String(bytes, Charsets.US_ASCII).trimEnd('\u0000')
    }

    private fun rational(tiff: ByteArray, e: Entry): Pair<Int, Int> {
        val b = ByteBuffer.wrap(tiff).order(ByteOrder.LITTLE_ENDIAN)
        return b.getInt(e.valueOrOffset) to b.getInt(e.valueOrOffset + 4)
    }

    private val time = ZonedDateTime.of(2026, 10, 31, 14, 5, 9, 123_000_000, ZoneOffset.ofHours(3))

    private fun build(orientation: Int = 6) = ExifWriter.build(
        ExifWriter.Metadata(
            make = "Google", model = "Pixel 9", software = "GXL Cam test",
            orientation = orientation, time = time, width = 4080, height = 3072, srgb = true,
            exposureTimeNs = 8_333_333, fNumber = 1.68f, iso = 125, focalLengthMm = 6.9f, flashFired = false,
        ),
    )

    @Test
    fun headerAndIfd0() {
        val tiff = build()
        assertArrayEquals(byteArrayOf('I'.code.toByte(), 'I'.code.toByte(), 42, 0, 8, 0, 0, 0), tiff.copyOf(8))
        val ifd0 = readIfd(tiff, 8)
        assertEquals(ifd0.map { it.tag }.sorted(), ifd0.map { it.tag }) // TIFF requires ascending tags
        assertEquals("Google", ascii(tiff, ifd0.first { it.tag == 0x010F }))
        assertEquals("Pixel 9", ascii(tiff, ifd0.first { it.tag == 0x0110 }))
        assertEquals(6, ifd0.first { it.tag == 0x0112 }.valueOrOffset and 0xFFFF)
        assertEquals("2026:10:31 14:05:09", ascii(tiff, ifd0.first { it.tag == 0x0132 }))
        // Out-of-line values must stay inside the block and be word aligned.
        ifd0.filter { it.type == 2 && it.count > 4 }.forEach {
            assertTrue(it.valueOrOffset % 2 == 0 && it.valueOrOffset + it.count <= tiff.size)
        }
    }

    @Test
    fun exifSubIfd() {
        val tiff = build()
        val pointer = readIfd(tiff, 8).first { it.tag == 0x8769 }
        assertEquals(4, pointer.type)
        val exif = readIfd(tiff, pointer.valueOrOffset)
        assertEquals(exif.map { it.tag }.sorted(), exif.map { it.tag })
        val (expNum, expDen) = rational(tiff, exif.first { it.tag == 0x829A })
        assertEquals(8333.0 / 1_000_000, expNum.toDouble() / expDen, 1e-9)
        val (fNum, fDen) = rational(tiff, exif.first { it.tag == 0x829D })
        assertEquals(1.68, fNum.toDouble() / fDen, 1e-6)
        assertEquals(125, exif.first { it.tag == 0x8827 }.valueOrOffset and 0xFFFF)
        assertEquals("2026:10:31 14:05:09", ascii(tiff, exif.first { it.tag == 0x9003 }))
        assertEquals("+03:00", ascii(tiff, exif.first { it.tag == 0x9011 }))
        assertEquals("123", ascii(tiff, exif.first { it.tag == 0x9291 }))
        assertEquals("0232", ascii(tiff, exif.first { it.tag == 0x9000 }))
        assertEquals(4080, exif.first { it.tag == 0xA002 }.valueOrOffset)
        assertEquals(3072, exif.first { it.tag == 0xA003 }.valueOrOffset)
        // Sub-IFD must end exactly at the end of the block.
        val last = exif.filter { it.count > 4 || it.type == 5 }.maxOf {
            it.valueOrOffset + if (it.type == 5) 8 else it.count
        }
        assertTrue(last <= tiff.size)
    }

    @Test
    fun orientationMapping() {
        assertEquals(1, ExifWriter.orientationFromDegrees(0))
        assertEquals(6, ExifWriter.orientationFromDegrees(90))
        assertEquals(3, ExifWriter.orientationFromDegrees(180))
        assertEquals(8, ExifWriter.orientationFromDegrees(270))
        assertEquals(6, ExifWriter.orientationFromDegrees(-270))
    }

    @Test
    fun extractExifFromJpeg() {
        val tiff = build(orientation = 1)
        val app0 = byteArrayOf(0xFF.toByte(), 0xE0.toByte(), 0, 16) + "JFIF".toByteArray() + ByteArray(10)
        val app1Payload = "Exif".toByteArray() + byteArrayOf(0, 0) + tiff
        val len = app1Payload.size + 2
        val app1 = byteArrayOf(0xFF.toByte(), 0xE1.toByte(), (len shr 8).toByte(), len.toByte()) + app1Payload
        val sos = byteArrayOf(0xFF.toByte(), 0xDA.toByte(), 0, 2)
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + app0 + app1 + sos
        assertArrayEquals(tiff, PhotoPipeline.extractExif(jpeg))
        assertNull(PhotoPipeline.extractExif(byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + app0 + sos))
        assertNull(PhotoPipeline.extractExif(byteArrayOf(1, 2, 3)))
    }
}
