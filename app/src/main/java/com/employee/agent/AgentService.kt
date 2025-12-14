package com.employee.agent

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import androidx.core.app.NotificationCompat
import com.employee.agent.application.AgentRuntime
import com.employee.agent.domain.agent.AgentMode
import com.employee.agent.domain.agent.Goal
import com.employee.agent.domain.agent.CompletionCondition
import com.employee.agent.domain.tool.ToolRegistry
import com.employee.agent.infrastructure.accessibility.*
import com.employee.agent.infrastructure.ai.HunyuanAIClient
import com.employee.agent.infrastructure.tools.*
import kotlinx.coroutines.*

class AgentService : AccessibilityService() {
    private var socketServer: SocketServer? = null
    private var agentRuntime: AgentRuntime? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    companion object {
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "agent_service_channel"
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d("Agent", "Service Connected")
        
        // 启动前台服务（防止被杀）
        startForegroundService()
        
        // 初始化 Agent 运行时
        initializeAgentRuntime()
        
        // 启动 Socket 服务器（兼容旧版）
        socketServer = SocketServer(this)
        socketServer?.start(11451)
        
        Log.i("Agent", "✅ Agent 服务已启动")
    }
    
    private fun initializeAgentRuntime() {
        try {
            // 创建基础组件
            val gestureExecutor = AccessibilityGestureExecutor(this)
            val uiParser = UITreeParser(this)
            val screenReader = AccessibilityScreenReader(this)
            
            // 注册工具
            val toolRegistry = ToolRegistry().apply {
                register(TapTool(gestureExecutor))
                register(TapElementTool(gestureExecutor, uiParser))
                register(SwipeTool(gestureExecutor))
                register(PressKeyTool(gestureExecutor))
                register(WaitTool())
                register(GetScreenTool(uiParser))
            }
            
            // 创建 AI 客户端（需要配置 API Key）
            val apiKey = "your_api_key_here" // TODO: 从配置读取
            val aiClient = HunyuanAIClient(apiKey)
            
            // 创建 Agent 运行时
            agentRuntime = AgentRuntime(
                aiClient = aiClient,
                toolRegistry = toolRegistry,
                screenReader = screenReader,
                mode = AgentMode.SEMI_AUTONOMOUS
            )
            
            Log.i("Agent", "✅ Agent 运行时初始化完成")
            
            // 测试执行一个简单目标
            testAgentExecution()
            
        } catch (e: Exception) {
            Log.e("Agent", "❌ Agent 初始化失败", e)
        }
    }
    
    private fun testAgentExecution() {
        scope.launch {
            try {
                val testGoal = Goal(
                    description = "打开微信",
                    completionCondition = CompletionCondition.AIDecided,
                    maxSteps = 10,
                    timeoutSeconds = 30
                )
                
                Log.i("Agent", "🚀 开始测试执行目标: ${testGoal.description}")
                agentRuntime?.executeGoal(testGoal)
                Log.i("Agent", "✅ 目标执行完成")
                
            } catch (e: Exception) {
                Log.e("Agent", "❌ 目标执行失败", e)
            }
        }
    }
    
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
        Log.d("Agent", "Service Interrupted")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.d("Agent", "Service Unbound")
        socketServer?.stop()
        scope.cancel()
        agentRuntime?.stop()
        return super.onUnbind(intent)
    }
}
