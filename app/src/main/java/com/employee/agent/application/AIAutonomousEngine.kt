// application/AIAutonomousEngine.kt
// module: application | layer: application | role: ai-autonomous-engine
// summary: AI 驱动的自主执行引擎 - 分析屏幕、生成脚本、执行、纠错的完整闭环

package com.employee.agent.application

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.employee.agent.domain.screen.UINode
import com.employee.agent.infrastructure.ai.HunyuanAIClient
import com.employee.agent.infrastructure.vision.ScreenAnalyzer
import com.employee.agent.infrastructure.vision.ScreenAnalysis
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

/**
 * AI 自主执行引擎
 * 
 * 完整闭环：分析 → 规划 → 执行 → 观察 → 纠错
 */
class AIAutonomousEngine(
    private val service: AccessibilityService,
    private val apiKey: String
) {
    companion object {
        private const val TAG = "AIAutonomousEngine"
        private const val MAX_RETRIES = 3
        private const val MAX_STEPS = 20
        private const val STEP_DELAY_MS = 1500L
    }
    
    private val aiClient = HunyuanAIClient(apiKey)
    private val screenAnalyzer = ScreenAnalyzer()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // 执行状态
    private var isRunning = false
    private var currentGoal: String = ""
    private var executionLog = mutableListOf<ExecutionLogEntry>()
    
    data class ExecutionLogEntry(
        val timestamp: Long,
        val type: String,  // THINK, ACTION, OBSERVE, ERROR, SUCCESS
        val content: String
    )
    
    data class ExecutionResult(
        val success: Boolean,
        val message: String,
        val stepsExecuted: Int,
        val logs: List<ExecutionLogEntry>
    )
    
    /**
     * 开始执行目标 (主入口)
     */
    suspend fun executeGoal(goal: String): ExecutionResult = withContext(Dispatchers.IO) {
        if (isRunning) {
            return@withContext ExecutionResult(false, "已有任务在执行中", 0, emptyList())
        }
        
        isRunning = true
        currentGoal = goal
        executionLog.clear()
        
        log("THINK", "🎯 开始执行目标: $goal")
        
        var stepCount = 0
        var retryCount = 0
        var lastError: String? = null
        
        try {
            while (isRunning && stepCount < MAX_STEPS) {
                stepCount++
                log("THINK", "📍 步骤 $stepCount/$MAX_STEPS")
                
                // 1. 观察当前屏幕
                val screenState = observeScreen()
                if (screenState == null) {
                    log("ERROR", "无法读取屏幕状态")
                    delay(1000)
                    continue
                }
                
                // 2. 让 AI 决定下一步动作
                val aiDecision = askAIForNextAction(
                    goal = goal,
                    screenAnalysis = screenState,
                    previousError = lastError,
                    stepCount = stepCount
                )
                
                log("THINK", "🤖 AI 决策: ${aiDecision.thought}")
                
                // 3. 检查是否完成
                if (aiDecision.isComplete) {
                    log("SUCCESS", "✅ 目标完成: ${aiDecision.thought}")
                    return@withContext ExecutionResult(true, aiDecision.thought, stepCount, executionLog.toList())
                }
                
                // 4. 执行动作
                if (aiDecision.action != null) {
                    log("ACTION", "▶️ 执行: ${aiDecision.action.type} - ${aiDecision.action.description}")
                    
                    val actionResult = executeAction(aiDecision.action, screenState)
                    
                    if (actionResult.success) {
                        log("OBSERVE", "✓ 动作成功")
                        lastError = null
                        retryCount = 0
                    } else {
                        log("ERROR", "✗ 动作失败: ${actionResult.error}")
                        lastError = actionResult.error
                        retryCount++
                        
                        if (retryCount >= MAX_RETRIES) {
                            log("ERROR", "❌ 连续失败 $MAX_RETRIES 次，停止执行")
                            return@withContext ExecutionResult(
                                false, 
                                "执行失败: $lastError", 
                                stepCount, 
                                executionLog.toList()
                            )
                        }
                    }
                }
                
                // 等待页面响应
                delay(STEP_DELAY_MS)
            }
            
            log("ERROR", "⚠️ 达到最大步数限制")
            return@withContext ExecutionResult(false, "达到最大步数限制", stepCount, executionLog.toList())
            
        } catch (e: Exception) {
            log("ERROR", "💥 执行异常: ${e.message}")
            return@withContext ExecutionResult(false, "异常: ${e.message}", stepCount, executionLog.toList())
        } finally {
            isRunning = false
        }
    }
    
    /**
     * 停止执行
     */
    fun stop() {
        isRunning = false
        log("THINK", "🛑 用户停止执行")
    }
    
    /**
     * 观察屏幕状态
     */
    private fun observeScreen(): ScreenAnalysis? {
        return try {
            val root = service.rootInActiveWindow ?: return null
            val uiNode = convertToUINode(root)
            screenAnalyzer.analyze(uiNode)
        } catch (e: Exception) {
            Log.e(TAG, "观察屏幕失败", e)
            null
        }
    }
    
    /**
     * 询问 AI 下一步动作
     */
    private suspend fun askAIForNextAction(
        goal: String,
        screenAnalysis: ScreenAnalysis,
        previousError: String?,
        stepCount: Int
    ): AIDecision {
        val prompt = buildAIPrompt(goal, screenAnalysis, previousError, stepCount)
        
        return try {
            val response = aiClient.chat(listOf(
                Message(role = "system", content = getSystemPrompt()),
                Message(role = "user", content = prompt)
            ))
            
            parseAIResponse(response)
        } catch (e: Exception) {
            Log.e(TAG, "AI 调用失败", e)
            AIDecision(
                thought = "AI 调用失败: ${e.message}",
                isComplete = false,
                action = null
            )
        }
    }
    
    private fun getSystemPrompt(): String = """
你是一个精准的手机操作 Agent。你的任务是根据用户目标，在屏幕元素列表中找到最匹配的元素并执行操作。

## 可用动作
1. tap - 点击。优先用坐标 {"x":100,"y":200}，或用文本 {"text":"抖音"}
2. swipe - 滑动 {"direction":"up/down/left/right"}
3. wait - 等待 {"ms":2000}
4. back - 返回 {}

## 输出格式 (严格 JSON，不要 markdown)
{"thought":"分析","is_complete":false,"action":{"type":"tap","params":{"x":171,"y":710}}}

## 核心规则
1. **精确匹配**: 用户说"点击抖音"就找"抖音"，说"点击小红书"就找"小红书"，不要混淆！
2. **直接用坐标**: 每个元素后面有 @ 坐标(x,y)，直接用这个坐标点击
3. **找到就点**: 在元素列表中看到目标就立即点击，不要犹豫
4. **找不到就滑**: 如果列表中没有目标元素，用 swipe down 寻找
5. **简短回复**: thought 用一句话说明你要做什么

## 示例
目标: 点击抖音
元素列表有 "抖音" @ 坐标(171, 710)
正确输出: {"thought":"找到抖音，点击","is_complete":false,"action":{"type":"tap","params":{"x":171,"y":710}}}
""".trimIndent()

    private fun buildAIPrompt(
        goal: String,
        analysis: ScreenAnalysis,
        previousError: String?,
        stepCount: Int
    ): String = buildString {
        appendLine("## 目标: $goal")
        appendLine("步骤: $stepCount/$MAX_STEPS")
        
        if (previousError != null) {
            appendLine("⚠️ 上一步失败: $previousError")
        }
        
        appendLine()
        appendLine("## 屏幕元素列表")
        
        // 先检查是否有精确匹配的元素
        val goalKeywords = extractKeywords(goal)
        val matchedElements = analysis.interactiveElements.filter { elem ->
            goalKeywords.any { keyword -> 
                elem.text.contains(keyword, ignoreCase = true) 
            }
        }
        
        if (matchedElements.isNotEmpty()) {
            appendLine("🎯 **匹配到目标元素**:")
            matchedElements.forEach { elem ->
                val (cx, cy) = elem.bounds.centerX() to elem.bounds.centerY()
                appendLine("  ➡️ \"${elem.text}\" @ 坐标($cx, $cy) ← 点这个!")
            }
            appendLine()
        }
        
        appendLine("全部可点击元素:")
        analysis.interactiveElements.take(12).forEachIndexed { i, elem ->
            val (cx, cy) = elem.bounds.centerX() to elem.bounds.centerY()
            appendLine("${i+1}. \"${elem.text}\" @ ($cx, $cy)")
        }
        
        if (analysis.hotContent.isNotEmpty()) {
            appendLine()
            appendLine("🔥 热门内容:")
            analysis.hotContent.take(5).forEach { hot ->
                appendLine("- \"${hot.text}\" ${hot.value}赞")
            }
        }
        
        appendLine()
        if (matchedElements.isNotEmpty()) {
            appendLine("✅ 已找到目标，直接用坐标点击！")
        } else {
            appendLine("❌ 未找到目标元素，考虑滑动寻找")
        }
    }
    
    /**
     * 从目标中提取关键词
     */
    private fun extractKeywords(goal: String): List<String> {
        // 常见应用名和关键词映射
        val keywords = mutableListOf<String>()
        
        // 提取中文应用名
        val chineseApps = listOf("抖音", "小红书", "微信", "淘宝", "支付宝", "微博", "快手", "拼多多", "京东", "美团")
        chineseApps.forEach { app ->
            if (goal.contains(app)) keywords.add(app)
        }
        
        // 提取英文/拼音
        val englishPattern = "[a-zA-Z]+".toRegex()
        englishPattern.findAll(goal).forEach { match ->
            val word = match.value.lowercase()
            when {
                word.contains("douyin") -> keywords.add("抖音")
                word.contains("xiaohongshu") || word.contains("xhs") -> keywords.add("小红书")
                word.contains("wechat") || word.contains("weixin") -> keywords.add("微信")
                else -> keywords.add(match.value)
            }
        }
        
        // 如果没提取到，用原始目标作为关键词
        if (keywords.isEmpty()) {
            keywords.add(goal)
        }
        
        return keywords.distinct()
    }
    
    private fun parseAIResponse(response: String): AIDecision {
        return try {
            // 移除 markdown 代码块标记
            var cleanResponse = response
                .replace("```json", "")
                .replace("```", "")
                .trim()
            
            // 提取 JSON（AI 可能会在 JSON 前后加文字）
            val jsonStart = cleanResponse.indexOf('{')
            val jsonEnd = cleanResponse.lastIndexOf('}') + 1
            if (jsonStart < 0 || jsonEnd <= jsonStart) {
                throw Exception("无法找到 JSON")
            }
            
            val jsonStr = cleanResponse.substring(jsonStart, jsonEnd)
            
            // 尝试解析 JSON，如果失败则用正则提取
            val (thought, isComplete, action) = try {
                val json = JSONObject(jsonStr)
                Triple(
                    json.optString("thought", "无分析"),
                    json.optBoolean("is_complete", false),
                    extractActionFromJson(json)
                )
            } catch (jsonError: Exception) {
                Log.w(TAG, "标准 JSON 解析失败，尝试正则提取", jsonError)
                extractWithRegex(jsonStr)
            }
            
            AIDecision(thought, isComplete, action)
            
        } catch (e: Exception) {
            Log.e(TAG, "解析 AI 响应失败: $response", e)
            AIDecision(
                thought = "解析失败，尝试继续",
                isComplete = false,
                action = AIAction("swipe", "向下滑动寻找目标", JSONObject().put("direction", "up"))
            )
        }
    }
    
    private fun extractActionFromJson(json: JSONObject): AIAction? {
        return if (json.has("action") && !json.isNull("action")) {
            val actionJson = json.getJSONObject("action")
            val type = actionJson.getString("type")
            val params = if (actionJson.has("params")) actionJson.getJSONObject("params") else JSONObject()
            
            AIAction(
                type = type,
                description = "$type: ${params.toString()}",
                params = params
            )
        } else null
    }
    
    private fun extractWithRegex(jsonStr: String): Triple<String, Boolean, AIAction?> {
        // 用正则提取关键字段
        val isComplete = jsonStr.contains("\"is_complete\"\\s*:\\s*true".toRegex())
        
        // 提取 action type
        val typeMatch = "\"type\"\\s*:\\s*\"(\\w+)\"".toRegex().find(jsonStr)
        val actionType = typeMatch?.groupValues?.get(1)
        
        // 提取 direction (for swipe)
        val dirMatch = "\"direction\"\\s*:\\s*\"(\\w+)\"".toRegex().find(jsonStr)
        val direction = dirMatch?.groupValues?.get(1)
        
        // 提取 text (for tap)
        val textMatch = "\"text\"\\s*:\\s*\"([^\"]+)\"".toRegex().find(jsonStr)
        val text = textMatch?.groupValues?.get(1)
        
        // 提取 x, y 坐标
        val xMatch = "\"x\"\\s*:\\s*(\\d+)".toRegex().find(jsonStr)
        val yMatch = "\"y\"\\s*:\\s*(\\d+)".toRegex().find(jsonStr)
        val x = xMatch?.groupValues?.get(1)?.toIntOrNull()
        val y = yMatch?.groupValues?.get(1)?.toIntOrNull()
        
        val action = when (actionType) {
            "tap" -> {
                val params = JSONObject()
                if (!text.isNullOrBlank()) params.put("text", text)
                if (x != null && y != null) {
                    params.put("x", x)
                    params.put("y", y)
                }
                AIAction("tap", "tap via regex", params)
            }
            "swipe" -> {
                val params = JSONObject().put("direction", direction ?: "up")
                AIAction("swipe", "swipe via regex", params)
            }
            "wait" -> {
                val msMatch = "\"ms\"\\s*:\\s*(\\d+)".toRegex().find(jsonStr)
                val ms = msMatch?.groupValues?.get(1)?.toLongOrNull() ?: 1000
                AIAction("wait", "wait via regex", JSONObject().put("ms", ms))
            }
            "back" -> AIAction("back", "back via regex", JSONObject())
            else -> null
        }
        
        return Triple("(regex extracted)", isComplete, action)
    }
    
    /**
     * 执行动作
     */
    private suspend fun executeAction(action: AIAction, screenAnalysis: ScreenAnalysis): ActionResult {
        return try {
            when (action.type) {
                "tap" -> {
                    // 优先使用坐标（更精确），其次使用文本
                    val x = action.params.optInt("x", 0)
                    val y = action.params.optInt("y", 0)
                    
                    if (x > 0 && y > 0) {
                        // 有坐标，直接点击
                        Log.d(TAG, "[ACTION] 使用坐标点击: ($x, $y)")
                        performTap(x.toFloat(), y.toFloat())
                    } else {
                        // 没有坐标，尝试用文本查找
                        val text = action.params.optString("text", "")
                        if (text.isNotBlank()) {
                            performTapByText(text, screenAnalysis)
                        } else {
                            ActionResult(false, "没有提供有效的坐标或文本")
                        }
                    }
                }
                "tap_text" -> {
                    val text = action.params.optString("text", "")
                    performTapByText(text, screenAnalysis)
                }
                "swipe" -> {
                    val direction = action.params.optString("direction", "up")
                    performSwipe(direction)
                }
                "wait" -> {
                    val ms = action.params.optLong("ms", 1000)
                    delay(ms)
                    ActionResult(true, null)
                }
                "back" -> {
                    service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                    ActionResult(true, null)
                }
                else -> ActionResult(false, "未知动作类型: ${action.type}")
            }
        } catch (e: Exception) {
            ActionResult(false, e.message ?: "执行异常")
        }
    }
    
    private suspend fun performTap(x: Float, y: Float): ActionResult {
        Log.d(TAG, "[GESTURE] 执行点击手势: ($x, $y)")
        return suspendCancellableCoroutine { cont ->
            val path = Path().apply { moveTo(x, y) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 150))
                .build()
            
            val callback = object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    Log.d(TAG, "[GESTURE] ✅ 点击手势完成")
                    cont.resume(ActionResult(true, null)) {}
                }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    Log.w(TAG, "[GESTURE] ❌ 点击手势被取消")
                    cont.resume(ActionResult(false, "手势被取消")) {}
                }
            }
            
            val dispatched = service.dispatchGesture(gesture, callback, null)
            Log.d(TAG, "[GESTURE] dispatchGesture 返回: $dispatched")
            
            if (!dispatched) {
                Log.e(TAG, "[GESTURE] dispatchGesture 失败！服务可能没有 canPerformGestures 权限")
                cont.resume(ActionResult(false, "dispatchGesture 返回 false")) {}
            }
        }
    }
    
    private suspend fun performTapByText(text: String, analysis: ScreenAnalysis): ActionResult {
        // 在可交互元素中查找匹配文本
        val element = analysis.interactiveElements.find { 
            it.text.contains(text, ignoreCase = true) 
        }
        
        return if (element != null) {
            val cx = element.bounds.centerX().toFloat()
            val cy = element.bounds.centerY().toFloat()
            log("ACTION", "找到元素 \"${element.text}\" @ ($cx, $cy)")
            performTap(cx, cy)
        } else {
            ActionResult(false, "未找到包含 \"$text\" 的元素")
        }
    }
    
    private suspend fun performSwipe(direction: String): ActionResult {
        val displayMetrics = service.resources.displayMetrics
        val width = displayMetrics.widthPixels
        val height = displayMetrics.heightPixels
        
        val (startX, startY, endX, endY) = when (direction.lowercase()) {
            "up" -> listOf(width/2f, height*0.7f, width/2f, height*0.3f)
            "down" -> listOf(width/2f, height*0.3f, width/2f, height*0.7f)
            "left" -> listOf(width*0.8f, height/2f, width*0.2f, height/2f)
            "right" -> listOf(width*0.2f, height/2f, width*0.8f, height/2f)
            else -> return ActionResult(false, "未知滑动方向: $direction")
        }
        
        return suspendCancellableCoroutine { cont ->
            val path = Path().apply {
                moveTo(startX, startY)
                lineTo(endX, endY)
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 300))
                .build()
            
            service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    cont.resume(ActionResult(true, null)) {}
                }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    cont.resume(ActionResult(false, "滑动被取消")) {}
                }
            }, null)
        }
    }
    
    private fun convertToUINode(node: AccessibilityNodeInfo): UINode {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        
        val children = mutableListOf<UINode>()
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                children.add(convertToUINode(child))
            }
        }
        
        return UINode(
            className = node.className?.toString() ?: "",
            text = node.text?.toString(),
            contentDescription = node.contentDescription?.toString(),
            resourceId = node.viewIdResourceName,
            bounds = rect,
            isClickable = node.isClickable,
            isEnabled = node.isEnabled,
            isPassword = node.isPassword,
            children = children
        )
    }
    
    private fun log(type: String, content: String) {
        executionLog.add(ExecutionLogEntry(System.currentTimeMillis(), type, content))
        Log.d(TAG, "[$type] $content")
    }
    
    // 数据类
    data class AIDecision(
        val thought: String,
        val isComplete: Boolean,
        val action: AIAction?
    )
    
    data class AIAction(
        val type: String,
        val description: String,
        val params: JSONObject
    )
    
    data class ActionResult(
        val success: Boolean,
        val error: String?
    )
}
