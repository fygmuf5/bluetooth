package com.mcu.bluetooth

import android.util.Log
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

object NetworkManager {

    // --- 1. 伺服器設定區 ---
    private const val BASE_URL = "https://micronemous-indefeasibly-cooper.ngrok-free.app"

    // 功能路徑
    private const val PATH_ATTENDANCE   = "/api/check-in"
    private const val PATH_USER_INFO    = "/api/my-courses"
    private const val PATH_VERIFY_CODE  = "/api/send-code"
    private const val PATH_REGISTER     = "/api/register"
    private const val PATH_LOGIN        = "/api/auth/login" // 假設登入路徑

    private const val TIMEOUT_MS = 5000

    /**
     * 登入驗證 (加入 device_id 驗證設備綁定)
     */
    fun login(email: String, password: String, deviceId: String, callback: (Boolean) -> Unit) {
        val json = JSONObject().apply {
            put("email", email)
            put("password", password)
            put("device_id", deviceId)
        }
        sendJsonPost(BASE_URL + PATH_LOGIN, json, callback)
    }

    /**
     * 同步點名紀錄
     */
    fun syncAttendance(studentInfo: String, address: String, callback: (Boolean) -> Unit) {
        val json = JSONObject().apply {
            put("student_info", studentInfo)
            put("device_address", address)
        }
        sendJsonPost(BASE_URL + PATH_ATTENDANCE, json, callback)
    }

    /**
     * 請求驗證碼
     */
    fun requestVerifyCode(email: String, callback: (Boolean) -> Unit) {
        val json = JSONObject().apply {
            put("email", email)
        }
        sendJsonPost(BASE_URL + PATH_VERIFY_CODE, json, callback)
    }

    /**
     * 註冊帳號 (加入 device_id 進行首次綁定)
     */
    fun registerUser(email: String, password: String, verifyCode: String, deviceId: String, callback: (Boolean) -> Unit) {
        val studentId = email.substringBefore("@")
        val json = JSONObject().apply {
            put("user_id", studentId) // 對應後端要求的 user_id
            put("name", "User")        // 這裡暫時填 User，或由後端決定
            put("email", email)
            put("password", password)
            put("code", verifyCode)    // 對應後端要求的 code
            put("device_id", deviceId) // 對應後端要求的 device_id
        }
        sendJsonPost(BASE_URL + PATH_REGISTER, json, callback)
    }

    /**
     * 獲取課表
     */
    fun getMyCourses(studentInfo: String, callback: (Boolean) -> Unit) {
        val json = JSONObject().apply {
            put("student_info", studentInfo)
        }
        sendJsonPost(BASE_URL + PATH_USER_INFO, json, callback)
    }

    // --- 內部私有通用工具方法 (JSON 版) ---

    private fun sendJsonPost(urlStr: String, jsonBody: JSONObject, callback: (Boolean) -> Unit) {
        Thread {
            var conn: HttpURLConnection? = null
            try {
                val url = URL(urlStr)
                conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.connectTimeout = TIMEOUT_MS
                conn.readTimeout = TIMEOUT_MS
                
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                conn.setRequestProperty("Accept", "application/json")

                OutputStreamWriter(conn.outputStream, StandardCharsets.UTF_8).use { writer ->
                    writer.write(jsonBody.toString())
                    writer.flush()
                }

                val responseCode = conn.responseCode
                Log.d("NetworkManager", "Node.js URL: $urlStr | Status: $responseCode")
                
                callback(responseCode == HttpURLConnection.HTTP_OK || responseCode == HttpURLConnection.HTTP_CREATED)

            } catch (e: Exception) {
                Log.e("NetworkManager", "Node.js Connection Error", e)
                callback(false)
            } finally {
                conn?.disconnect()
            }
        }.start()
    }
}
