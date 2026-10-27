package com.mcu.bluetooth

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import java.util.Locale

/**
 * 教室熱力圖與即時定位自訂 View (HeatmapView)
 * 負責繪製：
 * 1. 教室邊界外框與網格線 (0-32 格網格，非公尺單位，最左下角為 (0, 0))
 * 2. 方位標示：最下方為白板/講台 (老師側)，最上方為教室最後方
 * 3. 學生即時定位座標點與對應學號、(x, y) 座標標籤
 * (樹莓派接收器位置暫不顯示)
 */
class HeatmapView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        const val GRID_SIZE_X = 32f // X 軸總格數 (0 - 32 格)
        const val GRID_SIZE_Y = 32f // Y 軸總格數 (0 - 32 格)
    }

    // 儲存學生當前座標數據：學號 -> (X 格, Y 格)
    private val deviceLocations = mutableMapOf<String, PointF>()

    // 畫筆 (Paint) 宣告與屬性初始化
    private val studentDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E676") // 學生定位亮綠色圓點
        style = Paint.Style.FILL
    }
    private val studentGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4000E676") // 定位點外層光暈
        style = Paint.Style.FILL
    }
    private val studentCenterDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    // 學生標籤氣泡畫筆
    private val badgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E61A1C23") // 深色半透明背景
        style = Paint.Style.FILL
    }
    private val badgeBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#6600E676") // 標籤邊框
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
    }
    private val studentIdPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 21f
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }
    private val studentCoordPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#80D8FF") // 淺藍色座標文字
        textSize = 17f
        textAlign = Paint.Align.CENTER
    }

    // 牆壁邊界畫筆 (外框)
    private val wallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#455A64")
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }

    // 地圖背景畫筆
    private val mapBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#12161E")
        style = Paint.Style.FILL
    }

    // 網格線畫筆 (每 5 格細線)
    private val gridLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2E3846") // 清楚柔和的網格細線
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
    }

    // 軸刻度文字畫筆
    private val axisXTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#78909C")
        textSize = 20f
        textAlign = Paint.Align.CENTER
    }
    private val axisYTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#78909C")
        textSize = 20f
        textAlign = Paint.Align.RIGHT
    }

    // 教室方位標籤畫筆
    private val backClassroomLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#78909C")
        textSize = 22f
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }
    private val whiteboardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#ECEFF1")
        style = Paint.Style.FILL
    }
    private val whiteboardBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#90A4AE")
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val whiteboardLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1976D2")
        textSize = 22f
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }

    // 空狀態提示畫筆
    private val emptyHintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#607D8B")
        textSize = 26f
        textAlign = Paint.Align.CENTER
    }

    init {
        // 設定 Canvas 背景為乾淨白色，突顯黑色教室地圖
        setBackgroundColor(Color.WHITE)
    }

    /**
     * 更新學生座標點並觸發視圖重繪 (invalidate)
     * @param locations <學號, (x, y)> 座標映射表 (單位: 0-32 格)
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

        if (width <= 0 || height <= 0) return

        // 預留標籤與刻度之邊距
        val paddingLeft = 56f
        val paddingRight = 44f
        val paddingTop = 52f
        val paddingBottom = 84f

        val availW = width - paddingLeft - paddingRight
        val availH = height - paddingTop - paddingBottom
        if (availW <= 0f || availH <= 0f) return

        // 保證每格為正方形 (1:1 比例)
        val cellSize = minOf(availW / GRID_SIZE_X, availH / GRID_SIZE_Y)
        val gridW = cellSize * GRID_SIZE_X
        val gridH = cellSize * GRID_SIZE_Y

        // 居中放置網格
        val left = paddingLeft + (availW - gridW) / 2f
        val top = paddingTop + (availH - gridH) / 2f
        val rect = RectF(left, top, left + gridW, top + gridH)

        // 1. 繪製教室地圖背景矩形 (深色底)
        canvas.drawRect(rect, mapBgPaint)

        // 2. 繪製網格線與軸刻度 (間隔每 5 格: 0, 5, 10, 15, 20, 25, 30)
        val gridTicks = listOf(0, 5, 10, 15, 20, 25, 30)

        // X 軸 (垂直格線與刻度，從左 x=0 到右 x=30)
        for (tick in gridTicks) {
            val x = rect.left + tick * cellSize
            if (tick > 0 && tick < GRID_SIZE_X) {
                canvas.drawLine(x, rect.top, x, rect.bottom, gridLinePaint)
            }
            // X 軸刻度數字 (0, 5, 10, 15, 20, 25, 30)
            canvas.drawText("$tick", x, rect.bottom + 26f, axisXTextPaint)
        }
        // X 軸單位標籤
        canvas.drawText("(格)", rect.right + 20f, rect.bottom + 26f, axisXTextPaint)

        // Y 軸 (水平格線與刻度，最下方為 y=0，往上每 5 格到 y=30)
        for (tick in gridTicks) {
            val y = rect.bottom - tick * cellSize
            if (tick > 0 && tick < GRID_SIZE_Y) {
                canvas.drawLine(rect.left, y, rect.right, y, gridLinePaint)
            }
            // Y 軸刻度數字 (0, 5, 10, 15, 20, 25, 30)
            canvas.drawText("$tick", rect.left - 12f, y + 7f, axisYTextPaint)
        }

        // 3. 繪製外牆邊界框
        canvas.drawRect(rect, wallPaint)

        // 4. 繪製教室方位標示
        // 上方：教室最後方
        canvas.drawText("【 教室最後方 】", rect.centerX(), rect.top - 18f, backClassroomLabelPaint)

        // 下方：白板 / 講台（老師側）
        val wbW = minOf(rect.width() * 0.45f, 260f)
        val wbH = 12f
        val wbLeft = rect.centerX() - wbW / 2f
        val wbTop = rect.bottom + 36f
        val wbRect = RectF(wbLeft, wbTop, wbLeft + wbW, wbTop + wbH)
        canvas.drawRoundRect(wbRect, 4f, 4f, whiteboardPaint)
        canvas.drawRoundRect(wbRect, 4f, 4f, whiteboardBorderPaint)
        canvas.drawText("【 白板 / 講台（老師側）】", rect.centerX(), wbTop + 34f, whiteboardLabelPaint)

        // 5. 繪製學生定位座標點與學號標籤
        if (deviceLocations.isEmpty()) {
            canvas.drawText("尚無即時學生座標數據", rect.centerX(), rect.centerY() + 10f, emptyHintPaint)
        } else {
            deviceLocations.forEach { (id, pos) ->
                // 座標轉換邏輯：
                // 1. 最左下為 (0, 0)
                // 2. X 軸: 0 (左) -> 32 (右)
                // 3. Y 軸: 0 (最下方/白板老師側) -> 32 (最上方/教室最後方)
                val sx = rect.left + (pos.x / GRID_SIZE_X) * gridW
                val sy = rect.bottom - (pos.y / GRID_SIZE_Y) * gridH

                // 防呆限幅：限制不超出教室地圖邊界
                val finalX = sx.coerceIn(rect.left, rect.right)
                val finalY = sy.coerceIn(rect.top, rect.bottom)

                // 繪製定位光暈與雙層圓點
                canvas.drawCircle(finalX, finalY, 13f, studentGlowPaint)
                canvas.drawCircle(finalX, finalY, 8f, studentDotPaint)
                canvas.drawCircle(finalX, finalY, 3f, studentCenterDotPaint)

                // 格式化座標字串 (例如 "(12, 18)" 或 "(12.5, 18.2)")
                val coordStr = "(${formatCoord(pos.x)}, ${formatCoord(pos.y)})"

                // 計算學號與座標文字寬度以自適應氣泡標籤大小
                val idWidth = studentIdPaint.measureText(id)
                val coordWidth = studentCoordPaint.measureText(coordStr)
                val contentW = maxOf(idWidth, coordWidth)
                val badgeW = contentW + 24f
                val badgeH = 46f

                // 氣泡垂直位置：預設在點上方，若靠近頂部邊緣則移至點下方
                val badgeTop = if (finalY - badgeH - 14f < rect.top) {
                    finalY + 14f
                } else {
                    finalY - badgeH - 14f
                }

                // 氣泡水平位置：防呆限幅不超出地圖左右邊緣
                val badgeLeft = (finalX - badgeW / 2f).coerceIn(rect.left + 2f, rect.right - badgeW - 2f)
                val badgeRect = RectF(badgeLeft, badgeTop, badgeLeft + badgeW, badgeTop + badgeH)

                // 繪製標籤背景氣泡與外框
                canvas.drawRoundRect(badgeRect, 8f, 8f, badgeBgPaint)
                canvas.drawRoundRect(badgeRect, 8f, 8f, badgeBorderPaint)

                // 繪製學號 (第一行)
                canvas.drawText(id, badgeRect.centerX(), badgeTop + 19f, studentIdPaint)
                // 繪製座標 (第二行)
                canvas.drawText(coordStr, badgeRect.centerX(), badgeTop + 38f, studentCoordPaint)
            }
        }
    }

    /**
     * 輔助函式：若數值為整數則去除小數點 (如 12.0 -> "12")，若有小數則保留一位小數
     */
    private fun formatCoord(value: Float): String {
        return if (value % 1f == 0f) {
            value.toInt().toString()
        } else {
            String.format(Locale.US, "%.1f", value)
        }
    }
}
