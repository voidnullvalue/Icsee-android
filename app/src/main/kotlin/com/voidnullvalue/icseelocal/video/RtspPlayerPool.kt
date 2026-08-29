package com.voidnullvalue.icseelocal.video

import android.content.Context
import androidx.media3.common.util.UnstableApi
import java.util.LinkedHashMap

/**
 * Scaffold for Phase-2 concurrent multi-view (PR-F).
 *
 * Phase 1 ([com.voidnullvalue.icseelocal.ui.grid.CameraGridViewModel]) keeps a
 * single focused [RtspStreamManager]. This pool will own up to [maxPlayers]
 * managers keyed by camera id, pausing off-screen tiles once true concurrent
 * decode is enabled after device perf validation.
 *
 * Caps: phones typically [PHONE_MAX] (4), tablets [TABLET_MAX] (9).
 */
@UnstableApi
class RtspPlayerPool(
    private val context: Context,
    private val maxPlayers: Int = PHONE_MAX,
) {
    private val players = object : LinkedHashMap<String, RtspStreamManager>(maxPlayers, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, RtspStreamManager>?): Boolean {
            if (size <= maxPlayers) return false
            eldest?.value?.release()
            return true
        }
    }

    @Synchronized
    fun acquire(cameraId: String): RtspStreamManager {
        players[cameraId]?.let { return it }
        val created = RtspStreamManager(context.applicationContext)
        players[cameraId] = created
        return created
    }

    @Synchronized
    fun get(cameraId: String): RtspStreamManager? = players[cameraId]

    @Synchronized
    fun release(cameraId: String) {
        players.remove(cameraId)?.release()
    }

    @Synchronized
    fun pauseAllExcept(cameraId: String?) {
        players.forEach { (id, manager) ->
            manager.setPlayWhenReady(id == cameraId)
        }
    }

    @Synchronized
    fun releaseAll() {
        players.values.forEach { it.release() }
        players.clear()
    }

    companion object {
        const val PHONE_MAX = 4
        const val TABLET_MAX = 9

        fun recommendedMax(isTablet: Boolean): Int = if (isTablet) TABLET_MAX else PHONE_MAX
    }
}
