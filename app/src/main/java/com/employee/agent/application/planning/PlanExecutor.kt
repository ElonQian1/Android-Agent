// application/planning/PlanExecutor.kt
// module: application/planning | layer: application | role: plan-executor
// summary: 执行计划执行器，按计划逐步执行任务

package com.employee.agent.application.planning

import android.util.Log
import com.employee.agent.domain.planning.*
import com.employee.agent.domain.tool.ToolRegistry
import com.employee.agent.infrastructure.storage.MemoryRepository
import kotlinx.coroutines.delay

/**
 * 计划执行器
 * 
 * 职责：
 * - 按顺序执行计划中的任务
 * - 处理前置/后置条件检查
 * - 支持暂停、恢复、取消
 * - 记录执行日志
 */
class PlanExecutor(
    private val toolRegistry: ToolRegistry,
    private val screenProvider: ScreenContextProvider,
    private val memoryRepository: MemoryRepository?,
    private val taskPlanner: TaskPlanner
) {
    companion object {
        private const val TAG = "PlanExecutor"
        private const val DEFAULT_WAIT_AFTER_ACTION = 500L
        private const val MAX_RETRIES = 3
    }
    
    private var isPaused = false
    private var isCancelled = false
    private var currentPlan: ExecutionPlan? = null
    
    /**
     * 执行状态回调
     */
    var onTaskStarted: ((Task) -> Unit)? = null
    var onTaskCompleted: ((Task, Boolean) -> Unit)? = null
    var onPlanProgress: ((Float, String) -> Unit)? = null
    var onPlanCompleted: ((ExecutionPlan, Boolean) -> Unit)? = null
    
    /**
     * 执行计划
     */
    suspend fun execute(plan: ExecutionPlan): ExecutionResult {
        currentPlan = plan
        isCancelled = false
        isPaused = false
        
        Log.i(TAG, "开始执行计划: ${plan.goal.description}")
        
        var currentPlanVar = plan
        var totalRetries = 0
        
        while (!currentPlanVar.isComplete() && !isCancelled) {
            // 暂停检查
            while (isPaused && !isCancelled) {
                delay(100)
            }
            
            if (isCancelled) break
            
            // 获取下一个任务
            val task = currentPlanVar.getNextLeafTask()
            if (task == null) {
                // 没有更多任务，检查是否完成
                if (currentPlanVar.rootTask.status != TaskStatus.COMPLETED) {
                    currentPlanVar.rootTask.markCompleted()
                }
                break
            }
            
            onTaskStarted?.invoke(task)
            task.status = TaskStatus.IN_PROGRESS
            
            // 执行任务
            val result = executeTask(task)
            
            if (result.success) {
                task.markCompleted()
                onTaskCompleted?.invoke(task, true)
                updateParentStatus(currentPlanVar.rootTask)
                
                // 记录成功
                memoryRepository?.logAction(
                    goalId = plan.goal.id,
                    stepNumber = getCompletedCount(currentPlanVar),
                    toolName = task.action?.toolName ?: "unknown",
                    parameters = task.action?.parameters ?: emptyMap(),
                    success = true,
                    resultMessage = result.message,
                    aiReasoning = task.description
                )
            } else {
                Log.w(TAG, "任务失败: ${task.description}, 原因: ${result.message}")
                
                // 尝试重新规划
                if (totalRetries < MAX_RETRIES) {
                    totalRetries++
                    val screen = screenProvider.getCurrentScreen()
                    val feedback = ExecutionFeedback(
                        taskId = task.id,
                        success = false,
                        error = result.message,
                        currentScreen = screen
                    )
                    
                    currentPlanVar = taskPlanner.replan(currentPlanVar, feedback)
                    Log.i(TAG, "重新规划完成，继续执行")
                } else {
                    task.markFailed(result.message)
                    onTaskCompleted?.invoke(task, false)
                    
                    return ExecutionResult(
                        success = false,
                        plan = currentPlanVar,
                        error = "达到最大重试次数: ${result.message}"
                    )
                }
            }
            
            // 更新进度
            val progress = currentPlanVar.getProgress()
            onPlanProgress?.invoke(progress, "${(progress * 100).toInt()}% - ${task.description}")
            
            // 动作后等待
            delay(DEFAULT_WAIT_AFTER_ACTION)
        }
        
        val success = currentPlanVar.isComplete()
        
        // 如果成功，学习这次执行
        if (success) {
            memoryRepository?.learnFromSuccess(plan.goal.id)
        }
        
        onPlanCompleted?.invoke(currentPlanVar, success)
        
        return ExecutionResult(
            success = success,
            plan = currentPlanVar,
            error = if (isCancelled) "执行被取消" else null
        )
    }
    
    /**
     * 执行单个任务
     */
    private suspend fun executeTask(task: Task): TaskResult {
        // 1. 检查前置条件
        task.precondition?.let { condition ->
            val satisfied = checkCondition(condition)
            if (!satisfied) {
                return TaskResult(false, "前置条件不满足: ${condition.expression}")
            }
        }
        
        // 2. 执行动作
        val action = task.action
        if (action != null) {
            val tool = toolRegistry.getTool(action.toolName)
            if (tool == null) {
                return TaskResult(false, "工具不存在: ${action.toolName}")
            }
            
            try {
                val result = tool.execute(action.parameters)
                if (!result.success) {
                    return TaskResult(false, result.message)
                }
            } catch (e: Exception) {
                Log.e(TAG, "工具执行异常", e)
                return TaskResult(false, "执行异常: ${e.message}")
            }
        } else if (task.type == TaskType.WAIT) {
            val waitTime = task.metadata["waitTime"] as? Long ?: 1000L
            delay(waitTime)
        }
        
        // 3. 检查后置条件
        task.postcondition?.let { condition ->
            // 等待条件满足（带超时）
            val satisfied = waitForCondition(condition)
            if (!satisfied) {
                return TaskResult(false, "后置条件不满足: ${condition.expression}")
            }
        }
        
        return TaskResult(true, "执行成功")
    }
    
    /**
     * 检查条件
     */
    private suspend fun checkCondition(condition: TaskCondition): Boolean {
        val screen = screenProvider.getCurrentScreen()
        
        return when (condition.type) {
            ConditionType.SCREEN_CONTAINS -> {
                screen.visibleTexts.any { it.contains(condition.expression, ignoreCase = true) }
            }
            ConditionType.SCREEN_NOT_CONTAINS -> {
                screen.visibleTexts.none { it.contains(condition.expression, ignoreCase = true) }
            }
            ConditionType.ELEMENT_EXISTS -> {
                screen.clickableElements.any { it.contains(condition.expression, ignoreCase = true) }
            }
            ConditionType.APP_IN_FOREGROUND -> {
                screen.appPackage?.contains(condition.expression, ignoreCase = true) == true
            }
            else -> true
        }
    }
    
    /**
     * 等待条件满足
     */
    private suspend fun waitForCondition(
        condition: TaskCondition,
        checkInterval: Long = 500L
    ): Boolean {
        val startTime = System.currentTimeMillis()
        
        while (System.currentTimeMillis() - startTime < condition.timeout) {
            if (checkCondition(condition)) {
                return true
            }
            delay(checkInterval)
        }
        
        return false
    }
    
    /**
     * 更新父任务状态
     */
    private fun updateParentStatus(task: Task) {
        if (task.subtasks.isEmpty()) return
        
        val allCompleted = task.subtasks.all { 
            it.status == TaskStatus.COMPLETED || it.status == TaskStatus.SKIPPED 
        }
        
        if (allCompleted) {
            task.status = TaskStatus.COMPLETED
        }
        
        // 递归更新子任务
        task.subtasks.forEach { updateParentStatus(it) }
    }
    
    private fun getCompletedCount(plan: ExecutionPlan): Int {
        return plan.flattenTasks().count { 
            it.status == TaskStatus.COMPLETED || it.status == TaskStatus.SKIPPED 
        }
    }
    
    /**
     * 暂停执行
     */
    fun pause() {
        isPaused = true
        Log.i(TAG, "执行已暂停")
    }
    
    /**
     * 恢复执行
     */
    fun resume() {
        isPaused = false
        Log.i(TAG, "执行已恢复")
    }
    
    /**
     * 取消执行
     */
    fun cancel() {
        isCancelled = true
        Log.i(TAG, "执行已取消")
    }
    
    /**
     * 获取当前状态
     */
    fun getStatus(): ExecutorStatus {
        return ExecutorStatus(
            isRunning = currentPlan != null && !isCancelled,
            isPaused = isPaused,
            currentPlan = currentPlan,
            progress = currentPlan?.getProgress() ?: 0f
        )
    }
}

/**
 * 任务执行结果
 */
data class TaskResult(
    val success: Boolean,
    val message: String
)

/**
 * 计划执行结果
 */
data class ExecutionResult(
    val success: Boolean,
    val plan: ExecutionPlan,
    val error: String? = null
)

/**
 * 执行器状态
 */
data class ExecutorStatus(
    val isRunning: Boolean,
    val isPaused: Boolean,
    val currentPlan: ExecutionPlan?,
    val progress: Float
)

/**
 * 屏幕上下文提供者接口
 */
interface ScreenContextProvider {
    suspend fun getCurrentScreen(): ScreenContext
}
