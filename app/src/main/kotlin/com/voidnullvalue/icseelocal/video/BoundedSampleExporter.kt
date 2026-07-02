package com.voidnullvalue.icseelocal.video

import java.io.File
import java.io.FileOutputStream

/**
 * Writes reassembled media units to a file until a byte budget is
 * exhausted, then stops -- a short bounded raw-stream sample for offline
 * framing/codec analysis (e.g. with ffprobe), per the task brief's
 * "If decoding is initially blocked, first implement ... a short bounded
 * sample export." Never buffers the whole stream in memory.
 */
class BoundedSampleExporter(
    private val outputFile: File,
    private val maxBytes: Long = 4 * 1024 * 1024,
) {
    private var written = 0L
    private var stream: FileOutputStream? = FileOutputStream(outputFile)
    val isComplete: Boolean get() = stream == null

    fun offer(bytes: ByteArray) {
        val out = stream ?: return
        if (written >= maxBytes) {
            close()
            return
        }
        val remaining = (maxBytes - written).coerceAtMost(bytes.size.toLong()).toInt()
        out.write(bytes, 0, remaining)
        written += remaining
        if (written >= maxBytes) close()
    }

    fun close() {
        runCatching { stream?.close() }
        stream = null
    }
}
