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
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import java.nio.charset.Charset
import java.text.SimpleDateFormat
import java.util.*

@SuppressLint("MissingPermission")
class MainActivity : AppCompatActivity() {

    private val SERVICE_UUID: UUID = UUID.fromString("00001111-0000-1000-8000-00805F9B34FB")
    private val AUTO_REFRESH_INTERVAL = 2 * 60 * 1000L // 縮短為 2 分鐘，確保比老師端快，增加同步成功率 (修正點 5)

    private val bluetoothManager by lazy { getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager }
    private val bluetoothAdapter: BluetoothAdapter? get() = bluetoothManager.adapter
    private val bleAdvertiser: BluetoothLeAdvertiser? get() = bluetoothAdapter?.bluetoothLeAdvertiser

    // 儲存當前的廣播回呼，以便正確停止 (修正點 1)
    private var currentAdvertiseCallback: AdvertiseCallback? = null

    private lateinit var statusTextView: TextView
    private lateinit var studentIdTextView: TextView
    private lateinit var broadcastButton: Button
    private lateinit var settingsButton: ImageButton
    private lateinit var studentCard: View
    private lateinit var teacherContainer: FrameLayout

    private var currentRole: String? = null
    private var userEmail: String? = null
    private var studentId: String = ""

    private val handler = Handler(Looper.getMainLooper())
    private val autoAttendanceRunnable = object : Runnable {
        override fun run() {
            if (currentRole == "STUDENT") {
                startAutomaticAttendance()
                handler.postDelayed(this, AUTO_REFRESH_INTERVAL)
            }
        }
    }

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

        currentRole = intent.getStringExtra("EXTRA_ROLE")
        userEmail = intent.getStringExtra("EXTRA_EMAIL")
        studentId = userEmail?.substringBefore("@") ?: "Unknown"

        initializeUI()
        setupRoleUI()
        setupListeners()

        if (currentRole == "STUDENT") {
            checkAndStartAutoAttendance()
        }
    }

    private fun initializeUI() {
        statusTextView = findViewById(R.id.status_textview)
        studentIdTextView = findViewById(R.id.student_id_textview)
        broadcastButton = findViewById(R.id.broadcast_button)
        settingsButton = findViewById(R.id.settings_button)
        studentCard = findViewById(R.id.student_card)
        teacherContainer = findViewById(R.id.teacher_container)
        
        broadcastButton.text = "手動立即簽到"
    }

    private fun setupRoleUI() {
        when (currentRole) {
            "TEACHER" -> {
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
                teacherContainer.visibility = View.GONE
                studentCard.visibility = View.VISIBLE
                findViewById<View>(R.id.status_card).visibility = View.VISIBLE
                statusTextView.text = "身份: 學生 (背景自動點名中)"
                studentIdTextView.text = "學號 : $studentId"
            }
            else -> {
                startActivity(Intent(this, RoleSelectionActivity::class.java))
                finish()
            }
        }
    }

    private fun setupListeners() {
        broadcastButton.setOnClickListener { 
            // 防抖：避免短時間快速連點導致藍牙廣播實例衝突
            broadcastButton.isEnabled = false
            handler.postDelayed({ broadcastButton.isEnabled = true }, 2000)

            if (hasRequiredBluetoothPermissions()) {
                startAutomaticAttendance()
            } else {
                requestBluetoothPermissions.launch(getRequiredBluetoothPermissions())
            }
        }

        settingsButton.setOnClickListener { view ->
            showSettingsMenu(view)
        }
    }

    private fun checkAndStartAutoAttendance() {
        if (hasRequiredBluetoothPermissions()) {
            handler.post(autoAttendanceRunnable)
        } else {
            requestBluetoothPermissions.launch(getRequiredBluetoothPermissions())
        }
    }

    private fun startAutomaticAttendance() {
        if (studentId.isEmpty() || studentId == "Unknown") return

        if (bluetoothAdapter == null) {
            statusTextView.text = "狀態: 裝置無藍牙硬體"
            return
        }

        if (!bluetoothAdapter!!.isEnabled) {
            statusTextView.text = "狀態: 請先開啟藍牙"
            Toast.makeText(this, "請先開啟手機藍牙以進行點名", Toast.LENGTH_SHORT).show()
            return
        }

        // 避免因部分晶片 isMultipleAdvertisementSupported 回傳 false 誤判，改以 bleAdvertiser 是否有效判斷
        if (bleAdvertiser == null) {
            statusTextView.text = "狀態: 裝置不支援藍牙廣播點名"
            Toast.makeText(this, "您的手機不支援 BLE 廣播功能", Toast.LENGTH_LONG).show()
            return
        }

        val timeNow = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        statusTextView.text = "狀態: 正在同步權杖 ($timeNow)..."

        NetworkManager.getStudentToken(studentId) { otp, xorKey ->
            runOnUiThread {
                if (!otp.isNullOrEmpty() && !xorKey.isNullOrEmpty()) {
                    broadcastEncryptedMessage(studentId, otp, xorKey)
                    statusTextView.text = "狀態: 自動發送中 (OTP: $otp)"
                } else {
                    statusTextView.text = "狀態: 目前無點名活動 ($timeNow)"
                    stopBleAdvertising()
                }
            }
        }
    }

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
        startAdvertising(encryptedBytes)
    }

    private fun startAdvertising(dataBytes: ByteArray) {
        if (dataBytes.size > 26) return

        stopBleAdvertising()
        
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(false)
            .build()

        // 避免封包超過 31 bytes (DATA_TOO_LARGE)：
        // 將主要 Service Data 放在主廣播封包，Service UUID 放入 ScanResponse
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addServiceData(ParcelUuid(SERVICE_UUID), dataBytes)
            .build()

        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()

        currentAdvertiseCallback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                super.onStartSuccess(settingsInEffect)
            }
            override fun onStartFailure(errorCode: Int) {
                runOnUiThread {
                    val msg = when (errorCode) {
                        ADVERTISE_FAILED_DATA_TOO_LARGE -> "封包過大 (1)"
                        ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "廣播實例過多 (2)"
                        ADVERTISE_FAILED_ALREADY_STARTED -> return@runOnUiThread // 已在廣播中，不視為錯誤
                        ADVERTISE_FAILED_INTERNAL_ERROR -> "系統藍牙內部錯誤，請重啟藍牙 (4)"
                        ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "裝置不支援此廣播模式 (5)"
                        else -> "代碼 $errorCode"
                    }
                    statusTextView.text = "廣播失敗: $msg"
                }
            }
        }

        bleAdvertiser?.startAdvertising(settings, data, scanResponse, currentAdvertiseCallback)
    }

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
        handler.removeCallbacks(autoAttendanceRunnable)
        stopBleAdvertising()
    }

    private fun hasPermission(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
    private fun hasRequiredBluetoothPermissions() = getRequiredBluetoothPermissions().all { hasPermission(it) }
    private fun getRequiredBluetoothPermissions() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT)
    } else arrayOf(Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_ADMIN, Manifest.permission.ACCESS_FINE_LOCATION)
}
