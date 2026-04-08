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

    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        val allGranted = perms.values.all { it }
        if (allGranted) {
            startBleScan()
        } else {
            Toast.makeText(requireContext(), "未取得藍牙掃描權限，無法接收點名", Toast.LENGTH_LONG).show()
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
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        val missing = required.filter {
            ContextCompat.checkSelfPermission(requireContext(), it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isEmpty()) {
            startBleScan()
        } else {
            AlertDialog.Builder(requireContext())
                .setTitle("需要權限")
                .setMessage("接收點名訊息需要藍牙掃描與定位權限。")
                .setPositiveButton("確定") { _, _ ->
                    requestPermissionsLauncher.launch(required)
                }
                .setNegativeButton("取消") { _, _ ->
                    scanToggleButton.isChecked = false
                }
                .show()
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val scanRecord = result.scanRecord ?: return
            val address = result.device.address
            val payload = scanRecord.getServiceData(ParcelUuid(SERVICE_UUID)) ?: return
            
            val verifiedMsg = String(payload, Charset.forName("UTF-8"))

            val finalMsg = "✅ [簽到成功] $verifiedMsg"
            val timeString = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            
            // 檢查是否為重複簽到
            val isNewRecord = !attendanceRecords.containsKey(address) || attendanceRecords[address]?.first != verifiedMsg
            
            attendanceRecords[address] = Pair(verifiedMsg, timeString)

            if (isNewRecord) {
                // 同步到伺服器
                NetworkManager.syncAttendance(verifiedMsg, address) { success ->
                    if (!success) Log.e("TeacherFragment", "Failed to sync to server for $address")
                }
            }

            activity?.runOnUiThread {
                val current = latestMessages[address]
                if (current != finalMsg) {
                    if (current != null) {
                        val historyEntry = "$current\n[$address]"
                        if (historyMessages.isEmpty() || historyMessages[0] != historyEntry) {
                            historyMessages.add(0, historyEntry)
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

    private fun exportAttendanceToCsv() {
        if (attendanceRecords.isEmpty()) {
            Toast.makeText(requireContext(), "目前沒有點名紀錄", Toast.LENGTH_SHORT).show()
            return
        }
        val fileName = "點名紀錄_${SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(Date())}.csv"
        val csvContent = StringBuilder().append("學號/姓名,設備地址,最後更新時間\n")
        attendanceRecords.forEach { (address, pair) ->
            csvContent.append("\"${pair.first}\",\"$address\",\"${pair.second}\"\n")
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
