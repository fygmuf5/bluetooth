package com.mcu.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.*
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import java.nio.charset.Charset
import java.text.SimpleDateFormat
import java.util.*

/**
 * 應用程式主介面 Activity (MainActivity)
 * 負責根據使用者角色 (STUDENT 學生 / TEACHER 教師) 分分流顯示介面：
 * 
 * 1. 學生端功能 (STUDENT)：
 *    - 背景每 30 秒自動向伺服器請求專屬 OTP 權杖與 XOR 金鑰
 *    - 將 `student_id|otp` 以 XOR 加密後，透過 BLE 藍牙廣播 (BluetoothLeAdvertiser) 發送打卡訊號
 *    - 提供手動立即簽到按鈕與即時狀態顯示 (包含當前 OTP 與更新時間)
 * 
 * 2. 教師端功能 (TEACHER)：
 *    - 載入並嵌入 TeacherControlsFragment，展示課程選擇、點名管理及教室熱力圖
 */
@SuppressLint("MissingPermission")
class MainActivity : AppCompatActivity() {

    // 藍牙廣播 Service UUID (必須與老師端掃描過濾 UUID 完全一致)
    private val SERVICE_UUID: UUID = UUID.fromString("00001111-0000-1000-8000-00805F9B34FB")
    // 學生端自動請求 OTP 與發送廣播的週期 (每 30 秒)
    private val AUTO_REFRESH_INTERVAL = 30 * 1000L

    // 藍牙系統服務與廣播器宣告
    private val bluetoothManager by lazy { getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager }
    private val bluetoothAdapter: BluetoothAdapter? get() = bluetoothManager.adapter
    private val bleAdvertiser: BluetoothLeAdvertiser? get() = bluetoothAdapter?.bluetoothLeAdvertiser

    // 儲存當前的廣播 Callback，用於更換廣播內容時停止舊廣播
    private var currentAdvertiseCallback: AdvertiseCallback? = null

    // UI 控制元件宣告
    private lateinit var statusTextView: TextView
    private lateinit var studentIdTextView: TextView
    private lateinit var broadcastButton: Button
    private lateinit var settingsButton: ImageButton
    private lateinit var studentCard: View
    private lateinit var teacherContainer: FrameLayout

    // 登入角色與使用者資料
    private var currentRole: String? = null
    private var userEmail: String? = null
    private var studentId: String = ""

    // 學生端 30 秒輪詢定時器 (Handler + Runnable)
    private val handler = Handler(Looper.getMainLooper())
    private val autoAttendanceRunnable = object : Runnable {
        override fun run() {
            if (currentRole == "STUDENT") {
                startAutomaticAttendance()
                handler.postDelayed(this, AUTO_REFRESH_INTERVAL) // 30 秒後自動重複執行
            }
        }
    }

