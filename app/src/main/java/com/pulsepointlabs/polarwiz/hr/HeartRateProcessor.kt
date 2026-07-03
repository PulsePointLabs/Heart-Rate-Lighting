package com.pulsepointlabs.polarwiz.hr

import com.pulsepointlabs.polarwiz.model.HrZone
import kotlin.math.roundToInt

class HeartRateProcessor(
    private val windowMs: Long = 5_000,
    private val hysteresisBpm: Int = 3
) {
    private val samples = ArrayDeque<Pair<Long, Int>>()
    private var activeZone: HrZone? = null

    fun add(bpm: Int, now: Long = System.currentTimeMillis()): Int {
        samples.addLast(now to bpm)
        while (samples.firstOrNull()?.first?.let { now - it > windowMs } == true) samples.removeFirst()
        return samples.map { it.second }.average().roundToInt()
    }

    fun zoneFor(smoothedBpm: Int): HrZone {
        val candidate = HrZone.entries.last { smoothedBpm >= it.min }
        val current = activeZone ?: candidate.also { activeZone = it }
        if (candidate == current) return current
        val boundary = if (candidate.ordinal > current.ordinal) candidate.min else current.min
        val crossed = if (candidate.ordinal > current.ordinal) {
            smoothedBpm >= boundary + hysteresisBpm
        } else {
            smoothedBpm < boundary - hysteresisBpm
        }
        if (crossed) activeZone = candidate
        return activeZone ?: candidate
    }

    fun reset() { samples.clear(); activeZone = null }
}
