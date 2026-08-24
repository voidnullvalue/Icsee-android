package com.voidnullvalue.icseelocal.avtalk

/**
 * Pure I420 crop/rotate/scale helper used by the AVTalk phone-camera uplink.
 *
 * Input and output are packed I420 (Y plane, then U, then V). Target pixels
 * are nearest-neighbour sampled after applying rotation and a centered crop
 * that fills the requested output aspect ratio without stretching.
 */
object I420Transformer {
    fun transform(
        source: ByteArray,
        sourceWidth: Int,
        sourceHeight: Int,
        rotationDegrees: Int,
        targetWidth: Int,
        targetHeight: Int,
    ): ByteArray {
        require(sourceWidth > 0 && sourceHeight > 0 && sourceWidth % 2 == 0 && sourceHeight % 2 == 0) {
            "source dimensions must be positive and even"
        }
        require(targetWidth > 0 && targetHeight > 0 && targetWidth % 2 == 0 && targetHeight % 2 == 0) {
            "target dimensions must be positive and even"
        }
        val rotation = ((rotationDegrees % 360) + 360) % 360
        require(rotation == 0 || rotation == 90 || rotation == 180 || rotation == 270) {
            "rotation must be 0/90/180/270, got $rotationDegrees"
        }
        val expectedSourceSize = sourceWidth * sourceHeight * 3 / 2
        require(source.size >= expectedSourceSize) {
            "I420 source too small: ${source.size}, need $expectedSourceSize"
        }

        val target = ByteArray(targetWidth * targetHeight * 3 / 2)
        val sourceYSize = sourceWidth * sourceHeight
        val sourceChromaWidth = sourceWidth / 2
        val sourceChromaHeight = sourceHeight / 2
        val sourceChromaSize = sourceChromaWidth * sourceChromaHeight
        val sourceUOffset = sourceYSize
        val sourceVOffset = sourceYSize + sourceChromaSize

        val orientedWidth = if (rotation == 90 || rotation == 270) sourceHeight else sourceWidth
        val orientedHeight = if (rotation == 90 || rotation == 270) sourceWidth else sourceHeight
        val targetAspect = targetWidth.toDouble() / targetHeight.toDouble()
        val orientedAspect = orientedWidth.toDouble() / orientedHeight.toDouble()
        val cropWidth: Double
        val cropHeight: Double
        val cropX: Double
        val cropY: Double
        if (orientedAspect > targetAspect) {
            cropHeight = orientedHeight.toDouble()
            cropWidth = cropHeight * targetAspect
            cropX = (orientedWidth - cropWidth) / 2.0
            cropY = 0.0
        } else {
            cropWidth = orientedWidth.toDouble()
            cropHeight = cropWidth / targetAspect
            cropX = 0.0
            cropY = (orientedHeight - cropHeight) / 2.0
        }

        fun sampleSourceCoordinates(targetX: Double, targetY: Double): Pair<Int, Int> {
            val orientedX = cropX + ((targetX + 0.5) * cropWidth / targetWidth) - 0.5
            val orientedY = cropY + ((targetY + 0.5) * cropHeight / targetHeight) - 0.5
            val roundedX = kotlin.math.round(orientedX).toInt().coerceIn(0, orientedWidth - 1)
            val roundedY = kotlin.math.round(orientedY).toInt().coerceIn(0, orientedHeight - 1)
            val (sourceX, sourceY) = when (rotation) {
                0 -> roundedX to roundedY
                90 -> roundedY to (sourceHeight - 1 - roundedX)
                180 -> (sourceWidth - 1 - roundedX) to (sourceHeight - 1 - roundedY)
                else -> (sourceWidth - 1 - roundedY) to roundedX // 270 degrees clockwise
            }
            return sourceX.coerceIn(0, sourceWidth - 1) to sourceY.coerceIn(0, sourceHeight - 1)
        }

        // Luma.
        for (y in 0 until targetHeight) {
            for (x in 0 until targetWidth) {
                val (sx, sy) = sampleSourceCoordinates(x.toDouble(), y.toDouble())
                target[y * targetWidth + x] = source[sy * sourceWidth + sx]
            }
        }

        // Chroma. Map the centre of each target 2x2 luma block into the source,
        // then sample the corresponding source chroma cell.
        val targetYSize = targetWidth * targetHeight
        val targetChromaWidth = targetWidth / 2
        val targetChromaHeight = targetHeight / 2
        val targetChromaSize = targetChromaWidth * targetChromaHeight
        val targetUOffset = targetYSize
        val targetVOffset = targetYSize + targetChromaSize
        for (cy in 0 until targetChromaHeight) {
            for (cx in 0 until targetChromaWidth) {
                val (sx, sy) = sampleSourceCoordinates(cx * 2.0 + 0.5, cy * 2.0 + 0.5)
                val sourceCx = (sx / 2).coerceIn(0, sourceChromaWidth - 1)
                val sourceCy = (sy / 2).coerceIn(0, sourceChromaHeight - 1)
                val sourceChromaIndex = sourceCy * sourceChromaWidth + sourceCx
                val targetChromaIndex = cy * targetChromaWidth + cx
                target[targetUOffset + targetChromaIndex] = source[sourceUOffset + sourceChromaIndex]
                target[targetVOffset + targetChromaIndex] = source[sourceVOffset + sourceChromaIndex]
            }
        }
        return target
    }
}
