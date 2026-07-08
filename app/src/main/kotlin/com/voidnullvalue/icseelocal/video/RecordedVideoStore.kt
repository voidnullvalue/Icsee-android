package com.voidnullvalue.icseelocal.video

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

sealed class SavedVideo {
    /** [uri] is a content:// (scoped) or file path string, openable via ACTION_VIEW. */
    data class Success(val uri: String, val label: String) : SavedVideo()
    data class Failure(val reason: String) : SavedVideo()
}

/**
 * Saves a finished MP4 into the device's shared Movies collection so it shows up
 * as a normal downloaded video the user can open/share in any player. Mirrors
 * [SnapshotCapture]'s scoped (API 29+) / legacy split.
 */
object RecordedVideoStore {
    private const val SUBDIR = "iCSeeLocalControl"

    fun save(context: Context, tempMp4: File, displayName: String): SavedVideo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveScoped(context, tempMp4, displayName)
        } else {
            saveLegacy(tempMp4, displayName)
        }

    private fun saveScoped(context: Context, tempMp4: File, displayName: String): SavedVideo {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/$SUBDIR")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri: Uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: return SavedVideo.Failure("MediaStore insert failed")
        return try {
            resolver.openOutputStream(uri)?.use { out -> tempMp4.inputStream().use { it.copyTo(out) } }
                ?: return SavedVideo.Failure("could not open output stream")
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            SavedVideo.Success(uri.toString(), "Movies/$SUBDIR/$displayName")
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            SavedVideo.Failure("save failed: ${e.message}")
        }
    }

    private fun saveLegacy(tempMp4: File, displayName: String): SavedVideo {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), SUBDIR)
        if (!dir.exists() && !dir.mkdirs()) return SavedVideo.Failure("could not create $dir")
        val out = File(dir, displayName)
        return try {
            tempMp4.inputStream().use { input -> out.outputStream().use { input.copyTo(it) } }
            SavedVideo.Success(out.absolutePath, out.absolutePath)
        } catch (e: Exception) {
            SavedVideo.Failure("save failed: ${e.message}")
        }
    }
}
