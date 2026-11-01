package io.github.markspan.polarmobile

import io.github.markspan.polarmobile.ble.HeartRateMeasurement
import io.github.markspan.polarmobile.ble.PmdCommand
import io.github.markspan.polarmobile.ble.PmdControlResponse
import io.github.markspan.polarmobile.ble.PmdParser
import io.github.markspan.polarmobile.stream.FrameTimestamper
import io.github.markspan.polarmobile.stream.SensorClock
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PmdParserTest {

    private fun header(type: Int, timestampNs: Long, frameType: Int) =
        byteArrayOf(type.toByte()) + ByteArray(8) { (timestampNs shr (8 * it)).toByte() } + frameType.toByte()

    @Test
    fun startCommandsMatchDesktopApp() {
        // Same bytes PolarBLE (Windows) writes to the PMD control point.
        assertArrayEquals(
            byteArrayOf(0x02, 0x00, 0x00, 0x01, 0x82.toByte(), 0x00, 0x01, 0x01, 0x0E, 0x00),
            PmdCommand.START_ECG,
        )
        assertArrayEquals(
            byteArrayOf(0x02, 0x02, 0x00, 0x01, 0xC8.toByte(), 0x00, 0x01, 0x01, 0x10, 0x00, 0x02, 0x01, 0x08, 0x00),
            PmdCommand.startAcc(200, 8),
        )
    }

    @Test
    fun parsesEcgFrame() {
        val samples = byteArrayOf(
            0x10, 0x00, 0x00,                 // 16
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), // -1
            0x00, 0x00, 0x80.toByte(),        // -8388608
        )
        val frame = PmdParser.parse(header(0, 123_456_789L, 0) + samples)!!
        assertEquals(PmdCommand.MEASUREMENT_ECG, frame.measurementType)
        assertEquals(123_456_789L, frame.sensorTimeNs)
        assertEquals(1, frame.channels)
        assertArrayEquals(floatArrayOf(16f, -1f, -8388608f), frame.samples, 0f)
    }

    @Test
    fun parsesAcc16BitFrameAndDropsPartialSample() {
        val samples = byteArrayOf(
            0xE8.toByte(), 0x03, 0x18, 0xFC.toByte(), 0x00, 0x00, // 1000, -1000, 0
            0x01, 0x00, // incomplete sample
        )
        val frame = PmdParser.parse(header(2, 1L, 1) + samples)!!
        assertEquals(3, frame.channels)
        assertEquals(1, frame.sampleCount)
        assertArrayEquals(floatArrayOf(1000f, -1000f, 0f), frame.samples, 0f)
    }

    @Test
    fun rejectsCompressedAndUnknownFrames() {
        assertNull(PmdParser.parse(header(2, 1L, 0x81) + ByteArray(12)))
        assertNull(PmdParser.parse(header(5, 1L, 0) + ByteArray(12)))
        assertNull(PmdParser.parse(ByteArray(5)))
    }

    @Test
    fun parsesControlResponse() {
        val r = PmdControlResponse.parse(byteArrayOf(0xF0.toByte(), 0x02, 0x00, 0x0A, 0x00))!!
        assertEquals(2, r.opCode)
        assertEquals("invalid MTU", r.statusText)
    }

    @Test
    fun parsesHeartRateWithRr() {
        // flags: uint8 HR + RR present; HR 60; RR 1024 (=1000 ms), 512 (=500 ms)
        val hr = HeartRateMeasurement.parse(byteArrayOf(0x10, 60, 0x00, 0x04, 0x00, 0x02))!!
        assertEquals(60, hr.bpm)
        assertEquals(listOf(1000f, 500f), hr.rrMs)
    }

    @Test
    fun timestampsAreEvenlySpacedAndNeverInTheFuture() {
        val clock = SensorClock()
        val stamper = FrameTimestamper(130.0, clock)
        val dt = 1.0 / 130
        var sensorT = 10.0
        var lastTs = Double.NEGATIVE_INFINITY
        for (frame in 0 until 50) {
            sensorT += 73 * dt
            val arrival = sensorT + 1000.0 + 0.02 + (frame % 5) * 0.015 // BLE jitter
            val ts = stamper.timestamps((sensorT * 1e9).toLong(), 73, arrival)
            assertTrue(ts.last() <= arrival + 1e-9)
            for (i in 1 until ts.size) assertEquals(dt, ts[i] - ts[i - 1], 1e-6)
            assertTrue(ts.first() > lastTs)
            lastTs = ts.last()
        }
        // offset converges to the minimum latency (0.02 s)
        assertEquals(sensorT + 1000.02, lastTs, 1e-3)
    }
}
