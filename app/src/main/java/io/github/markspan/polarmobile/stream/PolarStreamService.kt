package io.github.markspan.polarmobile.stream

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import io.github.markspan.polarmobile.MainActivity
import io.github.markspan.polarmobile.R
import io.github.markspan.polarmobile.ble.HeartRateMeasurement
import io.github.markspan.polarmobile.ble.PmdCommand
import io.github.markspan.polarmobile.ble.PmdFrame
import io.github.markspan.polarmobile.ble.PolarH10Client
import io.github.markspan.polarmobile.lsl.IRREGULAR_RATE
import io.github.markspan.polarmobile.lsl.LslNative
import io.github.markspan.polarmobile.lsl.LslOutlet

/**
 * Foreground service that owns the BLE connection and the LSL outlets, so streaming continues
 * with the screen off. Outlets survive BLE reconnects: consumers such as LabRecorder simply see a
 * gap instead of a vanished stream.
 */
class PolarStreamService : Service() {

    private val main = Handler(Looper.getMainLooper())
    private var client: PolarH10Client? = null
    private var address: String? = null
    private var deviceName: String = ""
    private var config = PolarH10Client.Config()

    private var ecgOutlet: LslOutlet? = null
    private var accOutlet: LslOutlet? = null
    private var hrOutlet: LslOutlet? = null
    private var rrOutlet: LslOutlet? = null

