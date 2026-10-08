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

/**
 * 教室熱力圖與即時定位 Fragment (HeatmapFragment)
 * 負責：
 * 1. 建立並展示 HeatmapView 自訂地圖元件
 * 2. 每 2 秒向後端 API 輪詢讀取所有學生之最新三點定位座標
 * 3. 統計已定位學生數與未定位學生數，即時更新至視圖標籤
 */
class HeatmapFragment : Fragment() {

    // UI 元件與 Handler 宣告
    private lateinit var heatmapView: HeatmapView
    private lateinit var studentCountTv: TextView
    private val handler = Handler(Looper.getMainLooper())

    // 存放學生座標資料映射：學號 -> PointF(x, y)
    private val studentLocations = mutableMapOf<String, PointF>()
    // 本次點名的全班學生 ID 集合 (用於未定位防呆)
    private var fullStudentRoster = setOf<String>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_heatmap, container, false)
        // 綁定自訂熱力圖 View 與統計文字標籤
        heatmapView = view.findViewById(R.id.heatmap_view)
        studentCountTv = view.findViewById(R.id.student_count_tv)
        return view
    }

    override fun onResume() {
        super.onResume()
        // 當 Fragment 可見時，先同步學生名單再啟動座標輪詢迴圈
        syncRosterThenStartLoop()
    }

    override fun onPause() {
        super.onPause()
        // 當 Fragment 離開前台時，停止 Handler 輪詢以節省系統資源與網路流量
        handler.removeCallbacksAndMessages(null)
    }

    /**
     * 先同步全班名單，成功後啟動輪詢迴圈
     */
    private fun syncRosterThenStartLoop() {
        val email = activity?.intent?.getStringExtra("EXTRA_EMAIL") ?: "teacher@example.com"
        NetworkManager.getVerifyList(email) { list ->
            if (list != null) {
                fullStudentRoster = list.keys
            }
            startDataSyncLoop()
        }
    }

    /**
     * 啟動座標輪詢 Task：每 2 秒發送一次請求向伺服器讀取座標
     */
    private fun startDataSyncLoop() {
        handler.postDelayed(object : Runnable {
            override fun run() {
                fetchLocationsFromServer()
                handler.postDelayed(this, 2000) // 2 秒後重複執行
            }
        }, 1000)
    }

    /**
     * 向伺服器 API (getStudentCoordinates) 請求最新學生座標，並更新畫面
     */
    private fun fetchLocationsFromServer() {
        if (!isAdded) return

        val email = activity?.intent?.getStringExtra("EXTRA_EMAIL") ?: "teacher@example.com"
        val password = "teacherPassword"
        val sessionId = "sess_" + SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(Date())

        NetworkManager.getStudentCoordinates(email, password, sessionId) { response ->
            if (response != null) {
                val coordsObj = response.optJSONObject("coords")
                
                studentLocations.clear()

                // 1. 處理伺服器回傳之有效學生座標
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

                // 2. 防呆處理：若學生在點名名單內但尚未被計算出座標，暫時歸類於 (0,0) 左上角待掃描區
                fullStudentRoster.forEach { id ->
                    if (!receivedIds.contains(id)) {
                        studentLocations[id] = PointF(0f, 0f)
                    }
                }

                // 3. 切換至 UI 主執行緒更新熱力圖 View 與統計數據文字
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
