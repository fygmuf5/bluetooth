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
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import java.nio.charset.Charset
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.*

@SuppressLint("MissingPermission")
class TeacherControlsFragment : Fragment() {

    private val SERVICE_UUID: UUID = UUID.fromString("00001111-0000-1000-8000-00805F9B34FB")
    private val SECRET_KEY = "MCU_SECURE_KEY_2024"
    private val TIME_WINDOW_MS = 30000L
    private val HASH_SIZE = 6

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
                if (hasRequiredPermissions()) startBleScan() else scanToggleButton.isChecked = false
            } else {
                stopBleScan()
            }
        }
        exportCsvButton.setOnClickListener { exportAttendanceToCsv() }
    }

    private fun hasRequiredPermissions(): Boolean {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        
        return permissions.all { ContextCompat.checkSelfPermission(requireContext(), it) == PackageManager.PERMISSION_GRANTED }
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

    private fun xorTransform(data: ByteArray, timeOffset: Long): ByteArray {
        val timeBucket = (System.currentTimeMillis() + timeOffset) / TIME_WINDOW_MS
        val key = MessageDigest.getInstance("SHA-1").digest((SECRET_KEY + timeBucket).toByteArray())
        return ByteArray(data.size) { i -> (data[i].toInt() xor key[i % key.size].toInt()).toByte() }
    }

    private fun generateRollingHash(message: String, timeOffset: Long): ByteArray {
        val timeBucket = (System.currentTimeMillis() + timeOffset) / TIME_WINDOW_MS
        val input = message + SECRET_KEY + timeBucket
        return MessageDigest.getInstance("SHA-1").digest(input.toByteArray()).take(HASH_SIZE).toByteArray()
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
