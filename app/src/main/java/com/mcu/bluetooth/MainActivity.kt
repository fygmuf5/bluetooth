package com.mcu.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.ParcelUuid
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.nio.charset.Charset
import java.security.MessageDigest
import java.util.*
import java.util.Collections

@SuppressLint("MissingPermission")
class MainActivity : AppCompatActivity() {

    // --- 安全與通訊設定 ---
    private val SERVICE_UUID: UUID = UUID.fromString("00001111-0000-1000-8000-00805F9B34FB")
    private val SECRET_KEY = "MCU_SECURE_KEY_2024"
    private val TIME_WINDOW_MS = 30000L           // Rolling Code 週期 (30秒)
    private val HASH_SIZE = 6                     // 驗證碼長度
    private val ROLLCALL_SIGNAL = "[ROLLCALL]"   // 固定的點名訊號關鍵字

    private val bluetoothManager by lazy { getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager }
    private val bluetoothAdapter: BluetoothAdapter by lazy { bluetoothManager.adapter }
    private val bleAdvertiser: BluetoothLeAdvertiser by lazy { bluetoothAdapter.bluetoothLeAdvertiser }
    private val bleScanner: BluetoothLeScanner by lazy { bluetoothAdapter.bluetoothLeScanner }

    private lateinit var statusTextView: TextView
    private lateinit var messageEditText: EditText
    private lateinit var broadcastButton: Button
    private lateinit var signalOnlyButton: Button
    private lateinit var scanToggleButton: ToggleButton
    private lateinit var goToHeatmapButton: Button
    private lateinit var devicesListView: ListView
    private lateinit var receivedBroadcastsAdapter: ArrayAdapter<String>

    private val latestMessages = mutableMapOf<String, String>()
    private val historyMessages = Collections.synchronizedList(mutableListOf<String>())

    private val requestBluetoothPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { perms ->
        if (perms.values.any { !it }) Toast.makeText(this, "Permission denied", Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        initializeUI()
        setupListeners()

        if (!hasRequiredBluetoothPermissions()) {
            requestBluetoothPermissions.launch(getRequiredBluetoothPermissions())
        }
    }

    private fun initializeUI() {
        statusTextView = findViewById(R.id.status_textview)
        messageEditText = findViewById(R.id.message_edittext)
        broadcastButton = findViewById(R.id.broadcast_button)
        signalOnlyButton = findViewById(R.id.signal_only_button)
        scanToggleButton = findViewById(R.id.scan_toggle_button)
        goToHeatmapButton = findViewById(R.id.go_to_heatmap_button)
        devicesListView = findViewById(R.id.devices_listview)
        receivedBroadcastsAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1)
        devicesListView.adapter = receivedBroadcastsAdapter
    }

