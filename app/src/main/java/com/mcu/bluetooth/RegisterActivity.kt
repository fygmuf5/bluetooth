package com.mcu.bluetooth

import android.os.Bundle
import android.text.method.HideReturnsTransformationMethod
import android.text.method.PasswordTransformationMethod
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.textfield.TextInputLayout

class RegisterActivity : AppCompatActivity() {

    private lateinit var etStudentId: EditText
    private lateinit var etEmail: EditText
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
        etStudentId = findViewById(R.id.et_reg_student_id)
        etEmail = findViewById(R.id.et_reg_email)
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
        // 返回按鈕
        btnBack.setOnClickListener { finish() }

        // 密碼顯示切換
        tilPassword.setEndIconOnClickListener {
            isPasswordVisible = !isPasswordVisible
            togglePasswordVisibility(etPassword, tilPassword, isPasswordVisible)
        }

        tilPasswordConfirm.setEndIconOnClickListener {
            isConfirmPasswordVisible = !isConfirmPasswordVisible
            togglePasswordVisibility(etPasswordConfirm, tilPasswordConfirm, isConfirmPasswordVisible)
        }

        // 獲取驗證碼
        btnGetVerifyCode.setOnClickListener {
            val email = etEmail.text.toString().trim()
            if (email.isEmpty()) {
                Toast.makeText(this, "請先輸入電子郵件", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // 檢查信箱網域
            if (!email.endsWith("@ms1.mcu.edu.tw")) {
                Toast.makeText(this, "格式錯誤：必須使用 @ms1.mcu.edu.tw 信箱", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            
            Toast.makeText(this, "驗證碼發送中...", Toast.LENGTH_SHORT).show()
            
            // 串接 NetworkManager (假設未來有這個方法)
            // NetworkManager.requestVerifyCode(email) { success -> ... }
        }

        // 提交註冊
        btnRegisterSubmit.setOnClickListener {
            val studentId = etStudentId.text.toString().trim()
            val email = etEmail.text.toString().trim()
            val password = etPassword.text.toString()
            val passwordConfirm = etPasswordConfirm.text.toString()
            val verifyCode = etVerifyCode.text.toString().trim()

            // 1. 基本欄位檢查
            if (studentId.isEmpty() || email.isEmpty() || password.isEmpty() || verifyCode.isEmpty()) {
                Toast.makeText(this, "請填寫所有欄位", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // 2. 信箱網域檢查
            if (!email.endsWith("@ms1.mcu.edu.tw")) {
                Toast.makeText(this, "註冊失敗：必須使用 @ms1.mcu.edu.tw 信箱", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            // 3. 密碼格式檢查 (至少6位，僅限英數字)
            if (!isValidPassword(password)) {
                Toast.makeText(this, "密碼格式錯誤：請輸入至少 6 位英數字，且不能包含符號或中文", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            // 4. 兩次密碼一致性檢查
            if (password != passwordConfirm) {
                Toast.makeText(this, "兩次輸入的密碼不一致", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // 這裡未來執行 NetworkManager.register(...)
            Toast.makeText(this, "註冊請求已送出", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 驗證密碼是否符合：長度 >= 6 且 僅包含大小寫英文字母或數字 (不含符號與中文)
     */
    private fun isValidPassword(password: String): Boolean {
        // 檢查長度
        if (password.length < 6) return false
        
        // 使用正則表達式檢查是否「全為大小寫英文字母或數字」
        // ^[a-zA-Z0-9]*$ 代表從頭到尾只能是 a-z, A-Z 或 0-9
        return password.matches(Regex("^[a-zA-Z0-9]*$"))
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
