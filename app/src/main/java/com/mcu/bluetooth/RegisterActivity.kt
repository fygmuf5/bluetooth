package com.mcu.bluetooth

import android.annotation.SuppressLint
import android.os.Bundle
import android.provider.Settings
import android.text.method.HideReturnsTransformationMethod
import android.text.method.PasswordTransformationMethod
import android.util.Log
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.textfield.TextInputLayout

/**
 * 使用者註冊 Activity (RegisterActivity)
 * 負責處理：
 * 1. 填寫帳號 (學號/教師帳號)、密碼與二次確認密碼
 * 2. 請求與發送 Email 註冊驗證碼
 * 3. 欄位驗證與密碼一致性檢查
 * 4. 呼叫 NetworkManager 完成註冊流程
 */
class RegisterActivity : AppCompatActivity() {

    // UI 控制元件宣告
    private lateinit var etEmailInput: EditText
    private lateinit var etPassword: EditText
    private lateinit var etPasswordConfirm: EditText
    private lateinit var etVerifyCode: EditText
    private lateinit var btnGetVerifyCode: Button
    private lateinit var btnRegisterSubmit: Button
    private lateinit var btnBack: ImageButton
    
    private lateinit var tilPassword: TextInputLayout
    private lateinit var tilPasswordConfirm: TextInputLayout

    // 密碼與確認密碼之顯示/隱藏狀態切換
    private var isPasswordVisible = false
    private var isConfirmPasswordVisible = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_register)

        // 初始化 UI 元件與設定按鈕監聽器
        initializeUI()
        setupListeners()
    }

    /**
     * 初始化 UI 視圖元件綁定
     */
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

    /**
     * 設定按鈕與互動事件監聽器
     */
    private fun setupListeners() {
        // 返回按鈕：關閉註冊頁面返回登入頁
        btnBack.setOnClickListener { finish() }

        // 密碼小眼睛切換：切換密碼欄位明文/密文顯示
        tilPassword.setEndIconOnClickListener {
            isPasswordVisible = !isPasswordVisible
            togglePasswordVisibility(etPassword, tilPassword, isPasswordVisible)
        }

        // 確認密碼小眼睛切換：切換確認密碼欄位明文/密文顯示
        tilPasswordConfirm.setEndIconOnClickListener {
            isConfirmPasswordVisible = !isConfirmPasswordVisible
            togglePasswordVisibility(etPasswordConfirm, tilPasswordConfirm, isConfirmPasswordVisible)
        }

        // 1. 獲取驗證碼按鈕點擊事件處理
        btnGetVerifyCode.setOnClickListener {
            val input = etEmailInput.text.toString().trim()
            if (input.isEmpty()) {
                Toast.makeText(this, "請輸入學號或教師帳號", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            
            val fullEmail = formatEmail(input)
            Log.d("RegisterDebug", "準備發送驗證碼至郵箱: $fullEmail")

            // 防呆機制：發送期間禁用按鈕，防止重複連點造成網路連線阻塞
            btnGetVerifyCode.isEnabled = false
            btnGetVerifyCode.text = "傳送中..."

            // 呼叫 NetworkManager API 請求發送 Email 驗證碼
            NetworkManager.requestVerifyCode(fullEmail) { success, message ->
                runOnUiThread {
                    // 恢復按鈕狀態
                    btnGetVerifyCode.isEnabled = true
                    btnGetVerifyCode.text = "獲取驗證碼"

                    if (success) {
                        Toast.makeText(this, if (message.isNullOrEmpty()) "驗證碼已成功寄出！" else message, Toast.LENGTH_SHORT).show()
                    } else {
                        Log.e("RegisterDebug", "驗證碼發送失敗，原因: $message")
                        Toast.makeText(this, "連線失敗：${message ?: "後端網址無回應，請確認網路連線或 ngrok 是否開機"}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }

        // 2. 確認註冊提交按鈕點擊事件處理
        btnRegisterSubmit.setOnClickListener {
            val input = etEmailInput.text.toString().trim()
            val password = etPassword.text.toString()
            val passwordConfirm = etPasswordConfirm.text.toString()
            val verifyCode = etVerifyCode.text.toString().trim()

            // 欄位防呆檢查
            if (input.isEmpty() || password.isEmpty() || verifyCode.isEmpty()) {
                Toast.makeText(this, "請填寫所有欄位", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // 二次密碼一致性檢查
            if (password != passwordConfirm) {
                Toast.makeText(this, "兩次輸入的密碼不一致", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val fullEmail = formatEmail(input)
            val deviceId = getUniqueDeviceId()

            btnRegisterSubmit.isEnabled = false
            btnRegisterSubmit.text = "註冊中..."

            // 呼叫 NetworkManager API 發送註冊請求
            NetworkManager.registerUser(fullEmail, password, verifyCode, deviceId) { success, message ->
                runOnUiThread {
                    btnRegisterSubmit.isEnabled = true
                    btnRegisterSubmit.text = "確認註冊"

                    if (success) {
                        Toast.makeText(this, "註冊成功！", Toast.LENGTH_SHORT).show()
                        finish() // 註冊成功關閉本頁面返回登入頁
                    } else {
                        Toast.makeText(this, message ?: "註冊失敗，請重新確認驗證碼", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    /**
     * 取得裝置唯一識別碼 (Android ID)，供註冊時綁定裝置
     */
    @SuppressLint("HardwareIds")
    private fun getUniqueDeviceId(): String {
        return Settings.Secure.getString(this.contentResolver, Settings.Secure.ANDROID_ID) ?: "Unknown"
    }

    /**
     * 自動格式化與補全 Email 信箱：
     * 8 位純數字 -> 學生信箱 @me.mcu.edu.tw
     * 英文字元或非 8 位數 -> 教師信箱 @mail.mcu.edu.tw
     */
    private fun formatEmail(input: String): String {
        if (input.contains("@")) return input
        if (input.length == 8 && input.all { it.isDigit() }) {
            return "$input@me.mcu.edu.tw"
        }
        return "$input@mail.mcu.edu.tw"
    }

    /**
     * 切換密碼輸入框顯示型態 (明文 vs 隱藏點點)
     */
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
