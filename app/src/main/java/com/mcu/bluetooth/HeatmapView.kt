package com.mcu.bluetooth

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

/**
 * 教室熱力圖與即時定位自訂 View (HeatmapView)
 * 負責繪製：
 * 1. 教室邊界外框與網格線 (以米為單位的 8m x 10m 教室空間)
 * 2. 樹莓派 BLE 訊號接收器 (Pi 1, Pi 2, Pi 3) 之固定位置
 * 3. 學生定位座標點 (圓點與學號末 4 碼標籤)
 */
class HeatmapView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // 儲存學生當前座標數據：學號 -> (X 米, Y 米)
    private var deviceLocations = mutableMapOf<String, PointF>()

    // 三台樹莓派 (Raspberry Pi) 在教室中的相對米數座標
    private val piPositions = listOf(
        PointF(0f, 0f),       // Pi 1: 教室左上角 (0m, 0m)
        PointF(8f, 0f),       // Pi 2: 教室右上角 (8m, 0m)
        PointF(4f, 10f)       // Pi 3: 教室後方中間 (4m, 10m)
    )

    // 畫筆 (Paint) 宣告與屬性初始化
    private val studentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL } // 學生定位綠點畫筆
    private val piPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FF9800") } // 樹莓派橘色方塊畫筆
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 28f
        textAlign = Paint.Align.CENTER
    } // 標籤文字畫筆
    
    // 牆壁邊界畫筆 (外框)
    private val wallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 8f
    }
    
    // 格線畫筆 (網格)
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#EEEEEE") // 淺灰色
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    init {
        // 設定 Canvas 背景為白色
        setBackgroundColor(Color.WHITE)
    }

    /**
     * 更新學生座標點並觸發視圖重繪 (invalidate)
     * @param locations <學號, (x, y)> 座標映射表
     */
    fun updateStudentLocations(locations: Map<String, PointF>) {
        deviceLocations.clear()
        deviceLocations.putAll(locations)
        invalidate() // 請求系統重新呼叫 onDraw 重繪畫面
    }

    /**
     * 畫面繪製核心邏輯
     */
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // 定義繪製內邊距與教室實際米數 (長 8 米, 寬 10 米)
        val padding = 80f
        val metersX = 8f
        val metersY = 10f
        // 計算每 1 米對應的螢幕像素 (Pixel per Meter)
        val pxPerMeter = (width - 2 * padding) / metersX

        // 1. 繪製教室地圖背景矩形 (黑色底)
        val rect = RectF(padding, padding, padding + metersX * pxPerMeter, padding + metersY * pxPerMeter)
        val mapBgPaint = Paint().apply { color = Color.BLACK }
        canvas.drawRect(rect, mapBgPaint)

        // 2. 繪製教室內部米數格線 (X 軸與 Y 軸灰線)
        for (i in 0..metersX.toInt()) {
            val x = padding + i * pxPerMeter
            canvas.drawLine(x, padding, x, rect.bottom, gridPaint)
        }
        for (i in 0..metersY.toInt()) {
            val y = padding + i * pxPerMeter
            canvas.drawLine(padding, y, rect.right, y, gridPaint)
        }

        // 3. 繪製外牆邊界黑框
        canvas.drawRect(rect, wallPaint)

        // 4. 繪製三個樹莓派 BLE 訊號接收器 (Pi 1, Pi 2, Pi 3) 橘色方塊與文字
        piPositions.forEachIndexed { index, pos ->
            val px = padding + pos.x * pxPerMeter
            val py = padding + pos.y * pxPerMeter
            canvas.drawRect(px - 15f, py - 15f, px + 15f, py + 15f, piPaint)
            textPaint.color = Color.WHITE
            canvas.drawText("Pi ${index + 1}", px, py + 40f, textPaint)
        }

        // 5. 繪製學生定位綠色圓點與學號標籤
        deviceLocations.forEach { (id, pos) ->
            // 將米數座標轉換為像素座標
            val sx = padding + pos.x * pxPerMeter
            val sy = padding + pos.y * pxPerMeter

            // 防呆限幅：限制學生座標不超過教室地圖邊界
            val finalX = sx.coerceIn(padding, rect.right)
            val finalY = sy.coerceIn(padding, rect.bottom)

            // 繪製亮綠色定位圓點
            studentPaint.color = Color.parseColor("#69F0AE")
            canvas.drawCircle(finalX, finalY, 20f, studentPaint)
            
            // 繪製學號標籤 (顯示學號後 4 碼)
            textPaint.color = Color.WHITE
            canvas.drawText(id.takeLast(4), finalX, finalY + 50f, textPaint)
        }
    }
}
