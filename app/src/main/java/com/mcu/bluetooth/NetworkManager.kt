package com.mcu.bluetooth

import android.util.Log
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

object NetworkManager {

    // --- 1. 伺服器設定區 ---
    // 請在此處替換為您的伺服器位址 (例如 "http://192.168.1.100" 或 "http://www.yourdomain.com")
    private const val BASE_URL = "http://YOUR_SERVER_IP"

    // 定義各個 PHP 腳本的路徑 (與 BASE_URL 組合使用)
    private const val PATH_ATTENDANCE = "/save_attendance.php"
    private const val PATH_USER_INFO  = "/save_user.php"
    private const val PATH_VERIFY_CODE = "/request_verify_code.php"
    private const val PATH_REGISTER    = "/register_user.php"

    private const val TIMEOUT_MS = 5000

    /**
     * 同步點名紀錄到伺服器
     */
    fun syncAttendance(studentInfo: String, address: String, callback: (Boolean) -> Unit) {
        val fullUrl = BASE_URL + PATH_ATTENDANCE
        sendPostRequest(fullUrl, mapOf(
            "student_info" to studentInfo,
            "device_address" to address
        ), callback)
    }

    /**
     * 請求發送電子郵件驗證碼
     */
    fun requestVerifyCode(email: String, callback: (Boolean) -> Unit) {
        val fullUrl = BASE_URL + PATH_VERIFY_CODE
        sendPostRequest(fullUrl, mapOf("email" to email), callback)
    }

    /**
     * 註冊新帳號
     */
    fun registerUser(studentId: String, email: String, password: String, verifyCode: String, callback: (Boolean) -> Unit) {
        val fullUrl = BASE_URL + PATH_REGISTER
        sendPostRequest(fullUrl, mapOf(
            "student_id" to studentId,
            "email" to email,
            "password" to password,
            "verify_code" to verifyCode
        ), callback)
    }

    /**
     * 同步用戶帳戶資訊
     */
    fun syncUserInfo(studentInfo: String, callback: (Boolean) -> Unit) {
        val fullUrl = BASE_URL + PATH_USER_INFO
        sendPostRequest(fullUrl, mapOf("student_info" to studentInfo), callback)
    }

    // --- 內部私有通用工具方法 ---

    /**
     * 通用的 POST 請求發送方法
     */
    private fun sendPostRequest(urlStr: String, params: Map<String, String>, callback: (Boolean) -> Unit) {
        Thread {
            var conn: HttpURLConnection? = null
            try {
                val url = URL(urlStr)
                conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.connectTimeout = TIMEOUT_MS
                conn.readTimeout = TIMEOUT_MS
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")

                // 組合 POST 參數字串
                val postData = params.entries.joinToString("&") {
                    "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}"
                }

                OutputStreamWriter(conn.outputStream, StandardCharsets.UTF_8).use { writer ->
                    writer.write(postData)
                    writer.flush()
                }

                val responseCode = conn.responseCode
                Log.d("NetworkManager", "URL: $urlStr | Response: $responseCode")
                callback(responseCode == HttpURLConnection.HTTP_OK)

            } catch (e: Exception) {
                Log.e("NetworkManager", "Connection error at $urlStr", e)
                callback(false)
            } finally {
                conn?.disconnect()
            }
        }.start()
    }
}
