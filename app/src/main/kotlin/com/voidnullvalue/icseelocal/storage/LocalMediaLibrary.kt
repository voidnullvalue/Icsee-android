package com.voidnullvalue.icseelocal.storage

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

data class LocalMediaItem(
    val uri: String,
    val displayName: String,
    val mimeType: String,
    val dateTakenMs: Long,
    val isVideo: Boolean,
)

/**
 * Lists snapshots and downloaded/live recordings saved under the app's
 * Pictures/Movies `iCSeeLocalControl` folders.
 */
object LocalMediaLibrary {
    private const val SUBDIR = "iCSeeLocalControl"

    fun listAll(context: Context): List<LocalMediaItem> {
        val photos = listImages(context)
        val videos = listVideos(context)
        return (photos + videos).sortedByDescending { it.dateTakenMs }
    }

    private fun listImages(context: Context): List<LocalMediaItem> {
        val out = ArrayList<LocalMediaItem>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val projection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.MIME_TYPE,
                MediaStore.Images.Media.DATE_ADDED,
                MediaStore.Images.Media.RELATIVE_PATH,
            )
            val selection = "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
            val args = arrayOf("%$SUBDIR%")
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection, selection, args,
                "${MediaStore.Images.Media.DATE_ADDED} DESC",
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val mimeCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
                val dateCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                while (c.moveToNext()) {
                    val id = c.getLong(idCol)
                    val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                    out += LocalMediaItem(
                        uri = uri.toString(),
                        displayName = c.getString(nameCol) ?: "photo",
                        mimeType = c.getString(mimeCol) ?: "image/jpeg",
                        dateTakenMs = c.getLong(dateCol) * 1000L,
                        isVideo = false,
                    )
                }
            }
        } else {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), SUBDIR)
            dir.listFiles()?.filter { it.isFile && it.extension.lowercase() in listOf("jpg", "jpeg", "png") }
                ?.sortedByDescending { it.lastModified() }
                ?.forEach { f ->
                    out += LocalMediaItem(
                        uri = Uri.fromFile(f).toString(),
                        displayName = f.name,
                        mimeType = "image/jpeg",
                        dateTakenMs = f.lastModified(),
                        isVideo = false,
                    )
                }
        }
        return out
    }

    private fun listVideos(context: Context): List<LocalMediaItem> {
        val out = ArrayList<LocalMediaItem>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val projection = arrayOf(
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.MIME_TYPE,
                MediaStore.Video.Media.DATE_ADDED,
                MediaStore.Video.Media.RELATIVE_PATH,
            )
            val selection = "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?"
            val args = arrayOf("%$SUBDIR%")
            context.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection, selection, args,
                "${MediaStore.Video.Media.DATE_ADDED} DESC",
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val mimeCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.MIME_TYPE)
                val dateCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
                while (c.moveToNext()) {
                    val id = c.getLong(idCol)
                    val uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
                    out += LocalMediaItem(
                        uri = uri.toString(),
                        displayName = c.getString(nameCol) ?: "video",
                        mimeType = c.getString(mimeCol) ?: "video/mp4",
                        dateTakenMs = c.getLong(dateCol) * 1000L,
                        isVideo = true,
                    )
                }
            }
        } else {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), SUBDIR)
            dir.listFiles()?.filter { it.isFile && it.extension.lowercase() == "mp4" }
                ?.sortedByDescending { it.lastModified() }
                ?.forEach { f ->
                    out += LocalMediaItem(
                        uri = Uri.fromFile(f).toString(),
                        displayName = f.name,
                        mimeType = "video/mp4",
                        dateTakenMs = f.lastModified(),
                        isVideo = true,
                    )
                }
        }
        return out
    }
}
