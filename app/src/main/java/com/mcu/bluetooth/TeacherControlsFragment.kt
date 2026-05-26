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

    private lateinit var btnStartAttendance: Button
    private lateinit var btnStopAndUpload: Button
    private lateinit var exportCsvButton: Button
    private lateinit var devicesListView: ListView
    private lateinit var tvTeacherStatus: TextView
    private lateinit var receivedBroadcastsAdapter: ArrayAdapter<String>

    private val attendanceResults = mutableMapOf<String, String>() // Address -> Display String
    private val attendanceRecords = mutableMapOf<String, Pair<String, String>>() // Address -> (StudentId, Time)
    
    private var currentXorKey: String? = null
    private var otpVerifyList: Map<String, String>? = null
    private var isScanning = false

    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        val allGranted = perms.values.all { it }
        if (allGranted) {
            startSecureSession()
        } else {
            Toast.makeText(requireContext(), "未取得藍牙掃描權限", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_teacher_controls, container, false)
        btnStartAttendance = view.findViewById(R.id.btn_start_attendance)
        btnStopAndUpload = view.findViewById(R.id.btn_stop_and_upload)
        exportCsvButton = view.findViewById(R.id.export_csv_button)
        devicesListView = view.findViewById(R.id.devices_listview)
        tvTeacherStatus = view.findViewById(R.id.tv_teacher_status)
        
        receivedBroadcastsAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1)
        devicesListView.adapter = receivedBroadcastsAdapter
        
        setupListeners()
        return view
    }

    private fun setupListeners() {
        btnStartAttendance.setOnClickListener {
            checkAndRequestPermissions()
        }

        btnStopAndUpload.setOnClickListener {
            stopAttendanceAndUpload()
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
                .setNegativeButton("取消", null)
                .show()
        }
    }

    private fun startSecureSession() {
        val email = activity?.intent?.getStringExtra("EXTRA_EMAIL") ?: ""
        tvTeacherStatus.text = "正在獲取伺服器金鑰..."
        
        NetworkManager.startAttendanceSession(email) { xorKey ->
            activity?.runOnUiThread {
                if (xorKey != null) {
                    currentXorKey = xorKey
                    fetchOtpTableAndStart(email)
                } else {
                    tvTeacherStatus.text = "錯誤: 無法取得金鑰"
                }
            }
        }
    }

    private fun fetchOtpTableAndStart(email: String) {
        NetworkManager.getVerifyList(email) { list ->
            activity?.runOnUiThread {
                if (list != null && list.isNotEmpty()) {
                    otpVerifyList = list
                    tvTeacherStatus.text = "✅ 點名進行中 (已收到對照表)"
                    startBleScan()
                } else {
                    tvTeacherStatus.text = "❌ 未收到對應表格"
                    otpVerifyList = null
                    startBleScan() // 依然啟動掃描供測試
                }
            }
        }
    }

    private fun startBleScan() {
        attendanceResults.clear()
        attendanceRecords.clear()
        updateListView()
        
        val filter = ScanFilter.Builder().setServiceData(ParcelUuid(SERVICE_UUID), null).build()
        bleScanner?.startScan(listOf(filter), ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCallback)
        
        isScanning = true
        btnStartAttendance.isEnabled = false
        btnStopAndUpload.isEnabled = true
    }

    private fun stopAttendanceAndUpload() {
        stopBleScan()
        isScanning = false
        btnStartAttendance.isEnabled = true
        btnStopAndUpload.isEnabled = false
        
        tvTeacherStatus.text = "正在回傳名單至伺服器..."
        
        // 批次同步名單回伺服器 (這裡逐一呼叫 sync，實務上可改為批次 API)
        val recordList = attendanceRecords.values.toList()
        if (recordList.isEmpty()) {
            tvTeacherStatus.text = "點名結束 (無成功紀錄)"
            return
        }

        var successCount = 0
        recordList.forEach { (id, _) ->
            // 找到該學號對應的 address
            val address = attendanceRecords.filterValues { it.first == id }.keys.firstOrNull() ?: ""
            NetworkManager.syncAttendance(id, address) { success ->
                if (success) successCount++
                if (successCount == recordList.size || recordList.indexOf(Pair(id, "")) == recordList.size - 1) {
                    activity?.runOnUiThread {
                        tvTeacherStatus.text = "點名結束，已回傳 ${recordList.size} 筆紀錄"
                        Toast.makeText(requireContext(), "名單回傳完成", Toast.LENGTH_SHORT).show()
                    }
                }
            }
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
            val address = result.device.address

            // 3. 驗證 OTP
            val expectedOtp = otpVerifyList?.get(studentId)
            val isSuccess = expectedOtp != null && receivedOtp == expectedOtp

            processCheckInResult(studentId, address, isSuccess)
        }
    }

    private fun processCheckInResult(id: String, address: String, isSuccess: Boolean) {
        val statusText = if (isSuccess) "✅ 點名成功" else "❌ 點名失敗 (OTP不符)"
        val displayMsg = "[$id] $statusText\n設備: $address"
        val timeString = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        
        activity?.runOnUiThread {
            if (attendanceResults[address] != displayMsg) {
                attendanceResults[address] = displayMsg
                if (isSuccess) {
                    attendanceRecords[address] = Pair(id, timeString)
                }
                updateListView()
            }
        }
    }

    private fun stopBleScan() {
        try { bleScanner?.stopScan(scanCallback) } catch(e: Exception){}
    }

    private fun updateListView() {
        val displayList = attendanceResults.values.toList().reversed()
        receivedBroadcastsAdapter.clear()
        receivedBroadcastsAdapter.addAll(displayList)
        receivedBroadcastsAdapter.notifyDataSetChanged()
    }

    private fun exportAttendanceToCsv() {
        if (attendanceRecords.isEmpty()) {
            Toast.makeText(requireContext(), "目前沒有成功紀錄可匯出", Toast.LENGTH_SHORT).show()
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
