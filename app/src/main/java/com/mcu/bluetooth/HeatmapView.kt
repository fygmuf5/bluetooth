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
        color = Color.GRAY
        textSize = 28f
        textAlign = Paint.Align.CENTER
    }

    private val wallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.DKGRAY
        style = Paint.Style.STROKE
        strokeWidth = 20f
        strokeCap = Paint.Cap.ROUND
    }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E0E0E0")
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    fun updateDevices(distances: Map<String, Float>) {
        deviceDistances.clear()
        deviceDistances.putAll(distances)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val padding = 80f
        val roomWidth = width - 2 * padding
        val roomHeight = height - 2 * padding
        
        // 1. 繪製背景網格 (模擬地磚)
        val metersX = 8f // 假設教室寬 8 米
        val metersY = 10f // 假設教室長 10 米
        val pxPerMeter = roomWidth / metersX
        
        for (i in 0..metersX.toInt()) {
            val x = padding + i * pxPerMeter
            canvas.drawLine(x, padding, x, padding + metersY * pxPerMeter, gridPaint)
        }
        for (i in 0..metersY.toInt()) {
            val y = padding + i * pxPerMeter
            canvas.drawLine(padding, y, padding + metersX * pxPerMeter, y, gridPaint)
        }

        // 2. 繪製教室牆壁 (封閉矩形)
        val rect = RectF(padding, padding, padding + roomWidth, padding + (metersY * pxPerMeter))
        canvas.drawRect(rect, wallPaint)

        // 3. 老師（接收端）位置：設定在底部的講台
        val teacherX = padding + roomWidth / 2f
        val teacherY = rect.bottom - 40f

        textPaint.color = Color.BLACK
        textPaint.isFakeBoldText = true
        canvas.drawText("【 講台 / 老師 】", teacherX, teacherY + 60f, textPaint)
        studentPaint.color = Color.BLUE
        canvas.drawCircle(teacherX, teacherY, 20f, studentPaint)

        // 4. 繪製學生位置
        deviceDistances.forEach { (address, distance) ->
            // 學生分佈在老師前方的區域 (向上方發散)
            val stableAngle = 240.0 + (Math.abs(address.hashCode()) % 60)
            val angleRad = Math.toRadians(stableAngle)

            // 計算座標 (從老師位置出發)
            val radiusPx = distance * pxPerMeter
            var x = teacherX + (radiusPx * cos(angleRad)).toFloat()
            var y = teacherY + (radiusPx * sin(angleRad)).toFloat()

            // 邊界限制
            x = x.coerceIn(padding + 40f, rect.right - 40f)
            y = y.coerceIn(padding + 40f, rect.bottom - 40f)

            // 繪製熱點發光效果
            val radialGradient = RadialGradient(x, y, 60f, 
                intArrayOf(getHeatColor(distance), Color.TRANSPARENT),
                null, Shader.TileMode.CLAMP)
            val heatPaint = Paint().apply { shader = radialGradient }
            canvas.drawCircle(x, y, 60f, heatPaint)

            // 繪製中心點
            studentPaint.color = Color.BLACK
            canvas.drawCircle(x, y, 8f, studentPaint)
            
            // 顯示裝置 ID
            textPaint.color = Color.DKGRAY
            textPaint.isFakeBoldText = false
            canvas.drawText(address.takeLast(4), x, y + 40f, textPaint)
        }
    }

    private fun getHeatColor(distance: Float): Int {
        return when {
            distance < 2 -> Color.argb(180, 255, 0, 0)    // 紅 (近)
            distance < 5 -> Color.argb(180, 255, 165, 0)  // 橘 (中)
            else -> Color.argb(180, 0, 255, 0)            // 綠 (遠)
        }
    }
}