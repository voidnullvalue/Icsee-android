package com.voidnullvalue.icseelocal.avtalk

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.RectF
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.Image
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Range
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.view.WindowManager
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.math.max
import kotlinx.coroutines.suspendCancellableCoroutine

/** Which physical phone camera supplies AVTalk video. */
enum class AvTalkLens { FRONT, BACK }

data class EncodedHevcFrame(
    val annexB: ByteArray,
    val keyFrame: Boolean,
)

/**
 * Phone-camera source for AVTalk.
 *
 * Camera2 produces YUV_420_888 frames. They are packed to I420, rotated and
 * center-cropped to the stock 240x320 portrait profile, then handed to a
 * hardware/software HEVC MediaCodec encoder at 10 fps. Encoded output is
 * normalized to Annex-B before it reaches [AvTalkClient].
 */
class PhoneCameraHevcSource(
    context: Context,
    private val profile: AvTalkProfile,
    private val onFrame: (EncodedHevcFrame) -> Unit,
    private val onError: (Throwable) -> Unit,
) {
    private val appContext = context.applicationContext
    private val cameraManager = appContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    @Volatile
    private var running = false
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var previewSurface: Surface? = null
    private var codec: MediaCodec? = null
    private var codecColorFormat: Int = 0
    private var codecConfig: ByteArray = ByteArray(0)
    private var lastQueuedTimestampNs: Long = Long.MIN_VALUE
    private var lastPtsUs: Long = -1

    val isRunning: Boolean get() = running

    @SuppressLint("MissingPermission")
    suspend fun start(textureView: TextureView, lens: AvTalkLens) {
        check(!running) { "phone camera source already running" }
        require(
            ContextCompat.checkSelfPermission(appContext, android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED,
        ) { "CAMERA permission not granted" }
        require(textureView.isAvailable) { "camera preview surface is not available" }

        val thread = HandlerThread("avtalk-camera").also { it.start() }
        handlerThread = thread
        handler = Handler(thread.looper)
        try {
            val selection = selectCamera(lens)
            val captureSize = chooseCaptureSize(selection.characteristics)
            val rotation = relativeRotation(selection.characteristics, lens, textureView)
            configurePreviewTransform(textureView, captureSize, rotation, lens == AvTalkLens.FRONT)
            configureEncoder()

            val reader = ImageReader.newInstance(
                captureSize.width,
                captureSize.height,
                ImageFormat.YUV_420_888,
                3,
            )
            imageReader = reader
            reader.setOnImageAvailableListener({ r -> onImageAvailable(r, rotation) }, handler)

            val surfaceTexture = checkNotNull(textureView.surfaceTexture)
            surfaceTexture.setDefaultBufferSize(captureSize.width, captureSize.height)
            val preview = Surface(surfaceTexture)
            previewSurface = preview

            val device = openCamera(selection.cameraId, checkNotNull(handler))
            cameraDevice = device
            val session = createCaptureSession(device, listOf(preview, reader.surface), checkNotNull(handler))
            captureSession = session
            val request = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                addTarget(preview)
                addTarget(reader.surface)
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                chooseAeFpsRange(selection.characteristics)?.let {
                    set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it)
                }
            }.build()
            session.setRepeatingRequest(request, null, handler)
            running = true
        } catch (t: Throwable) {
            stop()
            throw t
        }
    }

    /** Ask MediaCodec for the next encoded frame to be an IDR/sync frame. */
    fun requestKeyFrame() {
        val h = handler ?: return
        h.post {
            val c = codec ?: return@post
            runCatching {
                c.setParameters(Bundle().apply {
                    putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
                })
            }
        }
    }

    @Synchronized
    fun stop() {
        if (!running && handlerThread == null) return
        running = false
        runCatching { captureSession?.stopRepeating() }
        runCatching { captureSession?.abortCaptures() }
        runCatching { captureSession?.close() }
        captureSession = null
        runCatching { cameraDevice?.close() }
        cameraDevice = null
        runCatching { imageReader?.setOnImageAvailableListener(null, null) }
        runCatching { imageReader?.close() }
        imageReader = null
        runCatching { previewSurface?.release() }
        previewSurface = null
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
        codecConfig = ByteArray(0)
        lastQueuedTimestampNs = Long.MIN_VALUE
        lastPtsUs = -1
        handler = null
        handlerThread?.quitSafely()
        handlerThread = null
    }

    private data class CameraSelection(
        val cameraId: String,
        val characteristics: CameraCharacteristics,
    )

    private fun selectCamera(lens: AvTalkLens): CameraSelection {
        val wanted = if (lens == AvTalkLens.FRONT) {
            CameraCharacteristics.LENS_FACING_FRONT
        } else {
            CameraCharacteristics.LENS_FACING_BACK
        }
        val exact = cameraManager.cameraIdList.firstNotNullOfOrNull { id ->
            val characteristics = cameraManager.getCameraCharacteristics(id)
            if (characteristics.get(CameraCharacteristics.LENS_FACING) == wanted) {
                CameraSelection(id, characteristics)
            } else {
                null
            }
        }
        if (exact != null) return exact
        val fallbackId = cameraManager.cameraIdList.firstOrNull()
            ?: error("this phone has no Camera2 camera")
        return CameraSelection(fallbackId, cameraManager.getCameraCharacteristics(fallbackId))
    }

    private fun chooseCaptureSize(characteristics: CameraCharacteristics): Size {
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: error("camera has no stream configuration map")
        val sizes = map.getOutputSizes(ImageFormat.YUV_420_888)?.toList().orEmpty()
        require(sizes.isNotEmpty()) { "camera exposes no YUV_420_888 output" }
        val desiredArea = 320 * 240
        return sizes.minByOrNull { size ->
            val ratio = max(size.width, size.height).toDouble() / minOf(size.width, size.height).toDouble()
            val aspectPenalty = abs(ratio - (4.0 / 3.0)) * 1_000_000.0
            val tooSmallPenalty = if (max(size.width, size.height) < 320 || minOf(size.width, size.height) < 240) {
                10_000_000.0
            } else {
                0.0
            }
            aspectPenalty + tooSmallPenalty + abs(size.width * size.height - desiredArea).toDouble()
        } ?: sizes.first()
    }

    private fun relativeRotation(
        characteristics: CameraCharacteristics,
        lens: AvTalkLens,
        textureView: TextureView,
    ): Int {
        val sensor = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        @Suppress("DEPRECATION")
        val displayRotation = textureView.display?.rotation
            ?: (appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation
        val deviceDegrees = when (displayRotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        return if (lens == AvTalkLens.FRONT) {
            (sensor + deviceDegrees) % 360
        } else {
            (sensor - deviceDegrees + 360) % 360
        }
    }

    private fun chooseAeFpsRange(characteristics: CameraCharacteristics): Range<Int>? {
        val ranges = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?: return null
        val target = profile.fps
        return ranges.minByOrNull { range ->
            val misses = if (target in range.lower..range.upper) 0 else 1000
            misses + abs(range.lower - target) + abs(range.upper - target)
        }
    }

    private fun configurePreviewTransform(
        textureView: TextureView,
        bufferSize: Size,
        rotation: Int,
        mirror: Boolean,
    ) {
        val viewWidth = textureView.width.toFloat().coerceAtLeast(1f)
        val viewHeight = textureView.height.toFloat().coerceAtLeast(1f)
        val rotated = rotation == 90 || rotation == 270
        val bufferWidth = if (rotated) bufferSize.height.toFloat() else bufferSize.width.toFloat()
        val bufferHeight = if (rotated) bufferSize.width.toFloat() else bufferSize.height.toFloat()
        val viewRect = RectF(0f, 0f, viewWidth, viewHeight)
        val bufferRect = RectF(0f, 0f, bufferWidth, bufferHeight)
        val centerX = viewRect.centerX()
        val centerY = viewRect.centerY()
        bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
        val matrix = Matrix()
        matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
        val scale = max(viewWidth / bufferWidth, viewHeight / bufferHeight)
        matrix.postScale(scale, scale, centerX, centerY)
        matrix.postRotate(rotation.toFloat(), centerX, centerY)
        if (mirror) matrix.postScale(-1f, 1f, centerX, centerY)
        textureView.setTransform(matrix)
    }

    private fun configureEncoder() {
        val mime = MediaFormat.MIMETYPE_VIDEO_HEVC
        val codecInfo = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull { info ->
            if (!info.isEncoder || info.supportedTypes.none { it.equals(mime, ignoreCase = true) }) return@firstOrNull false
            runCatching {
                info.getCapabilitiesForType(mime).videoCapabilities
                    .areSizeAndRateSupported(profile.width, profile.height, profile.fps.toDouble())
            }.getOrDefault(false)
        } ?: error("no HEVC encoder supports ${profile.width}x${profile.height}@${profile.fps}fps")
        val capabilities = codecInfo.getCapabilitiesForType(mime)
        codecColorFormat = listOf(
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar,
        ).firstOrNull { wanted -> capabilities.colorFormats.contains(wanted) }
            ?: error("HEVC encoder ${codecInfo.name} has no supported YUV420 byte-buffer input")

        val format = MediaFormat.createVideoFormat(mime, profile.width, profile.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, codecColorFormat)
            setInteger(MediaFormat.KEY_BIT_RATE, 300_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, profile.fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            if (capabilities.encoderCapabilities.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)) {
                setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            }
        }
        codec = MediaCodec.createByCodecName(codecInfo.name).also { c ->
            c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            c.start()
        }
    }

    private fun onImageAvailable(reader: ImageReader, rotation: Int) {
        val image = runCatching { reader.acquireLatestImage() }.getOrNull() ?: return
        try {
            if (!running) return
            val timestampNs = image.timestamp.takeIf { it > 0 } ?: System.nanoTime()
            val minimumSpacingNs = 1_000_000_000L / profile.fps
            if (lastQueuedTimestampNs != Long.MIN_VALUE && timestampNs - lastQueuedTimestampNs < minimumSpacingNs) {
                drainEncoder()
                return
            }
            val packed = imageToI420(image)
            val transformed = I420Transformer.transform(
                packed,
                image.width,
                image.height,
                rotation,
                profile.width,
                profile.height,
            )
            val c = codec ?: return
            val inputIndex = c.dequeueInputBuffer(0)
            if (inputIndex >= 0) {
                val ptsUs = max(timestampNs / 1000L, lastPtsUs + 1)
                val inputImage = c.getInputImage(inputIndex)
                if (inputImage != null) {
                    // Prefer Image planes whenever the codec exposes them: they carry
                    // the encoder's real row/pixel strides, avoiding assumptions about
                    // vendor-specific padding in otherwise-planar byte buffers.
                    writeI420ToImage(transformed, profile.width, profile.height, inputImage)
                } else {
                    when (codecColorFormat) {
                        MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible,
                        MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar -> {
                            writeDirectInput(c, inputIndex, transformed)
                        }
                        MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar -> {
                            writeDirectInput(c, inputIndex, i420ToNv12(transformed, profile.width, profile.height))
                        }
                        else -> error("unexpected MediaCodec color format $codecColorFormat")
                    }
                }
                c.queueInputBuffer(inputIndex, 0, transformed.size, ptsUs, 0)
                lastPtsUs = ptsUs
                lastQueuedTimestampNs = timestampNs
            }
            drainEncoder()
        } catch (t: Throwable) {
            if (running) {
                running = false
                onError(t)
            }
        } finally {
            image.close()
        }
    }

    private fun writeDirectInput(codec: MediaCodec, inputIndex: Int, payload: ByteArray) {
        val buffer = codec.getInputBuffer(inputIndex) ?: error("HEVC encoder returned no input buffer")
        require(buffer.capacity() >= payload.size) {
            "HEVC input buffer too small: ${buffer.capacity()} < ${payload.size}"
        }
        buffer.clear()
        buffer.put(payload)
    }

    private fun drainEncoder() {
        val c = codec ?: return
        val info = MediaCodec.BufferInfo()
        while (true) {
            when (val outputIndex = c.dequeueOutputBuffer(info, 0)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> return
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    codecConfig = extractCodecConfig(c.outputFormat)
                }
                else -> if (outputIndex >= 0) {
                    val output = c.getOutputBuffer(outputIndex)
                    if (output != null && info.size > 0) {
                        val bytes = ByteArray(info.size)
                        val duplicate = output.duplicate()
                        duplicate.position(info.offset)
                        duplicate.limit(info.offset + info.size)
                        duplicate.get(bytes)
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            codecConfig = HevcAnnexB.normalize(bytes)
                        } else {
                            val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                            var annexB = HevcAnnexB.normalize(bytes)
                            if (key && codecConfig.isNotEmpty() && !HevcAnnexB.containsParameterSets(annexB)) {
                                annexB = codecConfig + annexB
                            }
                            onFrame(EncodedHevcFrame(annexB, key))
                        }
                    }
                    c.releaseOutputBuffer(outputIndex, false)
                }
            }
        }
    }

    private fun extractCodecConfig(format: MediaFormat): ByteArray {
        val out = ByteArrayOutputStream()
        for (key in listOf("csd-0", "csd-1", "csd-2")) {
            val data = runCatching { format.getByteBuffer(key) }.getOrNull() ?: continue
            val bytes = ByteArray(data.remaining())
            data.duplicate().get(bytes)
            out.write(HevcAnnexB.normalize(bytes))
        }
        return out.toByteArray()
    }

    @SuppressLint("MissingPermission")
    private suspend fun openCamera(cameraId: String, handler: Handler): CameraDevice =
        suspendCancellableCoroutine { continuation ->
            cameraManager.openCamera(
                cameraId,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        if (continuation.isActive) continuation.resume(camera) else camera.close()
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        camera.close()
                        if (continuation.isActive) {
                            continuation.resumeWithException(IllegalStateException("phone camera disconnected"))
                        } else if (running) {
                            onError(IllegalStateException("phone camera disconnected"))
                        }
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        camera.close()
                        val failure = IllegalStateException("phone camera error $error")
                        if (continuation.isActive) continuation.resumeWithException(failure) else if (running) onError(failure)
                    }
                },
                handler,
            )
        }

    @Suppress("DEPRECATION")
    private suspend fun createCaptureSession(
        device: CameraDevice,
        surfaces: List<Surface>,
        handler: Handler,
    ): CameraCaptureSession = suspendCancellableCoroutine { continuation ->
        device.createCaptureSession(
            surfaces,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (continuation.isActive) continuation.resume(session) else session.close()
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    session.close()
                    if (continuation.isActive) {
                        continuation.resumeWithException(IllegalStateException("phone camera capture session configuration failed"))
                    }
                }
            },
            handler,
        )
    }

    private fun imageToI420(image: Image): ByteArray {
        require(image.format == ImageFormat.YUV_420_888) { "expected YUV_420_888 image" }
        val width = image.width
        val height = image.height
        val output = ByteArray(width * height * 3 / 2)
        copyImagePlane(image.planes[0], width, height, output, 0)
        val chromaWidth = width / 2
        val chromaHeight = height / 2
        val ySize = width * height
        val chromaSize = chromaWidth * chromaHeight
        copyImagePlane(image.planes[1], chromaWidth, chromaHeight, output, ySize)
        copyImagePlane(image.planes[2], chromaWidth, chromaHeight, output, ySize + chromaSize)
        return output
    }

    private fun copyImagePlane(
        plane: Image.Plane,
        width: Int,
        height: Int,
        output: ByteArray,
        outputOffset: Int,
    ) {
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        for (y in 0 until height) {
            val rowStart = y * rowStride
            for (x in 0 until width) {
                output[outputOffset + y * width + x] = buffer.get(rowStart + x * pixelStride)
            }
        }
    }

    private fun writeI420ToImage(source: ByteArray, width: Int, height: Int, image: Image) {
        val ySize = width * height
        val chromaWidth = width / 2
        val chromaHeight = height / 2
        val chromaSize = chromaWidth * chromaHeight
        writeImagePlane(source, 0, width, height, image.planes[0])
        writeImagePlane(source, ySize, chromaWidth, chromaHeight, image.planes[1])
        writeImagePlane(source, ySize + chromaSize, chromaWidth, chromaHeight, image.planes[2])
    }

    private fun writeImagePlane(
        source: ByteArray,
        sourceOffset: Int,
        width: Int,
        height: Int,
        plane: Image.Plane,
    ) {
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        for (y in 0 until height) {
            val rowStart = y * rowStride
            for (x in 0 until width) {
                buffer.put(rowStart + x * pixelStride, source[sourceOffset + y * width + x])
            }
        }
    }

    private fun i420ToNv12(source: ByteArray, width: Int, height: Int): ByteArray {
        val ySize = width * height
        val chromaSize = ySize / 4
        val output = ByteArray(source.size)
        source.copyInto(output, 0, 0, ySize)
        for (i in 0 until chromaSize) {
            output[ySize + i * 2] = source[ySize + i]
            output[ySize + i * 2 + 1] = source[ySize + chromaSize + i]
        }
        return output
    }
}

