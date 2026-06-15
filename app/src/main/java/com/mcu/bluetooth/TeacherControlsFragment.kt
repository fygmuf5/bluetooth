package com.mcu.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.os.*
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
    private val REFRESH_INTERVAL = 5 * 60 * 1000L // 5 分鐘

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

    private val attendanceResults = mutableMapOf<String, String>()
    private val attendanceRecords = mutableMapOf<String, Pair<String, String>>()
    
    private var currentXorKey: String? = null
    private var otpVerifyList: Map<String, String>? = null
    private var isScanning = false

    private val handler = Handler(Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() {
            if (isScanning) {
                performSessionRefresh()
                handler.postDelayed(this, REFRESH_INTERVAL)
            }
        }
    }

    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        if (perms.values.all { it }) startSecureSessionLoop()
        else Toast.makeText(requireContext(), "未取得權限", Toast.LENGTH_LONG).show()
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
            if (!isScanning) {
                checkAndRequestPermissions()
            } else {
                stopAttendanceAndUpload()
            }
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

        if (required.all { ContextCompat.checkSelfPermission(requireContext(), it) == PackageManager.PERMISSION_GRANTED }) {
            startSecureSessionLoop()
        } else {
            requestPermissionsLauncher.launch(required)
        }
    }

    private fun startSecureSessionLoop() {
        isScanning = true
        btnStartAttendance.text = "停止點名"
        btnStartAttendance.backgroundTintList = ContextCompat.getColorStateList(requireContext(), android.R.color.holo_red_dark)
        btnStopAndUpload.isEnabled = true
        
        attendanceResults.clear()
        attendanceRecords.clear()
        updateListView()
        
        // 立即執行第一次並啟動循環
        performSessionRefresh()
        handler.postDelayed(refreshRunnable, REFRESH_INTERVAL)
        
        // 啟動藍牙掃描
        val filter = ScanFilter.Builder().setServiceData(ParcelUuid(SERVICE_UUID), null).build()
        bleScanner?.startScan(listOf(filter), ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCallback)
    }

    private fun performSessionRefresh() {
        val email = activity?.intent?.getStringExtra("EXTRA_EMAIL") ?: ""
        tvTeacherStatus.text = "正在同步伺服器點名訊號..."
        
        NetworkManager.startAttendanceSession(email) { xorKey ->
            activity?.runOnUiThread {
                if (xorKey != null) {
                    currentXorKey = xorKey
                    NetworkManager.getVerifyList(email) { list ->
                        activity?.runOnUiThread {
                            otpVerifyList = list
                            tvTeacherStatus.text = "✅ 點名自動同步中 (${SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())})"
                        }
                    }
                }
            }
        }
    }

    private fun stopAttendanceAndUpload() {
        isScanning = false
        handler.removeCallbacks(refreshRunnable)
        try { bleScanner?.stopScan(scanCallback) } catch(e: Exception){}
        
        btnStartAttendance.text = "開始加密點名"
        btnStartAttendance.backgroundTintList = ContextCompat.getColorStateList(requireContext(), R.color.design_default_color_primary) // 或原本顏色
        btnStopAndUpload.isEnabled = false
        
        tvTeacherStatus.text = "正在回傳名單..."
        val recordList = attendanceRecords.values.toList()
        if (recordList.isEmpty()) {
            tvTeacherStatus.text = "點名結束 (無紀錄)"
            return
        }

        var count = 0
        attendanceRecords.forEach { (address, pair) ->
            NetworkManager.syncAttendance(pair.first, address) {
                count++
                if (count == attendanceRecords.size) {
                    activity?.runOnUiThread {
                        tvTeacherStatus.text = "點名結束，已回傳 $count 筆紀錄"
                        Toast.makeText(requireContext(), "回傳完成", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val payload = result.scanRecord?.getServiceData(ParcelUuid(SERVICE_UUID)) ?: return
            val address = result.device.address
            val xorKey = currentXorKey ?: return
            
            val keyBytes = xorKey.toByteArray(Charset.forName("UTF-8"))
            val decryptedBytes = ByteArray(payload.size)
            for (i in payload.indices) {
                decryptedBytes[i] = (payload[i].toInt() xor keyBytes[i % keyBytes.size].toInt()).toByte()
            }
            val decryptedStr = String(decryptedBytes, Charset.forName("UTF-8"))

            if (decryptedStr.contains("|")) {
                val parts = decryptedStr.split("|")
                if (parts.size >= 2) {
                    val studentId = parts[0]
                    val receivedOtp = parts[1]
                    val expectedOtp = otpVerifyList?.get(studentId)
                    if (expectedOtp != null && receivedOtp == expectedOtp) {
                        processCheckInResult(studentId, address, true)
                    }
                }
            }
        }
    }

    private fun processCheckInResult(id: String, address: String, isSuccess: Boolean) {
        val displayMsg = "[$id] ✅ 點名成功\n設備: $address"
        val timeString = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        activity?.runOnUiThread {
            if (attendanceResults[address] != displayMsg) {
                attendanceResults[address] = displayMsg
                attendanceRecords[address] = Pair(id, timeString)
                updateListView()
            }
        }
    }

    private fun updateListView() {
        val displayList = attendanceResults.values.toList().reversed()
        receivedBroadcastsAdapter.clear()
        receivedBroadcastsAdapter.addAll(displayList)
        receivedBroadcastsAdapter.notifyDataSetChanged()
    }

    private fun exportAttendanceToCsv() {
        if (attendanceRecords.isEmpty()) return
        val fileName = "點名紀錄_${SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(Date())}.csv"
        val csvContent = StringBuilder().append("學號,設備地址,簽到時間\n")
        attendanceRecords.forEach { (address, pair) -> csvContent.append("${pair.first},$address,${pair.second}\n") }
        try {
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = requireContext().contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
            uri?.let {
                requireContext().contentResolver.openOutputStream(it).use { os ->
                    os?.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))
                    os?.write(csvContent.toString().toByteArray(Charset.forName("UTF-8")))
                }
                Toast.makeText(requireContext(), "檔案已儲存：$fileName", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(refreshRunnable)
        try { bleScanner?.stopScan(scanCallback) } catch(e: Exception){}
    }
}