    // Android 12+ 藍牙與定位權限請求合約
    private val requestBluetoothPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { perms ->
        if (perms.values.all { it }) {
            startAutomaticAttendance()
        } else {
            Toast.makeText(this, "未取得權限，自動點名無法運作", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 接收傳入的角色與 Email 參數
        currentRole = intent.getStringExtra("EXTRA_ROLE")
        userEmail = intent.getStringExtra("EXTRA_EMAIL")
        studentId = userEmail?.substringBefore("@") ?: "Unknown"

        // 初始化 UI、設定角色視圖與綁定事件
        initializeUI()
        setupRoleUI()
        setupListeners()

        // 若為學生身分，自動啟動背景點名輪詢
        if (currentRole == "STUDENT") {
            checkAndStartAutoAttendance()
        }
    }

    /**
     * 1. 綁定 UI 畫面元件
     */
    private fun initializeUI() {
        statusTextView = findViewById(R.id.status_textview)
        studentIdTextView = findViewById(R.id.student_id_textview)
        broadcastButton = findViewById(R.id.broadcast_button)
        settingsButton = findViewById(R.id.settings_button)
        studentCard = findViewById(R.id.student_card)
        teacherContainer = findViewById(R.id.teacher_container)
        
        broadcastButton.text = "手動立即簽到"
    }

    /**
     * 2. 依角色 (TEACHER / STUDENT) 切換顯示視圖
     */
    private fun setupRoleUI() {
        when (currentRole) {
            "TEACHER" -> {
                // 顯示教師容器並載入 TeacherControlsFragment
                teacherContainer.visibility = View.VISIBLE
                studentCard.visibility = View.GONE
                findViewById<View>(R.id.status_card).visibility = View.GONE
                if (supportFragmentManager.findFragmentById(R.id.teacher_container) == null) {
                    supportFragmentManager.beginTransaction()
                        .replace(R.id.teacher_container, TeacherControlsFragment())
                        .commit()
                }
            }
            "STUDENT" -> {
                // 顯示學生打卡卡片與狀態列
                teacherContainer.visibility = View.GONE
                studentCard.visibility = View.VISIBLE
                findViewById<View>(R.id.status_card).visibility = View.VISIBLE
                statusTextView.text = "身份: 學生 (背景自動點名中)"
                studentIdTextView.text = "學號 : $studentId"
            }
            else -> {
                // 無角色資訊時返回角色選擇頁
                startActivity(Intent(this, RoleSelectionActivity::class.java))
                finish()
            }
        }
    }

    /**
     * 3. 設定按鈕點擊監聽器
     */
    private fun setupListeners() {
        // 手動簽到按鈕：按下後觸發即時簽到廣播
        broadcastButton.setOnClickListener { 
            // 按鈕防抖：防止 2 秒內快速連點導致廣播實例衝突
            broadcastButton.isEnabled = false
            handler.postDelayed({ broadcastButton.isEnabled = true }, 2000)

            if (hasRequiredBluetoothPermissions()) {
                startAutomaticAttendance()
            } else {
                requestBluetoothPermissions.launch(getRequiredBluetoothPermissions())
            }
        }

        // 頂部設定/選單按鈕 (扳手/齒輪圖示)
        settingsButton.setOnClickListener { view ->
            showSettingsMenu(view)
        }
    }

    /**
     * 檢查權限並啟動學生背景自動點名
     */
    private fun checkAndStartAutoAttendance() {
        if (hasRequiredBluetoothPermissions()) {
            handler.post(autoAttendanceRunnable)
        } else {
            requestBluetoothPermissions.launch(getRequiredBluetoothPermissions())
        }
    }

    /**
     * 4. 學生端核心點名流程：向伺服器請求 Token/XOR Key 並發送 BLE 廣播
     */
    private fun startAutomaticAttendance() {
        val cleanStudentId = studentId.substringBefore("@").trim()
        if (cleanStudentId.isEmpty() || cleanStudentId == "Unknown") return

        // 藍牙狀態防呆檢查
        if (bluetoothAdapter == null) {
            statusTextView.text = "狀態: 裝置無藍牙硬體"
            return
        }

        if (!bluetoothAdapter!!.isEnabled) {
            statusTextView.text = "狀態: 請先開啟藍牙"
            Toast.makeText(this, "請先開啟手機藍牙以進行點名", Toast.LENGTH_SHORT).show()
            return
        }

        if (bleAdvertiser == null) {
            statusTextView.text = "狀態: 裝置不支援藍牙廣播點名"
            Toast.makeText(this, "您的手機不支援 BLE 廣播功能", Toast.LENGTH_LONG).show()
            return
        }

        val timeNow = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        statusTextView.text = "狀態: 正在同步權杖 ($timeNow)..."

        // 向伺服器讀取所屬學號當前 30 秒有效之 OTP 與 XOR 金鑰
        NetworkManager.getStudentToken(cleanStudentId) { otp, xorKey ->
            runOnUiThread {
                val updateTime = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                if (!otp.isNullOrEmpty() && !xorKey.isNullOrEmpty()) {
                    // 對學號與 OTP 進行 XOR 加密並發送 BLE 廣播
                    broadcastEncryptedMessage(cleanStudentId, otp, xorKey)
                    // 更新 UI 狀態欄，清楚標示當前 OTP 與更新時間
                    statusTextView.text = "狀態: 自動廣播中\n學號: $cleanStudentId | OTP: $otp\n更新時間: $updateTime (每30秒自動更新)"
                } else {
                    statusTextView.text = "狀態: 目前無點名活動 ($updateTime)"
                    stopBleAdvertising()
                }
            }
        }
    }

    /**
     * 5. 彈出設定快顯功能表 (選單：查詢紀錄、登出)
     */
    private fun showSettingsMenu(view: View) {
        val popup = PopupMenu(this, view)
        popup.menu.add(0, 1, 0, "查詢紀錄")
        popup.menu.add(0, 2, 1, "登出")

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> {
                    Toast.makeText(this, "查詢功能開發中...", Toast.LENGTH_SHORT).show()
                    true
                }
                2 -> {
                    logout()
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    /**
     * 6. 登出系統：停止定時器與藍牙廣播，清除本機 SharedPreferences 快取並返回登入頁
     */
    private fun logout() {
        handler.removeCallbacks(autoAttendanceRunnable)
        stopBleAdvertising()

        val sharedPref = getSharedPreferences("AttendanceApp", Context.MODE_PRIVATE)
        sharedPref.edit().clear().apply()

        val intent = Intent(this, RoleSelectionActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }

    /**
     * 7. XOR 加密處理：將 `student_id|otp` 的 UTF-8 位元組陣列與 XOR Key 進行位元互斥或運算
     */
    private fun broadcastEncryptedMessage(id: String, otp: String, xorKey: String) {
        val rawData = "$id|$otp"
        val rawBytes = rawData.toByteArray(Charset.forName("UTF-8"))
        val keyBytes = xorKey.toByteArray(Charset.forName("UTF-8"))

        if (keyBytes.isEmpty()) {
            statusTextView.text = "狀態: 金鑰無效，無法加密"
            return
        }

        val encryptedBytes = ByteArray(rawBytes.size)
        for (i in rawBytes.indices) {
            encryptedBytes[i] = (rawBytes[i].toInt() xor keyBytes[i % keyBytes.size].toInt()).toByte()
        }
        // 將加密後的位元組陣列送入 BLE 廣播器
        startAdvertising(encryptedBytes)
    }

    /**
     * 8. 啟動 Android BLE 藍牙廣播 (BluetoothLeAdvertiser)
     * 將加密資料放入 Service Data，Service UUID 放入 Scan Response，防止 Data Too Large 錯誤。
     */
    private fun startAdvertising(dataBytes: ByteArray) {
        // BLE 主廣播封包限制 26 bytes 以內
        if (dataBytes.size > 26) {
            runOnUiThread {
                statusTextView.text = "狀態: 廣播封包過大 (${dataBytes.size} bytes > 26)"
            }
            android.util.Log.e("MainActivity", "廣播封包過大: ${dataBytes.size} bytes")
            return
        }

        // 先停止之前的廣播實例
        stopBleAdvertising()
        
        // 設定低延遲、高功率之藍牙廣播參數
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(false)
            .build()

        // 主廣播封包：包含 Service Data
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addServiceData(ParcelUuid(SERVICE_UUID), dataBytes)
            .build()

        // Scan Response 掃描回應封包：包含 Service UUID
        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()

        // 建立廣播結果 Callback
        currentAdvertiseCallback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                super.onStartSuccess(settingsInEffect)
            }
            override fun onStartFailure(errorCode: Int) {
                runOnUiThread {
                    val msg = when (errorCode) {
                        ADVERTISE_FAILED_DATA_TOO_LARGE -> "封包過大 (1)"
                        ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "廣播實例過多 (2)"
                        ADVERTISE_FAILED_ALREADY_STARTED -> return@runOnUiThread
                        ADVERTISE_FAILED_INTERNAL_ERROR -> "系統藍牙內部錯誤，請重啟藍牙 (4)"
                        ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "裝置不支援此廣播模式 (5)"
                        else -> "代碼 $errorCode"
                    }
                    statusTextView.text = "廣播失敗: $msg"
                }
            }
        }

        // 啟動藍牙廣播
        bleAdvertiser?.startAdvertising(settings, data, scanResponse, currentAdvertiseCallback)
    }

    /**
     * 9. 停止 BLE 藍牙廣播
     */
    private fun stopBleAdvertising() {
        try {
            currentAdvertiseCallback?.let {
                bleAdvertiser?.stopAdvertising(it)
                currentAdvertiseCallback = null
            }
        } catch(e: Exception){
            e.printStackTrace()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Activity 銷毀時停止定時任務與藍牙廣播，避免記憶體洩漏
        handler.removeCallbacks(autoAttendanceRunnable)
        stopBleAdvertising()
    }

    // 權限檢查輔助函式 (相容 Android 12 S 前後版本差異)
    private fun hasPermission(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
    private fun hasRequiredBluetoothPermissions() = getRequiredBluetoothPermissions().all { hasPermission(it) }
    private fun getRequiredBluetoothPermissions() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT)
    } else arrayOf(Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_ADMIN, Manifest.permission.ACCESS_FINE_LOCATION)
}