private object HevcAnnexB {
    private val START_CODE = byteArrayOf(0, 0, 0, 1)

    fun normalize(data: ByteArray): ByteArray {
        if (data.isEmpty()) return data
        if (startsWithStartCode(data)) return data
        val lengthPrefixed = parseLengthPrefixed(data)
        return lengthPrefixed ?: (START_CODE + data)
    }

    fun containsParameterSets(data: ByteArray): Boolean {
        var i = 0
        var vps = false
        var sps = false
        var pps = false
        while (i < data.size - 4) {
            val startLength = when {
                i + 4 <= data.size && data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte() -> 4
                i + 3 <= data.size && data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 1.toByte() -> 3
                else -> 0
            }
            if (startLength > 0 && i + startLength < data.size) {
                when ((data[i + startLength].toInt() ushr 1) and 0x3f) {
                    32 -> vps = true
                    33 -> sps = true
                    34 -> pps = true
                }
                if (vps && sps && pps) return true
                i += startLength
            } else {
                i++
            }
        }
        return false
    }

    private fun startsWithStartCode(data: ByteArray): Boolean =
        data.size >= 4 && data[0] == 0.toByte() && data[1] == 0.toByte() &&
            ((data[2] == 1.toByte()) || (data[2] == 0.toByte() && data[3] == 1.toByte()))

    private fun parseLengthPrefixed(data: ByteArray): ByteArray? {
        if (data.size < 5) return null
        var offset = 0
        val out = ByteArrayOutputStream(data.size + 16)
        var count = 0
        while (offset + 4 <= data.size) {
            val length = readBeInt(data, offset)
            offset += 4
            if (length <= 0 || length > data.size - offset) return null
            out.write(START_CODE)
            out.write(data, offset, length)
            offset += length
            count++
        }
        return if (offset == data.size && count > 0) out.toByteArray() else null
    }

    private fun readBeInt(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xff) shl 24) or
            ((data[offset + 1].toInt() and 0xff) shl 16) or
            ((data[offset + 2].toInt() and 0xff) shl 8) or
            (data[offset + 3].toInt() and 0xff)
}
