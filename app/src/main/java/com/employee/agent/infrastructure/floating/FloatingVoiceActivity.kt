// infrastructure/floating/FloatingVoiceActivity.kt
// module: infrastructure/floating | layer: infrastructure | role: voice-input-activity
// summary: 语音输入透明Activity - 从悬浮球单击触发

package com.employee.agent.infrastructure.floating

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.employee.agent.infrastructure.voice.VoiceRecognitionHelper

/**
 * 🎤 语音输入透明Activity
 * 
 * 从悬浮球单击触发，显示语音输入界面
 */
class FloatingVoiceActivity : AppCompatActivity() {
    
    private lateinit var voiceHelper: VoiceRecognitionHelper
    private lateinit var statusText: TextView
    private lateinit var resultText: TextView
    private lateinit var voiceIndicator: TextView
    
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startListening()
        } else {
            Toast.makeText(this, "需要麦克风权限", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 设置透明窗口
        window.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.6f)
        }
        
        // 初始化语音助手
        voiceHelper = VoiceRecognitionHelper(this)
        
        // 创建UI
        createUI()
        
        // 检查权限并开始
        checkPermissionAndStart()
    }
    
    private fun createUI() {
        val density = resources.displayMetrics.density
        
        val rootLayout = FrameLayout(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { finish() }  // 点击背景关闭
        }
        
        // 中央卡片
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(
                (32 * density).toInt(),
                (40 * density).toInt(),
                (32 * density).toInt(),
                (40 * density).toInt()
            )
            
            val bg = GradientDrawable().apply {
                setColor(Color.parseColor("#2D2D2D"))
                cornerRadius = 24 * density
            }
            background = bg
            elevation = 16 * density
            
            // 阻止点击穿透
            setOnClickListener { }
        }
        
        // 语音指示器
        voiceIndicator = TextView(this).apply {
            text = "🎤"
            textSize = 64f
            gravity = Gravity.CENTER
        }
        card.addView(voiceIndicator, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER
        })
        
        // 状态文字
        statusText = TextView(this).apply {
            text = "正在准备..."
            textSize = 18f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        card.addView(statusText, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER
            topMargin = (16 * density).toInt()
        })
        
        // 识别结果
        resultText = TextView(this).apply {
            text = ""
            textSize = 16f
            setTextColor(Color.parseColor("#AAAAAA"))
            gravity = Gravity.CENTER
            maxLines = 3
        }
        card.addView(resultText, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER
            topMargin = (12 * density).toInt()
        })
        
        // 提示
        val tipText = TextView(this).apply {
            text = "点击空白处取消"
            textSize = 12f
            setTextColor(Color.parseColor("#666666"))
            gravity = Gravity.CENTER
        }
        card.addView(tipText, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER
            topMargin = (24 * density).toInt()
        })
        
        // 添加卡片到根布局
        rootLayout.addView(card, FrameLayout.LayoutParams(
            (300 * density).toInt(),
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER
        })
        
        setContentView(rootLayout)
    }
    
    private fun checkPermissionAndStart() {
        when {
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED -> {
                startListening()
            }
            else -> {
                requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }
    
    private fun startListening() {
        voiceHelper.apply {
            onListeningStateChanged = { isListening ->
                runOnUiThread {
                    if (isListening) {
                        statusText.text = "请说出您的任务..."
                        voiceIndicator.text = "🔴"
                    }
                }
            }
            
            onPartialResult = { partial ->
                runOnUiThread {
                    resultText.text = partial
                }
            }
            
            onResult = { result ->
                runOnUiThread {
                    statusText.text = "识别成功"
                    resultText.text = result
                    
                    // 提交任务
                    if (result.isNotBlank()) {
                        submitTask(result)
                    } else {
                        Toast.makeText(this@FloatingVoiceActivity, "未识别到内容", Toast.LENGTH_SHORT).show()
                        finish()
                    }
                }
            }
            
            onError = { error ->
                runOnUiThread {
                    statusText.text = "识别失败"
                    resultText.text = error
                    voiceIndicator.text = "❌"
                    
                    // 延迟关闭
                    resultText.postDelayed({ finish() }, 1500)
                }
            }
        }
        
        voiceHelper.startListening()
    }
    
    private fun submitTask(goal: String) {
        // 检查无障碍服务是否运行
        if (!com.employee.agent.AgentService.isRunning()) {
            Toast.makeText(this, "❌ 请先开启无障碍服务", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        
        // 直接调用 AgentService 执行任务
        com.employee.agent.AgentService.executeTask(goal)
        
        Toast.makeText(this, "🚀 任务已提交: $goal", Toast.LENGTH_SHORT).show()
        
        // 延迟关闭
        resultText.postDelayed({ finish() }, 500)
    }
    
    override fun onDestroy() {
        super.onDestroy()
        voiceHelper.destroy()
    }
}
