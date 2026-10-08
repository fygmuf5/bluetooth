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
 * 網路通訊管理物件 (NetworkManager)
 * 負責處理 App 所有與後端伺服器 (API) 的 HTTP/HTTPS 請求通訊：
 * 1. 點名階段同步 (發起點名、獲取 OTP 名單、取得個人 Token/XOR Key)
 * 2. 帳號身分驗證 (登入、註冊、發送驗證碼)
 * 3. 點名結果回傳與紀錄同步
 * 4. 教室熱力圖座標查詢、上傳與清除
 * 
 * 內部採用固定數量執行緒池 (Fixed Thread Pool) 處理非同步網路連線，避免阻塞 UI 主執行緒。
 */
object NetworkManager {

    // 後端伺服器基礎 URL (使用 ngrok 穿透)
    private const val BASE_URL = "https://micronemous-indefeasibly-cooper.ngrok-free.dev"

    // 點名與帳號 API 路徑定義
    private const val PATH_ATTENDANCE   = "/api/check-in"      // 回傳同步點名紀錄
    private const val PATH_VERIFY_CODE  = "/api/send-code"     // 發送 Email 註冊驗證碼
    private const val PATH_REGISTER     = "/api/register"      // 使用者註冊
    private const val PATH_LOGIN        = "/api/auth/login"     // 使用者登入

    // 點名 Session 相關 API 路徑
    private const val PATH_START_SESSION = "/api/session/start"     // 老師發起點名 Session
    private const val PATH_GET_OTP_LIST  = "/api/session/otp-list"   // 老師取得全班 OTP 驗證名單
    private const val PATH_GET_MY_TOKEN  = "/api/session/get-token"  // 學生取得個人專屬 OTP 與 XOR Key// 教室熱力圖與定位相關 API 路徑
    private const val PATH_UPLOAD_COORDS = "/api/coords"        // 上傳學生座標 (定位計算 -> 伺服器)
    private const val PATH_GET_COORDS    = "/api/coords/get"    // 取得所有學生定位座標
    private const val PATH_CLEAR_COORDS  = "/api/coords/clear"  // 清除本次點名的定位紀錄

    // 網路連線逾時時間設定 (毫秒)
    private const val TIMEOUT_MS = 5000
    
    // 使用執行緒池 (4 個 worker 執行緒) 管理網路請求，防止高頻率發送請求時造成系統資源耗盡
    private val executor = Executors.newFixedThreadPool(4)

    /**
     * 1. 老師端發起/刷新點名 Session
     * 向伺服器告知點名開始，並取得該次點名循環的 XOR 加密金鑰。
     * @param email 老師 Email
     * @param callback 回傳 XOR Key (若失敗則回傳 null)
     */
    fun startAttendanceSession(email: String, callback: (String?) -> Unit) {
        val json = JSONObject().apply { put("email", email) }
        sendJsonPostWithResponse(BASE_URL + PATH_START_SESSION, json) { response ->
            val xorKey = response?.optString("xor_key")?.takeIf { it.isNotEmpty() }
            callback(xorKey)
        }
    }

    /**
     * 2. 老師端取得全班 OTP 驗證對照名單
     * 點名期間老師端每 30 秒呼叫一次，取得全班各學號當前對應的 OTP，用於解密 BLE 藍牙廣播後進行比對。
     * @param email 老師 Email
     * @param callback 回傳 <學號, OTP> 映射 Map (若無資料則回傳 null)
     */
    fun getVerifyList(email: String, callback: (Map<String, String>?) -> Unit) {
        val json = JSONObject().apply { put("email", email) }
        sendJsonPostWithResponse(BASE_URL + PATH_GET_OTP_LIST, json) { response ->
            val otpMap = mutableMapOf<String, String>()
            val data = response?.optJSONObject("otp_list")
            if (data != null) {
                val keys = data.keys()
                while (keys.hasNext()) {
                    val studentId = keys.next()
                    // 容錯提取：相容 String 與 Int/Long 型態之 OTP 數值
                    val otpValue = data.optString(studentId, "").takeIf { it.isNotEmpty() }
                        ?: data.opt(studentId)?.toString() ?: ""
                    if (otpValue.isNotEmpty()) {
                        otpMap[studentId] = otpValue
                    }
                }
            }
            Log.d("NetworkManager", "取得 OTP 名單成功，共 ${otpMap.size} 筆: $otpMap")
            callback(otpMap.ifEmpty { null })
        }
    }

