package com.voidnullvalue.icseelocal.video

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.io.File
import java.io.FileOutputStream

/** Extracts a JPEG thumbnail from a local MP4 (content URI or file path). */
object ClipThumbnailExtractor {
    fun extractToFile(context: Context, videoUri: String, outFile: File): Boolean {
        val retriever = MediaMetadataRetriever()
        return try {
            if (videoUri.startsWith("content:")) {
                retriever.setDataSource(context, Uri.parse(videoUri))
            } else {
                retriever.setDataSource(videoUri)
            }
            val frame = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: return false
            outFile.parentFile?.mkdirs()
            FileOutputStream(outFile).use { frame.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            frame.recycle()
            true
        } catch (_: Exception) {
            false
        } finally {
            runCatching { retriever.release() }
        }
    }
}
