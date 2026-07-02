package com.voidnullvalue.icseelocal.video

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.view.PixelCopy
import android.view.SurfaceView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

sealed class SnapshotResult {
    data class Success(val location: String) : SnapshotResult()
    data class Failure(val reason: String) : SnapshotResult()
}

/**
 * Captures the currently-rendered video frame from a [SurfaceView] via
 * [PixelCopy] (not from the decoder's internal buffers directly -- simpler
 * and correct regardless of the eventual decoder pixel format) and saves it
 * through MediaStore with a timestamped filename. Encoding and storage
 * happen off the decoder/render thread, on a dedicated [HandlerThread] plus
 * [Dispatchers.IO].
 */
class SnapshotCapture(private val context: Context) {
    private val handlerThread = HandlerThread("snapshot-pixelcopy").apply { start() }
    private val handler = Handler(handlerThread.looper)

    suspend fun captureFromSurfaceView(surfaceView: SurfaceView, cameraName: String): SnapshotResult {
        val bitmap = try {
            copySurface(surfaceView)
        } catch (e: Exception) {
            return SnapshotResult.Failure("capture failed: ${e.message}")
        }
        return withContext(Dispatchers.IO) {
            try {
                saveBitmap(bitmap, cameraName)
            } finally {
                bitmap.recycle()
            }
        }
    }

    private suspend fun copySurface(surfaceView: SurfaceView): Bitmap {
        require(surfaceView.width > 0 && surfaceView.height > 0) { "surface has no size yet" }
        val bitmap = Bitmap.createBitmap(surfaceView.width, surfaceView.height, Bitmap.Config.ARGB_8888)
        val deferred = CompletableDeferred<Boolean>()
        PixelCopy.request(surfaceView, bitmap, { result -> deferred.complete(result == PixelCopy.SUCCESS) }, handler)
        if (!deferred.await()) throw IllegalStateException("PixelCopy did not succeed")
        return bitmap
    }

    private fun saveBitmap(bitmap: Bitmap, cameraName: String): SnapshotResult {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val safeName = cameraName.replace(Regex("[^A-Za-z0-9_-]"), "_")
        val filename = "icsee_${safeName}_$timestamp.jpg"
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveViaMediaStoreScoped(bitmap, filename)
        } else {
            saveViaLegacyFile(bitmap, filename)
        }
    }

    private fun saveViaMediaStoreScoped(bitmap: Bitmap, filename: String): SnapshotResult {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/iCSeeLocalControl")
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return SnapshotResult.Failure("MediaStore insert failed")
        return try {
            val opened = resolver.openOutputStream(uri)?.use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out) }
            if (opened != true) return SnapshotResult.Failure("could not write snapshot")
            SnapshotResult.Success(uri.toString())
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            SnapshotResult.Failure("save failed: ${e.message}")
        }
    }

    private fun saveViaLegacyFile(bitmap: Bitmap, filename: String): SnapshotResult {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "iCSeeLocalControl")
        if (!dir.exists() && !dir.mkdirs()) return SnapshotResult.Failure("could not create $dir")
        val file = File(dir, filename)
        return try {
            FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out) }
            SnapshotResult.Success(file.absolutePath)
        } catch (e: Exception) {
            SnapshotResult.Failure("save failed: ${e.message}")
        }
    }

    fun release() {
        handlerThread.quitSafely()
    }
}
