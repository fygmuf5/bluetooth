package com.mcu.bluetooth

import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

/**
 * 優化後的 NetworkManager
 * 1. 使用固定執行緒池 (Fixed Thread Pool) 避免頻繁創建執行緒
 * 2. 強化錯誤處理與日誌紀錄
 * 3. 確保回傳邏輯一致
 */
object NetworkManager {

    private const val BASE_URL = "https://micronemous-indefeasibly-cooper.ngrok-free.dev"

    private const val PATH_ATTENDANCE   = "/api/check-in"
    private const val PATH_USER_INFO    = "/api/my-courses"
    private const val PATH_VERIFY_CODE  = "/api/send-code"
    private const val PATH_REGISTER     = "/api/register"
    private const val PATH_LOGIN        = "/api/auth/login"

    private const val PATH_START_SESSION = "/api/session/start"
    private const val PATH_GET_OTP_LIST  = "/api/session/otp-list"
    private const val PATH_GET_MY_TOKEN  = "/api/session/get-token"

    private const val TIMEOUT_MS = 5000
    
    // 使用執行緒池管理網路請求，避免同時發送大量請求時造成系統壓力
    private val executor = Executors.newFixedThreadPool(4)

    fun startAttendanceSession(email: String, callback: (String?) -> Unit) {
        val json = JSONObject().apply { put("email", email) }
        sendJsonPostWithResponse(BASE_URL + PATH_START_SESSION, json) { response ->
            callback(response?.optString("xor_key", null))
        }
    }

    fun getVerifyList(email: String, callback: (Map<String, String>?) -> Unit) {
        val json = JSONObject().apply { put("email", email) }
        sendJsonPostWithResponse(BASE_URL + PATH_GET_OTP_LIST, json) { response ->
            val otpMap = mutableMapOf<String, String>()
            val data = response?.optJSONObject("otp_list")
            data?.keys()?.forEach { studentId ->
                otpMap[studentId] = data.getString(studentId)
            }
            callback(if (otpMap.isEmpty()) null else otpMap)
        }
    }

    fun getStudentToken(studentId: String, callback: (otp: String?, xorKey: String?) -> Unit) {
        val json = JSONObject().apply { put("student_id", studentId) }
        sendJsonPostWithResponse(BASE_URL + PATH_GET_MY_TOKEN, json) { response ->
            callback(response?.optString("otp", null), response?.optString("xor_key", null))
        }
    }

    fun login(email: String, password: String, deviceId: String, callback: (Boolean) -> Unit) {
        val json = JSONObject().apply {
            put("email", email)
            put("password", password)
            put("device_id", deviceId)
        }
        sendJsonPost(BASE_URL + PATH_LOGIN, json, callback)
    }

    fun registerUser(email: String, password: String, verifyCode: String, deviceId: String, callback: (Boolean, String?) -> Unit) {
        val studentId = email.substringBefore("@")
        val json = JSONObject().apply {
            put("user_id", studentId)
            put("name", studentId)
            put("email", email)
            put("password", password)
            put("code", verifyCode)
            put("device_id", deviceId)
        }
        sendJsonPostWithResponse(BASE_URL + PATH_REGISTER, json) { response ->
            if (response != null) {
                val success = response.optBoolean("success", false)
                val message = response.optString("message", "連線失敗")
                callback(success, message)
            } else {
                callback(false, "網路連線異常")
            }
        }
    }

    fun requestVerifyCode(email: String, callback: (Boolean, String?) -> Unit) {
        val json = JSONObject().apply { put("email", email) }
        sendJsonPostWithResponse(BASE_URL + PATH_VERIFY_CODE, json) { response ->
            if (response != null) {
                val success = response.optBoolean("success", false)
                val message = response.optString("message", null)
                callback(success, message)
            } else {
                callback(false, "連線失敗")
            }
        }
    }

    fun syncAttendance(studentInfo: String, address: String, callback: (Boolean) -> Unit) {
        val json = JSONObject().apply {
            put("student_info", studentInfo)
            put("device_address", address)
        }
        sendJsonPost(BASE_URL + PATH_ATTENDANCE, json, callback)
    }

    private fun sendJsonPost(urlStr: String, jsonBody: JSONObject, callback: (Boolean) -> Unit) {
        sendJsonPostWithResponse(urlStr, jsonBody) { response ->
            callback(response != null && response.optBoolean("success", false))
        }
    }

    private fun sendJsonPostWithResponse(urlStr: String, jsonBody: JSONObject, callback: (JSONObject?) -> Unit) {
        executor.execute {
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

                OutputStreamWriter(conn.outputStream, StandardCharsets.UTF_8).use { 
                    it.write(jsonBody.toString())
                }

                val responseCode = conn.responseCode
                val stream = if (responseCode in 200..299) conn.inputStream else conn.errorStream
                
                if (stream != null) {
                    val reader = BufferedReader(InputStreamReader(stream))
                    val sb = StringBuilder()
                    var line: String?
                    while (reader.readLine().also { line = it } != null) sb.append(line)
                    
                    val responseStr = sb.toString()
                    try {
                        callback(JSONObject(responseStr))
                    } catch (e: Exception) {
                        Log.e("NetworkManager", "JSON Parse Error at $urlStr: $responseStr")
                        callback(null)
                    }
                } else {
                    Log.e("NetworkManager", "Response Stream is Null at $urlStr (Code: $responseCode)")
                    callback(null)
                }
            } catch (e: Exception) {
                Log.e("NetworkManager", "Network Error at $urlStr: ${e.message}")
                callback(null)
            } finally {
                conn?.disconnect()
            }
        }
    }
}
