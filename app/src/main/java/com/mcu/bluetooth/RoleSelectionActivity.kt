package com.mcu.bluetooth

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity

class RoleSelectionActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_role_selection)

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
        // We might want to finish() this activity if we don't want to go back with system back button
        // but the user wants to "回到選擇身分的介面", so finishing might not be desired unless we provide a button.
        // Actually finishing and starting new is cleaner.
        finish()
    }
}