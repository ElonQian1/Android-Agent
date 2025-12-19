// interface/AgentServiceV2.kt
// module: interface | layer: interface | role: service-entry
// summary: 增强版无障碍服务入口，集成所有 V2.0 新能力

package com.employee.agent

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import androidx.core.app.NotificationCompat
import com.employee.agent.application.*
import com.employee.agent.domain.agent.*
import com.employee.agent.domain.recovery.RecoveryStrategyRegistry
import com.employee.agent.domain.tool.ToolRegistry
import com.employee.agent.infrastructure.accessibility.*
import com.employee.agent.infrastructure.ai.HunyuanAIClient
import com.employee.agent.infrastructure.network.*
import com.employee.agent.infrastructure.recovery.createDefaultRecoveryRegistry
import com.employee.agent.infrastructure.storage.*
import com.employee.agent.infrastructure.tools.*
import com.employee.agent.infrastructure.vision.*
import kotlinx.coroutines.*

/**
 * 增强版 Agent 服务 (V2.0)
 * 
 * 新能力：
 * - 多模态屏幕理解（UI树 + 截图 + Vision API）
 * - 智能记忆系统（Room 持久化 + 模式学习）
 * - 层次化任务规划
 * - 自适应错误恢复
 * - PC-手机 WebSocket 协同
 */
class AgentServiceV2 : AccessibilityService() {
    
    companion object {
        private const val TAG = "AgentServiceV2"
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "agent_service_channel"
        
        // 端口配置（可被用户配置覆盖）
        private const val SOCKET_PORT = 11451      // 兼容旧版
    }
    
    // 用户配置
    private lateinit var userConfig: AgentConfig
    
    // 核心组件
    private var enhancedRuntime: EnhancedAgentRuntime? = null
    private var socketServer: SocketServer? = null
    private var webSocketServer: WebSocketServer? = null
    private var pcBridge: PCAgentBridge? = null
    
    // 基础设施
    private var database: AgentDatabase? = null
    private var memoryRepository: MemoryRepository? = null
    private var recoveryRegistry: RecoveryStrategyRegistry? = null
    
