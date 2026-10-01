package com.lumeter.camera

import com.lumeter.core.meter.EvMath
import com.lumeter.core.meter.FusedReading
import com.lumeter.core.meter.FrameExposure
import com.lumeter.core.meter.MeteringFusion
import com.lumeter.core.meter.MeteringFrameStat
import com.lumeter.core.meter.MeteringSource
import kotlin.math.abs

/**
 * Stability gate + median fusion shared by the YUV and RAW metering sources.
 *
 * A reading is accepted only while the camera's exposure has been stable for
 * [STABLE_FRAME_WINDOW] frames within a small tolerance (HAL jitter); stable frames
 * feed a ring whose median is published, suppressed below [PUBLISH_DELTA_EV] of change
 * so the UI never flickers. One class owns the policy so both sources behave alike.
 */
class ReadingAccumulator(private val source: MeteringSource) {

    data class Snapshot(
        /** Fused median of the stable ring (null while the ring is empty). */
        val fused: FusedReading?,
        /** What the UI should display: the new reading, or the last published one. */
        val display: FusedReading?,
        val converged: Boolean,
        val ringFull: Boolean,
    )

    private val statRing = ArrayDeque<MeteringFrameStat>()
    private val exposureHistory = ArrayDeque<FrameExposure>()
    private var lastPublished: FusedReading? = null

    /**
     * Feed one exposure-paired frame. [stat] is null when the frame could not be metered;
     * non-converged frames clear the ring so stale medians never leak into the reading.
     */
    fun offer(exposure: FrameExposure, converged: Boolean, stat: MeteringFrameStat?): Snapshot {
        if (converged && stat != null) {
            statRing.addLast(stat)
            while (statRing.size > STAT_RING_SIZE) statRing.removeFirst()
        } else if (!converged) {
            statRing.clear()
        }
        val fused = if (statRing.isNotEmpty()) {
            MeteringFusion.fuse(statRing.toList(), source)
        } else {
            null
        }
        val published = fused?.takeIf { new ->
            val last = lastPublished
            last == null || abs(new.sceneEv100 - last.sceneEv100) >= PUBLISH_DELTA_EV
        }
        if (published != null) lastPublished = published
        return Snapshot(
            fused = fused,
            display = published ?: lastPublished,
            converged = converged,
            ringFull = statRing.size >= STAT_RING_SIZE,
        )
    }

    /** The frozen display state while held/paused. */
    fun frozen(converged: Boolean): Snapshot = Snapshot(
        fused = null,
        display = lastPublished,
        converged = converged,
        ringFull = statRing.size >= STAT_RING_SIZE,
    )

    fun reset() {
        statRing.clear()
        exposureHistory.clear()
        lastPublished = null
    }

    /**
     * HAL-reported exposure jitters by tiny amounts frame to frame (±1 ISO step, a few
     * hundred ns), so exact equality never holds on real devices. Treat the exposure as
     * stable while every frame in the window sits within [STABLE_TOLERANCE_EV] of the first.
     */
    fun exposureStable(exposure: FrameExposure): Boolean {
        exposureHistory.addLast(exposure)
        while (exposureHistory.size > STABLE_FRAME_WINDOW) exposureHistory.removeFirst()
        if (exposureHistory.size < STABLE_FRAME_WINDOW) return false
        val first = exposureHistory.first()
        return exposureHistory.all { frame ->
            abs(EvMath.log2(frame.exposureTimeNs.toDouble() / first.exposureTimeNs)) <
                STABLE_TOLERANCE_EV &&
                abs(EvMath.log2(frame.sensitivity.toDouble() / first.sensitivity.toDouble())) <
                STABLE_TOLERANCE_EV
        }
    }

    companion object {
        const val STABLE_FRAME_WINDOW = 4
        const val STABLE_TOLERANCE_EV = 1.0 / 24.0 // ignore ±1/24 EV HAL jitter
        const val STAT_RING_SIZE = 5
        const val PUBLISH_DELTA_EV = 1.0 / 12.0
    }
}
