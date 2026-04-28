package com.mcu.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.ParcelUuid
import android.provider.MediaStore
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import java.nio.charset.Charset
import java.text.SimpleDateFormat
import java.util.*

@SuppressLint("MissingPermission")
class TeacherControlsFragment : Fragment() {

    private val SERVICE_UUID: UUID = UUID.fromString("00001111-0000-1000-8000-00805F9B34FB")

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        (requireContext().getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    }
    private val bleScanner: BluetoothLeScanner? by lazy { bluetoothAdapter?.bluetoothLeScanner }

    private lateinit var scanToggleButton: ToggleButton
    private lateinit var exportCsvButton: Button
    private lateinit var devicesListView: ListView
    private lateinit var receivedBroadcastsAdapter: ArrayAdapter<String>

    private val latestMessages = mutableMapOf<String, String>()
    private val historyMessages = Collections.synchronizedList(mutableListOf<String>())
    private val attendanceRecords = mutableMapOf<String, Pair<String, String>>()
    
    // 安全驗證相關
    private var currentXorKey: String? = null
    private var otpVerifyList: Map<String, String>? = null

    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        val allGranted = perms.values.all { it }
        if (allGranted) {
            startSecureSession()
        } else {
            Toast.makeText(requireContext(), "未取得藍牙掃描權限", Toast.LENGTH_LONG).show()
            scanToggleButton.isChecked = false
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_teacher_controls, container, false)
        scanToggleButton = view.findViewById(R.id.scan_toggle_button)
        exportCsvButton = view.findViewById(R.id.export_csv_button)
        devicesListView = view.findViewById(R.id.devices_listview)
        receivedBroadcastsAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1)
        devicesListView.adapter = receivedBroadcastsAdapter
        setupListeners()
        return view
    }

    private fun setupListeners() {
        scanToggleButton.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                checkAndRequestPermissions()
            } else {
                stopBleScan()
            }
        }
        exportCsvButton.setOnClickListener { exportAttendanceToCsv() }
    }

    private fun checkAndRequestPermissions() {
        val required = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

        val missing = required.filter {
            ContextCompat.checkSelfPermission(requireContext(), it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isEmpty()) {
            startSecureSession()
        } else {
            AlertDialog.Builder(requireContext())
                .setTitle("需要權限")
                .setMessage("接收點名訊息需要藍牙掃描與定位權限。")
                .setPositiveButton("確定") { _, _ -> requestPermissionsLauncher.launch(required) }
                .setNegativeButton("取消") { _, _ -> scanToggleButton.isChecked = false }
                .show()
        }
    }

    /**
     * 啟動安全點名 Session：先拿金鑰，再拿 OTP 名單，最後啟動掃描
     */
    private fun startSecureSession() {
        val email = activity?.intent?.getStringExtra("EXTRA_EMAIL") ?: ""
        
        // 1. 向伺服器拿 XOR 金鑰
        NetworkManager.startAttendanceSession(email) { xorKey ->
            activity?.runOnUiThread {
                if (xorKey != null) {
                    currentXorKey = xorKey
                    // 2. 拿 OTP 驗證清單
                    updateOtpVerifyList(email)
                    startBleScan()
                } else {
                    Toast.makeText(requireContext(), "無法啟動點名 Session", Toast.LENGTH_SHORT).show()
                    scanToggleButton.isChecked = false
                }
            }
        }
    }

    private fun updateOtpVerifyList(email: String) {
        NetworkManager.getVerifyList(email) { list ->
            otpVerifyList = list
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val payload = result.scanRecord?.getServiceData(ParcelUuid(SERVICE_UUID)) ?: return
            val xorKey = currentXorKey ?: return

            // 1. XOR 解密
            val keyBytes = xorKey.toByteArray(Charset.forName("UTF-8"))
            val decryptedBytes = ByteArray(payload.size)
            for (i in payload.indices) {
                decryptedBytes[i] = (payload[i].toInt() xor keyBytes[i % keyBytes.size].toInt()).toByte()
            }
            val decryptedStr = String(decryptedBytes, Charset.forName("UTF-8"))

            // 2. 解析封包 (學號|OTP)
            if (!decryptedStr.contains("|")) return
            val parts = decryptedStr.split("|")
            if (parts.size < 2) return
            val studentId = parts[0]
            val receivedOtp = parts[1]

            // 3. 驗證 OTP
            val expectedOtp = otpVerifyList?.get(studentId)
            if (receivedOtp == expectedOtp) {
                processValidCheckIn(studentId, result.device.address)
            }
        }
    }

    private fun processValidCheckIn(id: String, address: String) {
        val finalMsg = "✅ [簽到成功] $id"
        val timeString = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        
        activity?.runOnUiThread {
            if (latestMessages[address] != finalMsg) {
                latestMessages[address] = finalMsg
                attendanceRecords[address] = Pair(id, timeString)
                updateListView()
                // 同步結果回伺服器
                NetworkManager.syncAttendance(id, address) { _ -> }
            }
        }
    }

    private fun startBleScan() {
        latestMessages.clear()
        historyMessages.clear()
        attendanceRecords.clear()
        updateListView()
        val filter = ScanFilter.Builder().setServiceData(ParcelUuid(SERVICE_UUID), null).build()
        bleScanner?.startScan(listOf(filter), ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCallback)
    }

    private fun stopBleScan() {
        try { bleScanner?.stopScan(scanCallback) } catch(e: Exception){}
    }

    private fun updateListView() {
        val displayList = mutableListOf<String>()
        latestMessages.keys.sorted().forEach { addr ->
            displayList.add("${latestMessages[addr]}\n[$addr]")
        }
        receivedBroadcastsAdapter.clear()
        receivedBroadcastsAdapter.addAll(displayList)
        receivedBroadcastsAdapter.notifyDataSetChanged()
    }

    private fun exportAttendanceToCsv() {
        if (attendanceRecords.isEmpty()) {
            Toast.makeText(requireContext(), "目前沒有點名紀錄", Toast.LENGTH_SHORT).show()
            return
        }
        val fileName = "點名紀錄_${SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(Date())}.csv"
        val csvContent = StringBuilder().append("學號,設備地址,簽到時間\n")
        attendanceRecords.forEach { (address, pair) ->
            csvContent.append("${pair.first},$address,${pair.second}\n")
        }
        try {
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
            }
            val uri = requireContext().contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
            if (uri != null) {
                requireContext().contentResolver.openOutputStream(uri).use { 
                    it?.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))
                    it?.write(csvContent.toString().toByteArray(Charset.forName("UTF-8")))
                }
                Toast.makeText(requireContext(), "檔案已儲存：\n$fileName", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "匯出失敗: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopBleScan()
    }
}
