package com.androidtabletcast

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton

class TrustPromptActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_trust_prompt)
        val pcId = intent.getStringExtra(EXTRA_PC_ID).orEmpty()
        val name = intent.getStringExtra(EXTRA_NAME).orEmpty().ifBlank { "Windows" }
        findViewById<TextView>(R.id.pcName).text = name
        val message = findViewById<TextView>(R.id.promptMessage)
        findViewById<MaterialButton>(R.id.denyButton).setOnClickListener { finish() }
        findViewById<MaterialButton>(R.id.allowButton).setOnClickListener {
            val granted = CastService.instance?.approveTrust(pcId, name) == true
            if (granted) {
                finish()
            } else {
                message.text = "接続が切れたため信頼を登録できませんでした。PC からもう一度要求してください。"
            }
        }
    }

    companion object {
        const val EXTRA_PC_ID = "pcId"
        const val EXTRA_NAME = "name"
    }
}
