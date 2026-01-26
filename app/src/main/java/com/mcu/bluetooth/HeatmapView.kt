package com.mcu.bluetooth

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

class HeatmapView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var deviceDistances = mutableMapOf<String, Float>()

    private val studentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.LTGRAY
        textSize = 28f
        textAlign = Paint.Align.CENTER
    }

    private val roomPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#33FFFFFF") // 淺白色網格
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }

    fun updateDevices(distances: Map<String, Float>) {
        deviceDistances.clear()
        deviceDistances.putAll(distances)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // 設定教室邊界（留出一些 padding）
        val padding = 60f
        val roomWidth = width - 2 * padding
        val roomHeight = height - 2 * padding
        
        // 繪製教室外框
        canvas.drawRect(padding, padding, width - padding, height - padding, roomPaint)

        // 繪製地磚網格（每公尺一格，假設教室長寬為 10x10 米）
        val meters = 10f
        val pxPerMeter = roomWidth / meters
        for (i in 1 until meters.toInt()) {
            val x = padding + i * pxPerMeter
            canvas.drawLine(x, padding, x, height - padding, gridPaint)
            val y = padding + i * (roomHeight / meters)
            canvas.drawLine(padding, y, width - padding, y, gridPaint)
        }

        // 老師（接收端）位置：設定在底部的講台中心
        val teacherX = width / 2f
        val teacherY = height - padding - 20f

        // 繪製講台區域標籤
        textPaint.color = Color.CYAN
        canvas.drawText("【 講台 / 老師位置 】", teacherX, teacherY + 50f, textPaint)
        studentPaint.color = Color.CYAN
        canvas.drawCircle(teacherX, teacherY, 15f, studentPaint)

        // 繪製學生位置
        deviceDistances.forEach { (address, distance) ->
            // 將雜湊值映射到老師前方的 120 度扇形區域 (從 210度 到 330度)
            // 這樣學生看起來會分佈在老師的「前方」座位區
            val stableAngle = 210.0 + (Math.abs(address.hashCode()) % 120)
            val angleRad = stableAngle * (Math.PI / 180.0)

            // 計算座標
            val radiusPx = distance * pxPerMeter
            var x = teacherX + (radiusPx * cos(angleRad)).toFloat()
            var y = teacherY + (radiusPx * sin(angleRad)).toFloat()

            // 限制點位不要超出教室牆壁
            x = x.coerceIn(padding + 20f, width - padding - 20f)
            y = y.coerceIn(padding + 20f, height - padding - 20f)

            // 根據距離著色：近(紅)、中(黃)、遠(綠)
            studentPaint.color = when {
                distance < 2 -> Color.parseColor("#FF5252") // Red
                distance < 5 -> Color.parseColor("#FFD740") // Yellow
                else -> Color.parseColor("#69F0AE")         // Green
            }

            // 畫出學生點位
            canvas.drawCircle(x, y, 18f, studentPaint)
            
            // 顯示裝置後四碼
            textPaint.color = Color.WHITE
            canvas.drawText(address.takeLast(4), x, y - 25f, textPaint)
        }
    }
}
