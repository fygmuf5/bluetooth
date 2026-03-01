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
        color = Color.WHITE
        textSize = 34f
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    private val wallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.GRAY
        style = Paint.Style.STROKE
        strokeWidth = 10f
    }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#333333")
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    init {
        // 使用深色背景讓色點更突出
        setBackgroundColor(Color.parseColor("#1A1A1A"))
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
        
        // 1. 繪製背景網格
        val metersX = 8f
        val metersY = 10f
        val pxPerMeter = roomWidth / metersX
        
        for (i in 0..metersX.toInt()) {
            val x = padding + i * pxPerMeter
            canvas.drawLine(x, padding, x, padding + metersY * pxPerMeter, gridPaint)
        }
        for (i in 0..metersY.toInt()) {
            val y = padding + i * pxPerMeter
            canvas.drawLine(padding, y, padding + metersX * pxPerMeter, y, gridPaint)
        }

        // 2. 繪製教室邊界
        val rect = RectF(padding, padding, padding + roomWidth, padding + (metersY * pxPerMeter))
        canvas.drawRect(rect, wallPaint)

        // 3. 繪製老師位置 (藍色大方塊或圓點)
        val teacherX = padding + roomWidth / 2f
        val teacherY = rect.bottom - 40f
        studentPaint.color = Color.parseColor("#2196F3") // 亮藍色
        canvas.drawCircle(teacherX, teacherY, 25f, studentPaint)
        canvas.drawText("老師 (接收端)", teacherX, teacherY + 60f, textPaint)

        // 4. 繪製學生位置 (實心色點)
        deviceDistances.forEach { (address, distance) ->
            val stableAngle = 240.0 + (Math.abs(address.hashCode()) % 60)
            val angleRad = Math.toRadians(stableAngle)

            val radiusPx = distance * pxPerMeter
            var x = teacherX + (radiusPx * cos(angleRad)).toFloat()
            var y = teacherY + (radiusPx * sin(angleRad)).toFloat()

            // 確保不超出邊界
            x = x.coerceIn(padding + 30f, rect.right - 30f)
            y = y.coerceIn(padding + 30f, rect.bottom - 30f)

            // 繪製一般實心色點 (半徑 20f)
            studentPaint.color = getSolidColor(distance)
            canvas.drawCircle(x, y, 22f, studentPaint)
            
            // 加入細白色外圈增加識別度
            studentPaint.style = Paint.Style.STROKE
            studentPaint.color = Color.WHITE
            studentPaint.strokeWidth = 3f
            canvas.drawCircle(x, y, 22f, studentPaint)
            studentPaint.style = Paint.Style.FILL // 還原回填滿模式
            
            // 顯示裝置 ID
            textPaint.textSize = 28f
            canvas.drawText(address.takeLast(4), x, y + 55f, textPaint)
        }
    }

    private fun getSolidColor(distance: Float): Int {
        return when {
            distance < 2 -> Color.parseColor("#FF5252") // 實色紅
            distance < 5 -> Color.parseColor("#FFD740") // 實色黃
            else -> Color.parseColor("#69F0AE")         // 實色綠
        }
    }
}
