package io.github.markspan.polarmobile.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.util.ArrayDeque
import java.util.UUID

/**
 * GATT client for one Polar H10. Android only allows one outstanding GATT operation at a time, so
 * every request goes through a queue that is advanced from the completion callbacks. All callbacks
 * to [Listener] are made on the client's own handler thread.
 *
 * Sequence after connecting: discover services, raise the MTU (PMD frames need ~232 bytes),
 * read the battery, enable PMD control indications and PMD data / heart-rate notifications, then
 * send one PMD start command per measurement, waiting for each command's response.
 */
@SuppressLint("MissingPermission") // callers ensure BLUETOOTH_CONNECT is granted
class PolarH10Client(
    private val context: Context,
    private val device: BluetoothDevice,
    private val config: Config,
    private val listener: Listener,
) {
    data class Config(
        val ecg: Boolean = true,
        val acc: Boolean = true,
        val accRate: Int = 200,
        val accRangeG: Int = 8,
        val heartRate: Boolean = false,
    )

    interface Listener {
        fun onStatus(text: String)
        fun onStreaming()
        fun onPmdFrame(frame: PmdFrame)
        fun onHeartRate(measurement: HeartRateMeasurement)
        fun onBattery(percent: Int)
        fun onWarning(text: String)
        fun onDisconnected(reason: String)
    }

    private sealed class Op {
        data class RequestMtu(val mtu: Int) : Op()
        data class Read(val service: UUID, val char: UUID) : Op()
        data class Subscribe(val service: UUID, val char: UUID, val indicate: Boolean) : Op()
        /** Completes on the PMD control point response indication, not on the write callback. */
        data class PmdCommand(val label: String, val payload: ByteArray) : Op()
        object Started : Op()
    }

    private val thread = HandlerThread("PolarH10-${device.address}").apply { start() }
    private val handler = Handler(thread.looper)
    private val queue = ArrayDeque<Op>()
    private var current: Op? = null
    private var gatt: BluetoothGatt? = null
    private var closed = false

    fun connect() = handler.post {
        listener.onStatus("Connecting…")
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
    }

    /** Disconnects (the H10 stops its measurements on disconnect) and releases the handler thread. */
    fun close() = handler.post {
        if (closed) return@post
        closed = true
        handler.removeCallbacksAndMessages(null)
        queue.clear()
        current = null
        gatt?.let { g ->
            g.disconnect()
            g.close()
        }
        gatt = null
        thread.quitSafely()
    }

    private fun enqueue(op: Op) {
        queue.add(op)
        if (current == null) next()
    }

    private fun next() {
        handler.removeCallbacks(timeout)
        val g = gatt ?: return
        val op = queue.poll()
        current = op
        if (op == null) return
        if (op == Op.Started) {
            listener.onStatus("Streaming")
            listener.onStreaming()
            complete()
            return
        }
        handler.postDelayed(timeout, if (op is Op.PmdCommand) 10_000 else 5_000)
        val ok = when (op) {
            is Op.RequestMtu -> g.requestMtu(op.mtu)
            is Op.Read -> characteristic(g, op.service, op.char)?.let { g.readCharacteristic(it) } ?: false
            is Op.Subscribe -> subscribe(g, op)
            is Op.PmdCommand -> characteristic(g, PolarUuids.PMD_SERVICE, PolarUuids.PMD_CONTROL)
                ?.let { write(g, it, op.payload) } ?: false
            Op.Started -> true
        }
        if (!ok) {
            listener.onWarning("GATT request failed: ${describe(op)}")
            complete()
        }
    }

    private fun complete() {
        current = null
        next()
    }

    private val timeout = Runnable {
        current?.let { listener.onWarning("Timeout: ${describe(it)}") }
        complete()
    }

    private fun describe(op: Op) = when (op) {
        is Op.PmdCommand -> op.label
        is Op.Subscribe -> "subscribe ${op.char}"
        is Op.Read -> "read ${op.char}"
        is Op.RequestMtu -> "MTU ${op.mtu}"
        Op.Started -> "start"
    }

    private fun characteristic(g: BluetoothGatt, service: UUID, char: UUID) =
        g.getService(service)?.getCharacteristic(char)

    private fun subscribe(g: BluetoothGatt, op: Op.Subscribe): Boolean {
        val c = characteristic(g, op.service, op.char) ?: return false
        if (!g.setCharacteristicNotification(c, true)) return false
        val d = c.getDescriptor(PolarUuids.CCCD) ?: return false
        val value = if (op.indicate) BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        else BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(d, value) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            d.value = value
            @Suppress("DEPRECATION")
            g.writeDescriptor(d)
        }
    }

    private fun write(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray): Boolean {
        val type = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(c, value, type) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            c.writeType = type
            @Suppress("DEPRECATION")
            c.value = value
            @Suppress("DEPRECATION")
            g.writeCharacteristic(c)
        }
    }

    private fun onServicesReady(g: BluetoothGatt) {
        if (g.getService(PolarUuids.PMD_SERVICE) == null && (config.ecg || config.acc)) {
            listener.onWarning("Device has no PMD service; ECG/ACC unavailable")
        }
        enqueue(Op.RequestMtu(232))
        if (g.getService(PolarUuids.BATTERY_SERVICE) != null) {
            enqueue(Op.Read(PolarUuids.BATTERY_SERVICE, PolarUuids.BATTERY_LEVEL))
        }
        if (config.ecg || config.acc) {
            enqueue(Op.Subscribe(PolarUuids.PMD_SERVICE, PolarUuids.PMD_CONTROL, indicate = true))
            enqueue(Op.Subscribe(PolarUuids.PMD_SERVICE, PolarUuids.PMD_DATA, indicate = false))
        }
        if (config.heartRate) {
            enqueue(Op.Subscribe(PolarUuids.HR_SERVICE, PolarUuids.HR_MEASUREMENT, indicate = false))
        }
        if (config.ecg) enqueue(Op.PmdCommand("start ECG", PmdCommand.START_ECG))
        if (config.acc) {
            enqueue(Op.PmdCommand("start ACC", PmdCommand.startAcc(config.accRate, config.accRangeG)))
        }
        enqueue(Op.Started)
    }

    private fun onValue(uuid: UUID, value: ByteArray) {
        when (uuid) {
            PolarUuids.PMD_DATA -> PmdParser.parse(value)?.let(listener::onPmdFrame)
            PolarUuids.HR_MEASUREMENT -> HeartRateMeasurement.parse(value)?.let(listener::onHeartRate)
            PolarUuids.PMD_CONTROL -> {
                val response = PmdControlResponse.parse(value) ?: return
                val op = current
                if (!response.isSuccess && response.status != 6) { // 6 = already started
                    listener.onWarning(
                        (if (op is Op.PmdCommand) op.label else "PMD command") + " failed: ${response.statusText}"
                    )
                }
                if (op is Op.PmdCommand) complete()
            }
            PolarUuids.BATTERY_LEVEL -> if (value.isNotEmpty()) listener.onBattery(value[0].toInt() and 0xFF)
        }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            handler.post {
                if (closed) return@post
                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    listener.onStatus("Discovering services…")
                    g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                    // Give the link a moment to settle before discovery (helps on some phones).
                    handler.postDelayed({ if (!closed) g.discoverServices() }, 600)
                } else {
                    handler.removeCallbacks(timeout)
                    queue.clear()
                    current = null
                    g.close()
                    if (gatt === g) gatt = null
                    listener.onDisconnected(
                        if (status == BluetoothGatt.GATT_SUCCESS) "disconnected" else "GATT status $status"
                    )
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            handler.post {
                if (closed) return@post
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    listener.onWarning("Service discovery failed ($status)")
                    g.disconnect()
                } else {
                    listener.onStatus("Starting measurements…")
                    onServicesReady(g)
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            handler.post {
                Log.i(TAG, "MTU $mtu (status $status)")
                if (mtu < 232) listener.onWarning("Small MTU ($mtu); the H10 may refuse to stream")
                if (current is Op.RequestMtu) complete()
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            handler.post {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    listener.onWarning("Subscribing to ${d.characteristic.uuid} failed ($status)")
                }
                if (current is Op.Subscribe) complete()
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            handler.post {
                // PMD commands complete on their response indication; only fail fast here.
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    listener.onWarning("Write to ${c.uuid} failed ($status)")
                    if (current is Op.PmdCommand) complete()
                }
            }
        }

        override fun onCharacteristicRead(
            g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int,
        ) {
            handler.post {
                if (status == BluetoothGatt.GATT_SUCCESS) onValue(c.uuid, value)
                if (current is Op.Read) complete()
            }
        }

        @Deprecated("Used below API 33")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            @Suppress("DEPRECATION")
            onCharacteristicRead(g, c, c.value ?: ByteArray(0), status)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            val uuid = c.uuid
            handler.post { onValue(uuid, value) }
        }

        @Deprecated("Used below API 33")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            // The characteristic's value buffer is reused by the stack: copy it now.
            @Suppress("DEPRECATION")
            val value = c.value?.copyOf() ?: return
            val uuid = c.uuid
            handler.post { onValue(uuid, value) }
        }
    }

    private companion object {
        const val TAG = "PolarH10Client"
    }
}