    private var sensorClock = SensorClock()
    private var ecgTimes = FrameTimestamper(PmdCommand.ECG_SAMPLE_RATE.toDouble(), sensorClock)
    private var accTimes = FrameTimestamper(200.0, sensorClock)

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val addr = intent.getStringExtra(EXTRA_ADDRESS)
                if (addr == null || client != null) return START_NOT_STICKY
                start(
                    addr,
                    intent.getStringExtra(EXTRA_NAME) ?: "Polar H10",
                    PolarH10Client.Config(
                        ecg = intent.getBooleanExtra(EXTRA_ECG, true),
                        acc = intent.getBooleanExtra(EXTRA_ACC, true),
                        accRate = intent.getIntExtra(EXTRA_ACC_RATE, 200),
                        accRangeG = intent.getIntExtra(EXTRA_ACC_RANGE, 8),
                        heartRate = intent.getBooleanExtra(EXTRA_HR, false),
                    ),
                )
            }
            ACTION_STOP -> stop()
        }
        return START_NOT_STICKY
    }

    private fun start(addr: String, name: String, cfg: PolarH10Client.Config) {
        address = addr
        deviceName = name
        config = cfg
        goForeground()
        acquireLocks()
        StreamState.ecgPreview.clear()
        StreamState.accPreview.clear()
        StreamState.update {
            StreamUiState(running = true, deviceName = name, status = "Creating LSL outlets…")
        }
        try {
            createOutlets()
        } catch (e: Throwable) {
            StreamState.message("LSL error: ${e.message}")
            stop()
            return
        }
        connect()
        main.postDelayed(consumerPoll, 1000)
    }

    /** Same stream names and source ids as the PolarBLE desktop app. */
    private fun createOutlets() {
        val n = deviceName
        if (config.ecg) ecgOutlet = LslOutlet(
            "${n}_ecg", "ECG", 1, PmdCommand.ECG_SAMPLE_RATE.toDouble(), "${n}_ecg",
            listOf("ECG"), "microvolts", "ECG",
        )
        if (config.acc) accOutlet = LslOutlet(
            "${n}_acc", "Accelerometer", 3, config.accRate.toDouble(), "${n}_acc",
            listOf("X", "Y", "Z"), "mG", "ACC",
        )
        if (config.heartRate) {
            hrOutlet = LslOutlet("${n}_hr", "HeartRate", 1, IRREGULAR_RATE, "${n}_hr", listOf("HR"), "bpm", "HR")
            rrOutlet = LslOutlet("${n}_rr", "RR", 1, IRREGULAR_RATE, "${n}_rr", listOf("RR"), "ms", "RR")
        }
        publishOutlets()
    }

    private fun outlets() = listOfNotNull(
        ecgOutlet?.let { it to "ECG" }, accOutlet?.let { it to "ACC" },
        hrOutlet?.let { it to "HR" }, rrOutlet?.let { it to "RR" },
    )

    private fun publishOutlets() {
        val infos = outlets().map { (o, type) -> OutletInfo(o.name, type, o.hasConsumers) }
        StreamState.update { it.copy(outlets = infos) }
    }

    private val consumerPoll = object : Runnable {
        override fun run() {
            if (client == null) return
            publishOutlets()
            main.postDelayed(this, 1000)
        }
    }

    @SuppressLint("MissingPermission")
    private fun connect() {
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            StreamState.message("Bluetooth is off")
            stop()
            return
        }
        // A fresh sensor clock per connection: the H10's clock may restart while disconnected.
        sensorClock = SensorClock()
        ecgTimes = FrameTimestamper(PmdCommand.ECG_SAMPLE_RATE.toDouble(), sensorClock)
        accTimes = FrameTimestamper(config.accRate.toDouble(), sensorClock)
        client = PolarH10Client(this, adapter.getRemoteDevice(address), config, listener).also { it.connect() }
    }

    private val listener = object : PolarH10Client.Listener {
        override fun onStatus(text: String) {
            StreamState.update { it.copy(status = text) }
            updateNotification(text)
        }

        override fun onStreaming() = StreamState.update { it.copy(streaming = true) }

        override fun onPmdFrame(frame: PmdFrame) {
            val now = LslNative.localClock()
            when (frame.measurementType) {
                PmdCommand.MEASUREMENT_ECG -> {
                    val ts = ecgTimes.timestamps(frame.sensorTimeNs, frame.sampleCount, now)
                    ecgOutlet?.push(frame.samples, ts)
                    StreamState.ecgPreview.add(frame.samples)
                    StreamState.update { it.copy(ecgSamples = it.ecgSamples + frame.sampleCount) }
                }
                PmdCommand.MEASUREMENT_ACC -> {
                    val ts = accTimes.timestamps(frame.sensorTimeNs, frame.sampleCount, now)
                    accOutlet?.push(frame.samples, ts)
                    StreamState.accPreview.add(frame.samples)
                    StreamState.update { it.copy(accSamples = it.accSamples + frame.sampleCount) }
                }
            }
        }

        override fun onHeartRate(measurement: HeartRateMeasurement) {
            val now = LslNative.localClock()
            hrOutlet?.push(floatArrayOf(measurement.bpm.toFloat()), doubleArrayOf(now))
            if (measurement.rrMs.isNotEmpty()) {
                // The last interval ended at (about) the arrival time; earlier beats precede it.
                val rr = measurement.rrMs
                val ts = DoubleArray(rr.size)
                var t = now
                for (i in rr.indices.reversed()) {
                    ts[i] = t
                    t -= rr[i] / 1000.0
                }
                rrOutlet?.push(rr.toFloatArray(), ts)
            }
            StreamState.update { it.copy(heartRate = measurement.bpm) }
        }

        override fun onBattery(percent: Int) = StreamState.update { it.copy(battery = percent) }

        override fun onWarning(text: String) = StreamState.message(text)

        override fun onDisconnected(reason: String) {
            main.post {
                if (client == null) return@post // stopped by the user
                client?.close()
                client = null
                StreamState.update { it.copy(streaming = false, status = "Reconnecting… ($reason)") }
                updateNotification("Reconnecting…")
                main.postDelayed({ if (address != null && client == null && running) connect() }, 2000)
            }
        }
    }

    private val running get() = StreamState.state.value.running

    private fun stop() {
        main.removeCallbacksAndMessages(null)
        client?.close()
        client = null
        address = null
        outlets().forEach { it.first.close() }
        ecgOutlet = null; accOutlet = null; hrOutlet = null; rrOutlet = null
        releaseLocks()
        StreamState.update { it.copy(running = false, streaming = false, status = "Stopped", outlets = emptyList()) }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (client != null || ecgOutlet != null || accOutlet != null) stop()
        super.onDestroy()
    }

    // --- foreground notification -------------------------------------------------------------

    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW)
        )
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification("Connecting…"), type)
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, PolarStreamService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_heart)
            .setContentTitle(deviceName.ifEmpty { getString(R.string.app_name) })
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, getString(R.string.stop), stop)
            .build()
    }

    private fun updateNotification(text: String) {
        if (client == null && address == null) return
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
    }

    // --- locks --------------------------------------------------------------------------------

    /**
     * LSL discovery uses UDP multicast/broadcast, which Android drops unless a multicast lock is
     * held; the Wi-Fi lock avoids power-save latency spikes; the wake lock keeps the CPU running.
     */
    private fun acquireLocks() {
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wifi.createMulticastLock("PolarMobile:lsl").apply { setReferenceCounted(false); acquire() }
        @Suppress("DEPRECATION")
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
        wifiLock = wifi.createWifiLock(mode, "PolarMobile:lsl").apply { setReferenceCounted(false); acquire() }
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PolarMobile:stream")
            .apply { setReferenceCounted(false); acquire() }
    }

    private fun releaseLocks() {
        multicastLock?.takeIf { it.isHeld }?.release()
        wifiLock?.takeIf { it.isHeld }?.release()
        wakeLock?.takeIf { it.isHeld }?.release()
        multicastLock = null; wifiLock = null; wakeLock = null
    }

    companion object {
        const val ACTION_START = "io.github.markspan.polarmobile.START"
        const val ACTION_STOP = "io.github.markspan.polarmobile.STOP"
        const val EXTRA_ADDRESS = "address"
        const val EXTRA_NAME = "name"
        const val EXTRA_ECG = "ecg"
        const val EXTRA_ACC = "acc"
        const val EXTRA_ACC_RATE = "accRate"
        const val EXTRA_ACC_RANGE = "accRange"
        const val EXTRA_HR = "hr"

        private const val CHANNEL_ID = "streaming"
        private const val NOTIFICATION_ID = 1
    }
}
