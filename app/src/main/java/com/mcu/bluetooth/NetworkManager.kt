package com.mcu.bluetooth

import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

object NetworkManager {

    // --- 1. 伺服器設定區 ---
    private const val BASE_URL = "https://micronemous-indefeasibly-cooper.ngrok-free.app"

    // 原始功能路徑
    private const val PATH_ATTENDANCE   = "/api/check-in"
    private const val PATH_USER_INFO    = "/api/my-courses"
    private const val PATH_VERIFY_CODE  = "/api/send-code"
    private const val PATH_REGISTER     = "/api/register"
    private const val PATH_LOGIN        = "/api/auth/login"

    // 安全點名 Session 路徑 (OTP + XOR 方案)
    private const val PATH_START_SESSION = "/api/session/start"       // 老師：發起點名並拿 XOR Key
    private const val PATH_GET_OTP_LIST  = "/api/session/otp-list"    // 老師：獲取全班驗證清單
    private const val PATH_GET_MY_TOKEN  = "/api/session/get-token"   // 學生：獲取個人 OTP 與 XOR Key

    private const val TIMEOUT_MS = 5000

    /**
     * 老師端：開始點名，獲取本次 Session 的 XOR Key
     */
    fun startAttendanceSession(email: String, callback: (String?) -> Unit) {
        val json = JSONObject().apply { put("email", email) }
        sendJsonPostWithResponse(BASE_URL + PATH_START_SESSION, json) { response ->
            callback(response?.optString("xor_key"))
        }
    }

    /**
     * 老師端：獲取即時驗證名單 (學號對應 OTP)
     */
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

    /**
     * 學生端：獲取當次點名的專屬 OTP 與 XOR Key
     */
    fun getStudentToken(studentId: String, callback: (otp: String?, xorKey: String?) -> Unit) {
        val json = JSONObject().apply { put("student_id", studentId) }
        sendJsonPostWithResponse(BASE_URL + PATH_GET_MY_TOKEN, json) { response ->
            callback(response?.optString("otp"), response?.optString("xor_key"))
        }
    }

    /**
     * 登入驗證
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
     * 註冊帳號 (首次綁定設備)
     */
    fun registerUser(email: String, password: String, verifyCode: String, deviceId: String, callback: (Boolean) -> Unit) {
        val studentId = email.substringBefore("@")
        val json = JSONObject().apply {
            put("user_id", studentId)
            put("name", "User")
            put("email", email)
            put("password", password)
            put("code", verifyCode)
            put("device_id", deviceId)
        }
        sendJsonPost(BASE_URL + PATH_REGISTER, json, callback)
    }

    /**
     * 請求註冊驗證碼
     */
    fun requestVerifyCode(email: String, callback: (Boolean) -> Unit) {
        val json = JSONObject().apply { put("email", email) }
        sendJsonPost(BASE_URL + PATH_VERIFY_CODE, json, callback)
    }

    /**
     * 同步點名紀錄 (舊接口保留)
     */
    fun syncAttendance(studentInfo: String, address: String, callback: (Boolean) -> Unit) {
        val json = JSONObject().apply {
            put("student_info", studentInfo)
            put("device_address", address)
        }
        sendJsonPost(BASE_URL + PATH_ATTENDANCE, json, callback)
    }

    // --- 內部私有通用工具方法 ---

    private fun sendJsonPost(urlStr: String, jsonBody: JSONObject, callback: (Boolean) -> Unit) {
        sendJsonPostWithResponse(urlStr, jsonBody) { response ->
            callback(response != null)
        }
    }

    private fun sendJsonPostWithResponse(urlStr: String, jsonBody: JSONObject, callback: (JSONObject?) -> Unit) {
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

                OutputStreamWriter(conn.outputStream, StandardCharsets.UTF_8).use { 
                    it.write(jsonBody.toString())
                }

                if (conn.responseCode == HttpURLConnection.HTTP_OK || conn.responseCode == HttpURLConnection.HTTP_CREATED) {
                    val reader = BufferedReader(InputStreamReader(conn.inputStream))
                    val sb = StringBuilder()
                    var line: String?
                    while (reader.readLine().also { line = it } != null) sb.append(line)
                    callback(JSONObject(sb.toString()))
                } else {
                    callback(null)
                }
            } catch (e: Exception) {
                Log.e("NetworkManager", "Error: ${e.message}")
                callback(null)
            } finally {
                conn?.disconnect()
            }
        }.start()
    }
}
