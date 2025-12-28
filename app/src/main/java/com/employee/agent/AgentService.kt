package com.employee.agent

import android.accessibilityservice.AccessibilityService
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import androidx.core.app.NotificationCompat
import com.employee.agent.infrastructure.accessibility.*
import kotlinx.coroutines.*

/**
 * AI Agent 无障碍服务
 * 
 * 负责：
 * - 提供手机控制能力（点击、滑动、按键等）
 * - 提供屏幕读取能力（UI树解析）
 * - 接收 PC 端的命令
 */
class AgentService : AccessibilityService() {
    private var socketServer: SocketServer? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    // 核心组件
    private lateinit var gestureExecutor: AccessibilityGestureExecutor
    private lateinit var uiParser: UITreeParser
    private lateinit var screenReader: AccessibilityScreenReader
    
    companion object {
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "agent_service_channel"
        private const val TAG = "AgentService"
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "🚀 无障碍服务已连接")
        
        // 启动前台服务（防止被杀）
        startForegroundService()
        
        // 初始化核心组件
        initializeCoreComponents()
        
        // 启动 Socket 服务器（PC 通信）
        socketServer = SocketServer(this)
        socketServer?.loadSavedApiKey()  // 🆕 自动加载保存的 API Key
        socketServer?.start(11451)
        
        Log.i(TAG, "✅ Agent 服务已启动，等待 PC 端连接")
    }
    
    /**
     * 初始化核心组件
     */
    private fun initializeCoreComponents() {
        try {
            gestureExecutor = AccessibilityGestureExecutor(this)
            uiParser = UITreeParser(this)
            screenReader = AccessibilityScreenReader(this)
            
            Log.i(TAG, "✅ 核心组件初始化完成")
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ 核心组件初始化失败", e)
        }
    }
    
    /**
     * 获取手势执行器（供外部使用）
     */
    fun getGestureExecutor(): AccessibilityGestureExecutor = gestureExecutor
    
    /**
     * 获取 UI 解析器（供外部使用）
     */
    fun getUIParser(): UITreeParser = uiParser
    
    /**
     * 获取屏幕读取器（供外部使用）
     */
    fun getScreenReader(): AccessibilityScreenReader = screenReader
    
    private fun startForegroundService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "AI Agent 服务",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
        
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AI Agent 运行中")
            .setContentText("Agent 正在后台待命")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        
        startForeground(NOTIFICATION_ID, notification)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 可以监听屏幕变化事件
    }

    override fun onInterrupt() {
        Log.w(TAG, "服务被中断")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.i(TAG, "服务解绑")
        socketServer?.stop()
        scope.cancel()
        return super.onUnbind(intent)
    }
}
