package com.mcu.bluetooth

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

class HeatmapView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // 現在儲存的是計算好的 (x, y) 座標，單位：公尺
    private var deviceLocations = mutableMapOf<String, PointF>()

    // 定義三個樹莓派在教室的座標 (公尺)
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
    private val wallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.GRAY
        style = Paint.Style.STROKE
        strokeWidth = 8f
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#333333")
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    init {
        setBackgroundColor(Color.parseColor("#1A1A1A"))
    }

    /**
     * 更新學生位置
     * @param locations Map: 學生 ID 對應其在教室內的 (x, y) 座標 (單位：公尺)
     */
    fun updateStudentLocations(locations: Map<String, PointF>) {
        deviceLocations.clear()
        deviceLocations.putAll(locations)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val padding = 80f
        val metersX = 8f // 教室寬 8 米
        val metersY = 10f // 教室長 10 米
        val pxPerMeter = (width - 2 * padding) / metersX

        // 1. 繪製邊界與網格
        val rect = RectF(padding, padding, padding + metersX * pxPerMeter, padding + metersY * pxPerMeter)
        canvas.drawRect(rect, wallPaint)
        for (i in 0..metersX.toInt()) {
            val x = padding + i * pxPerMeter
            canvas.drawLine(x, padding, x, rect.bottom, gridPaint)
        }
        for (i in 0..metersY.toInt()) {
            val y = padding + i * pxPerMeter
            canvas.drawLine(padding, y, rect.right, y, gridPaint)
        }

        // 2. 繪製三個樹莓派接收端 (Pi)
        piPositions.forEachIndexed { index, pos ->
            val px = padding + pos.x * pxPerMeter
            val py = padding + pos.y * pxPerMeter
            canvas.drawRect(px - 15f, py - 15f, px + 15f, py + 15f, piPaint)
            canvas.drawText("Pi ${index + 1}", px, py + 40f, textPaint)
        }

        // 3. 繪製學生位置
        deviceLocations.forEach { (id, pos) ->
            val sx = padding + pos.x * pxPerMeter
            val sy = padding + pos.y * pxPerMeter

            // 確保座標不超出教室牆壁
            val finalX = sx.coerceIn(padding, rect.right)
            val finalY = sy.coerceIn(padding, rect.bottom)

            studentPaint.color = Color.parseColor("#69F0AE") // 預設綠色點
            canvas.drawCircle(finalX, finalY, 20f, studentPaint)
            
            // 繪製 ID
            canvas.drawText(id.takeLast(4), finalX, finalY + 50f, textPaint)
        }
    }
}
