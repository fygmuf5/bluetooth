package com.mcu.bluetooth

/**
 * 簡易卡爾曼濾波器，用於平滑 RSSI 訊號
 */
class KalmanFilter(
    private val processNoise: Double = 0.005, // 過程雜訊 (越小越平滑，但反應越慢)
    private val measurementNoise: Double = 0.5, // 測量雜訊 (越大越平滑)
    private var estimatedValue: Double = -70.0, // 初始估計值 (RSSI 通常在 -50 到 -100 之間)
    private var errorEstimate: Double = 1.0     // 初始誤差估計
) {
    fun filter(measurement: Double): Double {
        // 預測階段
        errorEstimate += processNoise

        // 更新階段 (Kalman Gain)
        val kalmanGain = errorEstimate / (errorEstimate + measurementNoise)
        estimatedValue += kalmanGain * (measurement - estimatedValue)
        errorEstimate *= (1 - kalmanGain)

        return estimatedValue
    }
}
