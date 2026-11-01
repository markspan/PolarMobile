package io.github.markspan.polarmobile.lsl

import java.io.Closeable

/** JNI bindings to liblsl, implemented in `src/main/cpp/lsl_jni.cpp`. */
object LslNative {
    init {
        System.loadLibrary("polarlsl")
    }

    external fun libraryVersion(): Int
    external fun libraryInfo(): String
    external fun localClock(): Double

    external fun createOutlet(
        name: String,
        type: String,
        channelCount: Int,
        nominalRate: Double,
        sourceId: String,
        channelLabels: Array<String>,
        unit: String,
        channelType: String,
        manufacturer: String,
        chunkSize: Int,
        maxBuffered: Int,
    ): Long

    external fun pushChunk(handle: Long, data: FloatArray, timestamps: DoubleArray, sampleCount: Int): Int
    external fun haveConsumers(handle: Long): Boolean
    external fun destroyOutlet(handle: Long)
}

/** Use for streams without a fixed sampling rate (heart rate, RR intervals). */
const val IRREGULAR_RATE = 0.0

/** A float32 LSL stream outlet. */
class LslOutlet(
    val name: String,
    type: String,
    val channelCount: Int,
    nominalRate: Double,
    sourceId: String,
    channelLabels: List<String>,
    unit: String,
    channelType: String,
    manufacturer: String = "Polar",
    maxBufferedSeconds: Int = 360,
) : Closeable {

    @Volatile
    private var handle: Long = LslNative.createOutlet(
        name, type, channelCount, nominalRate, sourceId, channelLabels.toTypedArray(),
        unit, channelType, manufacturer, 0, maxBufferedSeconds,
    )

    init {
        check(handle != 0L) { "Could not create LSL outlet '$name'" }
    }

    /** [data] holds `timestamps.size` samples of [channelCount] values each, multiplexed. */
    @Synchronized
    fun push(data: FloatArray, timestamps: DoubleArray) {
        val h = handle
        if (h != 0L && timestamps.isNotEmpty()) LslNative.pushChunk(h, data, timestamps, timestamps.size)
    }

    val hasConsumers: Boolean
        get() = handle.let { it != 0L && LslNative.haveConsumers(it) }

    @Synchronized
    override fun close() {
        val h = handle
        handle = 0L
        if (h != 0L) LslNative.destroyOutlet(h)
    }
}
