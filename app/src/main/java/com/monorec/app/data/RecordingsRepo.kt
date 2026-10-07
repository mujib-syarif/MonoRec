package com.monorec.app.data

import android.content.Context
import android.net.Uri
import android.provider.MediaStore

data class Recording(
    val id: Long,
    val uri: Uri,
    val name: String,
    val sizeBytes: Long,
    val durationMs: Long,
    val dateMs: Long
)

/** Membaca / menghapus rekaman yang tersimpan di Movies/MonoRec. */
object RecordingsRepo {

    fun load(context: Context): List<Recording> {
        val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.DATE_ADDED
        )
        val selection = "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?"
        val args = arrayOf("Movies/MonoRec%")
        val order = "${MediaStore.Video.Media.DATE_ADDED} DESC"

        val result = ArrayList<Recording>()
        try {
            context.contentResolver.query(collection, projection, selection, args, order)?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val sizeCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                val durCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                val dateCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
                while (c.moveToNext()) {
                    val id = c.getLong(idCol)
                    result += Recording(
                        id = id,
                        uri = Uri.withAppendedPath(collection, id.toString()),
                        name = c.getString(nameCol) ?: "rekaman.mp4",
                        sizeBytes = c.getLong(sizeCol),
                        durationMs = c.getLong(durCol),
                        dateMs = c.getLong(dateCol) * 1000L
                    )
                }
            }
        } catch (_: Exception) {
        }
        return result
    }

    fun delete(context: Context, rec: Recording): Boolean = try {
        context.contentResolver.delete(rec.uri, null, null) > 0
    } catch (_: Exception) {
        false
    }
}
