package com.mcu.bluetooth

class KalmanFilter(private val processNoise: Double = 0.005, private val measurementNoise: Double = 0.5) {
    private var estimatedValue: Double = 0.0
    private var errorCovariance: Double = 1.0
    private var isInitialized = false

    fun filter(measurement: Double): Double {
        if (!isInitialized) {
            estimatedValue = measurement
            isInitialized = true
            return measurement
        }

        // Prediction update
        errorCovariance += processNoise

        // Measurement update
        val kalmanGain = errorCovariance / (errorCovariance + measurementNoise)
        estimatedValue += kalmanGain * (measurement - estimatedValue)
        errorCovariance *= (1 - kalmanGain)

        return estimatedValue
    }
}