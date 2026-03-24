package com.mcu.bluetooth

import android.util.Log
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

object NetworkManager {
    // **請將此處替換為您伺服器的 IP (或是伺服器網址)與路徑 (例如 http://192.168.1.100/save.php)**
    private const val SERVER_URL = "http://YOUR_SERVER_IP/save_attendance.php"

    /**
     * 同步點名紀錄到伺服器
     */
    fun syncAttendance(studentInfo: String, address: String, callback: (Boolean) -> Unit) {
        Thread {
            var conn: HttpURLConnection? = null
            try {
                val url = URL(SERVER_URL)
                conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")

                val postData = "student_info=" + URLEncoder.encode(studentInfo, "UTF-8") +
                        "&device_address=" + URLEncoder.encode(address, "UTF-8")

                OutputStreamWriter(conn.outputStream, StandardCharsets.UTF_8).use { writer ->
                    writer.write(postData)
                    writer.flush()
                }

                val responseCode = conn.responseCode
                Log.d("NetworkManager", "Response Code: $responseCode")
                callback(responseCode == HttpURLConnection.HTTP_OK)

            } catch (e: Exception) {
                Log.e("NetworkManager", "Error syncing data", e)
                callback(false)
            } finally {
                conn?.disconnect()
            }
        }.start()
    }

    /**
     * 同步用戶帳戶資訊 (例如學生第一次輸入資料時)
     */
    fun syncUserInfo(studentInfo: String, callback: (Boolean) -> Unit) {
        // 邏輯與 syncAttendance 類似，可根據需求調整參數
        Thread {
            var conn: HttpURLConnection? = null
            try {
                val url = URL(SERVER_URL.replace("save_attendance.php", "save_user.php"))
                conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")

                val postData = "student_info=" + URLEncoder.encode(studentInfo, "UTF-8")

                OutputStreamWriter(conn.outputStream, StandardCharsets.UTF_8).use { writer ->
                    writer.write(postData)
                    writer.flush()
                }

                callback(conn.responseCode == HttpURLConnection.HTTP_OK)
            } catch (e: Exception) {
                callback(false)
            } finally {
                conn?.disconnect()
            }
        }.start()
    }
}
