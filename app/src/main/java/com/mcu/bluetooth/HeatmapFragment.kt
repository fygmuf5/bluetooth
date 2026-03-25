package com.mcu.bluetooth

import android.graphics.PointF
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment

class HeatmapFragment : Fragment() {

    private lateinit var heatmapView: HeatmapView
    private lateinit var studentCountTv: TextView
    private val handler = Handler(Looper.getMainLooper())

    // 模擬或從伺服器抓取到的學生座標資料
    private val studentLocations = mutableMapOf<String, PointF>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_heatmap, container, false)
        heatmapView = view.findViewById(R.id.heatmap_view)
        studentCountTv = view.findViewById(R.id.student_count_tv)
        return view
    }

    override fun onResume() {
        super.onResume()
        startDataSyncLoop()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacksAndMessages(null)
    }

    /**
     * 定期同步伺服器計算好的定位座標
     */
    private fun startDataSyncLoop() {
        handler.postDelayed(object : Runnable {
            override fun run() {
                fetchLocationsFromServer()
                handler.postDelayed(this, 1000) // 每秒更新一次
            }
        }, 1000)
    }

    private fun fetchLocationsFromServer() {
        // 這裡預留給 NetworkManager 抓取資料
        // 目前先用模擬數據測試 UI
        simulateServerData()
    }

    private fun simulateServerData() {
        if (!isAdded) return
        
        // 模擬幾位學生的座標移動，範圍在教室 8x10 米內
        studentLocations["Student_01"] = PointF(2f + (Math.random().toFloat() * 0.4f), 3f + (Math.random().toFloat() * 0.2f))
        studentLocations["Student_02"] = PointF(6f, 7f + (Math.random().toFloat() * 0.3f))
        studentLocations["Student_03"] = PointF(4f + (Math.random().toFloat() * 0.5f), 5f)
        
        activity?.runOnUiThread {
            heatmapView.updateStudentLocations(studentLocations)
            studentCountTv.text = "即時定位中 - 樹莓派接收端連線正常 (學生數: ${studentLocations.size})"
        }
    }
}
