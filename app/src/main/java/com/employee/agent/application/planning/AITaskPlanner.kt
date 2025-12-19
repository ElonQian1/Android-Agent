// application/planning/AITaskPlanner.kt
// module: application/planning | layer: application | role: ai-task-planner
// summary: AI 驱动的任务规划器实现

package com.employee.agent.application.planning

import android.util.Log
import com.employee.agent.application.AIClient
import com.employee.agent.application.Message
import com.employee.agent.domain.agent.Goal
import com.employee.agent.domain.planning.*
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName

/**
 * AI 驱动的任务规划器
 * 
 * 使用 LLM 将自然语言目标分解为结构化的执行计划
 */
class AITaskPlanner(
    private val aiClient: AIClient
) : TaskPlanner {
    
    companion object {
        private const val TAG = "AITaskPlanner"
    }
    
    private val gson = Gson()
    
    override suspend fun plan(goal: Goal, context: PlanningContext): ExecutionPlan {
        Log.d(TAG, "开始规划: ${goal.description}")
        
        // 1. 检查是否有学习到的策略
        val learnedPlan = tryLearnedStrategy(goal, context)
        if (learnedPlan != null) {
            Log.d(TAG, "使用学习到的策略")
            return learnedPlan
        }
        
        // 2. 使用 AI 进行规划
        return planWithAI(goal, context)
    }
    
    override suspend fun replan(
        originalPlan: ExecutionPlan,
        feedback: ExecutionFeedback
    ): ExecutionPlan {
        Log.d(TAG, "重新规划: ${feedback.error}")
        
        val prompt = buildReplanPrompt(originalPlan, feedback)
        val messages = listOf(
            Message("system", SYSTEM_PROMPT),
            Message("user", prompt)
        )
        
        val response = aiClient.chat(messages)
        return parseAIPlan(response, originalPlan.goal) ?: originalPlan
    }
    
    /**
     * 尝试使用学习到的策略
     */
    private fun tryLearnedStrategy(goal: Goal, context: PlanningContext): ExecutionPlan? {
        val strategy = context.learnedStrategies
            .filter { goal.description.contains(it.pattern) && it.confidence >= 0.7f }
            .maxByOrNull { it.confidence }
            ?: return null
        
        val rootTask = Task(
            description = goal.description,
            type = TaskType.COMPOSITE
        )
        
        strategy.steps.forEachIndexed { index, step ->
            val subtask = parseStepToTask(step, index)
            rootTask.addSubtask(subtask)
        }
        
        return ExecutionPlan(
            goal = goal,
            rootTask = rootTask,
            estimatedSteps = strategy.steps.size
        )
    }
    
    /**
     * 使用 AI 进行规划
     */
    private suspend fun planWithAI(goal: Goal, context: PlanningContext): ExecutionPlan {
        val prompt = buildPlanPrompt(goal, context)
        val messages = listOf(
            Message("system", SYSTEM_PROMPT),
            Message("user", prompt)
        )
        
        try {
            val response = aiClient.chat(messages)
            val plan = parseAIPlan(response, goal)
            
            if (plan != null) {
                Log.d(TAG, "AI 规划成功: ${plan.estimatedSteps} 步")
                return plan
            }
        } catch (e: Exception) {
            Log.e(TAG, "AI 规划失败", e)
        }
        
        // 回退到简单计划
        return createFallbackPlan(goal)
    }
    
    private fun buildPlanPrompt(goal: Goal, context: PlanningContext): String {
        return """
目标：${goal.description}

当前状态：
- 当前 App: ${context.currentScreen.appPackage ?: "未知"}
- 当前页面: ${context.currentScreen.activityName ?: "未知"}
- 屏幕内容: ${context.currentScreen.visibleTexts.take(10).joinToString(", ")}
- 可点击元素: ${context.currentScreen.clickableElements.take(10).joinToString(", ")}
- 有弹窗: ${context.currentScreen.hasDialog}

可用工具：
- tap(x, y): 点击坐标
- tap_element(text): 点击包含文本的元素
- swipe(direction, distance): 滑动 (up/down/left/right, short/medium/long)
- input_text(text): 输入文本
- press_key(key): 按键 (back/home/enter)
- wait(ms): 等待

请分解这个目标为具体的执行步骤，返回 JSON 格式：
{
  "analysis": "对目标的理解和分析",
  "steps": [
    {
      "description": "步骤描述",
      "type": "navigation|interaction|verification|wait",
      "action": {
        "tool": "工具名",
        "params": {"参数": "值"}
      },
      "precondition": "执行前需满足的条件（可选）",
      "subtasks": [] // 如果需要进一步分解
    }
  ],
  "estimatedSteps": 5,
  "risks": ["可能的风险"]
}
""".trimIndent()
    }
    
    private fun buildReplanPrompt(plan: ExecutionPlan, feedback: ExecutionFeedback): String {
        return """
原计划执行失败，需要重新规划。

原目标：${plan.goal.description}

失败信息：
- 任务: ${feedback.taskId}
- 错误: ${feedback.error}
- 当前屏幕: ${feedback.currentScreen.summary}

请根据当前屏幕状态，重新规划后续步骤。

返回相同的 JSON 格式。
""".trimIndent()
    }
    
    private fun parseAIPlan(response: String, goal: Goal): ExecutionPlan? {
        return try {
            // 提取 JSON
            val jsonStart = response.indexOf("{")
            val jsonEnd = response.lastIndexOf("}") + 1
            if (jsonStart == -1 || jsonEnd <= jsonStart) return null
            
            val json = response.substring(jsonStart, jsonEnd)
            val aiPlan = gson.fromJson(json, AIPlanResponse::class.java)
            
            // 转换为 ExecutionPlan
            val rootTask = Task(
                description = goal.description,
                type = TaskType.COMPOSITE
            )
            
            aiPlan.steps.forEachIndexed { index, step ->
                val task = convertToTask(step, index)
                rootTask.addSubtask(task)
            }
            
            ExecutionPlan(
                goal = goal,
                rootTask = rootTask,
                estimatedSteps = aiPlan.estimatedSteps ?: aiPlan.steps.size
            )
        } catch (e: Exception) {
            Log.e(TAG, "解析 AI 计划失败", e)
            null
        }
    }
    
    private fun convertToTask(step: AIStepResponse, index: Int): Task {
        val type = when (step.type?.lowercase()) {
            "navigation" -> TaskType.NAVIGATION
            "interaction" -> TaskType.INTERACTION
            "verification" -> TaskType.VERIFICATION
            "wait" -> TaskType.WAIT
            else -> TaskType.INTERACTION
        }
        
        val action = step.action?.let { a ->
            TaskAction(
                toolName = a.tool ?: "",
                parameters = a.params ?: emptyMap(),
                description = step.description ?: ""
            )
        }
        
        val precondition = step.precondition?.let {
            TaskCondition(
                type = ConditionType.SCREEN_CONTAINS,
                expression = it
            )
        }
        
        val task = Task(
            description = step.description ?: "步骤 ${index + 1}",
            type = type,
            priority = index,
            action = action,
            precondition = precondition
        )
        
        // 处理子任务
        step.subtasks?.forEachIndexed { subIndex, subStep ->
            task.addSubtask(convertToTask(subStep, subIndex))
        }
        
        return task
    }
    
    private fun parseStepToTask(step: String, index: Int): Task {
        // 简单解析学习到的步骤
        // 格式如: "tap_element(微信)" 或 "swipe(up, medium)"
        val toolMatch = Regex("(\\w+)\\((.*)\\)").find(step)
        
        return if (toolMatch != null) {
            val (tool, paramsStr) = toolMatch.destructured
            val params = paramsStr.split(",")
                .mapIndexed { i, v -> "param$i" to v.trim() }
                .toMap()
            
            Task(
                description = step,
                type = TaskType.INTERACTION,
                priority = index,
                action = TaskAction(tool, params, step)
            )
        } else {
            Task(
                description = step,
                type = TaskType.INTERACTION,
                priority = index
            )
        }
    }
    
    private fun createFallbackPlan(goal: Goal): ExecutionPlan {
        // 简单回退计划：让 AI 逐步决策
        val rootTask = Task(
            description = goal.description,
            type = TaskType.COMPOSITE
        ).addSubtask(
            Task(
                description = "执行目标: ${goal.description}",
                type = TaskType.INTERACTION,
                metadata = mapOf("fallback" to true)
            )
        )
        
        return ExecutionPlan(
            goal = goal,
            rootTask = rootTask,
            estimatedSteps = goal.maxSteps
        )
    }
    
    companion object {
        private const val SYSTEM_PROMPT = """
你是一个 Android 手机自动化专家。你的任务是将用户的目标分解为具体的执行步骤。

规划原则：
1. 每个步骤应该是原子性的（一个动作）
2. 优先使用 tap_element 而非坐标点击
3. 考虑可能的异常情况（弹窗、加载中）
4. 如果需要等待，明确等待时间
5. 验证步骤确保操作成功

返回严格的 JSON 格式，不要包含注释。
"""
    }
}

// ============ AI 响应数据模型 ============

data class AIPlanResponse(
    val analysis: String?,
    val steps: List<AIStepResponse>,
    val estimatedSteps: Int?,
    val risks: List<String>?
)

data class AIStepResponse(
    val description: String?,
    val type: String?,
    val action: AIActionResponse?,
    val precondition: String?,
    val subtasks: List<AIStepResponse>?
)

data class AIActionResponse(
    val tool: String?,
    val params: Map<String, Any>?
)
