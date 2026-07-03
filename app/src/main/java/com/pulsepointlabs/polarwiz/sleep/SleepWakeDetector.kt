package com.pulsepointlabs.polarwiz.sleep

class SleepWakeDetector(
    private val sleepDelayMs: Long = 20 * 60_000L,
    private val wakeDelayMs: Long = 15_000L,
    private val hrWakeDelayMs: Long = 30_000L
) {
    enum class State { AWAKE, SLEEPING }
    enum class Event { SLEEP, WAKE }

    var state: State = State.AWAKE
        private set
    private var lastMovementAt = 0L
    private var wakeMovementAt: Long? = null
    private var lastWakeMovementAt: Long? = null
    private var wakeHrAt: Long? = null
    private var currentHr: Int? = null
    private var sleepHr: Int? = null
    private val recentHr = ArrayDeque<Pair<Long, Int>>()

    fun reset(now: Long = System.currentTimeMillis()) {
        state = State.AWAKE
        lastMovementAt = now
        wakeMovementAt = null
        lastWakeMovementAt = null
        wakeHrAt = null
        currentHr = null
        sleepHr = null
        recentHr.clear()
    }

    fun onMotion(linearAcceleration: Float, now: Long = System.currentTimeMillis()): Event? {
        if (linearAcceleration >= 0.65f) {
            if (state == State.AWAKE) lastMovementAt = now
            if (state == State.SLEEPING && wakeMovementAt == null) wakeMovementAt = now
            if (state == State.SLEEPING) lastWakeMovementAt = now
        } else if (state == State.SLEEPING && lastWakeMovementAt?.let { now - it > 5_000L } == true) {
            wakeMovementAt = null
            lastWakeMovementAt = null
        }
        return evaluate(now)
    }

    fun onSignificantMotion(now: Long = System.currentTimeMillis()): Event? {
        if (state == State.SLEEPING) return transitionToAwake(now)
        lastMovementAt = now
        return null
    }

    fun onHeartRate(bpm: Int, now: Long = System.currentTimeMillis()): Event? {
        currentHr = bpm
        recentHr.addLast(now to bpm)
        while (recentHr.firstOrNull()?.first?.let { now - it > 5 * 60_000L } == true) recentHr.removeFirst()
        val sleepingRate = sleepHr
        if (state == State.SLEEPING && sleepingRate != null && bpm >= sleepingRate + 12) {
            if (wakeHrAt == null) wakeHrAt = now
        } else if (state == State.SLEEPING && sleepingRate != null && bpm < sleepingRate + 8) {
            wakeHrAt = null
        }
        return evaluate(now)
    }

    fun evaluate(now: Long = System.currentTimeMillis()): Event? {
        if (state == State.AWAKE) {
            val values = recentHr.map { it.second }
            val stableHr = values.size >= 5 && (values.maxOrNull()!! - values.minOrNull()!! <= 10)
            val plausibleSleepHr = currentHr?.let { it in 35..90 } == true
            if (now - lastMovementAt >= sleepDelayMs && stableHr && plausibleSleepHr) {
                state = State.SLEEPING
                sleepHr = currentHr
                wakeMovementAt = null
                lastWakeMovementAt = null
                wakeHrAt = null
                return Event.SLEEP
            }
        } else {
            if ((wakeMovementAt?.let { now - it >= wakeDelayMs } == true &&
                    lastWakeMovementAt?.let { now - it <= 5_000L } == true) ||
                wakeHrAt?.let { now - it >= hrWakeDelayMs } == true
            ) return transitionToAwake(now)
        }
        return null
    }

    private fun transitionToAwake(now: Long): Event {
        state = State.AWAKE
        lastMovementAt = now
        wakeMovementAt = null
        lastWakeMovementAt = null
        wakeHrAt = null
        sleepHr = null
        recentHr.clear()
        return Event.WAKE
    }
}
