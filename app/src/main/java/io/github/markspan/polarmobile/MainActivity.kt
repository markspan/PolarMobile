package io.github.markspan.polarmobile

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.RadioButton
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.snackbar.Snackbar
import io.github.markspan.polarmobile.ble.PmdCommand
import io.github.markspan.polarmobile.lsl.LslNative
import io.github.markspan.polarmobile.stream.PolarStreamService
import io.github.markspan.polarmobile.stream.StreamState
import io.github.markspan.polarmobile.stream.StreamUiState
import io.github.markspan.polarmobile.ui.SignalView
import kotlinx.coroutines.launch
import java.net.Inet4Address

class MainActivity : AppCompatActivity() {

    private data class FoundDevice(val address: String, val name: String, var rssi: Int)

    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }
    private val handler = Handler(Looper.getMainLooper())
    private val devices = mutableListOf<FoundDevice>()
    private var selected: String? = null
    private var scanning = false

    private lateinit var scanButton: MaterialButton
    private lateinit var scanProgress: LinearProgressIndicator
    private lateinit var noDevices: TextView
    private lateinit var deviceAdapter: DeviceAdapter
    private lateinit var ecgSwitch: MaterialSwitch
    private lateinit var accSwitch: MaterialSwitch
    private lateinit var hrSwitch: MaterialSwitch
    private lateinit var accRate: AutoCompleteTextView
    private lateinit var accRange: AutoCompleteTextView
    private lateinit var streamButton: MaterialButton

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (blePermissions().all { result[it] == true || granted(it) }) startScan()
            else snackbar(getString(R.string.permissions_needed))
        }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val enableBluetooth =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { ensureReadyAndScan() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        scanButton = findViewById(R.id.scanButton)
        scanProgress = findViewById(R.id.scanProgress)
        noDevices = findViewById(R.id.noDevices)
        ecgSwitch = findViewById(R.id.ecgSwitch)
        accSwitch = findViewById(R.id.accSwitch)
        hrSwitch = findViewById(R.id.hrSwitch)
        accRate = findViewById(R.id.accRate)
        accRange = findViewById(R.id.accRange)
        streamButton = findViewById(R.id.streamButton)

        deviceAdapter = DeviceAdapter()
        findViewById<RecyclerView>(R.id.deviceList).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = deviceAdapter
        }

        restoreSettings()
        scanButton.setOnClickListener { if (scanning) stopScan() else ensureReadyAndScan() }
        streamButton.setOnClickListener { toggleStreaming() }
        accSwitch.setOnCheckedChangeListener { _, _ -> updateControls(StreamState.state.value) }

        findViewById<SignalView>(R.id.ecgView).bind(StreamState.ecgPreview, "ECG (µV)")
        findViewById<SignalView>(R.id.accView).bind(StreamState.accPreview, "ACC X / Y / Z (mG)")
        findViewById<TextView>(R.id.versionText).text = runCatching {
            "PolarMobile ${BuildConfig.VERSION_NAME} · liblsl ${LslNative.libraryVersion() / 100}." +
                "${LslNative.libraryVersion() % 100}"
        }.getOrElse { "liblsl failed to load: ${it.message}" }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                StreamState.state.collect(::render)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !granted(Manifest.permission.POST_NOTIFICATIONS)
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) = showNetwork()
        override fun onLost(network: Network) = showNetwork()
    }

    override fun onStart() {
        super.onStart()
        showNetwork()
        getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(networkCallback)
    }

    override fun onStop() {
        super.onStop()
        getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkCallback)
    }

    private fun showNetwork() = runOnUiThread {
        findViewById<TextView>(R.id.networkText).text = networkSummary()
    }

    override fun onPause() {
        super.onPause()
        stopScan()
        saveSettings()
    }

    // --- settings -------------------------------------------------------------------------------

    private fun restoreSettings() {
        ecgSwitch.isChecked = prefs.getBoolean("ecg", true)
        accSwitch.isChecked = prefs.getBoolean("acc", true)
        hrSwitch.isChecked = prefs.getBoolean("hr", false)
        setupDropdown(accRate, PmdCommand.ACC_SAMPLE_RATES, prefs.getInt("accRate", 200))
        setupDropdown(accRange, PmdCommand.ACC_RANGES_G, prefs.getInt("accRange", 8))
        val lastAddress = prefs.getString("lastAddress", null)
        val lastName = prefs.getString("lastName", null)
        if (lastAddress != null && lastName != null) {
            devices += FoundDevice(lastAddress, lastName, 0)
            selected = lastAddress
        }
    }

    private fun saveSettings() = prefs.edit {
        putBoolean("ecg", ecgSwitch.isChecked)
        putBoolean("acc", accSwitch.isChecked)
        putBoolean("hr", hrSwitch.isChecked)
        putInt("accRate", accRate.text.toString().toIntOrNull() ?: 200)
        putInt("accRange", accRange.text.toString().toIntOrNull() ?: 8)
    }

    private fun setupDropdown(view: AutoCompleteTextView, values: List<Int>, current: Int) {
        val items = values.map { it.toString() }
        view.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, items))
        view.setText((if (current in values) current else values.last()).toString(), false)
    }

    // --- scanning -------------------------------------------------------------------------------

    private fun blePermissions(): List<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        listOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    private fun granted(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private val adapter: BluetoothAdapter?
        get() = getSystemService(BluetoothManager::class.java)?.adapter

    private fun ensureReadyAndScan() {
        val missing = blePermissions().filterNot(::granted)
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
            return
        }
        if (adapter?.isEnabled != true) {
            Snackbar.make(findViewById(R.id.root), R.string.bluetooth_off, Snackbar.LENGTH_LONG)
                .setAction(R.string.enable_bluetooth) {
                    enableBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                }.show()
            return
        }
        startScan()
    }

    @SuppressLint("MissingPermission")
    private fun startScan() {
        val scanner = adapter?.bluetoothLeScanner ?: return
        if (scanning) return
        devices.retainAll { it.address == selected }
        deviceAdapter.notifyDataSetChanged()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(null, settings, scanCallback)
        scanning = true
        handler.postDelayed(::stopScan, 20_000)
        updateScanUi()
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        if (!scanning) return
        handler.removeCallbacksAndMessages(null)
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
        scanning = false
        updateScanUi()
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.scanRecord?.deviceName ?: result.device.name ?: return
            if (!name.startsWith("Polar")) return
            val existing = devices.firstOrNull { it.address == result.device.address }
            if (existing != null) {
                existing.rssi = result.rssi
            } else {
                devices += FoundDevice(result.device.address, name, result.rssi)
                if (selected == null && name.contains("H10")) selected = result.device.address
            }
            deviceAdapter.notifyDataSetChanged()
            updateControls(StreamState.state.value)
        }

        override fun onScanFailed(errorCode: Int) {
            snackbar("Scan failed (error $errorCode)")
            scanning = false
            updateScanUi()
        }
    }

    private fun updateScanUi() {
        scanButton.setText(if (scanning) R.string.stop_scan else R.string.scan)
        scanProgress.visibility = if (scanning) View.VISIBLE else View.GONE
        noDevices.visibility = if (devices.isEmpty()) View.VISIBLE else View.GONE
    }

    // --- streaming ------------------------------------------------------------------------------

    private fun toggleStreaming() {
        if (StreamState.state.value.running) {
            startService(Intent(this, PolarStreamService::class.java).setAction(PolarStreamService.ACTION_STOP))
            return
        }
        val device = devices.firstOrNull { it.address == selected }
        if (device == null) {
            snackbar(getString(R.string.select_device))
            return
        }
        if (blePermissions().any { !granted(it) } || adapter?.isEnabled != true) {
            ensureReadyAndScan()
            return
        }
        stopScan()
        saveSettings()
        prefs.edit {
            putString("lastAddress", device.address)
            putString("lastName", device.name)
        }
        val intent = Intent(this, PolarStreamService::class.java)
            .setAction(PolarStreamService.ACTION_START)
            .putExtra(PolarStreamService.EXTRA_ADDRESS, device.address)
            .putExtra(PolarStreamService.EXTRA_NAME, device.name)
            .putExtra(PolarStreamService.EXTRA_ECG, ecgSwitch.isChecked)
            .putExtra(PolarStreamService.EXTRA_ACC, accSwitch.isChecked)
            .putExtra(PolarStreamService.EXTRA_ACC_RATE, accRate.text.toString().toIntOrNull() ?: 200)
            .putExtra(PolarStreamService.EXTRA_ACC_RANGE, accRange.text.toString().toIntOrNull() ?: 8)
            .putExtra(PolarStreamService.EXTRA_HR, hrSwitch.isChecked)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun render(s: StreamUiState) {
        updateControls(s)
        findViewById<TextView>(R.id.statusText).text =
            if (s.deviceName != null && s.running) "${s.deviceName}: ${s.status}" else s.status
        findViewById<TextView>(R.id.detailText).text = buildList {
            s.battery?.let { add("Battery $it%") }
            s.heartRate?.let { add("HR $it bpm") }
            if (s.ecgSamples > 0) add("ECG ${s.ecgSamples} samples")
            if (s.accSamples > 0) add("ACC ${s.accSamples} samples")
        }.joinToString("  ·  ")
        findViewById<TextView>(R.id.outletText).text = s.outlets.joinToString("\n") {
            (if (it.hasConsumers) "● " else "○ ") + it.name + if (it.hasConsumers) "  (consumer connected)" else ""
        }
        findViewById<TextView>(R.id.messageText).text = s.messages.joinToString("\n")
    }

    private fun updateControls(s: StreamUiState) {
        val idle = !s.running
        listOf(ecgSwitch, accSwitch, hrSwitch).forEach { it.isEnabled = idle }
        findViewById<View>(R.id.accRateLayout).isEnabled = idle && accSwitch.isChecked
        findViewById<View>(R.id.accRangeLayout).isEnabled = idle && accSwitch.isChecked
        accRate.isEnabled = idle && accSwitch.isChecked
        accRange.isEnabled = idle && accSwitch.isChecked
        scanButton.isEnabled = idle
        streamButton.isEnabled = s.running || selected != null
        streamButton.setText(if (s.running) R.string.stop_streaming else R.string.start_streaming)
        deviceAdapter.enabled = idle
        updateScanUi()
    }

    private fun networkSummary(): String {
        val cm = getSystemService(ConnectivityManager::class.java)
        val props = cm.activeNetwork?.let { cm.getLinkProperties(it) }
        val ips = props?.linkAddresses?.map { it.address }?.filterIsInstance<Inet4Address>()
            ?.map { it.hostAddress } ?: emptyList()
        return if (ips.isEmpty()) getString(R.string.no_network)
        else "LSL on ${props?.interfaceName ?: "network"}: ${ips.joinToString()}"
    }

    private fun snackbar(text: String) =
        Snackbar.make(findViewById(R.id.root), text, Snackbar.LENGTH_LONG).show()

    // --- device list ----------------------------------------------------------------------------

    private inner class DeviceAdapter : RecyclerView.Adapter<DeviceAdapter.Holder>() {
        var enabled = true
            set(value) {
                if (field != value) { field = value; notifyDataSetChanged() }
            }

        inner class Holder(v: View) : RecyclerView.ViewHolder(v) {
            val radio: RadioButton = v.findViewById(R.id.radio)
            val name: TextView = v.findViewById(R.id.name)
            val address: TextView = v.findViewById(R.id.address)
            val rssi: TextView = v.findViewById(R.id.rssi)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_device, parent, false))

        override fun getItemCount() = devices.size

        override fun onBindViewHolder(h: Holder, position: Int) {
            val d = devices[position]
            h.name.text = d.name
            h.address.text = d.address
            h.rssi.text = if (d.rssi != 0) "${d.rssi} dBm" else ""
            h.radio.isChecked = d.address == selected
            h.itemView.isEnabled = enabled
            h.radio.isEnabled = enabled
            h.itemView.setOnClickListener {
                if (!enabled) return@setOnClickListener
                selected = d.address
                notifyDataSetChanged()
                updateControls(StreamState.state.value)
            }
        }
    }
}
