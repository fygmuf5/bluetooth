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

    fun startAttendanceSession(email: String, callback: (String?) -> Unit) {
        val json = JSONObject().apply { put("email", email) }
        sendJsonPostWithResponse(BASE_URL + PATH_START_SESSION, json) { response ->
            callback(response?.optString("xor_key"))
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
            callback(response?.optString("otp"), response?.optString("xor_key"))
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

    /**
     * 註冊帳號：修改為回傳 (是否成功, 錯誤訊息)
     */
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
                val success = response.optBoolean("success", true)
                val message = response.optString("message", null)
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
                val success = response.optBoolean("success", true)
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
            callback(response != null && response.optBoolean("success", true))
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

                val responseCode = conn.responseCode
                // 讀取正常串流 (2xx) 或 錯誤串流 (4xx, 5xx)
                val stream = if (responseCode in 200..299) conn.inputStream else conn.errorStream
                
                if (stream != null) {
                    val reader = BufferedReader(InputStreamReader(stream))
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
