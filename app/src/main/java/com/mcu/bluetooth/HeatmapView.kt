package com.mcu.bluetooth

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

class HeatmapView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // 儲存裝置資訊：Map<地址, 距離(米)>
    private var deviceDistances = mutableMapOf<String, Float>()

    private val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.GREEN
        style = Paint.Style.FILL
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 30f
        textAlign = Paint.Align.CENTER
    }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.DKGRAY
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    // 更新裝置距離數據
    fun updateDevices(distances: Map<String, Float>) {
        deviceDistances.clear()
        deviceDistances.putAll(distances)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val centerX = width / 2f
        val centerY = height / 2f
        val pixelsPerMeter = width / 15f // 假設螢幕寬度代表 15 公尺

        // 1. 繪製背景參考圓環 (每 2 公尺一圈)
        for (i in 1..5) {
            val r = i * 2 * pixelsPerMeter
            canvas.drawCircle(centerX, centerY, r, gridPaint)
        }

        // 2. 繪製學生點位
        deviceDistances.forEach { (address, distance) ->
            // 利用 MAC 地址生成一個穩定的隨機角度 (0~360度)，防止點位重疊
            val angle = (address.hashCode().toDouble() % 360) * (Math.PI / 180.0)
            
            // 將極座標 (距離, 角度) 轉換為直角座標 (x, y)
            val radiusInPixels = distance * pixelsPerMeter
            val x = centerX + (radiusInPixels * cos(angle)).toFloat()
            val y = centerY + (radiusInPixels * sin(angle)).toFloat()

            // 根據距離改變顏色 (越近越紅，越遠越綠)
            pointPaint.color = when {
                distance < 2 -> Color.RED
                distance < 5 -> Color.YELLOW
                else -> Color.GREEN
            }

            // 畫點
            canvas.drawCircle(x, y, 20f, pointPaint)
            
            // 畫裝置後四碼作為標籤
            val label = address.takeLast(5)
            canvas.drawText(label, x, y + 50f, textPaint)
        }

        // 3. 繪製中心點 (老師)
        pointPaint.color = Color.CYAN
        canvas.drawCircle(centerX, centerY, 25f, pointPaint)
        canvas.drawText("老師", centerX, centerY - 40f, textPaint)
    }
}