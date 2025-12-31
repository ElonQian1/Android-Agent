// application/executor/ScriptExecutor.kt
// module: application/executor | layer: application | role: script-executor
// summary: 脚本执行器 - 支持多种执行模式的统一执行入口

package com.employee.agent.application.executor

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.util.Log
import com.employee.agent.application.AIClient
import com.employee.agent.application.Message
import com.employee.agent.application.StepResult
import com.employee.agent.domain.execution.ExecutionConfig
import com.employee.agent.domain.execution.ExecutionMode
import com.employee.agent.domain.script.Script
import com.employee.agent.domain.script.ScriptStep
import com.employee.agent.infrastructure.popup.PopupDismisser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * 🎮 脚本执行器
 * 
 * 统一的脚本执行入口，支持四种执行模式：
 * - FAST：极速模式，纯脚本执行
 * - SMART：智能模式，规则库+异常AI恢复
 * - MONITOR：监控模式，每步AI验证
 * - AGENT：代理模式，AI全程控制
 */
class ScriptExecutor(
    private val service: AccessibilityService,
    private val aiClient: AIClient,
    private val stepExecutor: StepExecutorInterface,
    private val screenshotProvider: ScreenshotProvider? = null
) {
    companion object {
        private const val TAG = "ScriptExecutor"
    }
    
    /** 弹窗清理器 */
    private val popupDismisser = PopupDismisser(service)
    
    /** 执行进度回调 */
    var onProgress: ((current: Int, total: Int, description: String) -> Unit)? = null
    
    /** 步骤开始回调 */
    var onStepStart: ((stepNum: Int, type: String, description: String) -> Unit)? = null
    
    /** 步骤完成回调 */
    var onStepComplete: ((stepNum: Int, success: Boolean, error: String?) -> Unit)? = null
    
    /** AI 介入回调 */
    var onAIIntervention: ((reason: String, action: String) -> Unit)? = null
    
    /** 弹窗清理回调 */
    var onPopupDismissed: ((popupType: String) -> Unit)? = null
    
    /**
     * 🎯 执行脚本
     * 
     * @param script 要执行的脚本
     * @param config 执行配置（包含执行模式）
     */
    suspend fun execute(
        script: Script,
        config: ExecutionConfig = ExecutionConfig.SMART_DEFAULT
    ): ExecutionResult = withContext(Dispatchers.IO) {
        Log.i(TAG, "▶️ 开始执行脚本: ${script.name} [模式: ${config.mode.displayName}]")
        
        return@withContext when (config.mode) {
            ExecutionMode.FAST -> executeFastMode(script, config)
            ExecutionMode.SMART -> executeSmartMode(script, config)
            ExecutionMode.MONITOR -> executeMonitorMode(script, config)
            ExecutionMode.AGENT -> executeAgentMode(script, config)
        }
    }
    
    // ==================== 极速模式 ====================
    
    /**
     * 🚀 极速模式执行
     * 纯脚本执行，不做任何额外检测
     */
    private suspend fun executeFastMode(
        script: Script,
        config: ExecutionConfig
    ): ExecutionResult {
        Log.d(TAG, "🚀 极速模式执行中...")
        
        val logs = mutableListOf<String>()
        val extractedData = mutableMapOf<String, Any>()
        
        for ((index, step) in script.steps.withIndex()) {
            val stepNum = index + 1
            onProgress?.invoke(stepNum, script.steps.size, step.description)
            onStepStart?.invoke(stepNum, step.type.name, step.description)
            
            val result = executeStepWithRetry(step, config.maxRetries, extractedData)
            
            if (result.success) {
                result.data?.let { extractedData.putAll(it) }
                logs.add("✅ 步骤 $stepNum 成功")
                onStepComplete?.invoke(stepNum, true, null)
            } else {
                logs.add("❌ 步骤 $stepNum 失败: ${result.error}")
                onStepComplete?.invoke(stepNum, false, result.error)
                
                return ExecutionResult(
                    success = false,
                    stepsExecuted = index,
                    totalSteps = script.steps.size,
                    error = "步骤 $stepNum 失败: ${result.error}",
                    failedStepIndex = index,
                    logs = logs,
                    extractedData = extractedData,
                    aiInterventions = 0,
                    popupsDismissed = 0
                )
            }
            
            delay(config.stepDelayMs)
        }
        
        return ExecutionResult(
            success = true,
            stepsExecuted = script.steps.size,
            totalSteps = script.steps.size,
            logs = logs,
            extractedData = extractedData,
            aiInterventions = 0,
            popupsDismissed = 0
        )
    }
    
    // ==================== 智能模式 ====================
    
    /**
     * 🛡️ 智能模式执行
     * 规则库清理弹窗 + 异常时 AI 恢复
     */
    private suspend fun executeSmartMode(
        script: Script,
        config: ExecutionConfig
    ): ExecutionResult {
        Log.d(TAG, "🛡️ 智能模式执行中...")
        
        val logs = mutableListOf<String>()
        val extractedData = mutableMapOf<String, Any>()
        var aiInterventions = 0
        var popupsDismissed = 0
        
        for ((index, step) in script.steps.withIndex()) {
            val stepNum = index + 1
            onProgress?.invoke(stepNum, script.steps.size, step.description)
            onStepStart?.invoke(stepNum, step.type.name, step.description)
            
            // 🛡️ 步骤前：清理弹窗
            if (config.popupDismissEnabled) {
                val dismissResult = popupDismisser.dismissAllPopups(
                    maxAttempts = 3,
                    delayMs = config.popupDismissDelayMs
                )
                if (dismissResult.popupsCleared > 0) {
                    popupsDismissed += dismissResult.popupsCleared
                    logs.add("🛡️ 清理了 ${dismissResult.popupsCleared} 个弹窗")
                    onPopupDismissed?.invoke("auto")
                }
            }
            
            // 执行步骤
            var result = executeStepWithRetry(step, config.maxRetries, extractedData)
            
            // ⚠️ 步骤失败：尝试 AI 恢复
            if (!result.success && config.aiRecoveryEnabled) {
                Log.w(TAG, "⚠️ 步骤 $stepNum 失败，尝试 AI 恢复...")
                onAIIntervention?.invoke("步骤失败", "分析原因")
                
                val recoveryResult = attemptAIRecovery(step, result.error ?: "未知错误")
                aiInterventions++
                
                if (recoveryResult.shouldRetry) {
                    logs.add("🤖 AI 建议: ${recoveryResult.suggestion}")
                    
                    // 执行 AI 建议的恢复操作
                    if (recoveryResult.recoveryAction != null) {
                        executeRecoveryAction(recoveryResult.recoveryAction)
                        delay(500)
                    }
                    
                    // 重试步骤
                    result = executeStepWithRetry(step, 1, extractedData)
                }
            }
            
            if (result.success) {
                result.data?.let { extractedData.putAll(it) }
                logs.add("✅ 步骤 $stepNum 成功")
                onStepComplete?.invoke(stepNum, true, null)
            } else {
                logs.add("❌ 步骤 $stepNum 失败: ${result.error}")
                onStepComplete?.invoke(stepNum, false, result.error)
                
                return ExecutionResult(
                    success = false,
                    stepsExecuted = index,
                    totalSteps = script.steps.size,
                    error = "步骤 $stepNum 失败: ${result.error}",
                    failedStepIndex = index,
                    logs = logs,
                    extractedData = extractedData,
                    aiInterventions = aiInterventions,
                    popupsDismissed = popupsDismissed
                )
            }
            
            delay(config.stepDelayMs)
        }
        
        return ExecutionResult(
            success = true,
            stepsExecuted = script.steps.size,
            totalSteps = script.steps.size,
            logs = logs,
            extractedData = extractedData,
            aiInterventions = aiInterventions,
            popupsDismissed = popupsDismissed
        )
    }
    
    // ==================== 监控模式 ====================
    
    /**
     * 👁️ 监控模式执行
     * 每步执行后 AI 验证
     */
    private suspend fun executeMonitorMode(
        script: Script,
        config: ExecutionConfig
    ): ExecutionResult {
        Log.d(TAG, "👁️ 监控模式执行中...")
        
        val logs = mutableListOf<String>()
        val extractedData = mutableMapOf<String, Any>()
        var aiInterventions = 0
        var popupsDismissed = 0
        
        for ((index, step) in script.steps.withIndex()) {
            val stepNum = index + 1
            onProgress?.invoke(stepNum, script.steps.size, step.description)
            onStepStart?.invoke(stepNum, step.type.name, step.description)
            
            // 🛡️ 清理弹窗
            if (config.popupDismissEnabled) {
                val dismissResult = popupDismisser.dismissAllPopups()
                popupsDismissed += dismissResult.popupsCleared
            }
            
            // 执行步骤
            val result = executeStepWithRetry(step, config.maxRetries, extractedData)
            
            if (!result.success) {
                logs.add("❌ 步骤 $stepNum 执行失败")
                onStepComplete?.invoke(stepNum, false, result.error)
                
                return ExecutionResult(
                    success = false,
                    stepsExecuted = index,
                    totalSteps = script.steps.size,
                    error = "步骤 $stepNum 失败",
                    failedStepIndex = index,
                    logs = logs,
                    extractedData = extractedData,
                    aiInterventions = aiInterventions,
                    popupsDismissed = popupsDismissed
                )
            }
            
            // 👁️ AI 验证步骤结果
            onAIIntervention?.invoke("步骤完成", "验证结果")
            val verification = verifyStepWithAI(step, stepNum, script.steps.size)
            aiInterventions++
            
            if (verification.isCorrect) {
                result.data?.let { extractedData.putAll(it) }
                logs.add("✅ 步骤 $stepNum 成功 (AI验证: ${verification.confidence})")
                onStepComplete?.invoke(stepNum, true, null)
            } else if (verification.confidence >= config.aiVerifyThreshold) {
                // 置信度足够，继续执行
                result.data?.let { extractedData.putAll(it) }
                logs.add("⚠️ 步骤 $stepNum 完成 (AI验证置信度: ${verification.confidence})")
                onStepComplete?.invoke(stepNum, true, null)
            } else {
                // 置信度不足，可能出错了
                logs.add("❌ 步骤 $stepNum AI验证失败: ${verification.reason}")
                
                // 尝试恢复
                if (config.aiRecoveryEnabled && verification.suggestion != null) {
                    logs.add("🤖 尝试恢复: ${verification.suggestion}")
                    // TODO: 执行恢复操作
                }
                
                onStepComplete?.invoke(stepNum, false, verification.reason)
            }
            
            delay(config.stepDelayMs)
        }
        
        return ExecutionResult(
            success = true,
            stepsExecuted = script.steps.size,
            totalSteps = script.steps.size,
            logs = logs,
            extractedData = extractedData,
            aiInterventions = aiInterventions,
            popupsDismissed = popupsDismissed
        )
    }
    
    // ==================== 代理模式 ====================
    
    /**
     * 🤖 代理模式执行
     * AI 全程决策控制
     */
    private suspend fun executeAgentMode(
        script: Script,
        config: ExecutionConfig
    ): ExecutionResult {
        Log.d(TAG, "🤖 代理模式执行中...")
        
        val logs = mutableListOf<String>()
        val extractedData = mutableMapOf<String, Any>()
        var aiInterventions = 0
        var popupsDismissed = 0
        var stepIndex = 0
        
        while (stepIndex < script.steps.size) {
            val step = script.steps[stepIndex]
            val stepNum = stepIndex + 1
            
            onProgress?.invoke(stepNum, script.steps.size, step.description)
            onAIIntervention?.invoke("分析屏幕", "决定下一步")
            
            // 🤖 AI 分析当前屏幕，决定下一步
            val decision = askAIForNextAction(script.goal, step, stepIndex, script.steps.size)
            aiInterventions++
            
            when (decision.action) {
                AgentAction.EXECUTE_STEP -> {
                    // 执行当前步骤
                    onStepStart?.invoke(stepNum, step.type.name, step.description)
                    
                    // 清理弹窗
                    if (config.popupDismissEnabled) {
                        val dismissResult = popupDismisser.dismissAllPopups()
                        popupsDismissed += dismissResult.popupsCleared
                    }
                    
                    val result = executeStepWithRetry(step, config.maxRetries, extractedData)
                    
                    if (result.success) {
                        result.data?.let { extractedData.putAll(it) }
                        logs.add("✅ 步骤 $stepNum 成功")
                        onStepComplete?.invoke(stepNum, true, null)
                        stepIndex++
                    } else {
                        logs.add("⚠️ 步骤 $stepNum 失败，AI 将重新评估")
                        // 不立即失败，让 AI 重新决策
                    }
                }
                
                AgentAction.SKIP_STEP -> {
                    logs.add("⏭️ AI 决定跳过步骤 $stepNum: ${decision.reason}")
                    stepIndex++
                }
                
                AgentAction.CUSTOM_ACTION -> {
                    logs.add("🤖 AI 执行自定义操作: ${decision.customAction}")
                    // TODO: 执行 AI 建议的自定义操作
                    decision.customAction?.let { executeCustomAction(it) }
                }
                
                AgentAction.ABORT -> {
                    logs.add("🛑 AI 决定终止执行: ${decision.reason}")
                    return ExecutionResult(
                        success = false,
                        stepsExecuted = stepIndex,
                        totalSteps = script.steps.size,
                        error = "AI 终止: ${decision.reason}",
                        failedStepIndex = stepIndex,
                        logs = logs,
                        extractedData = extractedData,
                        aiInterventions = aiInterventions,
                        popupsDismissed = popupsDismissed
                    )
                }
                
                AgentAction.GOAL_ACHIEVED -> {
                    logs.add("🎉 AI 判断目标已达成")
                    return ExecutionResult(
                        success = true,
                        stepsExecuted = stepIndex,
                        totalSteps = script.steps.size,
                        logs = logs,
                        extractedData = extractedData,
                        aiInterventions = aiInterventions,
                        popupsDismissed = popupsDismissed
                    )
                }
            }
            
            delay(config.stepDelayMs)
        }
        
        return ExecutionResult(
            success = true,
            stepsExecuted = script.steps.size,
            totalSteps = script.steps.size,
            logs = logs,
            extractedData = extractedData,
            aiInterventions = aiInterventions,
            popupsDismissed = popupsDismissed
        )
    }
    
    // ==================== 辅助方法 ====================
    
    /**
     * 执行步骤（带重试）
     */
    private suspend fun executeStepWithRetry(
        step: ScriptStep,
        maxRetries: Int,
        context: Map<String, Any>
    ): StepResult {
        var lastResult: StepResult? = null
        
        repeat(maxRetries + 1) { attempt ->
            val result = stepExecutor.executeStep(step, context)
            lastResult = result
            
            if (result.success) {
                return result
            }
            
            if (attempt < maxRetries) {
                delay(1000)
            }
        }
        
        return lastResult ?: StepResult(false, "No result")
    }
    
    /**
     * 尝试 AI 恢复
     */
    private suspend fun attemptAIRecovery(step: ScriptStep, error: String): RecoveryResult {
        return try {
            val screenshot = screenshotProvider?.takeScreenshot()
            val screenshotDesc = if (screenshot != null) "（已附截图）" else "（无截图）"
            
            val prompt = """
你是手机自动化助手。当前步骤执行失败，请分析原因并给出恢复建议。

## 失败的步骤
类型: ${step.type}
描述: ${step.description}
参数: ${step.params}

## 错误信息
$error

## 屏幕状态
$screenshotDesc

请分析：
1. 可能的失败原因
2. 是否需要重试
3. 恢复操作建议

返回 JSON:
{
  "reason": "失败原因分析",
  "shouldRetry": true/false,
  "recoveryAction": "恢复操作（如：关闭弹窗、等待加载等）",
  "suggestion": "给用户的建议"
}
""".trimIndent()
            
            val response = aiClient.chat(listOf(Message("user", prompt)))
            parseRecoveryResult(response)
        } catch (e: Exception) {
            Log.e(TAG, "AI 恢复分析失败: ${e.message}")
            RecoveryResult(shouldRetry = false, suggestion = "AI 分析失败")
        }
    }
    
    /**
     * AI 验证步骤结果
     */
    private suspend fun verifyStepWithAI(
        step: ScriptStep,
        currentStep: Int,
        totalSteps: Int
    ): VerificationResult {
        return try {
            val prompt = """
你是手机自动化验证助手。请验证刚才执行的步骤是否成功。

## 执行的步骤
步骤 $currentStep/$totalSteps
类型: ${step.type}
描述: ${step.description}

请判断步骤是否成功执行，返回 JSON:
{
  "isCorrect": true/false,
  "confidence": 0.0-1.0,
  "reason": "判断理由",
  "suggestion": "如果失败，恢复建议"
}
""".trimIndent()
            
            val response = aiClient.chat(listOf(Message("user", prompt)))
            parseVerificationResult(response)
        } catch (e: Exception) {
            Log.e(TAG, "AI 验证失败: ${e.message}")
            VerificationResult(isCorrect = true, confidence = 0.5f, reason = "AI 验证异常，默认通过")
        }
    }
    
    /**
     * AI 决定下一步操作（代理模式）
     */
    private suspend fun askAIForNextAction(
        goal: String,
        currentStep: ScriptStep,
        stepIndex: Int,
        totalSteps: Int
    ): AgentDecision {
        return try {
            val prompt = """
你是手机自动化 AI 代理。根据当前状态决定下一步操作。

## 任务目标
$goal

## 当前进度
步骤 ${stepIndex + 1}/$totalSteps

## 计划的下一步
类型: ${currentStep.type}
描述: ${currentStep.description}

请决定:
1. EXECUTE_STEP - 执行计划的步骤
2. SKIP_STEP - 跳过这个步骤
3. CUSTOM_ACTION - 执行自定义操作
4. ABORT - 终止执行
5. GOAL_ACHIEVED - 目标已达成

返回 JSON:
{
  "action": "EXECUTE_STEP/SKIP_STEP/CUSTOM_ACTION/ABORT/GOAL_ACHIEVED",
  "reason": "决策理由",
  "customAction": "如果是CUSTOM_ACTION，具体操作描述"
}
""".trimIndent()
            
            val response = aiClient.chat(listOf(Message("user", prompt)))
            parseAgentDecision(response)
        } catch (e: Exception) {
            Log.e(TAG, "AI 决策失败: ${e.message}")
            AgentDecision(AgentAction.EXECUTE_STEP, "AI 异常，默认执行")
        }
    }
    
    private fun executeRecoveryAction(action: String) {
        Log.d(TAG, "执行恢复操作: $action")
        // TODO: 解析并执行恢复操作
    }
    
    private fun executeCustomAction(action: String) {
        Log.d(TAG, "执行自定义操作: $action")
        // TODO: 解析并执行自定义操作
    }
    
    // ==================== 解析方法 ====================
    
    private fun parseRecoveryResult(response: String): RecoveryResult {
        return try {
            val json = extractJson(response)
            val map = com.google.gson.Gson().fromJson(json, Map::class.java)
            RecoveryResult(
                shouldRetry = map["shouldRetry"] as? Boolean ?: false,
                recoveryAction = map["recoveryAction"] as? String,
                suggestion = map["suggestion"] as? String ?: ""
            )
        } catch (e: Exception) {
            RecoveryResult(shouldRetry = false, suggestion = "解析失败")
        }
    }
    
    private fun parseVerificationResult(response: String): VerificationResult {
        return try {
            val json = extractJson(response)
            val map = com.google.gson.Gson().fromJson(json, Map::class.java)
            VerificationResult(
                isCorrect = map["isCorrect"] as? Boolean ?: true,
                confidence = (map["confidence"] as? Number)?.toFloat() ?: 0.8f,
                reason = map["reason"] as? String ?: "",
                suggestion = map["suggestion"] as? String
            )
        } catch (e: Exception) {
            VerificationResult(isCorrect = true, confidence = 0.5f, reason = "解析失败")
        }
    }
    
    private fun parseAgentDecision(response: String): AgentDecision {
        return try {
            val json = extractJson(response)
            val map = com.google.gson.Gson().fromJson(json, Map::class.java)
            val actionStr = map["action"] as? String ?: "EXECUTE_STEP"
            AgentDecision(
                action = AgentAction.valueOf(actionStr),
                reason = map["reason"] as? String ?: "",
                customAction = map["customAction"] as? String
            )
        } catch (e: Exception) {
            AgentDecision(AgentAction.EXECUTE_STEP, "解析失败，默认执行")
        }
    }
    
    private fun extractJson(text: String): String {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        return if (start >= 0 && end > start) {
            text.substring(start, end + 1)
        } else {
            "{}"
        }
    }
}