    /**
     * 3. 學生端取得個人專屬 OTP 與 XOR 加密金鑰
     * 學生端每 30 秒自動向伺服器請求一次，取得屬於該學號當前的 OTP 權杖與加密金鑰後廣播。
     * @param studentId 學生學號
     * @param callback 回傳 (otp, xorKey)
     */
    fun getStudentToken(studentId: String, callback: (otp: String?, xorKey: String?) -> Unit) {
        val json = JSONObject().apply { put("student_id", studentId) }
        sendJsonPostWithResponse(BASE_URL + PATH_GET_MY_TOKEN, json) { response ->
            val otp = response?.optString("otp")?.takeIf { it.isNotEmpty() }
                ?: response?.opt("otp")?.toString()?.takeIf { it.isNotEmpty() }
            val xorKey = response?.optString("xor_key")?.takeIf { it.isNotEmpty() }
                ?: response?.opt("xor_key")?.toString()?.takeIf { it.isNotEmpty() }
            callback(otp, xorKey)
        }
    }

    /**
     * 4. 使用者登入驗證
     * 支援學生與老師登入，驗證 Email、密碼與裝置識別碼。
     * @param email 使用者 Email
     * @param password 密碼
     * @param deviceId 裝置 Android ID
     * @param callback 回傳 (是否成功, 提示訊息)
     */
    fun login(email: String, password: String, deviceId: String, callback: (Boolean, String?) -> Unit) {
        val json = JSONObject().apply {
            put("email", email)
            put("password", password)
            put("device_id", deviceId)
        }
        
        sendJsonPostWithResponse(BASE_URL + PATH_LOGIN, json) { response ->
            if (response != null) {
                // 判斷多種可能成功格式，增加伺服器相容性
                val success = response.optBoolean("success", false) || 
                             response.has("token") || 
                             response.optString("status", "") == "success"
                
                val message = response.optString("message", if (success) "登入成功" else "帳號或密碼錯誤")
                callback(success, message)
            } else {
                callback(false, "網路連線異常，請確認後端網址")
            }
        }
    }

    /**
     * 5. 使用者帳號註冊
     * 將學號、Email、密碼、驗證碼及裝置 ID 送至後端完成帳號創建。
     * @param email 使用者 Email
     * @param password 密碼
     * @param verifyCode Email 驗證碼
     * @param deviceId 裝置 Android ID
     * @param callback 回傳 (是否成功, 提示訊息)
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
                val success = response.optBoolean("success", false)
                val message = response.optString("message", "連線失敗")
                callback(success, message)
            } else {
                callback(false, "網路連線異常")
            }
        }
    }

    /**
     * 6. 請求發送 Email 驗證碼
     * 用於註冊前獲取驗證碼。
     * @param email 目標 Email
     * @param callback 回傳 (是否發送成功, 提示訊息)
     */
    fun requestVerifyCode(email: String, callback: (Boolean, String?) -> Unit) {
        val json = JSONObject().apply { put("email", email) }
        sendJsonPostWithResponse(BASE_URL + PATH_VERIFY_CODE, json) { response ->
            if (response != null) {
                val success = response.optBoolean("success", false)
                val message = if (response.has("message")) response.optString("message", "") else null
                callback(success, message)
            } else {
                callback(false, "連線失敗")
            }
        }
    }

    /**
     * 7. 老師端回傳點名結果同步至後端
     * 點名結束時，將成功簽到的學生資訊與裝置位址同步上傳。
     * @param studentInfo 學生學號/資訊
     * @param address 裝置藍牙位址
     * @param callback 回傳是否同步成功
     */
    fun syncAttendance(studentInfo: String, address: String, callback: (Boolean) -> Unit) {
        val json = JSONObject().apply {
            put("student_info", studentInfo)
            put("device_address", address)
        }
        sendJsonPost(BASE_URL + PATH_ATTENDANCE, json, callback)
    }

