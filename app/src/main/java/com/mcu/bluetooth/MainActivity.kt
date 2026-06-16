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
    private val AUTO_REFRESH_INTERVAL = 5 * 60 * 1000L // 5 分鐘自動刷新一次

    private val bluetoothManager by lazy { getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager }
    private val bluetoothAdapter: BluetoothAdapter? by lazy { bluetoothManager.adapter }
    private val bleAdvertiser: BluetoothLeAdvertiser? by lazy { bluetoothAdapter?.bluetoothLeAdvertiser }

    private lateinit var statusTextView: TextView
    private lateinit var studentIdTextView: TextView
    private lateinit var broadcastButton: Button
    private lateinit var settingsButton: ImageButton
    private lateinit var studentCard: View
    private lateinit var teacherPagerContainer: View
    private lateinit var viewPager: ViewPager2
    private lateinit var dot1: ImageView
    private lateinit var dot2: ImageView

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
        teacherPagerContainer = findViewById(R.id.teacher_pager_container)
        viewPager = findViewById(R.id.teacher_view_pager)
        dot1 = findViewById(R.id.dot1)
        dot2 = findViewById(R.id.dot2)
        
        broadcastButton.text = "手動立即簽到"
    }

    private fun setupRoleUI() {
        when (currentRole) {
            "TEACHER" -> {
                teacherPagerContainer.visibility = View.VISIBLE
                studentCard.visibility = View.GONE
                statusTextView.text = "身份: 老師 (點名週期運行中)"
                setupTeacherViewPager()
            }
            "STUDENT" -> {
                teacherPagerContainer.visibility = View.GONE
                studentCard.visibility = View.VISIBLE
                statusTextView.text = "身份: 學生 (背景自動點名中)"
                studentIdTextView.text = "學號 : $studentId"
            }
            else -> {
                startActivity(Intent(this, RoleSelectionActivity::class.java))
                finish()
            }
        }
    }

    private fun setupTeacherViewPager() {
        val adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount(): Int = 2
            override fun createFragment(position: Int): Fragment {
                return if (position == 0) TeacherControlsFragment() else HeatmapFragment()
            }
        }
        viewPager.adapter = adapter
        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                if (position == 0) {
                    dot1.setImageResource(R.drawable.dot_filled)
                    dot2.setImageResource(R.drawable.dot_empty)
                } else {
                    dot1.setImageResource(R.drawable.dot_empty)
                    dot2.setImageResource(R.drawable.dot_filled)
                }
            }
        })
    }

    private fun setupListeners() {
        broadcastButton.setOnClickListener { 
            startAutomaticAttendance()
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

        val timeNow = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        statusTextView.text = "狀態: 正在同步權杖 ($timeNow)..."

        NetworkManager.getStudentToken(studentId) { otp, xorKey ->
            runOnUiThread {
                if (otp != null && xorKey != null) {
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

    /**
     * 優化後的登出功能：清除 SharedPreferences 中的登入紀錄
     */
    private fun logout() {
        // 停止背景任務與藍牙廣播
        handler.removeCallbacks(autoAttendanceRunnable)
        stopBleAdvertising()

        // 清除自動登入紀錄
        val sharedPref = getSharedPreferences("AttendanceApp", Context.MODE_PRIVATE)
        sharedPref.edit().clear().apply()

        // 返回登入畫面並清空 Activity 棧
        val intent = Intent(this, RoleSelectionActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }

    private fun broadcastEncryptedMessage(id: String, otp: String, xorKey: String) {
        val rawData = "$id|$otp"
        val rawBytes = rawData.toByteArray(Charset.forName("UTF-8"))
        val keyBytes = xorKey.toByteArray(Charset.forName("UTF-8"))

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
            .setConnectable(false)
            .build()
        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .addServiceData(ParcelUuid(SERVICE_UUID), dataBytes)
            .build()

        bleAdvertiser?.startAdvertising(settings, data, object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {}
            override fun onStartFailure(errorCode: Int) {
                runOnUiThread { statusTextView.text = "廣播失敗: $errorCode" }
            }
        })
    }

    private fun stopBleAdvertising() {
        try { bleAdvertiser?.stopAdvertising(object : AdvertiseCallback(){}) } catch(e: Exception){}
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
