package com.mcu.bluetooth

import android.annotation.SuppressLint
import android.os.Bundle
import android.provider.Settings
import android.text.method.HideReturnsTransformationMethod
import android.text.method.PasswordTransformationMethod
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.textfield.TextInputLayout

class RegisterActivity : AppCompatActivity() {

    private lateinit var etEmailInput: EditText
    private lateinit var etPassword: EditText
    private lateinit var etPasswordConfirm: EditText
    private lateinit var etVerifyCode: EditText
    private lateinit var btnGetVerifyCode: Button
    private lateinit var btnRegisterSubmit: Button
    private lateinit var btnBack: ImageButton
    
    private lateinit var tilPassword: TextInputLayout
    private lateinit var tilPasswordConfirm: TextInputLayout

    private var isPasswordVisible = false
    private var isConfirmPasswordVisible = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_register)

        initializeUI()
        setupListeners()
    }

    private fun initializeUI() {
        etEmailInput = findViewById(R.id.et_reg_email)
        etPassword = findViewById(R.id.et_reg_password)
        etPasswordConfirm = findViewById(R.id.et_reg_password_confirm)
        etVerifyCode = findViewById(R.id.et_reg_verify_code)
        btnGetVerifyCode = findViewById(R.id.btn_get_verify_code)
        btnRegisterSubmit = findViewById(R.id.btn_register_submit)
        btnBack = findViewById(R.id.btn_back)
        
        tilPassword = findViewById(R.id.til_reg_password)
        tilPasswordConfirm = findViewById(R.id.til_reg_password_confirm)
    }

    private fun setupListeners() {
        btnBack.setOnClickListener { finish() }

        tilPassword.setEndIconOnClickListener {
            isPasswordVisible = !isPasswordVisible
            togglePasswordVisibility(etPassword, tilPassword, isPasswordVisible)
        }

        tilPasswordConfirm.setEndIconOnClickListener {
            isConfirmPasswordVisible = !isConfirmPasswordVisible
            togglePasswordVisibility(etPasswordConfirm, tilPasswordConfirm, isConfirmPasswordVisible)
        }

        btnGetVerifyCode.setOnClickListener {
            val input = etEmailInput.text.toString().trim()
            if (input.isEmpty()) {
                Toast.makeText(this, "請輸入學號或帳號", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val fullEmail = formatEmail(input)
            NetworkManager.requestVerifyCode(fullEmail) { success ->
                runOnUiThread {
                    if (success) Toast.makeText(this, "驗證碼已寄出", Toast.LENGTH_SHORT).show()
                    else Toast.makeText(this, "發送失敗", Toast.LENGTH_SHORT).show()
                }
            }
        }

        btnRegisterSubmit.setOnClickListener {
            val input = etEmailInput.text.toString().trim()
            val password = etPassword.text.toString()
            val passwordConfirm = etPasswordConfirm.text.toString()
            val verifyCode = etVerifyCode.text.toString().trim()

            if (input.isEmpty() || password.isEmpty() || verifyCode.isEmpty()) {
                Toast.makeText(this, "請填寫所有欄位", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (password != passwordConfirm) {
                Toast.makeText(this, "密碼不一致", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val fullEmail = formatEmail(input)
            val deviceId = getUniqueDeviceId() // 使用修正後的函數名

            NetworkManager.registerUser(fullEmail, password, verifyCode, deviceId) { success ->
                runOnUiThread {
                    if (success) {
                        Toast.makeText(this, "註冊成功！", Toast.LENGTH_SHORT).show()
                        finish()
                    } else {
                        Toast.makeText(this, "註冊失敗", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    @SuppressLint("HardwareIds")
    private fun getUniqueDeviceId(): String {
        return Settings.Secure.getString(this.contentResolver, Settings.Secure.ANDROID_ID) ?: "Unknown"
    }

    private fun formatEmail(input: String): String {
        if (input.contains("@")) return input
        return if (input.length == 8 && input.all { it.isDigit() }) "$input@me.mcu.edu.tw" else "$input@gmail.com"
    }

    private fun togglePasswordVisibility(editText: EditText, textInputLayout: TextInputLayout, isVisible: Boolean) {
        if (isVisible) {
            editText.transformationMethod = HideReturnsTransformationMethod.getInstance()
            textInputLayout.setEndIconDrawable(R.drawable.ic_eye_visible)
        } else {
            editText.transformationMethod = PasswordTransformationMethod.getInstance()
            textInputLayout.setEndIconDrawable(R.drawable.ic_eye_hidden)
        }
        editText.setSelection(editText.text.length)
    }
}
