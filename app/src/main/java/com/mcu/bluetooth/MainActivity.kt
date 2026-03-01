package com.mcu.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.ParcelUuid
import android.provider.MediaStore
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.Group
import androidx.core.content.ContextCompat
import java.nio.charset.Charset
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.*

@SuppressLint("MissingPermission")
class MainActivity : AppCompatActivity() {

    // --- 安全與通訊設定 ---
    private val SERVICE_UUID: UUID = UUID.fromString("00001111-0000-1000-8000-00805F9B34FB")
    private val SECRET_KEY = "MCU_SECURE_KEY_2024"
    private val TIME_WINDOW_MS = 30000L
    private val HASH_SIZE = 6

    private val bluetoothManager by lazy { getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager }
    private val bluetoothAdapter: BluetoothAdapter by lazy { bluetoothManager.adapter }
    private val bleAdvertiser: BluetoothLeAdvertiser by lazy { bluetoothAdapter.bluetoothLeAdvertiser }
    private val bleScanner: BluetoothLeScanner by lazy { bluetoothAdapter.bluetoothLeScanner }

    private lateinit var statusTextView: TextView
    private lateinit var messageEditText: EditText
    private lateinit var broadcastButton: Button
    private lateinit var scanToggleButton: ToggleButton
    private lateinit var goToHeatmapButton: Button
    private lateinit var exportCsvButton: Button
    private lateinit var devicesListView: ListView
    private lateinit var backToRoleButton: Button
    private lateinit var receivedBroadcastsAdapter: ArrayAdapter<String>

    private lateinit var studentGroup: Group
    private lateinit var teacherGroup: Group

    private var currentRole: String? = null
    private val latestMessages = mutableMapOf<String, String>()
    private val historyMessages = Collections.synchronizedList(mutableListOf<String>())
    
    // 用於導出 CSV 的數據結構：地址 -> (學生資訊, 最後時間)
    private val attendanceRecords = mutableMapOf<String, Pair<String, String>>()

    private val sharedPrefs by lazy { getSharedPreferences("MCU_BT_PREFS", Context.MODE_PRIVATE) }

    private val requestBluetoothPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { perms ->
        val allGranted = perms.values.all { it }
        if (allGranted) {
            Toast.makeText(this, "權限已取得，請再次點擊按鈕", Toast.LENGTH_SHORT).show()
        } else {
            showPermissionExplanation()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        currentRole = intent.getStringExtra("EXTRA_ROLE")

        initializeUI()
        setupRoleUI()
        setupListeners()
        loadSavedStudentInfo()
    }

    private fun initializeUI() {
        statusTextView = findViewById(R.id.status_textview)
        messageEditText = findViewById(R.id.message_edittext)
        broadcastButton = findViewById(R.id.broadcast_button)
        scanToggleButton = findViewById(R.id.scan_toggle_button)
        goToHeatmapButton = findViewById(R.id.go_to_heatmap_button)
        exportCsvButton = findViewById(R.id.export_csv_button)
        devicesListView = findViewById(R.id.devices_listview)
        backToRoleButton = findViewById(R.id.back_to_role_selection)
        studentGroup = findViewById(R.id.student_group)
        teacherGroup = findViewById(R.id.teacher_group)

        receivedBroadcastsAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1)
        devicesListView.adapter = receivedBroadcastsAdapter
    }

    private fun loadSavedStudentInfo() {
        if (currentRole == "STUDENT") {
            val savedInfo = sharedPrefs.getString("STUDENT_INFO", "")
            messageEditText.setText(savedInfo)
        }
    }

    private fun setupRoleUI() {
        when (currentRole) {
            "TEACHER" -> {
                teacherGroup.visibility = View.VISIBLE
                studentGroup.visibility = View.GONE
                statusTextView.text = "身份: 老師 (接收模式)"
            }
            "STUDENT" -> {
                teacherGroup.visibility = View.GONE
                studentGroup.visibility = View.VISIBLE
                statusTextView.text = "身份: 學生 (發送模式)"
            }
            else -> {
                startActivity(Intent(this, RoleSelectionActivity::class.java))
                finish()
            }
        }
    }

