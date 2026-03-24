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
import android.text.Editable
import android.text.TextWatcher
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
import java.security.MessageDigest
import java.util.*

@SuppressLint("MissingPermission")
class MainActivity : AppCompatActivity() {

    // --- 安全與通訊設定 ---
    private val SERVICE_UUID: UUID = UUID.fromString("00001111-0000-1000-8000-00805F9B34FB")
    private val SECRET_KEY = "MCU_SECURE_KEY_2024"
    private val TIME_WINDOW_MS = 30000L
    private val HASH_SIZE = 6

    private val bluetoothManager by lazy { getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager }
    private val bluetoothAdapter: BluetoothAdapter by lazy { bluetoothManager.adapter }
    private val bleAdvertiser: BluetoothLeAdvertiser by lazy { bluetoothAdapter.bluetoothLeAdvertiser }

    private lateinit var statusTextView: TextView
    private lateinit var messageEditText: EditText
    private lateinit var broadcastButton: Button
    private lateinit var backToRoleButton: Button
    private lateinit var studentCard: View
    private lateinit var teacherPagerContainer: View
    private lateinit var viewPager: ViewPager2
    private lateinit var dot1: ImageView
    private lateinit var dot2: ImageView

    private var currentRole: String? = null
    private val sharedPrefs by lazy { getSharedPreferences("MCU_BT_PREFS", Context.MODE_PRIVATE) }

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

        initializeUI()
        setupRoleUI()
        setupListeners()
        loadSavedStudentInfo()
    }

    private fun initializeUI() {
        statusTextView = findViewById(R.id.status_textview)
        messageEditText = findViewById(R.id.message_edittext)
        broadcastButton = findViewById(R.id.broadcast_button)
        backToRoleButton = findViewById(R.id.back_to_role_selection)
        studentCard = findViewById(R.id.student_card)
        teacherPagerContainer = findViewById(R.id.teacher_pager_container)
        viewPager = findViewById(R.id.teacher_view_pager)
        dot1 = findViewById(R.id.dot1)
        dot2 = findViewById(R.id.dot2)
    }

    private fun loadSavedStudentInfo() {
        if (currentRole == "STUDENT") {
            val savedInfo = sharedPrefs.getString("STUDENT_INFO", "")
            messageEditText.setText(savedInfo)
        }
    }

    private fun setupRoleUI() {
        when (currentRole) {
            "TEACHER" -> {
                teacherPagerContainer.visibility = View.VISIBLE
                studentCard.visibility = View.GONE
                statusTextView.text = "身份: 老師 (左右滑動切換熱點圖)"
                setupTeacherViewPager()
            }
            "STUDENT" -> {
                teacherPagerContainer.visibility = View.GONE
                studentCard.visibility = View.VISIBLE
                statusTextView.text = "身份: 學生 (發送模式)"
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
        messageEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                sharedPrefs.edit().putString("STUDENT_INFO", s.toString()).apply()
            }
        })

        broadcastButton.setOnClickListener { 
            val content = messageEditText.text.toString().trim()
            if (content.isEmpty()) {
                Toast.makeText(this, "請先輸入學號姓名", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (checkAndRequestPermissions()) broadcastSecureMessage(content) 
        }

        backToRoleButton.setOnClickListener {
            stopBleAdvertising()
            startActivity(Intent(this, RoleSelectionActivity::class.java))
            finish()
        }
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

    private fun xorTransform(data: ByteArray, timeOffset: Long): ByteArray {
        val timeBucket = (System.currentTimeMillis() + timeOffset) / TIME_WINDOW_MS
        val key = MessageDigest.getInstance("SHA-1").digest((SECRET_KEY + timeBucket).toByteArray())
        return ByteArray(data.size) { i -> (data[i].toInt() xor key[i % key.size].toInt()).toByte() }
    }

    private fun generateRollingHash(message: String, timeOffset: Long): ByteArray {
        val timeBucket = (System.currentTimeMillis() + timeOffset) / TIME_WINDOW_MS
        val input = message + SECRET_KEY + timeBucket
        return MessageDigest.getInstance("SHA-1").digest(input.toByteArray()).take(HASH_SIZE).toByteArray()
    }

    private fun broadcastSecureMessage(message: String) {
        if (!hasPermission(Manifest.permission.BLUETOOTH_ADVERTISE)) return
        val hashPart = generateRollingHash(message, 0L)
        val encryptedPart = xorTransform(message.toByteArray(Charset.forName("UTF-8")), 0L)
        val payload = hashPart + encryptedPart

        if (payload.size > 24) {
            Toast.makeText(this, "訊息過長", Toast.LENGTH_SHORT).show()
            return
        }

        stopBleAdvertising()
        val settings = AdvertiseSettings.Builder().setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY).setConnectable(false).build()
        val data = AdvertiseData.Builder().addServiceUuid(ParcelUuid(SERVICE_UUID)).addServiceData(ParcelUuid(SERVICE_UUID), payload).build()

        bleAdvertiser.startAdvertising(settings, data, object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                runOnUiThread { statusTextView.text = "Status: 正在發送簽到訊息..." }
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
