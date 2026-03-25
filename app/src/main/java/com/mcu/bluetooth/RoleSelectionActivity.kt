package com.mcu.bluetooth

import android.content.Intent
import android.os.Bundle
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

        // 初始化 UI
        etUsername = findViewById(R.id.et_username)
        etPassword = findViewById(R.id.et_password)
        tilPassword = findViewById(R.id.til_password)
        loginButton = findViewById(R.id.login_button)
        tvForgotPassword = findViewById(R.id.tv_forgot_password)
        tvRegister = findViewById(R.id.tv_register)

        // 初始化密碼圖示：預設隱藏
        tilPassword.setEndIconDrawable(R.drawable.ic_eye_hidden)

        // 密碼顯示切換邏輯
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

        // 登入按鈕
        loginButton.setOnClickListener {
            val username = etUsername.text.toString().trim()
            val password = etPassword.text.toString()
            
            if (username.isEmpty() || password.isEmpty()) {
                Toast.makeText(this, "請輸入帳號與密碼", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // 角色判斷邏輯：
            // 取出 @ 前面的字串，如果全為數字則為學生，否則為老師
            val localPart = username.substringBefore("@")
            val role = if (localPart.isNotEmpty() && localPart.all { it.isDigit() }) {
                "STUDENT"
            } else {
                "TEACHER"
            }

            Toast.makeText(this, "登入身分: ${if(role == "STUDENT") "學生" else "老師"}", Toast.LENGTH_SHORT).show()
            startMainActivity(role)
        }

        // 跳轉到註冊頁面
        tvRegister.setOnClickListener {
            val intent = Intent(this, RegisterActivity::class.java)
            startActivity(intent)
        }

        tvForgotPassword.setOnClickListener {
            Toast.makeText(this, "請聯繫管理員", Toast.LENGTH_SHORT).show()
        }

        // 快速測試按鈕 (保留供測試使用)
        findViewById<Button>(R.id.teacher_button).setOnClickListener { startMainActivity("TEACHER") }
        findViewById<Button>(R.id.student_button).setOnClickListener { startMainActivity("STUDENT") }
    }

    private fun startMainActivity(role: String) {
        val intent = Intent(this, MainActivity::class.java).apply { putExtra("EXTRA_ROLE", role) }
        startActivity(intent)
        finish()
    }
}
