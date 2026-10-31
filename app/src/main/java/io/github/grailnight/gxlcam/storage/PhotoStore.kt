package io.github.grailnight.gxlcam.storage

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** Saves photos into the shared "Pictures/GXL Cam" folder via MediaStore. */
object PhotoStore {
    private const val TAG = "PhotoStore"
    const val ALBUM = "GXL Cam"
    const val MIME_JXL = "image/jxl"

    fun fileName(time: ZonedDateTime, extension: String): String =
        "GXL_" + time.format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS")) + "." + extension

    /**
     * Writes [bytes] as a new image and returns its content URI.
     * Falls back to Downloads if MediaStore refuses the image collection for
     * this MIME type (older platform MIME tables may not know image/jxl).
     */
    fun save(
        context: Context,
        bytes: ByteArray,
        displayName: String,
        mimeType: String,
        dateTakenMillis: Long,
    ): Uri {
        val resolver = context.contentResolver
        val attempts = listOf(
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to
                "${Environment.DIRECTORY_PICTURES}/$ALBUM",
            MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to
                "${Environment.DIRECTORY_DOWNLOADS}/$ALBUM",
        )
        var lastError: Exception? = null
        for ((collection, relativePath) in attempts) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.MediaColumns.DATE_TAKEN, dateTakenMillis)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = try {
                resolver.insert(collection, values)
            } catch (e: Exception) {
                Log.w(TAG, "insert into $collection failed", e)
                lastError = e
                null
            } ?: continue
            try {
                resolver.openOutputStream(uri, "w")?.use { it.write(bytes) }
                    ?: throw IOException("cannot open $uri")
                resolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                    null, null,
                )
                return uri
            } catch (e: Exception) {
                lastError = e
                runCatching { resolver.delete(uri, null, null) }
            }
        }
        throw IOException("Could not save $displayName", lastError)
    }

    fun displayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull() ?: uri.lastPathSegment
}