    /**
     * 8. 上傳學生教室定位座標 (定位計算 -> 後端)
     * @param sessionId 點名 Session ID
     * @param studentId 學生學號
     * @param x X 座標
     * @param y Y 座標
     * @param coordinateSystem 座標系統 (預設 grid32)
     * @param timestamp 上傳時間戳記
     * @param callback 回傳是否成功接收
     */
    fun uploadCoordinates(
        sessionId: String,
        studentId: String,
        x: Number,
        y: Number,
        coordinateSystem: String = "grid32",
        timestamp: String,
        callback: (Boolean) -> Unit
    ) {
        val json = JSONObject().apply {
            put("session_id", sessionId)
            put("student_id", studentId)
            put("x", x)
            put("y", y)
            put("coordinate_system", coordinateSystem)
            put("timestamp", timestamp)
        }
        sendJsonPostWithResponse(BASE_URL + PATH_UPLOAD_COORDS, json) { response ->
            callback(response != null && (response.optBoolean("accepted", false) || response.optString("status", "") == "ok"))
        }
    }

    /**
     * 9. 取得本次點名所有學生之教室熱力圖座標
     * @param email 老師/使用者 Email
     * @param password 密碼驗證
     * @param sessionId 本次 Session ID
     * @param callback 回傳伺服器 JSON 內容 (包含 coords 物件與 updated_at 時間)
     */
    fun getStudentCoordinates(
        email: String,
        password: String,
        sessionId: String,
        callback: (JSONObject?) -> Unit
    ) {
        val json = JSONObject().apply {
            put("email", email)
            put("password", password)
            put("session_id", sessionId)
        }
        sendJsonPostWithResponse(BASE_URL + PATH_GET_COORDS, json, callback)
    }

    /**
     * 10. 老師端清除本次點名之定位紀錄
     * @param email 老師 Email
     * @param password 密碼驗證
     * @param sessionId 本次 Session ID
     * @param callback 回傳 (是否清除成功, 清除比數)
     */
    fun clearCoordinates(
        email: String,
        password: String,
        sessionId: String,
        callback: (Boolean, Int) -> Unit
    ) {
        val json = JSONObject().apply {
            put("email", email)
            put("password", password)
            put("session_id", sessionId)
        }
        sendJsonPostWithResponse(BASE_URL + PATH_CLEAR_COORDS, json) { response ->
            if (response != null && response.optString("status", "") == "ok") {
                val clearedCount = response.optInt("cleared", 0)
                callback(true, clearedCount)
            } else {
                callback(false, 0)
            }
        }
    }

    /**
     * 底層工具輔助函式：發送 HTTP POST 請求並僅關心 Boolean 結果
     */
    private fun sendJsonPost(urlStr: String, jsonBody: JSONObject, callback: (Boolean) -> Unit) {
        sendJsonPostWithResponse(urlStr, jsonBody) { response ->
            callback(response != null && response.optBoolean("success", false))
        }
    }

    /**
     * 底層核心函式：使用 HttpURLConnection 在背景執行緒發送 HTTP POST JSON 請求，並回傳 JSONObject 響應
     * 包含請求標頭設定、串流讀取與 JSON 解析錯誤例外處理。
     */
    private fun sendJsonPostWithResponse(urlStr: String, jsonBody: JSONObject, callback: (JSONObject?) -> Unit) {
        // 將耗時網路任務提交至固定執行緒池處理
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

                // 寫入 JSON 請求內文 (Request Body)
                OutputStreamWriter(conn.outputStream, StandardCharsets.UTF_8).use { 
                    it.write(jsonBody.toString())
                }

                // 讀取 HTTP 回應狀態碼與對應之輸入串流
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
                        Log.e("NetworkManager", "JSON Parse Error at $urlStr: $responseStr", e)
                        callback(null)
                    }
                } else {
                    Log.e("NetworkManager", "Response Stream is Null at $urlStr (Code: $responseCode)")
                    callback(null)
                }
            } catch (e: Exception) {
                Log.e("NetworkManager", "Network Error at $urlStr: ${e.message}", e)
                callback(null)
            } finally {
                conn?.disconnect() // 確保關閉 HTTP 連線
            }
        }
    }
}
