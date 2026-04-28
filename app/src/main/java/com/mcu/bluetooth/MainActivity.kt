package com.mcu.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.ParcelUuid
import android.util.Log
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
import java.util.*

@SuppressLint("MissingPermission")
class MainActivity : AppCompatActivity() {

    // --- 通訊設定 ---
    private val SERVICE_UUID: UUID = UUID.fromString("00001111-0000-1000-8000-00805F9B34FB")

    private val bluetoothManager by lazy { getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager }
    private val bluetoothAdapter: BluetoothAdapter by lazy { bluetoothManager.adapter }
    private val bleAdvertiser: BluetoothLeAdvertiser by lazy { bluetoothAdapter.bluetoothLeAdvertiser }

    private lateinit var statusTextView: TextView
    private lateinit var studentIdTextView: TextView
    private lateinit var sessionCodeEditText: EditText
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

    private val requestBluetoothPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { perms ->
        val allGranted = perms.values.all { it }
        if (allGranted) {
            Toast.makeText(this, "權限已取得，請再次點擊按鈕", Toast.LENGTH_SHORT).show()
        } else {
            showPermissionExplanation()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        currentRole = intent.getStringExtra("EXTRA_ROLE")
        userEmail = intent.getStringExtra("EXTRA_EMAIL")
        
        // 提取學號 (Email @ 前面的部分)
        studentId = userEmail?.substringBefore("@") ?: "Unknown"

        initializeUI()
        setupRoleUI()
        setupListeners()
    }

    private fun initializeUI() {
        statusTextView = findViewById(R.id.status_textview)
        studentIdTextView = findViewById(R.id.student_id_textview)
        sessionCodeEditText = findViewById(R.id.session_code_edittext)
        broadcastButton = findViewById(R.id.broadcast_button)
        settingsButton = findViewById(R.id.settings_button)
        studentCard = findViewById(R.id.student_card)
        teacherPagerContainer = findViewById(R.id.teacher_pager_container)
        viewPager = findViewById(R.id.teacher_view_pager)
        dot1 = findViewById(R.id.dot1)
        dot2 = findViewById(R.id.dot2)
    }

    private fun setupRoleUI() {
        when (currentRole) {
            "TEACHER" -> {
                teacherPagerContainer.visibility = View.VISIBLE
                studentCard.visibility = View.GONE
                statusTextView.text = "身份: 老師 (左右滑動切換)"
                setupTeacherViewPager()
            }
            "STUDENT" -> {
                teacherPagerContainer.visibility = View.GONE
                studentCard.visibility = View.VISIBLE
                statusTextView.text = "身份: 學生 (安全發送模式)"
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
            val sessionCode = sessionCodeEditText.text.toString().trim()
            if (sessionCode.isEmpty()) {
                Toast.makeText(this, "請輸入點名代碼", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (studentId.isNotEmpty() && studentId != "Unknown") {
                if (checkAndRequestPermissions()) {
                    // 1. 先向伺服器獲取 OTP 與 XOR Key
                    statusTextView.text = "正在獲取安全權杖..."
                    NetworkManager.getStudentToken(studentId) { otp, xorKey ->
                        runOnUiThread {
                            if (otp != null && xorKey != null) {
                                // 2. 組合封包並加密發送
                                broadcastEncryptedMessage(studentId, otp, xorKey)
                            } else {
                                statusTextView.text = "獲取權杖失敗，請確認代碼"
                                Toast.makeText(this, "獲取 OTP 失敗", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            } else {
                Toast.makeText(this, "無法獲取學號資訊", Toast.LENGTH_SHORT).show()
            }
        }

        settingsButton.setOnClickListener { view ->
            showSettingsMenu(view)
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
                    stopBleAdvertising()
                    val intent = Intent(this, RoleSelectionActivity::class.java)
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    startActivity(intent)
                    finish()
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun checkAndRequestPermissions(): Boolean {
        if (hasRequiredBluetoothPermissions()) return true
        
        AlertDialog.Builder(this)
            .setTitle("需要權限")
            .setMessage("本功能需要藍牙與定位權限來發送點名訊號。")
            .setPositiveButton("確定") { _, _ ->
                requestBluetoothPermissions.launch(getRequiredBluetoothPermissions())
            }
            .setNegativeButton("取消", null)
            .show()
        return false
    }

    private fun showPermissionExplanation() {
        Toast.makeText(this, "未取得必要權限，功能無法運作", Toast.LENGTH_LONG).show()
    }

    /**
     * 執行 XOR 加密並發送
     */
    private fun broadcastEncryptedMessage(id: String, otp: String, xorKey: String) {
        // 封包結構：學號|OTP
        val rawData = "$id|$otp"
        val rawBytes = rawData.toByteArray(Charset.forName("UTF-8"))
        val keyBytes = xorKey.toByteArray(Charset.forName("UTF-8"))

        // XOR 加密運算
        val encryptedBytes = ByteArray(rawBytes.size)
        for (i in rawBytes.indices) {
            encryptedBytes[i] = (rawBytes[i].toInt() xor keyBytes[i % keyBytes.size].toInt()).toByte()
        }

        if (encryptedBytes.size > 26) {
            Toast.makeText(this, "封包過大", Toast.LENGTH_SHORT).show()
            return
        }

        stopBleAdvertising()
        val settings = AdvertiseSettings.Builder().setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY).setConnectable(false).build()
        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .addServiceData(ParcelUuid(SERVICE_UUID), encryptedBytes)
            .build()

        bleAdvertiser.startAdvertising(settings, data, object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                runOnUiThread { 
                    statusTextView.text = "Status: 已加密發送 (OTP: $otp)" 
                    Toast.makeText(this@MainActivity, "簽到訊號發送中...", Toast.LENGTH_SHORT).show()
                }
            }
            override fun onStartFailure(errorCode: Int) {
                runOnUiThread { statusTextView.text = "發送失敗: $errorCode" }
            }
        })
    }

    private fun stopBleAdvertising() {
        try { bleAdvertiser.stopAdvertising(object : AdvertiseCallback(){}) } catch(e: Exception){}
    }

    override fun onDestroy() {
        super.onDestroy()
        stopBleAdvertising()
    }

    private fun hasPermission(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
    private fun hasRequiredBluetoothPermissions() = getRequiredBluetoothPermissions().all { hasPermission(it) }
    private fun getRequiredBluetoothPermissions() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT)
    } else arrayOf(Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_ADMIN, Manifest.permission.ACCESS_FINE_LOCATION)
}
