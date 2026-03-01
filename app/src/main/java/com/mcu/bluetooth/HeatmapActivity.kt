package com.mcu.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.util.*
import kotlin.math.pow

@SuppressLint("MissingPermission")
class HeatmapActivity : AppCompatActivity() {

    private val SERVICE_UUID: UUID = UUID.fromString("00001111-0000-1000-8000-00805F9B34FB")

    private val bluetoothAdapter: BluetoothAdapter by lazy {
        (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    }
    private val bleScanner: BluetoothLeScanner by lazy { bluetoothAdapter.bluetoothLeScanner }

    private lateinit var heatmapView: HeatmapView
    private lateinit var studentCountTv: TextView

    private val kalmanFilters = mutableMapOf<String, KalmanFilter>()
    private val deviceData = mutableMapOf<String, Pair<Float, Long>>()
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_heatmap)

        heatmapView = findViewById(R.id.heatmap_view)
        studentCountTv = findViewById(R.id.student_count_tv)
        findViewById<Button>(R.id.back_button).setOnClickListener { finish() }

        startScanning()
        startCleanupLoop()
    }

    private fun startScanning() {
        // 使用更精確的過濾器
        val scanFilter = ScanFilter.Builder()
            .setServiceData(ParcelUuid(SERVICE_UUID), null)
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        bleScanner.startScan(listOf(scanFilter), settings, scanCallback)
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val scanRecord = result.scanRecord ?: return
            
            // 關鍵修改：雙重驗證。只有包含我們自定義 Service Data 的裝置才處理
            // 這能過濾掉其他雖然有相同 UUID 但資料格式不符的藍牙裝置
            val payload = scanRecord.getServiceData(ParcelUuid(SERVICE_UUID)) ?: return
            
            // 如果資料長度太短（小於我們的 HASH_SIZE），也視為無效裝置
            if (payload.size <= 6) return

            val address = result.device.address
            val rssi = result.rssi.toDouble()

            val filter = kalmanFilters.getOrPut(address) { 
                KalmanFilter(processNoise = 0.005, measurementNoise = 1.2) 
            }
            val filteredRssi = filter.filter(rssi)

            // 距離估算
            val distance = 10.0.pow((-59.0 - filteredRssi) / (10.0 * 2.5)).toFloat()

            deviceData[address] = Pair(distance, System.currentTimeMillis())

            runOnUiThread { updateUI() }
        }
    }

    private fun updateUI() {
        studentCountTv.text = "監測中 - 目前在線學生: ${deviceData.size}"
        heatmapView.updateDevices(deviceData.mapValues { it.value.first })
    }

    private fun startCleanupLoop() {
        handler.postDelayed(object : Runnable {
            override fun run() {
                val currentTime = System.currentTimeMillis()
                val removed = deviceData.entries.removeIf { currentTime - it.value.second > 10000 }
                if (removed) {
                    kalmanFilters.keys.removeIf { !deviceData.containsKey(it) }
                    updateUI()
                }
                handler.postDelayed(this, 2000)
            }
        }, 2000)
    }

    override fun onDestroy() {
        super.onDestroy()
        try { bleScanner.stopScan(scanCallback) } catch(e: Exception) {}
        handler.removeCallbacksAndMessages(null)
    }
}