    // 协程作用域
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "🚀 AgentService V2.0 启动中...")
        
        try {
            // 0. 加载用户配置
            userConfig = AgentConfigActivity.getAgentConfig(this)
            
            if (userConfig.hunyuanApiKey.isEmpty()) {
                Log.e(TAG, "❌ 未配置 API Key，请先在配置界面设置")
                // 仍然启动服务，但运行时会提示用户
            }
            
            // 1. 启动前台服务
            startForegroundService()
            
            // 2. 初始化数据库
            initializeDatabase()
            
            // 3. 初始化 Agent 运行时
            initializeAgentRuntime()
            
            // 4. 启动网络服务
            startNetworkServers()
            
            Log.i(TAG, "✅ AgentService V2.0 启动完成")
            
            // 5. 可选：执行测试目标
            // testAgentExecution()
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ AgentService 启动失败", e)
        }
    }
    
    /**
     * 初始化数据库
     */
    private fun initializeDatabase() {
        database = AgentDatabase.getInstance(this)
        memoryRepository = MemoryRepository(database!!)
        Log.d(TAG, "数据库初始化完成")
    }
    
    /**
     * 初始化 Agent 运行时
     */
    private fun initializeAgentRuntime() {
        // 检查 API Key
        if (userConfig.hunyuanApiKey.isEmpty()) {
            Log.w(TAG, "⚠️ 未配置混元 API Key，Agent 功能将受限")
            return
        }
        
        // 基础组件
        val gestureExecutor = AccessibilityGestureExecutor(this)
        val uiParser = UITreeParser(this)
        
        // 工具注册
        val toolRegistry = ToolRegistry().apply {
            register(TapTool(gestureExecutor))
            register(TapElementTool(gestureExecutor, uiParser))
            register(SwipeTool(gestureExecutor))
            register(PressKeyTool(gestureExecutor))
            register(WaitTool())
            register(GetScreenTool(uiParser))
        }
        
        // AI 客户端 - 从用户配置读取
        val aiClient = HunyuanAIClient(userConfig.hunyuanApiKey)
        Log.d(TAG, "✅ 混元 AI 客户端已初始化")
        
        // 多模态分析器 - 根据用户配置选择视觉服务
        val screenshotCapture = ScreenshotCapture(this, this)
        val visionClient: VisionClient? = when {
            userConfig.visionProvider == "qwen" && userConfig.qwenVLApiKey.isNotEmpty() -> {
                Log.d(TAG, "✅ 启用通义千问 VL 视觉服务")
                QwenVLClient(userConfig.qwenVLApiKey)
            }
            userConfig.visionProvider == "openai" && userConfig.openaiApiKey.isNotEmpty() -> {
                Log.d(TAG, "✅ 启用 OpenAI GPT-4V 视觉服务")
                GPT4VisionClient(userConfig.openaiApiKey)
            }
            else -> {
                Log.d(TAG, "ℹ️ 未启用视觉服务，仅使用 UI 树分析")
                null
            }
        }
        val screenAnalyzer = MultimodalScreenAnalyzer(screenshotCapture, visionClient, uiParser)
        
        // 错误恢复策略
        recoveryRegistry = createDefaultRecoveryRegistry(
            gestureExecutor = gestureExecutor,
            autoGrantPermissions = false  // 生产环境建议 false
        )
        
        // 创建增强运行时（使用内部 RuntimeConfig）
        enhancedRuntime = EnhancedAgentRuntime(
            aiClient = aiClient,
            toolRegistry = toolRegistry,
            screenAnalyzer = screenAnalyzer,
            memoryRepository = memoryRepository,
            recoveryRegistry = recoveryRegistry,
            pcBridge = pcBridge,
            config = RuntimeConfig(
                enableVision = visionClient != null,
                enableLearning = true,
                enableRecovery = true
            )
        )
        
        Log.d(TAG, "Agent 运行时初始化完成")
    }
    
    /**
     * 启动网络服务
     */
    private fun startNetworkServers() {
        // 兼容旧版 Socket 服务器
        socketServer = SocketServer(this)
        socketServer?.start(SOCKET_PORT)
        Log.d(TAG, "Socket 服务器启动在端口 $SOCKET_PORT")
        
        // 新版 WebSocket 服务器 - 使用用户配置的端口
        val wsPort = userConfig.websocketPort
        webSocketServer = WebSocketServer(wsPort)
        webSocketServer?.start()
        Log.d(TAG, "WebSocket 服务器启动在端口 $wsPort")
        
        // PC-手机协同桥接
        val uiParser = UITreeParser(this)
        val screenshotCapture = ScreenshotCapture(this, this)
        
        pcBridge = PCAgentBridge(
            webSocketServer = webSocketServer!!,
            agentRuntimeProvider = { enhancedRuntime },
            uiTreeParser = uiParser,
            screenshotCapture = screenshotCapture
        )
        pcBridge?.initialize()
        
        Log.d(TAG, "PC 协同桥接初始化完成")
    }
    
    /**
     * 测试 Agent 执行
     */
    private fun testAgentExecution() {
        scope.launch {
            delay(3000)  // 等待初始化完成
            
            try {
                val testGoal = Goal(
                    description = "打开微信",
                    completionCondition = CompletionCondition.AIDecided,
                    maxSteps = 10,
                    timeoutSeconds = 30
                )
                
                Log.i(TAG, "🧪 开始测试目标: ${testGoal.description}")
                enhancedRuntime?.executeGoal(testGoal)
                Log.i(TAG, "🧪 测试完成")
                
            } catch (e: Exception) {
                Log.e(TAG, "测试失败", e)
            }
        }
    }
    
    /**
     * 公开方法：执行目标
     */
    fun executeGoal(description: String, maxSteps: Int = 20, timeoutSeconds: Int = 60) {
        scope.launch {
            val goal = Goal(
                description = description,
                completionCondition = CompletionCondition.AIDecided,
                maxSteps = maxSteps,
                timeoutSeconds = timeoutSeconds
            )
            enhancedRuntime?.executeGoal(goal)
        }
    }
    
    /**
     * 公开方法：暂停
     */
    fun pauseExecution() {
        enhancedRuntime?.pause()
    }
    
    /**
     * 公开方法：恢复
     */
    fun resumeExecution() {
        enhancedRuntime?.resume()
    }
    
    /**
     * 公开方法：停止
     */
    fun stopExecution() {
        enhancedRuntime?.stop()
    }
    
    /**
     * 获取当前状态
     */
    fun getAgentState(): AgentRunState {
        return enhancedRuntime?.getState() ?: AgentRunState.IDLE
    }
    
    /**
     * 启动前台服务
     */
    private fun startForegroundService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "AI Agent 服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Agent 正在后台运行"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
        
        val intent = Intent(this, AgentActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AI Agent V2.0 运行中")
            .setContentText("点击打开控制面板")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
        
        startForeground(NOTIFICATION_ID, notification)
    }
    
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 可以监听屏幕变化事件
        // 用于触发屏幕状态更新等
    }
    
    override fun onInterrupt() {
        Log.d(TAG, "Service Interrupted")
    }
    
    override fun onUnbind(intent: Intent?): Boolean {
        Log.d(TAG, "Service Unbound")
        cleanup()
        return super.onUnbind(intent)
    }
    
    override fun onDestroy() {
        super.onDestroy()
        cleanup()
    }
    
    private fun cleanup() {
        Log.i(TAG, "清理资源...")
        
        enhancedRuntime?.release()
        pcBridge?.release()
        webSocketServer?.stop()
        socketServer?.stop()
        scope.cancel()
        
        Log.i(TAG, "资源清理完成")
    }
}
