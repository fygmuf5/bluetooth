package com.mcu.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlin.math.pow

@SuppressLint("MissingPermission")
class HeatmapActivity : AppCompatActivity() {

    private val bluetoothAdapter: BluetoothAdapter by lazy {
        (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    }
    private val bleScanner: BluetoothLeScanner by lazy { bluetoothAdapter.bluetoothLeScanner }

    private lateinit var heatmapView: HeatmapView
    private lateinit var studentCountTv: TextView
    
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
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        bleScanner.startScan(null, settings, scanCallback)
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val address = result.device.address
            val rssi = result.rssi
            
            // 使用 RSSI 估算距離 (公尺)
            // 公式：d = 10^((Measured Power - RSSI) / (10 * N))
            // 這裡取經驗值：Measured Power = -59, N = 2.0
            val distance = 10.0.pow((-59 - rssi) / (10.0 * 2.0)).toFloat()
            
            deviceData[address] = Pair(distance, System.currentTimeMillis())
            updateUI()
        }
    }

    private fun updateUI() {
        studentCountTv.text = "偵測到學生數: ${deviceData.size}"
        
        // 將 Map<地址, Pair(距離, 時間)> 轉換為 Map<地址, 距離> 傳給 View
        val distances = deviceData.mapValues { it.value.first }
        heatmapView.updateDevices(distances)
    }

    private fun startCleanupLoop() {
        handler.postDelayed(object : Runnable {
            override fun run() {
                val currentTime = System.currentTimeMillis()
                val changed = deviceData.entries.removeIf { currentTime - it.value.second > 5000 }
                if (changed) updateUI()
                handler.postDelayed(this, 1000)
            }
        }, 1000)
    }

    override fun onDestroy() {
        super.onDestroy()
        bleScanner.stopScan(scanCallback)
        handler.removeCallbacksAndMessages(null)
    }
}