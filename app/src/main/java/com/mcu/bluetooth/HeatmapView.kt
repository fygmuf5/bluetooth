package com.mcu.bluetooth

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

class HeatmapView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var deviceLocations = mutableMapOf<String, PointF>()

    private val piPositions = listOf(
        PointF(0f, 0f),       // Pi 1: 左上角
        PointF(8f, 0f),       // Pi 2: 右上角
        PointF(4f, 10f)       // Pi 3: 後方中間
    )

    private val studentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val piPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FF9800") }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 28f
        textAlign = Paint.Align.CENTER
    }
    
    // 用於圖表外框
    private val wallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK // 改為黑色
        style = Paint.Style.STROKE
        strokeWidth = 8f
    }
    
    // 用於格線
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#EEEEEE") // 淺灰色
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    init {
        // 設定背景為白色
        setBackgroundColor(Color.WHITE)
    }

    fun updateStudentLocations(locations: Map<String, PointF>) {
        deviceLocations.clear()
        deviceLocations.putAll(locations)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val padding = 80f
        val metersX = 8f
        val metersY = 10f
        val pxPerMeter = (width - 2 * padding) / metersX

        // 1. 繪製地圖主體背景（黑色區域）
        val rect = RectF(padding, padding, padding + metersX * pxPerMeter, padding + metersY * pxPerMeter)
        val mapBgPaint = Paint().apply { color = Color.BLACK }
        canvas.drawRect(rect, mapBgPaint)

        // 2. 繪製格線 (顯示在黑色區域內)
        for (i in 0..metersX.toInt()) {
            val x = padding + i * pxPerMeter
            canvas.drawLine(x, padding, x, rect.bottom, gridPaint)
        }
        for (i in 0..metersY.toInt()) {
            val y = padding + i * pxPerMeter
            canvas.drawLine(padding, y, rect.right, y, gridPaint)
        }

        // 3. 繪製邊界線
        canvas.drawRect(rect, wallPaint)

        // 4. 繪製三個樹莓派接收端 (Pi)
        piPositions.forEachIndexed { index, pos ->
            val px = padding + pos.x * pxPerMeter
            val py = padding + pos.y * pxPerMeter
            canvas.drawRect(px - 15f, py - 15f, px + 15f, py + 15f, piPaint)
            // 文字在黑色背景上顯示白色
            textPaint.color = Color.WHITE
            canvas.drawText("Pi ${index + 1}", px, py + 40f, textPaint)
        }

        // 5. 繪製學生位置
        deviceLocations.forEach { (id, pos) ->
            val sx = padding + pos.x * pxPerMeter
            val sy = padding + pos.y * pxPerMeter

            val finalX = sx.coerceIn(padding, rect.right)
            val finalY = sy.coerceIn(padding, rect.bottom)

            studentPaint.color = Color.parseColor("#69F0AE")
            canvas.drawCircle(finalX, finalY, 20f, studentPaint)
            
            textPaint.color = Color.WHITE
            canvas.drawText(id.takeLast(4), finalX, finalY + 50f, textPaint)
        }
    }
}