// ==================== 数据类 ====================

/**
 * 执行结果
 */
data class ExecutionResult(
    val success: Boolean,
    val stepsExecuted: Int,
    val totalSteps: Int,
    val error: String? = null,
    val failedStepIndex: Int? = null,
    val logs: List<String> = emptyList(),
    val extractedData: Map<String, Any> = emptyMap(),
    /** AI 介入次数 */
    val aiInterventions: Int = 0,
    /** 清理的弹窗数量 */
    val popupsDismissed: Int = 0
)

/**
 * 恢复结果
 */
data class RecoveryResult(
    val shouldRetry: Boolean,
    val recoveryAction: String? = null,
    val suggestion: String
)

/**
 * 验证结果
 */
data class VerificationResult(
    val isCorrect: Boolean,
    val confidence: Float,
    val reason: String,
    val suggestion: String? = null
)

/**
 * AI 代理动作
 */
enum class AgentAction {
    EXECUTE_STEP,   // 执行当前步骤
    SKIP_STEP,      // 跳过当前步骤
    CUSTOM_ACTION,  // 执行自定义操作
    ABORT,          // 终止执行
    GOAL_ACHIEVED   // 目标已达成
}

/**
 * AI 代理决策
 */
data class AgentDecision(
    val action: AgentAction,
    val reason: String,
    val customAction: String? = null
)

/**
 * 步骤执行器接口
 */
interface StepExecutorInterface {
    suspend fun executeStep(step: ScriptStep, context: Map<String, Any>): StepResult
}

/**
 * 截图提供者接口
 */
interface ScreenshotProvider {
    fun takeScreenshot(): Bitmap?
}
