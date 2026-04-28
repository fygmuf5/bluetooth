package com.mcu.bluetooth

import android.annotation.SuppressLint
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

class RoleSelectionActivity : AppCompatActivity() {

    private lateinit var etUsername: EditText
    private lateinit var etPassword: EditText
    private lateinit var tilPassword: TextInputLayout
    private lateinit var loginButton: Button
    private lateinit var tvForgotPassword: TextView
    private lateinit var tvRegister: TextView

    private var isPasswordVisible = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_role_selection)

        etUsername = findViewById(R.id.et_username)
        etPassword = findViewById(R.id.et_password)
        tilPassword = findViewById(R.id.til_password)
        loginButton = findViewById(R.id.login_button)
        tvForgotPassword = findViewById(R.id.tv_forgot_password)
        tvRegister = findViewById(R.id.tv_register)

        tilPassword.setEndIconDrawable(R.drawable.ic_eye_hidden)

        tilPassword.setEndIconOnClickListener {
            isPasswordVisible = !isPasswordVisible
            if (isPasswordVisible) {
                etPassword.transformationMethod = HideReturnsTransformationMethod.getInstance()
                tilPassword.setEndIconDrawable(R.drawable.ic_eye_visible)
            } else {
                etPassword.transformationMethod = PasswordTransformationMethod.getInstance()
                tilPassword.setEndIconDrawable(R.drawable.ic_eye_hidden)
            }
            etPassword.setSelection(etPassword.text.length)
        }

        loginButton.setOnClickListener {
            val input = etUsername.text.toString().trim()
            val password = etPassword.text.toString()
            
            if (input.isEmpty() || password.isEmpty()) {
                Toast.makeText(this, "請輸入帳號與密碼", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val fullEmail = formatEmail(input)
            val deviceId = getUniqueDeviceId()
            
            // 修正：傳入 deviceId 參數以符合 NetworkManager.login 的定義
            NetworkManager.login(fullEmail, password, deviceId) { success ->
                runOnUiThread {
                    if (success) {
                        val localPart = fullEmail.substringBefore("@")
                        val role = if (localPart.isNotEmpty() && localPart.all { it.isDigit() }) {
                            "STUDENT"
                        } else {
                            "TEACHER"
                        }
                        
                        Toast.makeText(this, "登入成功！", Toast.LENGTH_SHORT).show()
                        startMainActivity(role, fullEmail)
                    } else {
                        Toast.makeText(this, "登入失敗：帳號、密碼錯誤或設備未綁定", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }

        tvRegister.setOnClickListener {
            val intent = Intent(this, RegisterActivity::class.java)
            startActivity(intent)
        }

        tvForgotPassword.setOnClickListener {
            Toast.makeText(this, "請聯繫管理員", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.teacher_button).setOnClickListener { 
            startMainActivity("TEACHER", "test_teacher@gmail.com") 
        }
        findViewById<Button>(R.id.student_button).setOnClickListener { 
            startMainActivity("STUDENT", "11012345@me.mcu.edu.tw") 
        }
    }

    /**
     * 獲取設備唯一識別碼 (Android ID) 以驗證設備綁定
     */
    @SuppressLint("HardwareIds")
    private fun getUniqueDeviceId(): String {
        return Settings.Secure.getString(this.contentResolver, Settings.Secure.ANDROID_ID) ?: "Unknown"
    }

    private fun formatEmail(input: String): String {
        if (input.contains("@")) return input
        return if (input.length == 8 && input.all { it.isDigit() }) {
            "$input@me.mcu.edu.tw"
        } else {
            "$input@gmail.com"
        }
    }

    private fun startMainActivity(role: String, email: String) {
        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra("EXTRA_ROLE", role)
            putExtra("EXTRA_EMAIL", email)
        }
        startActivity(intent)
        finish()
    }
}
