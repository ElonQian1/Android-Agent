// src/application/ScriptEngine.kt
// module: script | layer: application | role: script-engine
// summary: 脚本引擎 - 负责脚本的生成、执行、存储和自我改进

package com.employee.agent.application

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.util.Log
import com.employee.agent.domain.script.*
import com.employee.agent.infrastructure.ai.HunyuanAIClient
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.*
import java.io.File
import java.util.UUID

/**
 * 🚀 脚本引擎
 * 核心功能：
 * 1. AI 生成脚本 - 根据目标自动生成可复用脚本
 * 2. 执行脚本 - 按步骤执行脚本
 * 3. 自我改进 - 执行失败时 AI 自动优化脚本
 * 4. 持久化 - 保存和加载脚本
 */
class ScriptEngine(
    private val service: AccessibilityService,
    private val apiKey: String
) {
    companion object {
        private const val TAG = "ScriptEngine"
        private const val SCRIPTS_DIR = "scripts"
        private const val MAX_IMPROVE_ATTEMPTS = 3
    }
    
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val aiClient = HunyuanAIClient(apiKey)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // 脚本缓存
    private val scriptsCache = mutableMapOf<String, Script>()
    
    // 执行日志回调
    var onLog: ((String) -> Unit)? = null
    
    /**
     * 🎯 根据目标生成脚本
     */
    suspend fun generateScript(goal: String): Result<Script> = withContext(Dispatchers.IO) {
        try {
            log("📝 开始为目标生成脚本: $goal")
            
            val prompt = buildScriptGenerationPrompt(goal)
            val messages = listOf(Message(role = "user", content = prompt))
            val response = aiClient.chat(messages)
            
            val script = parseScriptFromAI(response, goal)
            if (script != null) {
                saveScript(script)
                log("✅ 脚本生成成功: ${script.name} (${script.steps.size} 步骤)")
                Result.success(script)
            } else {
                log("❌ 脚本解析失败")
                Result.failure(Exception("Failed to parse script from AI response"))
            }
        } catch (e: Exception) {
            log("❌ 生成脚本失败: ${e.message}")
            Result.failure(e)
        }
    }
    
    /**
     * ▶️ 执行脚本
     */
    suspend fun executeScript(
        scriptId: String,
        onProgress: ((Int, Int, String) -> Unit)? = null
    ): ScriptExecutionResult = withContext(Dispatchers.IO) {
        val script = loadScript(scriptId)
        if (script == null) {
            return@withContext ScriptExecutionResult(
                success = false,
                stepsExecuted = 0,
                totalSteps = 0,
                error = "Script not found: $scriptId"
            )
        }
        
        executeScriptInternal(script, onProgress)
    }
    
    /**
     * 🔄 执行并自动改进脚本
     */
    suspend fun executeWithAutoImprove(
        scriptId: String,
        onProgress: ((Int, Int, String) -> Unit)? = null
    ): ScriptExecutionResult = withContext(Dispatchers.IO) {
        var script: Script = loadScript(scriptId) ?: return@withContext ScriptExecutionResult(
            success = false,
            stepsExecuted = 0,
            totalSteps = 0,
            error = "Script not found: $scriptId"
        )
        
        var attempts = 0
        var result: ScriptExecutionResult
        
        do {
            log("🔄 执行尝试 ${attempts + 1}/$MAX_IMPROVE_ATTEMPTS")
            result = executeScriptInternal(script, onProgress)
            
            if (result.success) {
                // 更新成功计数
                script = script.copy(successCount = script.successCount + 1)
                saveScript(script)
                break
            }
            
            // 执行失败，尝试改进脚本
            attempts++
            if (attempts < MAX_IMPROVE_ATTEMPTS) {
                log("⚠️ 执行失败，尝试 AI 改进脚本...")
                val improvedScript = improveScript(script, result)
                if (improvedScript != null) {
                    script = improvedScript
                    saveScript(script)
                    log("✨ 脚本已改进到版本 ${script.version}")
                } else {
                    log("❌ AI 无法改进脚本")
                    break
                }
            }
        } while (attempts < MAX_IMPROVE_ATTEMPTS)
        
        result
    }
    
    /**
     * 🔧 AI 改进脚本
     */
    suspend fun improveScript(script: Script, failResult: ScriptExecutionResult): Script? {
        return try {
            log("🔧 AI 正在分析失败原因并改进脚本...")
            
            val prompt = buildImprovementPrompt(script, failResult)
            val messages = listOf(Message(role = "user", content = prompt))
            val response = aiClient.chat(messages)
            
            val improvedSteps = parseImprovedSteps(response)
            if (improvedSteps != null) {
                val newVersion = incrementVersion(script.version)
                script.copy(
                    version = newVersion,
                    steps = improvedSteps,
                    failCount = script.failCount + 1
                )
            } else {
                null
            }
        } catch (e: Exception) {
            log("❌ 脚本改进失败: ${e.message}")
            null
        }
    }
    
    /**
     * 内部执行逻辑
     */
    private suspend fun executeScriptInternal(
        script: Script,
        onProgress: ((Int, Int, String) -> Unit)?
    ): ScriptExecutionResult {
        val logs = mutableListOf<String>()
        val extractedData = mutableMapOf<String, Any>()
        
        log("▶️ 开始执行脚本: ${script.name}")
        
        for ((index, step) in script.steps.withIndex()) {
            val stepNum = index + 1
            log("📍 步骤 $stepNum/${script.steps.size}: ${step.description}")
            onProgress?.invoke(stepNum, script.steps.size, step.description)
            
            var retries = 0
            var stepSuccess = false
            
            while (retries <= step.maxRetries && !stepSuccess) {
                try {
                    val stepResult = executeStep(step, extractedData)
                    if (stepResult.success) {
                        stepSuccess = true
                        stepResult.data?.let { extractedData.putAll(it) }
                        logs.add("✅ 步骤 $stepNum 成功")
                    } else {
                        retries++
                        if (retries <= step.maxRetries) {
                            log("⚠️ 步骤失败，重试 $retries/${step.maxRetries}")
                            delay(1000)
                        }
                    }
                } catch (e: Exception) {
                    retries++
                    logs.add("❌ 步骤 $stepNum 异常: ${e.message}")
                }
            }
            
            if (!stepSuccess) {
                return ScriptExecutionResult(
                    success = false,
                    stepsExecuted = index,
                    totalSteps = script.steps.size,
                    extractedData = extractedData,
                    error = "步骤 $stepNum 失败: ${step.description}",
                    failedStepIndex = index,
                    logs = logs
                )
            }
            
            // 步骤间延迟
            delay(500)
        }
        
        log("✅ 脚本执行完成!")
        return ScriptExecutionResult(
            success = true,
            stepsExecuted = script.steps.size,
            totalSteps = script.steps.size,
            extractedData = extractedData,
            logs = logs
        )
    }
    
    /**
     * 执行单个步骤
     */
    private suspend fun executeStep(
        step: ScriptStep,
        context: Map<String, Any>
    ): StepResult {
        return when (step.type) {
            StepType.LAUNCH_APP -> executeLaunchApp(step)
            StepType.TAP -> executeTap(step)
            StepType.SWIPE -> executeSwipe(step)
            StepType.WAIT -> executeWait(step)
            StepType.FIND_AND_TAP -> executeFindAndTap(step)
            StepType.SCROLL_UNTIL_FIND -> executeScrollUntilFind(step)
            StepType.EXTRACT_DATA -> executeExtractData(step)
            StepType.INPUT_TEXT -> executeInputText(step)
            StepType.BACK -> executeBack(step)
            StepType.ASSERT -> executeAssert(step)
            StepType.AI_DECIDE -> executeAIDecide(step)
            StepType.SEARCH -> executeSearch(step) // SEARCH 等同于 FIND_AND_TAP
            else -> StepResult(false, "Unsupported step type: ${step.type}")
        }
    }
    
    // ========== 步骤执行实现 ==========
    
    /**
     * 执行搜索步骤（等同于FIND_AND_TAP）
     */
    private suspend fun executeSearch(step: ScriptStep): StepResult {
        val text = step.params["text"] as? String
        val contains = step.params["contains"] as? String
        
        log("🔍 SEARCH: text=$text, contains=$contains")
        
        // 如果有text参数，先尝试点击搜索框然后输入
        if (text != null) {
            // 尝试找到并点击包含"搜索"的元素
            val root = service.rootInActiveWindow ?: return StepResult(false, "No window")
            val searchBox = findMatchingNodeEnhanced(root, null, "搜索", null)
            if (searchBox != null) {
                val rect = android.graphics.Rect()
                searchBox.getBoundsInScreen(rect)
                performTap(rect.centerX(), rect.centerY())
                delay(500)
                // TODO: 输入文本
            }
            return StepResult(true, "Search initiated")
        }
        
        // 如果有contains，当作FIND_AND_TAP处理
        if (contains != null) {
            return executeFindAndTap(step)
        }
        
        return StepResult(false, "Missing search parameters")
    }
    
    private suspend fun executeLaunchApp(step: ScriptStep): StepResult {
        val packageName = step.params["package"] as? String ?: return StepResult(false, "Missing package name")
        val goToHome = step.params["go_home"] as? Boolean ?: true // 默认回到首页
        
        try {
            val intent = service.packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                service.startActivity(intent)
                delay(2000) // 等待应用启动
                
                // 如果是小红书，自动点击"首页"按钮确保回到首页
                if (goToHome && packageName == "com.xingin.xhs") {
                    log("🏠 尝试回到首页...")
                    delay(500)
                    ensureXhsHomePage()
                }
                
                return StepResult(true)
            }
            return StepResult(false, "App not found: $packageName")
        } catch (e: Exception) {
            return StepResult(false, "Launch failed: ${e.message}")
        }
    }
    
    /**
     * 确保小红书在首页
     * 通过查找并点击底部导航栏的"首页"按钮
     */
    private suspend fun ensureXhsHomePage() {
        val root = service.rootInActiveWindow ?: return
        
        // 方法1: 查找底部导航栏的"首页"按钮
        val homeTab = findMatchingNodeEnhanced(root, "首页", null, null)
        if (homeTab != null) {
            log("🏠 找到首页按钮，点击回到首页")
            val rect = android.graphics.Rect()
            homeTab.getBoundsInScreen(rect)
            performTap(rect.centerX(), rect.centerY())
            delay(1000)
            return
        }
        
        // 方法2: 如果找不到首页按钮，尝试按返回键直到到达首页
        for (i in 0 until 3) {
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            delay(800)
            
            val root2 = service.rootInActiveWindow ?: continue
            val home2 = findMatchingNodeEnhanced(root2, "首页", null, null)
            if (home2 != null) {
                val rect = android.graphics.Rect()
                home2.getBoundsInScreen(rect)
                performTap(rect.centerX(), rect.centerY())
                delay(1000)
                log("🏠 已回到首页")
                return
            }
        }
        
        log("⚠️ 未能确保回到首页，可能已经在首页")
    }
    
    private suspend fun executeTap(step: ScriptStep): StepResult {
        val x = (step.params["x"] as? Number)?.toInt()
        val y = (step.params["y"] as? Number)?.toInt()
        val text = step.params["text"] as? String
        
        return if (x != null && y != null) {
            performTap(x, y)
        } else if (text != null) {
            findAndTapByText(text)
        } else {
            StepResult(false, "Missing tap coordinates or text")
        }
    }
    
    private suspend fun executeSwipe(step: ScriptStep): StepResult {
        val direction = step.params["direction"] as? String ?: "up"
        return performSwipe(direction)
    }
    
    private suspend fun executeWait(step: ScriptStep): StepResult {
        val ms = (step.params["ms"] as? Number)?.toLong() ?: 1000
        delay(ms)
        return StepResult(true)
    }
    
    private suspend fun executeFindAndTap(step: ScriptStep): StepResult {
        val text = step.params["text"] as? String
        val contains = step.params["contains"] as? String
        val pattern = step.params["pattern"] as? String
        
        log("🔍 FIND_AND_TAP: text=$text, contains=$contains, pattern=$pattern")
        
        val root = service.rootInActiveWindow ?: return StepResult(false, "No window")
        
        // 遍历查找匹配元素（使用增强版）
        val target = findMatchingNodeEnhanced(root, text, contains, pattern)
        if (target != null) {
            val rect = android.graphics.Rect()
            target.getBoundsInScreen(rect)
            log("✅ 找到元素，点击坐标: (${rect.centerX()}, ${rect.centerY()})")
            return performTap(rect.centerX(), rect.centerY())
        }
        
        return StepResult(false, "Element not found: text=$text, contains=$contains, pattern=$pattern")
    }
    
    private suspend fun executeScrollUntilFind(step: ScriptStep): StepResult {
        val text = step.params["text"] as? String
        val contains = step.params["contains"] as? String
        val pattern = step.params["pattern"] as? String
        val maxScrolls = (step.params["max_scrolls"] as? Number)?.toInt() ?: 10
        val direction = step.params["direction"] as? String ?: "up"
        val tapAfterFind = step.params["tap"] as? Boolean ?: true
        
        // 🆕 排除条件：避免匹配到直播等无效内容
        val excludes = step.params["excludes"] as? List<*> ?: emptyList<String>()
        val excludePatterns = excludes.mapNotNull { it as? String }
        
        log("🔍 SCROLL_UNTIL_FIND: text=$text, contains=$contains, pattern=$pattern")
        if (excludePatterns.isNotEmpty()) {
            log("🚫 排除关键词: ${excludePatterns.joinToString(", ")}")
        }
        
        var attempts = 0
        val maxAttempts = 3  // 最多找3个匹配项（如果前面的被排除）
        
        for (i in 0 until maxScrolls) {
            val root = service.rootInActiveWindow ?: continue
            
            // 调试：打印当前可见的文本元素（仅在前3次滚动时）
            if (i < 3) {
                val visibleTexts = mutableListOf<String>()
                collectAllTexts(root, visibleTexts, 20)
                log("📋 当前可见元素 (前20个): ${visibleTexts.take(10).joinToString(", ")}")
            }
            
            // 🆕 使用增强版查找，支持排除条件
            val target = findMatchingNodeWithExcludes(root, text, contains, pattern, excludePatterns)
            
            if (target != null) {
                val matchedText = target.text?.toString() ?: target.contentDescription?.toString() ?: ""
                log("✅ 找到匹配元素: ${matchedText.take(50)}...")
                
                if (tapAfterFind) {
                    val rect = android.graphics.Rect()
                    target.getBoundsInScreen(rect)
                    val tapResult = performTap(rect.centerX(), rect.centerY())
                    
                    // 🆕 点击后验证：检查是否进入了有效页面（非直播）
                    delay(2000)  // 等待页面加载
                    val pageValidation = validatePageAfterTap()
                    
                    if (pageValidation.isValid) {
                        return tapResult
                    } else {
                        // 进入了无效页面（如直播），返回重试
                        log("⚠️ 进入了无效页面: ${pageValidation.reason}，返回重试...")
                        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                        delay(1000)
                        attempts++
                        
                        if (attempts >= maxAttempts) {
                            return StepResult(false, "尝试 $maxAttempts 次都进入无效页面")
                        }
                        
                        // 继续滚动查找下一个
                        performSwipe(direction)
                        delay(1000)
                        continue
                    }
                }
                return StepResult(true)
            }
            
            log("📜 滚动 ${i + 1}/$maxScrolls...")
            performSwipe(direction)
            delay(1000)
        }
        
        return StepResult(false, "Element not found after $maxScrolls scrolls")
    }
    
    /**
     * 🆕 验证点击后的页面是否有效（非直播、有评论区等）
     */
    private data class PageValidation(val isValid: Boolean, val reason: String)
    
    private fun validatePageAfterTap(): PageValidation {
        val root = service.rootInActiveWindow ?: return PageValidation(false, "无法获取页面")
        
        val allTexts = mutableListOf<String>()
        collectAllTexts(root, allTexts, 50)
        val pageContent = allTexts.joinToString(" ")
        
        // 检测直播页面特征
        val liveIndicators = listOf("人观看", "正在直播", "直播中", "连麦", "礼物", "在线", "送礼")
        for (indicator in liveIndicators) {
            if (pageContent.contains(indicator)) {
                return PageValidation(false, "这是直播页面 (包含 '$indicator')")
            }
        }
        
        // 检测笔记/视频页面特征（应该有评论相关元素）
        val validIndicators = listOf("评论", "赞", "收藏", "分享", "写评论", "回复")
        val hasValidIndicator = validIndicators.any { pageContent.contains(it) }
        
        if (!hasValidIndicator) {
            return PageValidation(false, "页面缺少评论区特征")
        }
        
        return PageValidation(true, "有效的笔记/视频页面")
    }
    
    /**
     * 🆕 带排除条件的节点查找
     */
    private fun findMatchingNodeWithExcludes(
        node: android.view.accessibility.AccessibilityNodeInfo,
        text: String?,
        contains: String?,
        pattern: String?,
        excludes: List<String>
    ): android.view.accessibility.AccessibilityNodeInfo? {
        val nodeText = node.text?.toString() ?: ""
        val nodeDesc = node.contentDescription?.toString() ?: ""
        val combined = "$nodeText $nodeDesc"
        
        // 先检查排除条件
        if (excludes.isNotEmpty()) {
            for (exclude in excludes) {
                if (combined.contains(exclude, ignoreCase = true)) {
                    // 被排除，跳过这个节点
                    // 但继续检查子节点
                    break
                }
            }
        }
        
        // 检查是否匹配且不被排除
        val isMatch = when {
            text != null -> nodeText == text || nodeDesc == text
            contains != null -> combined.contains(contains, ignoreCase = true)
            pattern != null -> Regex(pattern).containsMatchIn(combined)
            else -> false
        }
        
        val isExcluded = excludes.any { combined.contains(it, ignoreCase = true) }
        
        if (isMatch && !isExcluded) {
            log("🎯 匹配: '$combined'")
            return node
        }
        
        // 递归检查子节点
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findMatchingNodeWithExcludes(child, text, contains, pattern, excludes)
            if (result != null) return result
        }
        
        return null
    }
    
    // 收集所有文本元素用于调试
    private fun collectAllTexts(node: android.view.accessibility.AccessibilityNodeInfo, results: MutableList<String>, maxCount: Int) {
        if (results.size >= maxCount) return
        val text = node.text?.toString()?.trim()
        val desc = node.contentDescription?.toString()?.trim()
        if (!text.isNullOrEmpty()) results.add(text)
        else if (!desc.isNullOrEmpty()) results.add(desc)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectAllTexts(child, results, maxCount)
        }
    }
    
    private suspend fun executeExtractData(step: ScriptStep): StepResult {
        val field = step.params["field"] as? String ?: "data"
        val selector = step.params["selector"] as? String
        val count = (step.params["count"] as? Number)?.toInt() ?: 5
        
        val root = service.rootInActiveWindow ?: return StepResult(false, "No window")
        val extractedItems = mutableListOf<String>()
        
        // 根据字段类型选择不同的提取策略
        when (field.lowercase()) {
            "comments", "评论" -> extractComments(root, extractedItems, count)
            "likes", "点赞" -> extractLikes(root, extractedItems, count)
            else -> extractTexts(root, extractedItems, count)
        }
        
        log("📊 提取到 ${extractedItems.size} 条 $field 数据")
        
        return StepResult(true, data = mapOf(field to extractedItems))
    }
    
    /**
     * 智能提取评论
     * 小红书评论格式特征：
     * 1. 用户名 + 内容，通常包含 ":" 或在相邻节点
     * 2. 评论区通常有 "回复"、"赞" 按钮
     * 3. 过滤掉系统文本（如"展开更多"、"查看全部"）
     */
    private fun extractComments(
        node: android.view.accessibility.AccessibilityNodeInfo,
        results: MutableList<String>,
        maxCount: Int
    ) {
        val allTexts = mutableListOf<Pair<String, android.graphics.Rect>>()
        collectAllTextWithBounds(node, allTexts)
        
        // 过滤出可能是评论的文本
        val systemTexts = setOf(
            "展开更多", "查看全部", "回复", "赞", "分享", "收藏", 
            "评论", "写评论", "发送", "取消", "确定", "全部评论",
            "相关推荐", "猜你喜欢", "更多精彩", "查看更多"
        )
        
        // 评论通常较长，包含用户名和内容
        for ((text, rect) in allTexts) {
            if (results.size >= maxCount) break
            
            // 跳过系统文本
            if (systemTexts.any { text.contains(it) }) continue
            
            // 跳过太短或太长的文本
            if (text.length < 8 || text.length > 500) continue
            
            // 跳过纯数字（可能是点赞数）
            if (text.matches(Regex("""^\d+\.?\d*[万亿]*$"""))) continue
            
            // 评论特征：包含用户名分隔符或明显的评论格式
            val isComment = text.contains(":") || 
                           text.contains("：") ||
                           text.matches(Regex(""".*@.*:.*""")) ||
                           text.matches(Regex(""".{2,20}[:：].{5,}""")) ||  // 用户名:内容
                           (text.length > 15 && !text.contains("\n"))  // 较长的单行文本可能是评论
            
            if (isComment || text.length > 20) {
                results.add(text)
                log("📝 提取评论: ${text.take(50)}...")
            }
        }
        
        // 如果提取不够，降低标准再试
        if (results.size < maxCount) {
            for ((text, rect) in allTexts) {
                if (results.size >= maxCount) break
                if (results.contains(text)) continue
                if (systemTexts.any { text.contains(it) }) continue
                if (text.length in 10..200) {
                    results.add(text)
                    log("📝 补充评论: ${text.take(50)}...")
                }
            }
        }
    }
    
    private fun collectAllTextWithBounds(
        node: android.view.accessibility.AccessibilityNodeInfo,
        results: MutableList<Pair<String, android.graphics.Rect>>
    ) {
        val text = node.text?.toString()?.trim()
        val desc = node.contentDescription?.toString()?.trim()
        val rect = android.graphics.Rect()
        node.getBoundsInScreen(rect)
        
        if (!text.isNullOrEmpty()) {
            results.add(text to rect)
        } else if (!desc.isNullOrEmpty() && desc.length > 5) {
            results.add(desc to rect)
        }
        
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectAllTextWithBounds(child, results)
        }
    }
    
    /**
     * 提取点赞数
     */
    private fun extractLikes(
        node: android.view.accessibility.AccessibilityNodeInfo,
        results: MutableList<String>,
        maxCount: Int
    ) {
        val allTexts = mutableListOf<String>()
        extractAllTexts(node, allTexts)
        
        // 查找包含点赞数格式的文本
        val likePattern = Regex("""(\d+\.?\d*[万亿]?\s*(?:赞|点赞|喜欢))|((?:赞|点赞|喜欢)\s*\d+\.?\d*[万亿]?)""")
        for (text in allTexts) {
            if (results.size >= maxCount) break
            if (likePattern.containsMatchIn(text)) {
                results.add(text)
            }
        }
    }
    
    private fun extractAllTexts(
        node: android.view.accessibility.AccessibilityNodeInfo,
        results: MutableList<String>
    ) {
        val text = node.text?.toString()?.trim()
        if (!text.isNullOrEmpty()) {
            results.add(text)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            extractAllTexts(child, results)
        }
    }
    
    private suspend fun executeInputText(step: ScriptStep): StepResult {
        val text = step.params["text"] as? String ?: return StepResult(false, "Missing text")
        // TODO: 实现输入文本
        return StepResult(true)
    }
    
    private suspend fun executeBack(step: ScriptStep): StepResult {
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        delay(500)
        return StepResult(true)
    }
    
    private suspend fun executeAssert(step: ScriptStep): StepResult {
        val condition = step.condition ?: return StepResult(false, "No condition")
        // TODO: 实现断言检查
        return StepResult(true)
    }
    
    private suspend fun executeAIDecide(step: ScriptStep): StepResult {
        val goal = step.params["goal"] as? String ?: step.description
        log("🤖 AI 决策: $goal")
        
        // 获取当前屏幕状态
        val root = service.rootInActiveWindow ?: return StepResult(false, "No window")
        val elements = collectElements(root)
        
        // 调用 AI 决策
        val prompt = """
当前屏幕元素:
$elements

目标: $goal

请决定下一步操作，返回 JSON:
{"action":"tap/swipe/wait","params":{...}}
""".trimIndent()
        
        val messages = listOf(Message(role = "user", content = prompt))
        val response = aiClient.chat(messages)
        // 解析并执行 AI 决策
        // TODO: 实现 AI 决策执行
        
        return StepResult(true)
    }
    
    // ========== 辅助函数 ==========
    
    private fun performTap(x: Int, y: Int): StepResult {
        val path = android.graphics.Path().apply {
            moveTo(x.toFloat(), y.toFloat())
        }
        val gesture = android.accessibilityservice.GestureDescription.Builder()
            .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 150))
            .build()
        
        val success = service.dispatchGesture(gesture, null, null)
        return StepResult(success, if (!success) "Gesture failed" else null)
    }
    
    private fun performSwipe(direction: String): StepResult {
        val displayMetrics = service.resources.displayMetrics
        val width = displayMetrics.widthPixels
        val height = displayMetrics.heightPixels
        
        val (startX, startY, endX, endY) = when (direction.lowercase()) {
            "up" -> listOf(width / 2, height * 3 / 4, width / 2, height / 4)
            "down" -> listOf(width / 2, height / 4, width / 2, height * 3 / 4)
            "left" -> listOf(width * 3 / 4, height / 2, width / 4, height / 2)
            "right" -> listOf(width / 4, height / 2, width * 3 / 4, height / 2)
            else -> return StepResult(false, "Invalid direction")
        }
        
        val path = android.graphics.Path().apply {
            moveTo(startX.toFloat(), startY.toFloat())
            lineTo(endX.toFloat(), endY.toFloat())
        }
        val gesture = android.accessibilityservice.GestureDescription.Builder()
            .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 300))
            .build()
        
        val success = service.dispatchGesture(gesture, null, null)
        return StepResult(success)
    }
    
    private fun findAndTapByText(text: String): StepResult {
        val root = service.rootInActiveWindow ?: return StepResult(false, "No window")
        val node = findMatchingNode(root, text, null, null)
        
        if (node != null) {
            val rect = android.graphics.Rect()
            node.getBoundsInScreen(rect)
            return performTap(rect.centerX(), rect.centerY())
        }
        
        return StepResult(false, "Text not found: $text")
    }
    
    private fun findMatchingNode(
        node: android.view.accessibility.AccessibilityNodeInfo,
        exactText: String?,
        containsText: String?,
        pattern: String?
    ): android.view.accessibility.AccessibilityNodeInfo? {
        val nodeText = node.text?.toString() ?: ""
        val nodeDesc = node.contentDescription?.toString() ?: ""
        val combined = "$nodeText $nodeDesc"
        
        val matches = when {
            exactText != null -> nodeText == exactText || nodeDesc == exactText
            containsText != null -> combined.contains(containsText, ignoreCase = true)
            pattern != null -> Regex(pattern).containsMatchIn(combined)
            else -> false
        }
        
        if (matches && node.isClickable) return node
        
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findMatchingNode(child, exactText, containsText, pattern)
            if (result != null) return result
        }
        
        return null
    }
    
    /**
     * 增强版节点查找 - 即使元素不可点击也返回（用于获取坐标点击）
     * 优先返回可点击元素，否则返回匹配元素本身
     */
    private fun findMatchingNodeEnhanced(
        node: android.view.accessibility.AccessibilityNodeInfo,
        exactText: String?,
        containsText: String?,
        pattern: String?,
        clickableParent: android.view.accessibility.AccessibilityNodeInfo? = null
    ): android.view.accessibility.AccessibilityNodeInfo? {
        val nodeText = node.text?.toString() ?: ""
        val nodeDesc = node.contentDescription?.toString() ?: ""
        val combined = "$nodeText $nodeDesc"
        
        // 更新可点击父级
        val currentClickable = if (node.isClickable) node else clickableParent
        
        val matches = when {
            exactText != null -> nodeText == exactText || nodeDesc == exactText
            containsText != null -> smartContainsMatch(combined, containsText)
            pattern != null -> {
                try {
                    // 处理可能过度转义的正则表达式
                    val cleanPattern = pattern
                        .replace("\\\\\\\\", "\\")  // 4个反斜杠 -> 1个
                        .replace("\\\\", "\\")       // 2个反斜杠 -> 1个
                    Regex(cleanPattern).containsMatchIn(combined)
                } catch (e: Exception) {
                    log("⚠️ 正则匹配错误: ${e.message}, pattern=$pattern")
                    // 尝试简单的数字匹配作为后备
                    val hasLargeNumber = Regex("\\d+(\\.\\d)?[万w]|[1-9]\\d{4,}").containsMatchIn(combined)
                    if (hasLargeNumber) log("🎯 后备正则匹配成功")
                    hasLargeNumber
                }
            }
            else -> false
        }
        
        if (matches) {
            // 如果找到匹配，优先返回可点击父级，否则返回当前节点
            log("🎯 匹配: '$combined'")
            return currentClickable ?: node
        }
        
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findMatchingNodeEnhanced(child, exactText, containsText, pattern, currentClickable)
            if (result != null) return result
        }
        
        return null
    }
    
    /**
     * 智能包含匹配 - 处理各种等价表达
     * 例如：搜索"万"时也匹配"w"、"1.2w"、"10000+"等
     */
    private fun smartContainsMatch(text: String, searchTerm: String): Boolean {
        // 首先尝试直接匹配
        if (text.contains(searchTerm, ignoreCase = true)) {
            return true
        }
        
        // 特殊语义匹配
        when (searchTerm.lowercase()) {
            // 匹配大数字的各种表达: 万、w、10000+
            "万", "w" -> {
                // 匹配: 1万、1.2万、1w、1.2w、10000+
                val largeNumberPattern = Regex("\\d+(\\.\\d+)?[万wW]|[1-9]\\d{4,}")
                return largeNumberPattern.containsMatchIn(text)
            }
            // 匹配赞/点赞
            "赞", "点赞", "喜欢" -> {
                return text.contains("赞", ignoreCase = true) || 
                       text.contains("喜欢", ignoreCase = true) ||
                       text.contains("like", ignoreCase = true)
            }
            // 匹配评论
            "评论", "留言" -> {
                return text.contains("评论", ignoreCase = true) ||
                       text.contains("留言", ignoreCase = true) ||
                       text.contains("comment", ignoreCase = true)
            }
        }
        
        return false
    }
    
    private fun extractTexts(
        node: android.view.accessibility.AccessibilityNodeInfo,
        results: MutableList<String>,
        maxCount: Int
    ) {
        if (results.size >= maxCount) return
        
        val text = node.text?.toString()?.trim()
        if (!text.isNullOrEmpty() && text.length > 5) {
            results.add(text)
        }
        
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            extractTexts(child, results, maxCount)
        }
    }
    
    private fun collectElements(node: android.view.accessibility.AccessibilityNodeInfo): String {
        val elements = mutableListOf<String>()
        collectElementsRecursive(node, elements, 20)
        return elements.joinToString("\n")
    }
    
    private fun collectElementsRecursive(
        node: android.view.accessibility.AccessibilityNodeInfo,
        elements: MutableList<String>,
        maxCount: Int
    ) {
        if (elements.size >= maxCount) return
        
        val text = node.text?.toString() ?: node.contentDescription?.toString()
        if (!text.isNullOrEmpty() && node.isClickable) {
            val rect = android.graphics.Rect()
            node.getBoundsInScreen(rect)
            elements.add("\"$text\" @ (${rect.centerX()}, ${rect.centerY()})")
        }
        
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectElementsRecursive(child, elements, maxCount)
        }
    }
    
    // ========== 脚本存储 ==========
    
    fun saveScript(script: Script) {
        scriptsCache[script.id] = script
        
        try {
            val scriptsDir = File(service.filesDir, SCRIPTS_DIR)
            if (!scriptsDir.exists()) scriptsDir.mkdirs()
            
            val file = File(scriptsDir, "${script.id}.json")
            file.writeText(gson.toJson(script))
            log("💾 脚本已保存: ${script.name}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save script", e)
        }
    }
    
    fun loadScript(scriptId: String): Script? {
        scriptsCache[scriptId]?.let { return it }
        
        try {
            val file = File(service.filesDir, "$SCRIPTS_DIR/$scriptId.json")
            if (file.exists()) {
                val script = gson.fromJson(file.readText(), Script::class.java)
                scriptsCache[scriptId] = script
                return script
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load script", e)
        }
        
        return null
    }
    
    fun listScripts(): List<Script> {
        val scripts = mutableListOf<Script>()
        
        try {
            val scriptsDir = File(service.filesDir, SCRIPTS_DIR)
            if (scriptsDir.exists()) {
                scriptsDir.listFiles()?.forEach { file ->
                    if (file.extension == "json") {
                        try {
                            val script = gson.fromJson(file.readText(), Script::class.java)
                            scripts.add(script)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to parse script: ${file.name}", e)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to list scripts", e)
        }
        
        return scripts
    }
    
    fun deleteScript(scriptId: String): Boolean {
        scriptsCache.remove(scriptId)
        
        try {
            val file = File(service.filesDir, "$SCRIPTS_DIR/$scriptId.json")
            return file.delete()
        } catch (e: Exception) {
            return false
        }
    }
    
    // ========== AI Prompt 构建 ==========
    
    private fun buildScriptGenerationPrompt(goal: String): String {
        return """
你是一个自动化脚本生成专家。根据用户目标，生成一个可复用的自动化脚本。

## 用户目标
$goal

## 输出格式 (严格 JSON)
{
  "name": "脚本名称",
  "steps": [
    {
      "index": 1,
      "type": "LAUNCH_APP|TAP|SWIPE|WAIT|FIND_AND_TAP|SCROLL_UNTIL_FIND|EXTRACT_DATA|BACK|AI_DECIDE",
      "description": "步骤描述",
      "params": { ... },
      "on_fail": "RETRY|SKIP|ABORT|AI_TAKEOVER",
      "max_retries": 3
    }
  ],
  "outputs": ["expected_output_1", "expected_output_2"]
}

## 可用步骤类型
1. LAUNCH_APP - 启动应用 {"package": "com.xingin.xhs"}
2. TAP - 点击 {"x": 100, "y": 200} 或 {"text": "搜索"}
3. SWIPE - 滑动 {"direction": "up|down|left|right"}
4. WAIT - 等待 {"ms": 1000}
5. FIND_AND_TAP - 查找并点击 {"text": "精确文本"} 或 {"contains": "包含文本"} 或 {"pattern": "正则表达式"}
6. SCROLL_UNTIL_FIND - 滚动直到找到并**自动点击** 
   参数: {"contains": "文本", "max_scrolls": 10, "direction": "up", "excludes": ["排除词1", "排除词2"]}
   ⚠️ 注意：此步骤会自动点击找到的元素，不需要额外的TAP或FIND_AND_TAP步骤！
   ⚠️ 重要：使用 excludes 参数排除不想要的内容类型（如直播）
7. EXTRACT_DATA - 提取数据 {"field": "comments", "count": 5}
8. BACK - 返回 {}
9. AI_DECIDE - AI动态决策 {"goal": "子目标描述"}

## ⚠️ 关键规则
1. **禁止使用占位符文本**！如"笔记标题"、"目标内容"等。必须使用 contains 或 pattern 匹配真实内容
2. **SCROLL_UNTIL_FIND 会自动点击**：找到后会自动点击进入，不需要再加FIND_AND_TAP步骤
3. **数字匹配优先用正则**：查找"点赞过万"应使用 {"contains": "万"}
4. **小红书特殊处理**：
   - 笔记点赞数通常显示在笔记卡片右下角，格式如"1.2万"、"8.5w"、"12345"
   - 评论区通常需要向上滑动才能看到
   - ⚠️ **直播卡片没有评论区**！要提取评论时，必须排除直播！使用 excludes: ["直播", "观看", "连麦"]
5. **步骤要精简**：SCROLL_UNTIL_FIND找到并点击后，直接WAIT然后继续下一步

## 常见APP包名
- 小红书: com.xingin.xhs
- 抖音: com.ss.android.ugc.aweme
- 微信: com.tencent.mm

## 示例：获取小红书热门评论（排除直播）
{
  "name": "获取小红书点赞过万笔记评论",
  "steps": [
    {"index": 1, "type": "LAUNCH_APP", "description": "打开小红书", "params": {"package": "com.xingin.xhs"}, "on_fail": "RETRY", "max_retries": 3},
    {"index": 2, "type": "WAIT", "description": "等待首页加载", "params": {"ms": 2500}, "on_fail": "SKIP", "max_retries": 1},
    {"index": 3, "type": "SCROLL_UNTIL_FIND", "description": "滚动找到点赞过万的笔记并点击进入（排除直播）", "params": {"contains": "万赞", "excludes": ["直播", "观看", "连麦", "在线"], "max_scrolls": 15, "direction": "up"}, "on_fail": "RETRY", "max_retries": 2},
    {"index": 4, "type": "WAIT", "description": "等待笔记详情加载", "params": {"ms": 2000}, "on_fail": "SKIP", "max_retries": 1},
    {"index": 5, "type": "SWIPE", "description": "向上滑动查看评论区", "params": {"direction": "up"}, "on_fail": "RETRY", "max_retries": 3},
    {"index": 6, "type": "EXTRACT_DATA", "description": "提取前5条评论", "params": {"field": "comments", "count": 5}, "on_fail": "AI_TAKEOVER", "max_retries": 2}
  ],
  "outputs": ["comments"]
}

注意：SCROLL_UNTIL_FIND 在第3步找到并点击了笔记，不需要额外的FIND_AND_TAP步骤！

请根据用户目标生成脚本，只返回 JSON，不要其他内容。
""".trimIndent()
    }
    
    private fun buildImprovementPrompt(script: Script, failResult: ScriptExecutionResult): String {
        return """
你是脚本优化专家。脚本执行失败，请分析原因并改进。

## 原脚本
${gson.toJson(script)}

## 执行结果
- 成功步骤: ${failResult.stepsExecuted}/${failResult.totalSteps}
- 失败步骤: ${failResult.failedStepIndex?.plus(1) ?: "未知"}
- 错误: ${failResult.error}
- 日志: ${failResult.logs.joinToString("\n")}

## 要求
1. 分析失败原因
2. 改进失败的步骤（增加重试、调整等待时间、换用 AI_DECIDE 等）
3. 返回改进后的 steps 数组（只返回 steps，JSON 格式）

## 改进策略
- 如果是元素找不到：增加等待时间、改用 SCROLL_UNTIL_FIND、或使用 AI_DECIDE
- 如果是点击失败：改用 FIND_AND_TAP、调整坐标
- 如果是超时：增加 max_retries

只返回改进后的 steps JSON 数组，不要其他内容。
""".trimIndent()
    }
    
    private fun parseScriptFromAI(response: String, goal: String): Script? {
        return try {
            // 提取 JSON
            val jsonStr = extractJson(response)
            val parsed = gson.fromJson(jsonStr, Map::class.java)
            
            val name = parsed["name"] as? String ?: "未命名脚本"
            val stepsRaw = parsed["steps"] as? List<*> ?: return null
            val outputs = (parsed["outputs"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList()
            
            val steps = stepsRaw.mapIndexed { index, stepRaw ->
                val stepMap = stepRaw as? Map<*, *> ?: return@mapIndexed null
                val typeStr = stepMap["type"] as? String ?: "WAIT"
                
                // 容错处理：映射未知类型到已知类型
                val type = try {
                    StepType.valueOf(typeStr)
                } catch (e: IllegalArgumentException) {
                    mapUnknownStepType(typeStr)
                }
                
                ScriptStep(
                    index = (stepMap["index"] as? Number)?.toInt() ?: (index + 1),
                    type = type,
                    description = stepMap["description"] as? String ?: "",
                    params = (stepMap["params"] as? Map<*, *>)?.mapKeys { it.key.toString() }?.mapValues { it.value as Any } ?: emptyMap(),
                    onFail = try { FailAction.valueOf(stepMap["on_fail"] as? String ?: "RETRY") } catch (e: Exception) { FailAction.RETRY },
                    maxRetries = (stepMap["max_retries"] as? Number)?.toInt() ?: 3
                )
            }.filterNotNull()
            
            Script(
                id = UUID.randomUUID().toString(),
                name = name,
                goal = goal,
                steps = steps,
                outputs = outputs
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse script", e)
            null
        }
    }
    
    private fun parseImprovedSteps(response: String): List<ScriptStep>? {
        return try {
            val jsonStr = extractJson(response)
            val stepsRaw = gson.fromJson(jsonStr, List::class.java) as? List<*> ?: return null
            
            stepsRaw.mapIndexed { index, stepRaw ->
                val stepMap = stepRaw as? Map<*, *> ?: return@mapIndexed null
                val typeStr = stepMap["type"] as? String ?: "WAIT"
                
                // 复用相同的类型映射逻辑
                val type = try {
                    StepType.valueOf(typeStr)
                } catch (e: IllegalArgumentException) {
                    mapUnknownStepType(typeStr)
                }
                
                ScriptStep(
                    index = (stepMap["index"] as? Number)?.toInt() ?: (index + 1),
                    type = type,
                    description = stepMap["description"] as? String ?: "",
                    params = (stepMap["params"] as? Map<*, *>)?.mapKeys { it.key.toString() }?.mapValues { it.value as Any } ?: emptyMap(),
                    onFail = try { FailAction.valueOf(stepMap["on_fail"] as? String ?: "RETRY") } catch (e: Exception) { FailAction.RETRY },
                    maxRetries = (stepMap["max_retries"] as? Number)?.toInt() ?: 3
                )
            }.filterNotNull()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse improved steps", e)
            null
        }
    }
    
    /**
     * 将未知步骤类型映射到已知类型
     */
    private fun mapUnknownStepType(typeStr: String): StepType {
        log("⚠️ 未知步骤类型 '$typeStr'，尝试智能映射...")
        return when {
            typeStr.contains("SEARCH", ignoreCase = true) -> StepType.SEARCH
            typeStr.contains("CLICK", ignoreCase = true) -> StepType.TAP
            typeStr.contains("SCROLL", ignoreCase = true) -> StepType.SCROLL_UNTIL_FIND
            typeStr.contains("FIND", ignoreCase = true) -> StepType.FIND_AND_TAP
            typeStr.contains("INPUT", ignoreCase = true) -> StepType.INPUT_TEXT
            typeStr.contains("TYPE", ignoreCase = true) -> StepType.INPUT_TEXT
            typeStr.contains("DELAY", ignoreCase = true) -> StepType.WAIT
            typeStr.contains("SLEEP", ignoreCase = true) -> StepType.WAIT
            typeStr.contains("OPEN", ignoreCase = true) -> StepType.LAUNCH_APP
            typeStr.contains("LAUNCH", ignoreCase = true) -> StepType.LAUNCH_APP
            typeStr.contains("EXTRACT", ignoreCase = true) -> StepType.EXTRACT_DATA
            typeStr.contains("GET", ignoreCase = true) -> StepType.EXTRACT_DATA
            else -> {
                log("⚠️ 无法映射类型 '$typeStr'，使用 AI_DECIDE")
                StepType.AI_DECIDE
            }
        }
    }
    
    private fun extractJson(text: String): String {
        // 尝试提取 JSON
        val jsonPattern = Regex("""\{[\s\S]*\}|\[[\s\S]*\]""")
        return jsonPattern.find(text)?.value ?: text
    }
    
    private fun incrementVersion(version: String): String {
        val parts = version.split(".")
        return if (parts.size >= 2) {
            "${parts[0]}.${(parts[1].toIntOrNull() ?: 0) + 1}"
        } else {
            "1.1"
        }
    }
    
    private fun log(message: String) {
        Log.d(TAG, message)
        onLog?.invoke(message)
    }
}

/**
 * 步骤执行结果
 */
data class StepResult(
    val success: Boolean,
    val error: String? = null,
    val data: Map<String, Any>? = null
)
