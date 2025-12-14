// interface/AgentConfigActivity.kt
package com.employee.agent

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast

/**
 * Agent 配置界面
 */
class AgentConfigActivity : Activity() {
    
    private lateinit var apiKeyInput: EditText
    private lateinit var statusText: TextView
    private lateinit var saveButton: Button
    private lateinit var openSettingsButton: Button
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 简单的代码布局（实际项目应使用 XML 布局）
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }
        
        // 标题
        val title = TextView(this).apply {
            text = "🤖 AI Agent 配置"
            textSize = 24f
            setPadding(0, 0, 0, 32)
        }
        layout.addView(title)
        
        // 状态文本
        statusText = TextView(this).apply {
            text = "无障碍服务状态: 检查中..."
        }
        layout.addView(statusText)
        
        // API Key 输入
        val apiLabel = TextView(this).apply {
            text = "混元 API Key:"
            setPadding(0, 32, 0, 8)
        }
        layout.addView(apiLabel)
        
        apiKeyInput = EditText(this).apply {
            hint = "输入 API Key"
            setText(getApiKey())
        }
        layout.addView(apiKeyInput)
        
        // 保存按钮
        saveButton = Button(this).apply {
            text = "保存配置"
            setOnClickListener { saveApiKey() }
        }
        layout.addView(saveButton)
        
        // 打开无障碍设置按钮
        openSettingsButton = Button(this).apply {
            text = "打开无障碍设置"
            setOnClickListener { openAccessibilitySettings() }
            setPadding(0, 16, 0, 0)
        }
        layout.addView(openSettingsButton)
        
        setContentView(layout)
        
        // 检查无障碍服务状态
        updateServiceStatus()
    }
    
    override fun onResume() {
        super.onResume()
        updateServiceStatus()
    }
    
    private fun saveApiKey() {
        val apiKey = apiKeyInput.text.toString().trim()
        if (apiKey.isEmpty()) {
            Toast.makeText(this, "API Key 不能为空", Toast.LENGTH_SHORT).show()
            return
        }
        
        getSharedPreferences("agent_config", Context.MODE_PRIVATE)
            .edit()
            .putString("api_key", apiKey)
            .apply()
        
        Toast.makeText(this, "✅ 保存成功", Toast.LENGTH_SHORT).show()
    }
    
    private fun getApiKey(): String {
        return getSharedPreferences("agent_config", Context.MODE_PRIVATE)
            .getString("api_key", "") ?: ""
    }
    
    private fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        startActivity(intent)
    }
    
    private fun updateServiceStatus() {
        val isEnabled = isAccessibilityServiceEnabled()
        statusText.text = if (isEnabled) {
            "✅ 无障碍服务：已启用"
        } else {
            "❌ 无障碍服务：未启用（点击下方按钮开启）"
        }
    }
    
    private fun isAccessibilityServiceEnabled(): Boolean {
        val serviceName = "${packageName}/.AgentService"
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        
        return enabledServices.contains(serviceName)
    }
    
    companion object {
        fun getApiKey(context: Context): String {
            return context.getSharedPreferences("agent_config", Context.MODE_PRIVATE)
                .getString("api_key", "") ?: ""
        }
    }
}
