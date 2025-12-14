// interface/AgentService.kt (重构版)
package com.employee.agent

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import androidx.annotation.RequiresApi
import com.employee.agent.application.AgentRuntime
import com.employee.agent.domain.agent.AgentMode
import com.employee.agent.domain.agent.Goal
import com.employee.agent.domain.agent.CompletionCondition
import com.employee.agent.domain.tool.ToolRegistry
import com.employee.agent.infrastructure.accessibility.AccessibilityGestureExecutor
import com.employee.agent.infrastructure.accessibility.UITreeParser
import com.employee.agent.infrastructure.ai.HunyuanAIClient
import com.employee.agent.infrastructure.tools.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Agent 无障碍服务入口（重构版）
 * 
 * 职责：
 * 1. 初始化依赖（AI、工具、执行器）
 * 2. 启动 Socket 服务器（兼容旧版通信）
 * 3. 管理 Agent 运行时生命周期
 */
@RequiresApi(Build.VERSION_CODES.N)
class AgentService : AccessibilityService() {
    
    // 旧版兼容：Socket 服务器
    private var socketServer: SocketServer? = null
    
    // 新架构：Agent 运行时
    private lateinit var agentRuntime: AgentRuntime
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i("AgentService", "无障碍服务已连接")
        
        // 初始化基础设施层
        initializeInfrastructure()
        
        // 启动旧版 Socket 服务器（兼容 PC 端）
        socketServer = SocketServer(this)
        socketServer?.start(11451)
        
        Log.i("AgentService", "✅ Agent 服务初始化完成")
    }
    
    /**
     * 初始化基础设施层组件
     */
    private fun initializeInfrastructure() {
        // 1. 手势执行器
        val gestureExecutor = AccessibilityGestureExecutor(this)
        
        // 2. UI 树解析器
        val uiTreeParser = UITreeParser(this)
        
        // 3. AI 客户端
        val apiKey = "your-hunyuan-api-key-here"  // TODO: 从配置文件读取
        val aiClient = HunyuanAIClient(apiKey)
        
        // 4. 工具注册表
        val toolRegistry = ToolRegistry().apply {
            register(TapTool(gestureExecutor))
            register(TapElementTool(gestureExecutor))
            register(SwipeTool(gestureExecutor))
            register(InputTextTool(gestureExecutor))
            register(PressKeyTool(gestureExecutor))
            register(WaitTool())
        }
        
        // 5. 创建 Agent 运行时
        agentRuntime = AgentRuntime(
            aiClient = aiClient,
            toolRegistry = toolRegistry,
            screenReader = uiTreeParser,
            mode = AgentMode.SEMI_AUTONOMOUS
        )
    }
    
    /**
     * 执行目标（供外部调用）
     */
    fun executeGoal(goalDescription: String) {
        val goal = Goal(
            description = goalDescription,
            completionCondition = CompletionCondition.AIDecided,
            maxSteps = 50,
            timeoutSeconds = 300
        )
        
        scope.launch {
            try {
                Log.i("AgentService", "开始执行目标: $goalDescription")
                agentRuntime.executeGoal(goal)
                Log.i("AgentService", "目标执行完成")
            } catch (e: Exception) {
                Log.e("AgentService", "目标执行失败", e)
            }
        }
    }
    
    /**
     * 获取 Agent 状态
     */
    fun getAgentStatus(): String {
        val snapshot = agentRuntime.getSnapshot()
        return "状态: ${snapshot.state}, 进度: ${snapshot.progress}, 模式: ${snapshot.mode}"
    }
    
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 可选：监听屏幕变化事件
        // event?.let { Log.d("AgentService", "事件: ${it.eventType}") }
    }
    
    override fun onInterrupt() {
        Log.w("AgentService", "服务被中断")
    }
    
    override fun onUnbind(intent: Intent?): Boolean {
        Log.i("AgentService", "服务解绑")
        agentRuntime.stop()
        socketServer?.stop()
        return super.onUnbind(intent)
    }
}
