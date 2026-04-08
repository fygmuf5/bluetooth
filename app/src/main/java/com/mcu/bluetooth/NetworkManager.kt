package com.mcu.bluetooth

import android.util.Log
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

object NetworkManager {

    // --- 1. 伺服器設定區 ---
    // 將此處改為 ngrok 提供的手機公網網址
    private const val BASE_URL = "https://你的ngrok代碼.ngrok-free.app"

    // Node.js RESTful API 路徑
    private const val PATH_ATTENDANCE   = "/api/attendance"
    private const val PATH_USER_INFO    = "/api/user/info"
    private const val PATH_VERIFY_CODE  = "/api/auth/verify-code"
    private const val PATH_REGISTER     = "/api/auth/register"
    private const val PATH_LOGIN        = "/api/auth/login"

    private const val TIMEOUT_MS = 5000

    /**
     * 登入驗證
     */
    fun login(email: String, password: String, callback: (Boolean) -> Unit) {
        val json = JSONObject().apply {
            put("email", email)
            put("password", password)
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
     * 註冊帳號 (移除 studentId，由伺服器端從 email 提取)
     */
    fun registerUser(email: String, password: String, verifyCode: String, callback: (Boolean) -> Unit) {
        val json = JSONObject().apply {
            put("email", email)
            put("password", password)
            put("verify_code", verifyCode)
        }
        sendJsonPost(BASE_URL + PATH_REGISTER, json, callback)
    }

    /**
     * 同步用戶資訊
     */
    fun syncUserInfo(studentInfo: String, callback: (Boolean) -> Unit) {
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
