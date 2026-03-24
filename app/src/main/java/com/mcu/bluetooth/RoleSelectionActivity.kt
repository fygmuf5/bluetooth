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

        // 1. 初始化圖示：預設隱藏，顯示我們自訂的「斜線眼睛」
        tilPassword.setEndIconDrawable(R.drawable.ic_eye_hidden)

        // 2. 手動實作密碼顯示/隱藏切換 (圖示代表目前狀態)
        tilPassword.setEndIconOnClickListener {
            isPasswordVisible = !isPasswordVisible
            
            if (isPasswordVisible) {
                // 切換為可見狀態
                etPassword.transformationMethod = HideReturnsTransformationMethod.getInstance()
                // 顯示「正常眼睛」= 目前是看到的狀態
                tilPassword.setEndIconDrawable(R.drawable.ic_eye_visible)
            } else {
                // 切換為隱藏狀態
                etPassword.transformationMethod = PasswordTransformationMethod.getInstance()
                // 顯示「斜線眼睛」= 目前是隱藏狀態
                tilPassword.setEndIconDrawable(R.drawable.ic_eye_hidden)
            }
            // 保持游標在最後面
            etPassword.setSelection(etPassword.text.length)
        }

        // 登入按鈕邏輯
        loginButton.setOnClickListener {
            val username = etUsername.text.toString()
            val password = etPassword.text.toString()

            if (username.isEmpty() || password.isEmpty()) {
                Toast.makeText(this, "請輸入帳號與密碼", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // 暫時測試邏輯
            if (username.contains("teacher", ignoreCase = true)) {
                startMainActivity("TEACHER")
            } else {
                startMainActivity("STUDENT")
            }
        }

        findViewById<Button>(R.id.teacher_button).setOnClickListener {
            startMainActivity("TEACHER")
        }

        findViewById<Button>(R.id.student_button).setOnClickListener {
            startMainActivity("STUDENT")
        }
    }

    private fun startMainActivity(role: String) {
        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra("EXTRA_ROLE", role)
        }
        startActivity(intent)
        finish()
    }
}
