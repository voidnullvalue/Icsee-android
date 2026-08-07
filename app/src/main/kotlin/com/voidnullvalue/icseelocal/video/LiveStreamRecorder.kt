package com.voidnullvalue.icseelocal.video

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Handler
import android.os.HandlerThread
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import androidx.media3.ui.PlayerView
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Records what the user sees on the live [PlayerView] by periodically
 * capturing frames (TextureView bitmap or SurfaceView PixelCopy) and encoding
 * H.264 into an MP4. Phone-side recording of the viewed stream — not SD-card
 * OPPlayBack.
 */
class LiveStreamRecorder(private val context: Context) {
    private val running = AtomicBoolean(false)
    private var worker: Thread? = null
    private var startedAtMs = 0L

    val isRecording: Boolean get() = running.get()
    val elapsedMs: Long get() = if (running.get()) System.currentTimeMillis() - startedAtMs else 0L

    fun start(playerView: PlayerView, outFile: File, width: Int = 1280, height: Int = 720, fps: Int = 12): Boolean {
        if (!running.compareAndSet(false, true)) return false
        startedAtMs = System.currentTimeMillis()
        worker = thread(name = "live-stream-recorder", isDaemon = true) {
            try {
                recordLoop(playerView, outFile, width, height, fps)
            } catch (_: Exception) {
                runCatching { if (outFile.exists()) outFile.delete() }
            } finally {
                running.set(false)
            }
        }
        return true
    }

    fun stop(): File? {
        if (!running.getAndSet(false)) return null
        worker?.join(3_000)
        worker = null
        return null // caller already knows outFile path
    }

    private fun recordLoop(playerView: PlayerView, outFile: File, width: Int, height: Int, fps: Int) {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar)
            setInteger(MediaFormat.KEY_BIT_RATE, 2_500_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()

        val muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track = -1
        var muxerStarted = false
        val bufferInfo = MediaCodec.BufferInfo()
        val frameIntervalMs = 1000L / fps
        var ptsUs = 0L

        try {
            while (running.get()) {
                val frameStart = System.currentTimeMillis()
                val bitmap = captureFrame(playerView, width, height)
                if (bitmap != null) {
                    val nv12 = bitmapToNv12(bitmap, width, height)
                    bitmap.recycle()
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val inBuf = codec.getInputBuffer(inIndex)!!
                        inBuf.clear()
                        inBuf.put(nv12)
                        codec.queueInputBuffer(inIndex, 0, nv12.size, ptsUs, 0)
                        ptsUs += frameIntervalMs * 1000L
                    }
                }
                while (true) {
                    val outIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
                    when {
                        outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            if (!muxerStarted) {
                                track = muxer.addTrack(codec.outputFormat)
                                muxer.start()
                                muxerStarted = true
                            }
                        }
                        outIndex >= 0 -> {
                            if (muxerStarted && bufferInfo.size > 0) {
                                val outBuf = codec.getOutputBuffer(outIndex)!!
                                muxer.writeSampleData(track, outBuf, bufferInfo)
                            }
                            codec.releaseOutputBuffer(outIndex, false)
                        }
                        else -> break
                    }
                }
                val slept = frameIntervalMs - (System.currentTimeMillis() - frameStart)
                if (slept > 0) Thread.sleep(slept)
            }
            val inIndex = codec.dequeueInputBuffer(10_000)
            if (inIndex >= 0) {
                codec.queueInputBuffer(inIndex, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            }
            var eos = false
            while (!eos) {
                val outIndex = codec.dequeueOutputBuffer(bufferInfo, 50_000)
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED && !muxerStarted) {
                    track = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    muxerStarted = true
                } else if (outIndex >= 0) {
                    if (muxerStarted && bufferInfo.size > 0) {
                        muxer.writeSampleData(track, codec.getOutputBuffer(outIndex)!!, bufferInfo)
                    }
                    eos = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    codec.releaseOutputBuffer(outIndex, false)
                } else break
            }
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            if (muxerStarted) runCatching { muxer.stop() }
            runCatching { muxer.release() }
        }
    }

    private fun captureFrame(playerView: PlayerView, width: Int, height: Int): Bitmap? {
        val texture = findTextureView(playerView)
        if (texture != null) {
            val bmp = texture.bitmap ?: return null
            return Bitmap.createScaledBitmap(bmp, width, height, true).also {
                if (it !== bmp) bmp.recycle()
            }
        }
        val surface = findSurfaceView(playerView) ?: return null
        if (surface.width <= 0 || surface.height <= 0) return null
        val full = Bitmap.createBitmap(surface.width, surface.height, Bitmap.Config.ARGB_8888)
        val ok = copySurfaceSync(surface, full)
        if (!ok) {
            full.recycle()
            return null
        }
        val scaled = Bitmap.createScaledBitmap(full, width, height, true)
        if (scaled !== full) full.recycle()
        return scaled
    }

    private fun copySurfaceSync(surfaceView: SurfaceView, bitmap: Bitmap): Boolean {
        val thread = HandlerThread("pixelcopy-rec").apply { start() }
        return try {
            val handler = Handler(thread.looper)
            val lock = Object()
            var success = false
            synchronized(lock) {
                PixelCopy.request(surfaceView, bitmap, { result ->
                    synchronized(lock) {
                        success = result == PixelCopy.SUCCESS
                        lock.notifyAll()
                    }
                }, handler)
                lock.wait(500)
            }
            success
        } catch (_: Exception) {
            false
        } finally {
            thread.quitSafely()
        }
    }

    private fun findTextureView(root: View): TextureView? {
        if (root is TextureView) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                findTextureView(root.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    private fun findSurfaceView(root: View): SurfaceView? {
        if (root is SurfaceView) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                findSurfaceView(root.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    /** Naive ARGB → NV12 (semi-planar YUV420). */
    private fun bitmapToNv12(bitmap: Bitmap, width: Int, height: Int): ByteArray {
        val argb = IntArray(width * height)
        bitmap.getPixels(argb, 0, width, 0, 0, width, height)
        val ySize = width * height
        val out = ByteArray(ySize + ySize / 2)
        var yIndex = 0
        var uvIndex = ySize
        for (j in 0 until height) {
            for (i in 0 until width) {
                val c = argb[j * width + i]
                val r = (c shr 16) and 0xff
                val g = (c shr 8) and 0xff
                val b = c and 0xff
                val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                out[yIndex++] = y.coerceIn(0, 255).toByte()
                if (j % 2 == 0 && i % 2 == 0) {
                    val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                    val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                    out[uvIndex++] = u.coerceIn(0, 255).toByte()
                    out[uvIndex++] = v.coerceIn(0, 255).toByte()
                }
            }
        }
        return out
    }
}