    private fun setupListeners() {
        // 發送自定義文字廣播
        broadcastButton.setOnClickListener { broadcastSecureMessage(messageEditText.text.toString()) }
        
        // 只發送點名訊號 (不清除輸入框)
        signalOnlyButton.setOnClickListener { broadcastSecureMessage(ROLLCALL_SIGNAL) }

        scanToggleButton.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) startBleScan() else stopBleScan()
        }

        goToHeatmapButton.setOnClickListener {
            startActivity(Intent(this, HeatmapActivity::class.java))
        }
    }

    // --- 安全加密核心 ---

    private fun getRollingKeySource(timeOffset: Long): ByteArray {
        val timeBucket = (System.currentTimeMillis() + timeOffset) / TIME_WINDOW_MS
        return MessageDigest.getInstance("SHA-1").digest((SECRET_KEY + timeBucket).toByteArray())
    }

    private fun xorTransform(data: ByteArray, timeOffset: Long): ByteArray {
        val key = getRollingKeySource(timeOffset)
        return ByteArray(data.size) { i -> (data[i].toInt() xor key[i % key.size].toInt()).toByte() }
    }

    private fun generateRollingHash(message: String, timeOffset: Long): ByteArray {
        val timeBucket = (System.currentTimeMillis() + timeOffset) / TIME_WINDOW_MS
        val input = message + SECRET_KEY + timeBucket
        return MessageDigest.getInstance("SHA-1").digest(input.toByteArray()).take(HASH_SIZE).toByteArray()
    }

    private fun broadcastSecureMessage(message: String) {
        if (!hasPermission(Manifest.permission.BLUETOOTH_ADVERTISE)) return
        if (message.isBlank()) return

        // 1. 計算驗證雜湊
        val hashPart = generateRollingHash(message, 0L)
        // 2. 對訊息進行 XOR 加密
        val encryptedPart = xorTransform(message.toByteArray(Charset.forName("UTF-8")), 0L)
        val payload = hashPart + encryptedPart

        if (payload.size > 24) {
            Toast.makeText(this, "訊息過長", Toast.LENGTH_SHORT).show()
            return
        }

        try { bleAdvertiser.stopAdvertising(object : AdvertiseCallback(){}) } catch(e: Exception){}
        
        val settings = AdvertiseSettings.Builder().setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY).setConnectable(false).build()
        val data = AdvertiseData.Builder().addServiceUuid(ParcelUuid(SERVICE_UUID)).addServiceData(ParcelUuid(SERVICE_UUID), payload).build()

        bleAdvertiser.startAdvertising(settings, data, object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                statusTextView.text = if (message == ROLLCALL_SIGNAL) "Status: Sending Signal..." else "Status: Secure Broadcasting..."
            }
        })
    }

    // --- 掃描與解析 ---
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val scanRecord = result.scanRecord ?: return
            val address = result.device.address
            val payload = scanRecord.getServiceData(ParcelUuid(SERVICE_UUID)) ?: return
            
            var verifiedMsg: String? = null
            if (payload.size > HASH_SIZE) {
                val receivedHash = payload.take(HASH_SIZE).toByteArray()
                val encryptedContent = payload.drop(HASH_SIZE).toByteArray()

                // 嘗試在 Rolling Window 內還原訊息
                for (offset in listOf(0L, -TIME_WINDOW_MS, TIME_WINDOW_MS)) {
                    val decryptedBytes = xorTransform(encryptedContent, offset)
                    val testMsg = String(decryptedBytes, Charset.forName("UTF-8"))
                    if (generateRollingHash(testMsg, offset).contentEquals(receivedHash)) {
                        verifiedMsg = testMsg
                        break
                    }
                }
            }

            // 格式化顯示內容
            val finalMsg = if (verifiedMsg != null) {
                if (verifiedMsg == ROLLCALL_SIGNAL) "✅ [點名成功] 已收到訊號" else "[Secure] $verifiedMsg"
            } else {
                "[Plain] " + String(payload, Charset.forName("UTF-8"))
            }

            runOnUiThread {
                val current = latestMessages[address]
                if (current != finalMsg) {
                    if (current != null) {
                        val historyEntry = "$current\n[$address]"
                        if (historyMessages.isEmpty() || historyMessages[0] != historyEntry) {
                            historyMessages.add(0, historyEntry)
                            if (historyMessages.size > 50) historyMessages.removeAt(historyMessages.lastIndex)
                        }
                    }
                    latestMessages[address] = finalMsg
                    updateListView()
                }
            }
        }
    }

    private fun startBleScan() {
        latestMessages.clear()
        historyMessages.clear()
        updateListView()
        // 設定過濾器，只掃描我們頻道的訊息
        val filter = ScanFilter.Builder().setServiceData(ParcelUuid(SERVICE_UUID), null).build()
        bleScanner.startScan(listOf(filter), ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCallback)
        statusTextView.text = "Status: Message Scanning..."
    }

    private fun stopBleScan() {
        try { bleScanner.stopScan(scanCallback) } catch(e: Exception){}
        statusTextView.text = "Status: Ready"
    }

    private fun stopBleAdvertising() {
        try { bleAdvertiser.stopAdvertising(object : AdvertiseCallback(){}) } catch(e: Exception){}
    }

    private fun updateListView() {
        val displayList = mutableListOf<String>()
        if (latestMessages.isNotEmpty()) {
            displayList.add("=== 最新訊息 (Latest) ===")
            latestMessages.keys.sorted().forEach { addr ->
                displayList.add("${latestMessages[addr]}\n[$addr]")
            }
        }
        if (historyMessages.isNotEmpty()) {
            displayList.add("\n=== 歷史紀錄 (History) ===")
            displayList.addAll(historyMessages)
        }
        receivedBroadcastsAdapter.clear()
        receivedBroadcastsAdapter.addAll(displayList)
        receivedBroadcastsAdapter.notifyDataSetChanged()
    }

    private fun hasPermission(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
    private fun hasRequiredBluetoothPermissions() = getRequiredBluetoothPermissions().all { hasPermission(it) }
    private fun getRequiredBluetoothPermissions() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT)
    } else arrayOf(Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_ADMIN, Manifest.permission.ACCESS_FINE_LOCATION)
}
