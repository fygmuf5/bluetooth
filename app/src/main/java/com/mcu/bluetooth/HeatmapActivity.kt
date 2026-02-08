package com.mcu.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.util.UUID
import kotlin.math.pow

@SuppressLint("MissingPermission")
class HeatmapActivity : AppCompatActivity() {

    // 與 MainActivity 的安全通訊頻道保持一致
    private val SERVICE_UUID: UUID = UUID.fromString("00001111-0000-1000-8000-00805F9B34FB")

    private val bluetoothAdapter: BluetoothAdapter by lazy {
        (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    }
    private val bleScanner: BluetoothLeScanner by lazy { bluetoothAdapter.bluetoothLeScanner }

    private lateinit var heatmapView: HeatmapView
    private lateinit var studentCountTv: TextView

    // 儲存每個裝置的卡爾曼濾波器，讓位置移動更平滑
    private val kalmanFilters = mutableMapOf<String, KalmanFilter>()

    // 儲存裝置資訊：地址 -> Pair(距離(米), 最後更新時間)
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
        val scanFilter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        bleScanner.startScan(listOf(scanFilter), settings, scanCallback)
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val address = result.device.address
            val rssi = result.rssi.toDouble()

            // 1. 取得或建立該裝置的濾波器
            val filter = kalmanFilters.getOrPut(address) { KalmanFilter(processNoise = 0.008, measurementNoise = 0.8) }

            // 2. 進行濾波，平滑化 RSSI 訊號
            val filteredRssi = filter.filter(rssi)

            // 3. 使用濾波後的 RSSI 估算距離 (公尺)
            // Measured Power (1米處的RSSI) 假設為 -59
            val distance = 10.0.pow((-59.0 - filteredRssi) / (10.0 * 2.0)).toFloat()

            deviceData[address] = Pair(distance, System.currentTimeMillis())

            runOnUiThread { updateUI() }
        }
    }

    private fun updateUI() {
        studentCountTv.text = "本班監測中 - 目前在線學生: ${deviceData.size}"

        // 將距離數據傳給自定義 View 繪圖
        val distances = deviceData.mapValues { it.value.first }
        heatmapView.updateDevices(distances)
    }

    private fun startCleanupLoop() {
        handler.postDelayed(object : Runnable {
            override fun run() {
                val currentTime = System.currentTimeMillis()
                // 如果裝置超過 8 秒沒發送任何訊號，就視為離開
                val removed = deviceData.entries.removeIf { currentTime - it.value.second > 8000 }
                if (removed) {
                    // 同步清理濾波器
                    val activeAddresses = deviceData.keys
                    kalmanFilters.keys.removeIf { !activeAddresses.contains(it) }
                    updateUI()
                }
                handler.postDelayed(this, 1000)
            }
        }, 1000)
    }

    override fun onDestroy() {
        super.onDestroy()
        try { bleScanner.stopScan(scanCallback) } catch(e: Exception) {}
        handler.removeCallbacksAndMessages(null)
    }
}