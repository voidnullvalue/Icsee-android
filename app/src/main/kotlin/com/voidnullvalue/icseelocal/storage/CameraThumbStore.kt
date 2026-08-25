package com.voidnullvalue.icseelocal.storage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Last-seen JPEG for a saved camera, shown as the Devices-list thumbnail.
 * Written from the live player (periodically + on leave), snapshots, and presets.
 */
class CameraThumbStore(private val context: Context) {
    fun fileFor(cameraId: String): File {
        val dir = File(context.filesDir, "camera_thumbs").also { it.mkdirs() }
        val safe = cameraId.replace(Regex("[^A-Za-z0-9_-]"), "_")
        return File(dir, "$safe.jpg")
    }

    fun pathIfExists(cameraId: String): String? =
        fileFor(cameraId).takeIf { it.exists() && it.length() > 0L }?.absolutePath

    fun saveJpeg(cameraId: String, bitmap: Bitmap) {
        val scaled = scaleToMax(bitmap, MAX_EDGE_PX)
        val outFile = fileFor(cameraId)
        try {
            FileOutputStream(outFile).use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, 75, out)
            }
            // Filesystems may keep the same mtime second; force a change the list can see.
            outFile.setLastModified(System.currentTimeMillis())
            bumpGeneration()
        } finally {
            if (scaled !== bitmap) scaled.recycle()
        }
    }

    fun copyFrom(cameraId: String, src: File) {
        val bmp = BitmapFactory.decodeFile(src.absolutePath) ?: return
        try {
            saveJpeg(cameraId, bmp)
        } finally {
            bmp.recycle()
        }
    }

    fun copyFromUri(cameraId: String, uri: Uri) {
        val bmp = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } ?: return
        try {
            saveJpeg(cameraId, bmp)
        } finally {
            bmp.recycle()
        }
    }

    private fun scaleToMax(bitmap: Bitmap, maxEdge: Int): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= maxEdge) return bitmap
        val scale = maxEdge.toFloat() / longest
        val w = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val h = (bitmap.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, w, h, true)
    }

    companion object {
        private const val MAX_EDGE_PX = 240

        private val _generation = MutableStateFlow(0L)
        /** Bumps whenever any process writes a camera thumb — list screens collect this. */
        val generation: StateFlow<Long> = _generation.asStateFlow()

        fun bumpGeneration() {
            _generation.value = System.currentTimeMillis()
        }
    }
}
