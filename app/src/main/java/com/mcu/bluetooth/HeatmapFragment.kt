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
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import java.util.*
import kotlin.math.pow

@SuppressLint("MissingPermission")
class HeatmapFragment : Fragment() {

    private val SERVICE_UUID: UUID = UUID.fromString("00001111-0000-1000-8000-00805F9B34FB")

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        (requireContext().getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    }
    private val bleScanner: BluetoothLeScanner? by lazy { bluetoothAdapter?.bluetoothLeScanner }

    private lateinit var heatmapView: HeatmapView
    private lateinit var studentCountTv: TextView

    private val kalmanFilters = mutableMapOf<String, KalmanFilter>()
    private val deviceData = mutableMapOf<String, Pair<Float, Long>>()
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_heatmap, container, false)
        heatmapView = view.findViewById(R.id.heatmap_view)
        studentCountTv = view.findViewById(R.id.student_count_tv)
        return view
    }

    override fun onResume() {
        super.onResume()
        startScanning()
        startCleanupLoop()
    }

    override fun onPause() {
        super.onPause()
        stopScanning()
        handler.removeCallbacksAndMessages(null)
    }

    private fun startScanning() {
        val scanFilter = ScanFilter.Builder()
            .setServiceData(ParcelUuid(SERVICE_UUID), null)
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        bleScanner?.startScan(listOf(scanFilter), settings, scanCallback)
    }

    private fun stopScanning() {
        try { bleScanner?.stopScan(scanCallback) } catch(e: Exception) {}
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val scanRecord = result.scanRecord ?: return
            val payload = scanRecord.getServiceData(ParcelUuid(SERVICE_UUID)) ?: return
            if (payload.size <= 6) return

            val address = result.device.address
            val rssi = result.rssi.toDouble()

            val filter = kalmanFilters.getOrPut(address) { 
                KalmanFilter(processNoise = 0.005, measurementNoise = 1.2) 
            }
            val filteredRssi = filter.filter(rssi)
            val distance = 10.0.pow((-59.0 - filteredRssi) / (10.0 * 2.5)).toFloat()

            deviceData[address] = Pair(distance, System.currentTimeMillis())
            activity?.runOnUiThread { updateUI() }
        }
    }

    private fun updateUI() {
        if (!isAdded) return
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
}
