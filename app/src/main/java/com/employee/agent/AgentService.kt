package com.employee.agent

import android.accessibilityservice.AccessibilityService
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.employee.agent.application.ScriptEngine
import com.employee.agent.infrastructure.NotificationActionReceiver
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
        
        // 静态实例，供悬浮球等组件调用
        @Volatile
        private var instance: AgentService? = null
        
        /**
         * 获取服务实例
         */
        fun getInstance(): AgentService? = instance
        
        /**
         * 从外部执行任务（悬浮球等）
         */
        fun executeTask(goal: String) {
            instance?.executeGoalIndependently(goal) ?: run {
                Log.e(TAG, "❌ 无障碍服务未运行，无法执行任务")
            }
        }
        
        /**
         * 检查服务是否运行
         */
        fun isRunning(): Boolean = instance != null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this  // 保存实例
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
        
        // 创建增强通知
        val notification = createEnhancedNotification()
        startForeground(NOTIFICATION_ID, notification)
        
        // 注册本地广播接收器（用于 Activity 通信）
        registerLocalBroadcastReceivers()
    }
    
    /**
     * 创建增强通知（带快捷操作按钮）
     */
    private fun createEnhancedNotification(): android.app.Notification {
        // 打开界面的 Intent
        val openIntent = Intent(this, NotificationActionReceiver::class.java).apply {
            action = NotificationActionReceiver.ACTION_OPEN_APP
        }
        val openPendingIntent = PendingIntent.getBroadcast(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        // 快捷任务：打开小红书
        val xhsIntent = Intent(this, NotificationActionReceiver::class.java).apply {
            action = NotificationActionReceiver.ACTION_QUICK_TASK
            putExtra("task", NotificationActionReceiver.TASK_OPEN_XHS)
        }
        val xhsPendingIntent = PendingIntent.getBroadcast(
            this, 1, xhsIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        // 快捷任务：热门笔记
        val hotIntent = Intent(this, NotificationActionReceiver::class.java).apply {
            action = NotificationActionReceiver.ACTION_QUICK_TASK
            putExtra("task", NotificationActionReceiver.TASK_HOT_NOTES)
        }
        val hotPendingIntent = PendingIntent.getBroadcast(
            this, 2, hotIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("🤖 AI Agent 运行中")
            .setContentText("点击打开 · 下拉查看快捷操作")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(openPendingIntent)
            // 快捷操作按钮
            .addAction(android.R.drawable.ic_menu_send, "📱 小红书", xhsPendingIntent)
            .addAction(android.R.drawable.ic_menu_search, "🔥 热门", hotPendingIntent)
            .addAction(android.R.drawable.ic_menu_edit, "✏️ 自定义", openPendingIntent)
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("Agent 正在后台待命\n\n" +
                    "📱 小红书 - 打开小红书应用\n" +
                    "🔥 热门 - 查找点赞过万的笔记\n" +
                    "✏️ 自定义 - 输入任意任务"))
            .build()
    }
    
    // 脚本引擎实例（用于独立执行）
    private var scriptEngine: ScriptEngine? = null
    private var currentJob: Job? = null
    
    /**
     * 注册本地广播接收器
     */
    private fun registerLocalBroadcastReceivers() {
        val localBroadcastManager = LocalBroadcastManager.getInstance(this)
        
        // 执行任务广播
        localBroadcastManager.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val goal = intent?.getStringExtra("goal") ?: return
                executeGoalIndependently(goal)
            }
        }, IntentFilter("agent.execute"))
        
        // 停止任务广播
        localBroadcastManager.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                stopCurrentExecution()
            }
        }, IntentFilter("agent.stop"))
    }
    
    /**
     * 🎯 独立执行目标（不依赖 PC）
     */
    private fun executeGoalIndependently(goal: String) {
        Log.i(TAG, "🎯 开始独立执行: $goal")
        
        val apiKey = AgentConfigActivity.getApiKey(this)
        if (apiKey.isEmpty()) {
            sendLogBroadcast("❌ 请先配置 API Key")
            sendCompleteBroadcast(false, "未配置 API Key")
            return
        }
        
        if (scriptEngine == null) {
            scriptEngine = ScriptEngine(this, apiKey)
        }
        
        currentJob = scope.launch {
            try {
                // 设置日志回调
                scriptEngine?.onLog = { log ->
                    sendLogBroadcast(log)
                }
                
                // 生成脚本
                sendLogBroadcast("📝 AI 正在生成脚本...")
                val generateResult = scriptEngine?.generateScript(goal)
                
                if (generateResult == null || generateResult.isFailure) {
                    sendLogBroadcast("❌ 脚本生成失败")
                    sendCompleteBroadcast(false, generateResult?.exceptionOrNull()?.message ?: "生成失败")
                    return@launch
                }
                
                val script = generateResult.getOrNull() ?: run {
                    sendCompleteBroadcast(false, "脚本为空")
                    return@launch
                }
                
                sendLogBroadcast("✅ 脚本生成成功: ${script.name} (${script.steps.size} 步)")
                
                // 执行脚本（带自动改进）
                sendLogBroadcast("▶️ 开始执行脚本...")
                val executeResult = scriptEngine?.executeWithAutoImprove(script.id) { current, total, stepName ->
                    // 发送进度广播
                    LocalBroadcastManager.getInstance(this@AgentService).sendBroadcast(
                        Intent("agent.progress")
                            .putExtra("current", current)
                            .putExtra("total", total)
                            .putExtra("step_name", stepName)
                    )
                }
                
                if (executeResult?.success == true) {
                    sendCompleteBroadcast(true, "")
                } else {
                    sendCompleteBroadcast(false, executeResult?.error ?: "执行失败")
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "独立执行失败", e)
                sendLogBroadcast("❌ 错误: ${e.message}")
                sendCompleteBroadcast(false, e.message ?: "未知错误")
            }
        }
    }
    
    /**
     * 停止当前执行
     */
    private fun stopCurrentExecution() {
        currentJob?.cancel()
        currentJob = null
        sendLogBroadcast("⏹️ 已停止执行")
    }
    
    /**
     * 发送日志广播
     */
    private fun sendLogBroadcast(log: String) {
        LocalBroadcastManager.getInstance(this).sendBroadcast(
            Intent("agent.log").putExtra("log", log)
        )
    }
    
    /**
     * 发送完成广播
     */
    private fun sendCompleteBroadcast(success: Boolean, result: String) {
        LocalBroadcastManager.getInstance(this).sendBroadcast(
            Intent("agent.complete")
                .putExtra("success", success)
                .putExtra("result", result)
        )
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 可以监听屏幕变化事件
    }

    override fun onInterrupt() {
        Log.w(TAG, "服务被中断")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.i(TAG, "服务解绑")
        instance = null  // 清除实例
        socketServer?.stop()
        scope.cancel()
        return super.onUnbind(intent)
    }
}
