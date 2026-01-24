package com.mcu.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.ParcelUuid
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import android.widget.ToggleButton
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.nio.charset.Charset
import java.util.UUID

@SuppressLint("MissingPermission") // We check permissions dynamically
class MainActivity : AppCompatActivity() {

    private val SERVICE_UUID: UUID = UUID.fromString("00001234-0000-1000-8000-00805F9B34FB")

    private val bluetoothManager by lazy { getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager }
    private val bluetoothAdapter: BluetoothAdapter by lazy { bluetoothManager.adapter }
    private val bleAdvertiser: BluetoothLeAdvertiser by lazy { bluetoothAdapter.bluetoothLeAdvertiser }
    private val bleScanner: BluetoothLeScanner by lazy { bluetoothAdapter.bluetoothLeScanner }

    private lateinit var statusTextView: TextView
    private lateinit var messageEditText: EditText
    private lateinit var broadcastButton: Button
    private lateinit var scanToggleButton: ToggleButton
    private lateinit var goToHeatmapButton: Button
    private lateinit var devicesListView: ListView
    private lateinit var receivedBroadcastsAdapter: ArrayAdapter<String>

    private val receivedMessages = mutableMapOf<String, String>()

    private val requestBluetoothPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { perms ->
        if (perms.values.any { !it }) {
            Toast.makeText(this, "Required Bluetooth permissions not granted", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        initializeUI()
        setupListeners()

        if (!hasRequiredBluetoothPermissions()) {
            requestBluetoothPermissions.launch(getRequiredBluetoothPermissions())
        }

        if (!bluetoothAdapter.isEnabled) {
            if(hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
                 startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            }
        }
    }

    override fun onPause() {
        super.onPause()
        if (hasPermission(Manifest.permission.BLUETOOTH_SCAN) && scanToggleButton.isChecked) {
            stopBleScan()
            scanToggleButton.isChecked = false
        }
        if (hasPermission(Manifest.permission.BLUETOOTH_ADVERTISE)) {
            stopBleAdvertising()
        }
    }

    private fun initializeUI() {
        statusTextView = findViewById(R.id.status_textview)
        messageEditText = findViewById(R.id.message_edittext)
        broadcastButton = findViewById(R.id.broadcast_button)
        scanToggleButton = findViewById(R.id.scan_toggle_button)
        goToHeatmapButton = findViewById(R.id.go_to_heatmap_button)
        devicesListView = findViewById(R.id.devices_listview)
        receivedBroadcastsAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1)
        devicesListView.adapter = receivedBroadcastsAdapter
    }

    private fun setupListeners() {
        broadcastButton.setOnClickListener { broadcastMessage() }
        scanToggleButton.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) startBleScan()
            else stopBleScan()
        }
        // 跳轉到熱點圖 Activity
        goToHeatmapButton.setOnClickListener {
            val intent = Intent(this, HeatmapActivity::class.java)
            startActivity(intent)
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            statusTextView.text = "Status: Broadcasting"
        }
        override fun onStartFailure(errorCode: Int) {
            statusTextView.text = "Status: Broadcast Failed ($errorCode)"
        }
    }

    private fun broadcastMessage() {
        if (!hasPermission(Manifest.permission.BLUETOOTH_ADVERTISE)) return
        val message = messageEditText.text.toString()
        if (message.isBlank()) return
        val messageBytes = message.toByteArray(Charset.forName("UTF-8"))
        if (messageBytes.size > 20) return

        stopBleAdvertising()
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(false)
            .build()

        val parcelUuid = ParcelUuid(SERVICE_UUID)
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(parcelUuid)
            .addServiceData(parcelUuid, messageBytes)
            .build()
        bleAdvertiser.startAdvertising(settings, data, advertiseCallback)
    }

    private fun stopBleAdvertising() {
        if (hasPermission(Manifest.permission.BLUETOOTH_ADVERTISE)) {
            bleAdvertiser.stopAdvertising(advertiseCallback)
            statusTextView.text = "Status: Ready"
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val scanRecord = result.scanRecord ?: return
            val serviceData = scanRecord.getServiceData(ParcelUuid(SERVICE_UUID))
            if (serviceData != null) {
                val message = String(serviceData, Charset.forName("UTF-8"))
                receivedMessages[result.device.address] = message
                updateListView()
            }
        }
    }

    private fun startBleScan() {
        if (!hasPermission(Manifest.permission.BLUETOOTH_SCAN)) return
        val scanFilter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        bleScanner.startScan(listOf(scanFilter), settings, scanCallback)
        statusTextView.text = "Status: Scanning..."
    }

    private fun stopBleScan() {
        if (hasPermission(Manifest.permission.BLUETOOTH_SCAN)) {
            bleScanner.stopScan(scanCallback)
            statusTextView.text = "Status: Ready"
        }
    }

    private fun updateListView() {
        val displayList = receivedMessages.map { (address, message) -> "$message\n[$address]" }
        receivedBroadcastsAdapter.clear()
        receivedBroadcastsAdapter.addAll(displayList)
        receivedBroadcastsAdapter.notifyDataSetChanged()
    }

    private fun hasPermission(p: String): Boolean = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
    private fun hasRequiredBluetoothPermissions(): Boolean = getRequiredBluetoothPermissions().all { hasPermission(it) }
    private fun getRequiredBluetoothPermissions(): Array<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_ADMIN, Manifest.permission.ACCESS_FINE_LOCATION)
    }
}