    private fun setupListeners() {
        messageEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                sharedPrefs.edit().putString("STUDENT_INFO", s.toString()).apply()
            }
        })

        broadcastButton.setOnClickListener { 
            val content = messageEditText.text.toString().trim()
            if (content.isEmpty()) {
                Toast.makeText(this, "請先輸入學號姓名", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (checkAndRequestPermissions()) broadcastSecureMessage(content) 
        }

        scanToggleButton.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                if (checkAndRequestPermissions()) startBleScan() else scanToggleButton.isChecked = false
            } else {
                stopBleScan()
            }
        }

        goToHeatmapButton.setOnClickListener {
            if (checkAndRequestPermissions()) startActivity(Intent(this, HeatmapActivity::class.java))
        }

        exportCsvButton.setOnClickListener { exportAttendanceToCsv() }

        backToRoleButton.setOnClickListener {
            stopBleScan()
            stopBleAdvertising()
            startActivity(Intent(this, RoleSelectionActivity::class.java))
            finish()
        }
    }

    private fun checkAndRequestPermissions(): Boolean {
        if (hasRequiredBluetoothPermissions()) return true
        
        AlertDialog.Builder(this)
            .setTitle("需要權限")
            .setMessage("本功能需要藍牙與定位權限來發送/接收點名訊號。請在接下來的對話框中允許權限。")
            .setPositiveButton("確定") { _, _ ->
                requestBluetoothPermissions.launch(getRequiredBluetoothPermissions())
            }
            .setNegativeButton("取消", null)
            .show()
        return false
    }

    private fun showPermissionExplanation() {
        Toast.makeText(this, "未取得必要權限，功能無法運作", Toast.LENGTH_LONG).show()
    }

    private fun exportAttendanceToCsv() {
        if (attendanceRecords.isEmpty()) {
            Toast.makeText(this, "目前沒有點名紀錄", Toast.LENGTH_SHORT).show()
            return
        }

        val fileName = "點名紀錄_${SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(Date())}.csv"
        val csvContent = StringBuilder()
        csvContent.append("學號/姓名,設備地址,最後更新時間\n")
        
        attendanceRecords.forEach { (address, pair) ->
            val (info, time) = pair
            csvContent.append("\"$info\",\"$address\",\"$time\"\n")
        }

        try {
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
            }

            val resolver = contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)

            if (uri != null) {
                resolver.openOutputStream(uri).use { outputStream ->
                    outputStream?.write(0xEF) // BOM for Excel UTF-8
                    outputStream?.write(0xBB)
                    outputStream?.write(0xBF)
                    outputStream?.write(csvContent.toString().toByteArray(Charset.forName("UTF-8")))
                }
                Toast.makeText(this, "檔案已儲存至下載資料夾：\n$fileName", Toast.LENGTH_LONG).show()
            } else {
                throw Exception("無法建立檔案")
            }
        } catch (e: Exception) {
            Toast.makeText(this, "匯出失敗: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

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

        val hashPart = generateRollingHash(message, 0L)
        val encryptedPart = xorTransform(message.toByteArray(Charset.forName("UTF-8")), 0L)
        val payload = hashPart + encryptedPart

        if (payload.size > 24) {
            Toast.makeText(this, "訊息過長，請簡短學號姓名", Toast.LENGTH_SHORT).show()
            return
        }

        stopBleAdvertising()
        val settings = AdvertiseSettings.Builder().setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY).setConnectable(false).build()
        val data = AdvertiseData.Builder().addServiceUuid(ParcelUuid(SERVICE_UUID)).addServiceData(ParcelUuid(SERVICE_UUID), payload).build()

        bleAdvertiser.startAdvertising(settings, data, object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                runOnUiThread {
                    statusTextView.text = "Status: 正在發送簽到訊息..."
                }
            }
        })
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val scanRecord = result.scanRecord ?: return
            val address = result.device.address
            val payload = scanRecord.getServiceData(ParcelUuid(SERVICE_UUID)) ?: return
            
            var verifiedMsg: String? = null
            if (payload.size > HASH_SIZE) {
                val receivedHash = payload.take(HASH_SIZE).toByteArray()
                val encryptedContent = payload.drop(HASH_SIZE).toByteArray()

                for (offset in listOf(0L, -TIME_WINDOW_MS, TIME_WINDOW_MS)) {
                    val decryptedBytes = xorTransform(encryptedContent, offset)
                    val testMsg = String(decryptedBytes, Charset.forName("UTF-8"))
                    if (generateRollingHash(testMsg, offset).contentEquals(receivedHash)) {
                        verifiedMsg = testMsg
                        break
                    }
                }
            }

            if (verifiedMsg != null) {
                val finalMsg = "✅ [簽到成功] $verifiedMsg"
                val timeString = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                attendanceRecords[address] = Pair(verifiedMsg, timeString)

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
    }

    private fun startBleScan() {
        if (!hasPermission(Manifest.permission.BLUETOOTH_SCAN)) return
        latestMessages.clear()
        historyMessages.clear()
        attendanceRecords.clear()
        updateListView()
        val filter = ScanFilter.Builder().setServiceData(ParcelUuid(SERVICE_UUID), null).build()
        bleScanner.startScan(listOf(filter), ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCallback)
        statusTextView.text = "Status: 正在掃描簽到訊息..."
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
            displayList.add("=== 已簽到名單 (${latestMessages.size}) ===")
            latestMessages.keys.sorted().forEach { addr ->
                displayList.add("${latestMessages[addr]}\n[$addr]")
            }
        }
        if (historyMessages.isNotEmpty()) {
            displayList.add("\n=== 歷史紀錄 ===")
            displayList.addAll(historyMessages)
        }
        receivedBroadcastsAdapter.clear()
        receivedBroadcastsAdapter.addAll(displayList)
        receivedBroadcastsAdapter.notifyDataSetChanged()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopBleScan()
        stopBleAdvertising()
    }

    private fun hasPermission(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
    private fun hasRequiredBluetoothPermissions() = getRequiredBluetoothPermissions().all { hasPermission(it) }
    private fun getRequiredBluetoothPermissions() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT)
    } else arrayOf(Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_ADMIN, Manifest.permission.ACCESS_FINE_LOCATION)
}
