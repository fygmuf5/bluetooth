package com.mcu.bluetooth

/**
 * 卡爾曼濾波器 (KalmanFilter)
 * 用於平滑 BLE 藍牙接收訊號強度 (RSSI) 數值。
 * 由於藍牙 RSSI 容易受牆壁遮擋、人體吸收及環境雜訊干擾而剧烈跳動，
 * 透過卡爾曼濾波演算法可以有效去除突發雜訊，獲得穩定平滑的 RSSI 測量數據。
 * 
 * @param processNoise 過程雜訊 Q (越小估計越平滑，但對快速移動反應較慢)
 * @param measurementNoise 測量雜訊 R (越大代表環境測量雜訊越高，濾波效果越強)
 * @param estimatedValue 初始估計 RSSI 數值 (預設 -70 dBm)
 * @param errorEstimate 初始共變異數誤差估計 (預設 1.0)
 */
class KalmanFilter(
    private val processNoise: Double = 0.005,
    private val measurementNoise: Double = 0.5,
    private var estimatedValue: Double = -70.0,
    private var errorEstimate: Double = 1.0
) {
    /**
     * 輸入最新一次的 RSSI 原始測量值，進行卡爾曼預測與更新，回傳濾波後的平滑 RSSI 數值。
     * @param measurement 原始 RSSI 數值 (例如 -65.0)
     * @return 濾波估算後的 RSSI 平滑數值
     */
    fun filter(measurement: Double): Double {
        // 1. 預測階段 (Predict): 增加共變異數預測誤差
        errorEstimate += processNoise

        // 2. 計算卡爾曼增益 (Kalman Gain): 權衡測量值與前次估算值的信任度
        val kalmanGain = errorEstimate / (errorEstimate + measurementNoise)

        // 3. 更新階段 (Update): 根據卡爾曼增益校正最新估計值
        estimatedValue += kalmanGain * (measurement - estimatedValue)

        // 4. 更新共變異數誤差估計
        errorEstimate *= (1 - kalmanGain)

        // 回傳平滑化後的估算結果
        return estimatedValue
    }
}
