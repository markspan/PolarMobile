package io.github.markspan.polarmobile.ble

import java.util.UUID

/** GATT identifiers used by the Polar H10. */
object PolarUuids {
    val PMD_SERVICE: UUID = UUID.fromString("FB005C80-02E7-F387-1CAD-8ACD2D8DF0C8")
    val PMD_CONTROL: UUID = UUID.fromString("FB005C81-02E7-F387-1CAD-8ACD2D8DF0C8")
    val PMD_DATA: UUID = UUID.fromString("FB005C82-02E7-F387-1CAD-8ACD2D8DF0C8")

    val HR_SERVICE: UUID = UUID.fromString("0000180D-0000-1000-8000-00805F9B34FB")
    val HR_MEASUREMENT: UUID = UUID.fromString("00002A37-0000-1000-8000-00805F9B34FB")

    val BATTERY_SERVICE: UUID = UUID.fromString("0000180F-0000-1000-8000-00805F9B34FB")
    val BATTERY_LEVEL: UUID = UUID.fromString("00002A19-0000-1000-8000-00805F9B34FB")

    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")
}

/**
 * Polar Measurement Data (PMD) control point commands.
 *
 * A start command is `0x02 <measurement type>` followed by settings, each encoded as
 * `<setting type> <count=1> <value uint16 LE>`. Setting types: 0 = sample rate, 1 = resolution,
 * 2 = range.
 */
object PmdCommand {
    const val MEASUREMENT_ECG = 0x00
    const val MEASUREMENT_ACC = 0x02

    private const val OP_START = 0x02
    private const val OP_STOP = 0x03

    const val ECG_SAMPLE_RATE = 130

    val ACC_SAMPLE_RATES = listOf(25, 50, 100, 200)
    val ACC_RANGES_G = listOf(2, 4, 8)

    /** ECG at 130 Hz, 14-bit resolution (the only setting the H10 offers). */
    val START_ECG: ByteArray = bytes(OP_START, MEASUREMENT_ECG, 0x00, 0x01, 0x82, 0x00, 0x01, 0x01, 0x0E, 0x00)

    /** ACC with 16-bit resolution; [sampleRate] in Hz and [rangeG] in g. */
    fun startAcc(sampleRate: Int = 200, rangeG: Int = 8): ByteArray {
        require(sampleRate in ACC_SAMPLE_RATES) { "Unsupported ACC sample rate $sampleRate" }
        require(rangeG in ACC_RANGES_G) { "Unsupported ACC range $rangeG" }
        return bytes(
            OP_START, MEASUREMENT_ACC,
            0x00, 0x01, sampleRate and 0xFF, sampleRate shr 8,
            0x01, 0x01, 0x10, 0x00,
            0x02, 0x01, rangeG, 0x00,
        )
    }

    fun stop(measurementType: Int): ByteArray = bytes(OP_STOP, measurementType)

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
}

/** Response indication from the PMD control point: `F0 <op code> <measurement type> <status> ...`. */
data class PmdControlResponse(val opCode: Int, val measurementType: Int, val status: Int) {
    val isSuccess get() = status == 0

    val statusText: String
        get() = STATUS_TEXT.getOrElse(status) { "error $status" }

    companion object {
        private val STATUS_TEXT = listOf(
            "success", "invalid op code", "invalid measurement type", "not supported",
            "invalid length", "invalid parameter", "already in state", "invalid resolution",
            "invalid sample rate", "invalid range", "invalid MTU", "invalid number of channels",
            "invalid state", "device in charger",
        )

        fun parse(data: ByteArray): PmdControlResponse? {
            if (data.size < 4 || (data[0].toInt() and 0xFF) != 0xF0) return null
            return PmdControlResponse(data[1].u8, data[2].u8, data[3].u8)
        }
    }
}

/**
 * One PMD data notification.
 * @param sensorTimeNs sensor clock time of the *last* sample in the frame, in nanoseconds.
 * @param samples multiplexed sample values, [channels] per sample.
 */
class PmdFrame(
    val measurementType: Int,
    val sensorTimeNs: Long,
    val channels: Int,
    val samples: FloatArray,
) {
    val sampleCount get() = samples.size / channels
}

object PmdParser {
    private const val HEADER_SIZE = 10

    /**
     * Parses an ECG or ACC data notification. Returns null for other measurement types and for
     * delta-compressed frames, which the H10 does not use for these measurements.
     *
     * ECG values are in µV; ACC values are in mG.
     */
    fun parse(data: ByteArray): PmdFrame? {
        if (data.size <= HEADER_SIZE) return null
        val type = data[0].u8
        val timestamp = readLong(data, 1)
        val frameType = data[9].u8
        if (frameType and 0x80 != 0) return null

        return when (type) {
            PmdCommand.MEASUREMENT_ECG -> {
                if (frameType != 0) return null
                PmdFrame(type, timestamp, 1, readSigned(data, HEADER_SIZE, 3, 1))
            }
            PmdCommand.MEASUREMENT_ACC -> {
                // frame type 0/1/2 = 8/16/24 bit per axis
                if (frameType > 2) return null
                PmdFrame(type, timestamp, 3, readSigned(data, HEADER_SIZE, frameType + 1, 3))
            }
            else -> null
        }
    }

    /** Reads consecutive little-endian signed integers of [width] bytes, whole samples only. */
    private fun readSigned(data: ByteArray, offset: Int, width: Int, channels: Int): FloatArray {
        val sampleBytes = width * channels
        val count = (data.size - offset) / sampleBytes * channels
        val shift = 32 - 8 * width
        return FloatArray(count) { i ->
            val p = offset + i * width
            var raw = 0
            for (b in 0 until width) raw = raw or (data[p + b].u8 shl (8 * b))
            ((raw shl shift) shr shift).toFloat() // sign-extend
        }
    }

    private fun readLong(data: ByteArray, offset: Int): Long {
        var v = 0L
        for (b in 0 until 8) v = v or ((data[offset + b].toLong() and 0xFF) shl (8 * b))
        return v
    }
}

/** Standard Bluetooth Heart Rate Measurement (0x2A37). RR intervals are in milliseconds. */
data class HeartRateMeasurement(val bpm: Int, val rrMs: List<Float>) {
    companion object {
        fun parse(data: ByteArray): HeartRateMeasurement? {
            if (data.size < 2) return null
            val flags = data[0].u8
            var p = 1
            val bpm = if (flags and 0x01 != 0) {
                if (data.size < 3) return null
                (data[1].u8 or (data[2].u8 shl 8)).also { p = 3 }
            } else {
                data[1].u8.also { p = 2 }
            }
            if (flags and 0x08 != 0) p += 2 // energy expended
            val rr = mutableListOf<Float>()
            if (flags and 0x10 != 0) {
                while (p + 1 < data.size) {
                    val v = data[p].u8 or (data[p + 1].u8 shl 8)
                    rr += v * 1000f / 1024f
                    p += 2
                }
            }
            return HeartRateMeasurement(bpm, rr)
        }
    }
}

private val Byte.u8 get() = toInt() and 0xFF
