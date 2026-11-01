package io.github.markspan.polarmobile.stream

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class OutletInfo(val name: String, val type: String, val hasConsumers: Boolean = false)

data class StreamUiState(
    val running: Boolean = false,
    val deviceName: String? = null,
    val status: String = "Idle",
    val streaming: Boolean = false,
    val battery: Int? = null,
    val heartRate: Int? = null,
    val ecgSamples: Long = 0,
    val accSamples: Long = 0,
    val outlets: List<OutletInfo> = emptyList(),
    val messages: List<String> = emptyList(),
)

/** Process-wide state shared between [PolarStreamService] and the UI. */
object StreamState {
    private val _state = MutableStateFlow(StreamUiState())
    val state: StateFlow<StreamUiState> = _state.asStateFlow()

    /** Last seconds of signal for the on-screen preview. */
    val ecgPreview = PreviewBuffer(channels = 1, capacity = 130 * 5)
    val accPreview = PreviewBuffer(channels = 3, capacity = 200 * 5)

    fun update(transform: (StreamUiState) -> StreamUiState) = _state.update(transform)

    fun message(text: String) = _state.update {
        it.copy(messages = (it.messages + text).takeLast(MAX_MESSAGES))
    }

    private const val MAX_MESSAGES = 6
}

/** Fixed-size ring buffer of multiplexed samples, written by the service and read by the UI. */
class PreviewBuffer(val channels: Int, val capacity: Int) {
    private val data = FloatArray(channels * capacity)
    private var head = 0
    private var size = 0

    @Synchronized
    fun add(samples: FloatArray) {
        var i = 0
        while (i + channels <= samples.size) {
            System.arraycopy(samples, i, data, head * channels, channels)
            head = (head + 1) % capacity
            if (size < capacity) size++
            i += channels
        }
    }

    @Synchronized
    fun clear() {
        head = 0
        size = 0
    }

    /** Copies the buffered samples, oldest first, into [out] and returns the sample count. */
    @Synchronized
    fun snapshot(out: FloatArray): Int {
        val start = (head - size + capacity) % capacity
        for (s in 0 until size) {
            System.arraycopy(data, ((start + s) % capacity) * channels, out, s * channels, channels)
        }
        return size
    }
}
