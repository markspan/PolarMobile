package io.github.markspan.polarmobile.stream

/**
 * Maps the sensor's own clock onto the LSL clock (`lsl_local_clock()`, seconds).
 *
 * The offset estimate is the smallest observed `arrival - sensorTime`, i.e. the packet that
 * travelled with the least BLE latency. It is allowed to creep upwards by [driftPerSecond] so the
 * mapping follows a sensor crystal that runs slow relative to the phone. Samples therefore keep the
 * sensor's regular spacing instead of inheriting BLE delivery jitter, and ECG/ACC frames that share
 * one [SensorClock] stay aligned with each other.
 */
class SensorClock(
    private val driftPerSecond: Double = 2e-4,
    private val resetThreshold: Double = 2.0,
) {
    private var offset = Double.NaN
    private var lastArrival = Double.NaN

    @Synchronized
    fun toLsl(sensorSeconds: Double, arrivalLsl: Double): Double {
        val observed = arrivalLsl - sensorSeconds
        if (offset.isNaN() || kotlin.math.abs(observed - offset) > resetThreshold) {
            offset = observed
        } else {
            offset += driftPerSecond * (arrivalLsl - lastArrival).coerceAtLeast(0.0)
            if (observed < offset) offset = observed
        }
        lastArrival = arrivalLsl
        return sensorSeconds + offset
    }
}

/**
 * Turns per-frame sensor timestamps (time of the last sample) into per-sample LSL timestamps.
 * The sample interval is taken from consecutive frames when plausible, otherwise from the
 * nominal rate.
 */
class FrameTimestamper(private val nominalRate: Double, private val clock: SensorClock) {
    private var lastSensorSeconds = Double.NaN

    fun timestamps(sensorTimeNs: Long, sampleCount: Int, arrivalLsl: Double): DoubleArray {
        val sensorSeconds = sensorTimeNs / 1e9
        val nominalDt = 1.0 / nominalRate
        var dt = nominalDt
        if (!lastSensorSeconds.isNaN()) {
            val measured = (sensorSeconds - lastSensorSeconds) / sampleCount
            if (measured > 0.8 * nominalDt && measured < 1.25 * nominalDt) dt = measured
        }
        lastSensorSeconds = sensorSeconds
        val last = clock.toLsl(sensorSeconds, arrivalLsl)
        return DoubleArray(sampleCount) { i -> last - (sampleCount - 1 - i) * dt }
    }
}
