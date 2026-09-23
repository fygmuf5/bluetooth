package com.mcu.bluetooth

import android.graphics.PointF
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import java.text.SimpleDateFormat
import java.util.*

class HeatmapFragment : Fragment() {

    private lateinit var heatmapView: HeatmapView
    private lateinit var studentCountTv: TextView
    private val handler = Handler(Looper.getMainLooper())

    // 存放學生座標資料
    private val studentLocations = mutableMapOf<String, PointF>()
    // 本次點名的所有學生 ID 清單 (用於 Debug 防呆)
    private var fullStudentRoster = setOf<String>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_heatmap, container, false)
        heatmapView = view.findViewById(R.id.heatmap_view)
        studentCountTv = view.findViewById(R.id.student_count_tv)
        return view
    }

    override fun onResume() {
        super.onResume()
        // 進入畫面時先同步一次名單，再啟動座標輪詢
        syncRosterThenStartLoop()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacksAndMessages(null)
    }

    private fun syncRosterThenStartLoop() {
        val email = activity?.intent?.getStringExtra("EXTRA_EMAIL") ?: "teacher@example.com"
        NetworkManager.getVerifyList(email) { list ->
            if (list != null) {
                fullStudentRoster = list.keys
            }
            startDataSyncLoop()
        }
    }

    private fun startDataSyncLoop() {
        handler.postDelayed(object : Runnable {
            override fun run() {
                fetchLocationsFromServer()
                handler.postDelayed(this, 2000)
            }
        }, 1000)
    }

    private fun fetchLocationsFromServer() {
        if (!isAdded) return

        val email = activity?.intent?.getStringExtra("EXTRA_EMAIL") ?: "teacher@example.com"
        val password = "teacherPassword"
        val sessionId = "sess_" + SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(Date())

        NetworkManager.getStudentCoordinates(email, password, sessionId) { response ->
            if (response != null) {
                val coordsObj = response.optJSONObject("coords")
                
                studentLocations.clear()

                // 1. 先處理伺服器有回傳真實座標的學生
                val receivedIds = mutableSetOf<String>()
                if (coordsObj != null) {
                    val keys = coordsObj.keys()
                    while (keys.hasNext()) {
                        val studentId = keys.next()
                        val coordData = coordsObj.optJSONObject(studentId)
                        if (coordData != null) {
                            val x = coordData.optDouble("x", 0.0).toFloat()
                            val y = coordData.optDouble("y", 0.0).toFloat()
                            studentLocations[studentId] = PointF(x, y)
                            receivedIds.add(studentId)
                        }
                    }
                }

                // 2. Debug 防呆：對比名單，如果學生在名單內但座標消失，強制擺在 (0,0) 左上角
                fullStudentRoster.forEach { id ->
                    if (!receivedIds.contains(id)) {
                        studentLocations[id] = PointF(0f, 0f)
                    }
                }

                activity?.runOnUiThread {
                    heatmapView.updateStudentLocations(studentLocations)
                    val updatedAt = response.optString("updated_at", "未知")
                    val activeCount = receivedIds.size
                    val missingCount = fullStudentRoster.size - activeCount
                    
                    studentCountTv.text = "即時定位 - 已定位: $activeCount, 待掃描(左上角): $missingCount (更新: $updatedAt)"
                }
            } else {
                Log.e("HeatmapFragment", "座標抓取失敗")
            }
        }
    }
}
