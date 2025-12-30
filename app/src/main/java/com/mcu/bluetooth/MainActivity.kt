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

    // A custom 16-bit UUID to identify our app's broadcasts, keeping the packet size small.
    private val SERVICE_UUID: UUID = UUID.fromString("00001111-0000-1000-8000-00805F9B34FB")

    // --- Bluetooth Components ---
    private val bluetoothManager by lazy { getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager }
    private val bluetoothAdapter: BluetoothAdapter by lazy { bluetoothManager.adapter }
    private val bleAdvertiser: BluetoothLeAdvertiser by lazy { bluetoothAdapter.bluetoothLeAdvertiser }
    private val bleScanner: BluetoothLeScanner by lazy { bluetoothAdapter.bluetoothLeScanner }

    // --- UI Components ---
    private lateinit var statusTextView: TextView
    private lateinit var messageEditText: EditText
    private lateinit var broadcastButton: Button
    private lateinit var scanToggleButton: ToggleButton
    private lateinit var devicesListView: ListView
    private lateinit var receivedBroadcastsAdapter: ArrayAdapter<String>

    // --- State Management ---
    private val latestMessages = mutableMapOf<String, String>() // Map<DeviceAddress, Message>
    private val historyMessages = mutableListOf<String>()       // List of historical messages

    // --- Permissions ---
    private val requestBluetoothPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { perms ->
        if (perms.values.any { !it }) {
            Toast.makeText(this, "Required Bluetooth permissions not granted", Toast.LENGTH_LONG).show()
        }
    }

    // --- Activity Lifecycle ---
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        initializeUI()
        setupListeners()

        if (!hasRequiredBluetoothPermissions()) {
            requestBluetoothPermissions.launch(getRequiredBluetoothPermissions())
        }

        // Ensure Bluetooth is enabled
        if (!bluetoothAdapter.isEnabled) {
            if(hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
                startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            }
        }
    }

    override fun onPause() {
        super.onPause()
        // Stop scanning and advertising to save battery when the app is not in the foreground
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
    }

    // --- BLE Advertising ---
    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            statusTextView.text = "Status: Broadcasting"
        }

        override fun onStartFailure(errorCode: Int) {
            val errorMessage = when (errorCode) {
                ADVERTISE_FAILED_DATA_TOO_LARGE -> "Broadcast Failed: Data is too large. Try a shorter message."
                ADVERTISE_FAILED_ALREADY_STARTED -> "Broadcast Failed: Already started."
                else -> "Broadcast Failed (Code: $errorCode)"
            }
            statusTextView.text = "Status: $errorMessage"
        }
    }

    private fun broadcastMessage() {
        if (!hasPermission(Manifest.permission.BLUETOOTH_ADVERTISE)) {
            Toast.makeText(this, "BLUETOOTH_ADVERTISE permission needed", Toast.LENGTH_SHORT).show()
            return
        }

        val message = messageEditText.text.toString()
        if (message.isBlank()) {
            Toast.makeText(this, "Message cannot be empty", Toast.LENGTH_SHORT).show()
            return
        }

        val messageBytes = message.toByteArray(Charset.forName("UTF-8"))
        if (messageBytes.size > 20) { // Keep payload small
            Toast.makeText(this, "Message is too long to broadcast (max 20 bytes)", Toast.LENGTH_LONG).show()
            return
        }

        stopBleAdvertising()

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
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

    // --- BLE Scanning ---
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            super.onScanResult(callbackType, result)
            val scanRecord = result.scanRecord ?: return

            val serviceData = scanRecord.getServiceData(ParcelUuid(SERVICE_UUID))
            if (serviceData != null) {
                val message = String(serviceData, Charset.forName("UTF-8"))
                val deviceAddress = result.device.address

                // 檢查是否需要更新歷史紀錄
                val previousMessage = latestMessages[deviceAddress]
                if (previousMessage != null && previousMessage != message) {
                    // 如果訊息改變了，將舊訊息加入歷史紀錄
                    historyMessages.add(0, "$previousMessage\n[$deviceAddress]")
                    // 限制歷史紀錄數量，避免過多
                    if (historyMessages.size > 50) {
                        historyMessages.removeAt(historyMessages.lastIndex)
                    }
                }

                // 更新最新訊息
                if (latestMessages[deviceAddress] != message) {
                    latestMessages[deviceAddress] = message
                    updateListView()
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            statusTextView.text = "Status: Scan Failed (Code: $errorCode)"
            scanToggleButton.isChecked = false
        }
    }

    private fun startBleScan() {
        if (!hasPermission(Manifest.permission.BLUETOOTH_SCAN)) {
            Toast.makeText(this, "BLUETOOTH_SCAN permission needed", Toast.LENGTH_SHORT).show()
            scanToggleButton.isChecked = false
            return
        }

        // 清空列表以開始新的掃描
        latestMessages.clear()
        historyMessages.clear()
        updateListView()

        val scanFilter = ScanFilter.Builder()
            .setServiceData(ParcelUuid(SERVICE_UUID), null)
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

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
        val displayList = mutableListOf<String>()

        if (latestMessages.isNotEmpty()) {
            displayList.add("=== 最新訊息 (Latest) ===")
            for ((address, message) in latestMessages) {
                displayList.add("$message\n[$address]")
            }
        }

        if (historyMessages.isNotEmpty()) {
            if (displayList.isNotEmpty()) {
                displayList.add("") // 空行分隔
            }
            displayList.add("=== 歷史訊息 (History) ===")
            displayList.addAll(historyMessages)
        }

        receivedBroadcastsAdapter.clear()
        receivedBroadcastsAdapter.addAll(displayList)
        receivedBroadcastsAdapter.notifyDataSetChanged()
    }

    // --- Permission Helpers ---
    private fun hasPermission(permission: String): Boolean = ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun hasRequiredBluetoothPermissions(): Boolean {
        return getRequiredBluetoothPermissions().all { permission ->
            hasPermission(permission)
        }
    }

    private fun getRequiredBluetoothPermissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.BLUETOOTH_CONNECT // Needed for enabling bluetooth adapter
        )
        else -> arrayOf(
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN,
            Manifest.permission.ACCESS_FINE_LOCATION // Scanning on older versions needs location
        )
    }
}
