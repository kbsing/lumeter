package com.lumeter.camera

import android.hardware.camera2.CaptureResult
import android.os.SystemClock

/**
 * Bridges Camera2Interop's session capture callback to per-frame analysis.
 *
 * CameraX (as of 1.4.x) has no public per-frame CaptureResult accessor for ImageAnalysis,
 * so results arrive via a session-level callback on a CameraX-internal thread and are
 * paired with ImageProxy frames by SENSOR_TIMESTAMP (the same timebase CameraX reports
 * through ImageInfo.getTimestamp). This mirrors lightstop's TimestampedResultPairer
 * contract: an unmatched frame is dropped, never metered with stale metadata.
 *
 * A stale entry that never got a matching analysis frame is evicted by age so a paused
 * analysis stream cannot pin memory.
 */
class CaptureResultStore(
    private val capacity: Int = 8,
    private val maxAgeMillis: Long = 2_000L,
) {
    private val lock = Any()
    private val map = LinkedHashMap<Long, Timestamped>()

    private class Timestamped(val result: CaptureResult, val storedAtMillis: Long)

    fun offer(result: CaptureResult) {
        val timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: return
        val now = SystemClock.elapsedRealtime()
        synchronized(lock) {
            map[timestamp] = Timestamped(result, now)
            while (map.size > capacity) {
                map.remove(map.keys.first())
            }
            val iterator = map.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (now - entry.value.storedAtMillis > maxAgeMillis) {
                    iterator.remove()
                } else {
                    break // LinkedHashMap iterates in insertion order; the rest are newer
                }
            }
        }
    }

    fun take(frameTimestampNanos: Long): CaptureResult? = synchronized(lock) {
        val result = map.remove(frameTimestampNanos)?.result
        if (result == null) missedCount++
        result
    }

    fun clear() = synchronized(lock) { map.clear() }

    @Volatile var missedCount: Int = 0
        private set
}
