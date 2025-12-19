// application/EnhancedAgentRuntime.kt
// module: application | layer: application | role: enhanced-runtime
// summary: 增强版 Agent 运行时，集成多模态、记忆、规划、恢复能力

package com.employee.agent.application

import android.util.Log
import com.employee.agent.domain.agent.*
import com.employee.agent.domain.planning.*
import com.employee.agent.domain.recovery.*
import com.employee.agent.domain.screen.UINode
import com.employee.agent.domain.tool.ToolRegistry
import com.employee.agent.application.planning.*
import com.employee.agent.infrastructure.storage.MemoryRepository
import com.employee.agent.infrastructure.vision.*
import com.employee.agent.infrastructure.network.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * 增强版 Agent 运行时
 * 
 * 相比基础版新增：
 * 1. 多模态屏幕理解
 * 2. 智能记忆系统
 * 3. 层次化任务规划
 * 4. 自适应错误恢复
 * 5. PC 协同通信
 */
class EnhancedAgentRuntime(
    private val aiClient: AIClient,
    private val toolRegistry: ToolRegistry,
    private val screenAnalyzer: MultimodalScreenAnalyzer,
    private val memoryRepository: MemoryRepository?,
    private val recoveryRegistry: RecoveryStrategyRegistry?,
    private val pcBridge: PCAgentBridge?,
    private val config: RuntimeConfig = RuntimeConfig()
) : ScreenContextProvider {
    
    companion object {
        private const val TAG = "EnhancedAgentRuntime"
    }
    
    // 状态
    private var _state = MutableStateFlow(AgentRunState.IDLE)
    val state: StateFlow<AgentRunState> = _state.asStateFlow()
    
    private val memory = AgentMemory()
    private var currentGoal: Goal? = null
    private var currentPlan: ExecutionPlan? = null
    private var stepCount = 0
    private var errorCount = 0
    
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var executionJob: Job? = null
    
    // 任务规划器
    private val taskPlanner = AITaskPlanner(aiClient)
    
    // 计划执行器
    private val planExecutor by lazy {
        PlanExecutor(
            toolRegistry = toolRegistry,
            screenProvider = this,
            memoryRepository = memoryRepository,
            taskPlanner = taskPlanner
        ).apply {
            onTaskStarted = { task ->
                Log.d(TAG, "任务开始: ${task.description}")
                scope.launch {
                    pcBridge?.sendProgress(
                        stepNumber = stepCount,
                        totalSteps = currentPlan?.estimatedSteps ?: 10,
                        currentTask = task.description
                    )
                }
            }
            onTaskCompleted = { task, success ->
                Log.d(TAG, "任务完成: ${task.description}, 成功: $success")
                stepCount++
            }
            onPlanProgress = { progress, message ->
                Log.d(TAG, "进度: ${(progress * 100).toInt()}% - $message")
            }
        }
    }
    
    /**
     * 执行目标
     */
    suspend fun executeGoal(goal: Goal) {
        if (_state.value != AgentRunState.IDLE) {
            Log.w(TAG, "Agent 正在执行中，忽略新目标")
            return
        }
        
        currentGoal = goal
        stepCount = 0
        errorCount = 0
        
        Log.i(TAG, "🚀 开始执行目标: ${goal.description}")
        
        // 记录目标开始
        memoryRepository?.startGoal(goal)
        
        // 初始化工作记忆
        memory.workingMemory = WorkingMemory(goal)
        memory.addShortTerm(MemoryEntry(
            timestamp = System.currentTimeMillis(),
            type = MemoryType.THOUGHT,
            content = "开始执行目标: ${goal.description}"
        ))
        
        executionJob = scope.launch {
            try {
                // 阶段1：分析当前屏幕
                _state.value = AgentRunState.OBSERVING
                val screenAnalysis = analyzeCurrentScreen()
                
                // 阶段2：检索相关记忆和模式
                val learnedStrategies = retrieveStrategies(goal.description)
                
                // 阶段3：规划任务
                _state.value = AgentRunState.THINKING
                pcBridge?.sendThinking("正在分析目标并制定计划...", null, null)
                
                val planningContext = PlanningContext(
                    currentScreen = screenAnalysis.toScreenContext(),
                    learnedStrategies = learnedStrategies
                )
                
                val plan = taskPlanner.plan(goal, planningContext)
                currentPlan = plan
                
                Log.i(TAG, "📋 规划完成: ${plan.estimatedSteps} 步")
                pcBridge?.sendThinking(
                    "计划制定完成",
                    "分解为 ${plan.estimatedSteps} 个步骤",
                    plan.rootTask.description
                )
                
                // 阶段4：执行计划
                _state.value = AgentRunState.EXECUTING
                val result = planExecutor.execute(plan)
                
                // 阶段5：记录结果
                if (result.success) {
                    Log.i(TAG, "✅ 目标执行成功")
                    memoryRepository?.completeGoal(goal.id, true, stepCount)
                    memoryRepository?.learnFromSuccess(goal.id)
                } else {
                    Log.w(TAG, "❌ 目标执行失败: ${result.error}")
                    memoryRepository?.completeGoal(goal.id, false, stepCount, result.error)
                }
                
            } catch (e: CancellationException) {
                Log.i(TAG, "目标执行被取消")
                memoryRepository?.completeGoal(goal.id, false, stepCount, "被取消")
            } catch (e: Exception) {
                Log.e(TAG, "目标执行异常", e)
                memoryRepository?.completeGoal(goal.id, false, stepCount, e.message)
            } finally {
                _state.value = AgentRunState.IDLE
                currentGoal = null
                currentPlan = null
            }
        }
        
        executionJob?.join()
    }
    
    /**
     * 分析当前屏幕（多模态）
     */
    private suspend fun analyzeCurrentScreen(): ScreenAnalysisResult {
        return try {
            val mode = if (config.enableVision) {
                MultimodalScreenAnalyzer.AnalysisMode.HYBRID
            } else {
                MultimodalScreenAnalyzer.AnalysisMode.UI_TREE_ONLY
            }
            
            screenAnalyzer.analyzeScreen(mode)
        } catch (e: Exception) {
            Log.e(TAG, "屏幕分析失败", e)
            ScreenAnalysisResult(
                success = false,
                error = e.message,
                analysisMode = MultimodalScreenAnalyzer.AnalysisMode.UI_TREE_ONLY
            )
        }
    }
    
    /**
     * 检索相关策略
     */
    private suspend fun retrieveStrategies(goalDescription: String): List<LearnedStrategy> {
        val pattern = memoryRepository?.findApplicablePattern(goalDescription)
        
        return if (pattern != null) {
            val actions = memoryRepository.getPatternActions(pattern)
            listOf(LearnedStrategy(
                pattern = pattern.goalPattern,
                steps = actions.map { "${it.toolName}(${it.parameters})" },
                confidence = pattern.confidence
            ))
        } else {
            emptyList()
        }
    }
    
    /**
     * 尝试错误恢复
     */
    private suspend fun tryRecover(
        errorType: ErrorType,
        errorMessage: String?,
        lastAction: LastAction?
    ): RecoveryResult {
        if (recoveryRegistry == null) {
            return RecoveryResult.Failure("恢复策略未配置")
        }
        
        val screenContext = getCurrentScreen()
        
        val context = RecoveryContext(
            errorType = errorType,
            errorMessage = errorMessage,
            currentScreen = screenContext,
            lastAction = lastAction,
            retryCount = errorCount
        )
        
        return recoveryRegistry.tryRecover(context)
    }
    
    /**
     * 实现 ScreenContextProvider 接口
     */
    override suspend fun getCurrentScreen(): ScreenContext {
        val analysis = analyzeCurrentScreen()
        return analysis.toScreenContext()
    }
    
    /**
     * 暂停执行
     */
    fun pause() {
        if (_state.value == AgentRunState.EXECUTING || _state.value == AgentRunState.THINKING) {
            _state.value = AgentRunState.PAUSED
            planExecutor.pause()
            Log.i(TAG, "执行已暂停")
        }
    }
    
    /**
     * 恢复执行
     */
    fun resume() {
        if (_state.value == AgentRunState.PAUSED) {
            _state.value = AgentRunState.EXECUTING
            planExecutor.resume()
            Log.i(TAG, "执行已恢复")
        }
    }
    
    /**
     * 停止执行
     */
    fun stop() {
        executionJob?.cancel()
        planExecutor.cancel()
        _state.value = AgentRunState.STOPPED
        Log.i(TAG, "执行已停止")
    }
    
    /**
     * 获取当前状态
     */
    fun getState(): AgentRunState = _state.value
    
    /**
     * 释放资源
     */
    fun release() {
        stop()
        scope.cancel()
    }
}

/**
 * 运行时配置（与 AgentConfigActivity.AgentConfig 区分）
 */
data class RuntimeConfig(
    val enableVision: Boolean = true,          // 启用视觉分析
    val enableLearning: Boolean = true,        // 启用学习
    val enableRecovery: Boolean = true,        // 启用错误恢复
    val autoGrantPermissions: Boolean = false, // 自动授予权限
    val maxRetries: Int = 3,                   // 最大重试次数
    val defaultTimeout: Long = 60_000          // 默认超时
)

/**
 * ScreenAnalysisResult 扩展
 */
private fun ScreenAnalysisResult.toScreenContext(): ScreenContext {
    return ScreenContext(
        appPackage = uiTree?.packageName,
        activityName = null,
        visibleTexts = elements.map { it.text }.filter { it.isNotBlank() },
        clickableElements = elements.filter { it.isClickable }.map { it.text },
        hasDialog = visionDescription?.contains("弹窗") == true ||
                    visionDescription?.contains("dialog") == true,
        summary = visionDescription ?: elements.take(5).joinToString { it.text }
    )
}
