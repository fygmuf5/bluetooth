package com.mcu.bluetooth

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.method.HideReturnsTransformationMethod
import android.text.method.PasswordTransformationMethod
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.textfield.TextInputLayout

/**
 * 登入與角色選擇 Activity (RoleSelectionActivity)
 * 負責處理：
 * 1. 檢查是否已存在免登入紀錄 (SharedPreferences 自動登入)
 * 2. 登入介面 UI (帳號密碼輸入、密碼可視性切換)
 * 3. 呼叫 NetworkManager 進行網路登入驗證與角色判斷 (學生 STUDENT / 老師 TEACHER)
 * 4. 快速導向註冊頁面 (RegisterActivity)
 * 5. 測試專用快速登入按鈕 (開發與演示使用)
 */
class RoleSelectionActivity : AppCompatActivity() {

    // UI 元件宣告
    private lateinit var etUsername: EditText
    private lateinit var etPassword: EditText
    private lateinit var tilPassword: TextInputLayout
    private lateinit var loginButton: Button
    private lateinit var tvForgotPassword: TextView
    private lateinit var tvRegister: TextView

    // 密碼顯示/隱藏狀態切換變數
    private var isPasswordVisible = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 1. 自動登入檢查：讀取本機儲存的登入狀態
        val sharedPref = getSharedPreferences("AttendanceApp", Context.MODE_PRIVATE)
        val savedEmail = sharedPref.getString("saved_email", null)
        val savedRole = sharedPref.getString("saved_role", null)
        
        // 若已有登入紀錄則直接跳轉至主頁面 (MainActivity)
        if (savedEmail != null && savedRole != null) {
            startMainActivity(savedRole, savedEmail)
            return
        }

        setContentView(R.layout.activity_role_selection)

        // 2. 初始化 UI 元件與視圖綁定
        etUsername = findViewById(R.id.et_username)
        etPassword = findViewById(R.id.et_password)
        tilPassword = findViewById(R.id.til_password)
        loginButton = findViewById(R.id.login_button)
        tvForgotPassword = findViewById(R.id.tv_forgot_password)
        tvRegister = findViewById(R.id.tv_register)

        // 設定密碼輸入框預設小眼睛圖示 (隱藏狀態)
        tilPassword.setEndIconDrawable(R.drawable.ic_eye_hidden)

        // 3. 密碼顯示/隱藏切換事件處理
        tilPassword.setEndIconOnClickListener {
            isPasswordVisible = !isPasswordVisible
            if (isPasswordVisible) {
                // 顯示明文密碼
                etPassword.transformationMethod = HideReturnsTransformationMethod.getInstance()
                tilPassword.setEndIconDrawable(R.drawable.ic_eye_visible)
            } else {
                // 隱藏密碼為點點
                etPassword.transformationMethod = PasswordTransformationMethod.getInstance()
                tilPassword.setEndIconDrawable(R.drawable.ic_eye_hidden)
            }
            // 保持游標在文字最後面
            etPassword.setSelection(etPassword.text.length)
        }

        // 4. 登入按鈕點擊事件處理
        loginButton.setOnClickListener {
            val input = etUsername.text.toString().trim()
            val password = etPassword.text.toString()
            
            // 欄位防呆檢查
            if (input.isEmpty() || password.isEmpty()) {
                Toast.makeText(this, "請輸入帳號與密碼", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // 格式化 Email 與取得裝置唯一 ID
            val fullEmail = formatEmail(input)
            val deviceId = getUniqueDeviceId()
            
            // 呼叫 NetworkManager API 發送登入請求
            NetworkManager.login(fullEmail, password, deviceId) { success, message ->
                runOnUiThread {
                    if (success) {
                        // 依據帳號字首判斷角色：若全為數字代表學號 (STUDENT)，否則為教師 (TEACHER)
                        val localPart = fullEmail.substringBefore("@")
                        val role = if (localPart.isNotEmpty() && localPart.all { it.isDigit() }) {
                            "STUDENT"
                        } else {
                            "TEACHER"
                        }
                        
                        // 儲存登入憑證至 SharedPreferences 以利後續 API 驗證與免登入
                        sharedPref.edit().putString("saved_email", fullEmail)
                                        .putString("saved_role", role)
                                        .putString("saved_password", password)
                                        .apply()
                        
                        Toast.makeText(this, "登入成功！", Toast.LENGTH_SHORT).show()
                        startMainActivity(role, fullEmail)
                    } else {
                        Toast.makeText(this, message ?: "登入失敗", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }

        // 5. 點擊「立即註冊」導向註冊頁面
        tvRegister.setOnClickListener {
            startActivity(Intent(this, RegisterActivity::class.java))
        }

        // 6. 忘記密碼提示
        tvForgotPassword.setOnClickListener {
            Toast.makeText(this, "請聯繫管理員", Toast.LENGTH_SHORT).show()
        }

        // 7. 測試用快速登入按鈕事件設定 (開發/示範快速入口)
        findViewById<Button>(R.id.teacher_button).setOnClickListener { 
            sharedPref.edit().putString("saved_email", "test_teacher@mail.mcu.edu.tw")
                            .putString("saved_role", "TEACHER")
                            .putString("saved_password", "teacher_pass").apply()
            startMainActivity("TEACHER", "test_teacher@mail.mcu.edu.tw") 
        }
        findViewById<Button>(R.id.student_button).setOnClickListener { 
            sharedPref.edit().putString("saved_email", "12360615@me.mcu.edu.tw")
                            .putString("saved_role", "STUDENT")
                            .putString("saved_password", "student_pass").apply()
            startMainActivity("STUDENT", "12360615@me.mcu.edu.tw") 
        }
    }

    /**
     * 取得裝置唯一識別碼 (Android ID)，用於後端綁定裝置安全紀錄
     */
    @SuppressLint("HardwareIds")
    private fun getUniqueDeviceId(): String {
        return Settings.Secure.getString(this.contentResolver, Settings.Secure.ANDROID_ID) ?: "Unknown"
    }

    /**
     * 自動補全 Email 後綴：
     * 若輸入 8 位數純數字則補全學生信箱 @me.mcu.edu.tw
     * 否則預設補全教職員信箱 @mail.mcu.edu.tw
     */
    private fun formatEmail(input: String): String {
        if (input.contains("@")) return input
        return if (input.length == 8 && input.all { it.isDigit() }) {
            "$input@me.mcu.edu.tw"
        } else {
            "$input@mail.mcu.edu.tw"
        }
    }

    /**
     * 導向主頁面 MainActivity，帶入使用者角色與 Email 參數
     */
    private fun startMainActivity(role: String, email: String) {
        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra("EXTRA_ROLE", role)
            putExtra("EXTRA_EMAIL", email)
        }
        startActivity(intent)
        finish()
    }
}
