package com.employee.agent

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.employee.agent.application.AIAutonomousEngine
import com.employee.agent.application.ScriptEngine
import com.employee.agent.domain.screen.UINode
import com.employee.agent.infrastructure.vision.ScreenAnalyzer
import com.employee.agent.infrastructure.vision.ScriptGenerator
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

data class NodeData(
    val className: String,
    val text: String?,
    val contentDescription: String?,
    val resourceId: String?,
    var bounds: String,
    val children: MutableList<NodeData>
)

class SocketServer(private val service: AccessibilityService) {
    private var serverSocket: ServerSocket? = null
    private var isRunning = false
    private val executor = Executors.newCachedThreadPool()
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // 智能分析组件
    private val screenAnalyzer = ScreenAnalyzer()
    private val scriptGenerator = ScriptGenerator()
    
    /**
     * 🆕 获取 Root Window 的辅助函数
     * 
     * 先尝试 rootInActiveWindow，如果为 null 则从 windows 中获取活动窗口的 root
     * 这解决了部分设备（特别是小米/MIUI）上 rootInActiveWindow 返回 null 的问题
     */
    private fun getRootNode(): AccessibilityNodeInfo? {
        // 首先尝试标准方法
        service.rootInActiveWindow?.let { return it }
        
        // 备选方案：从 windows 列表中获取
        try {
            val windows = service.windows
            if (windows != null && windows.isNotEmpty()) {
                Log.d("Agent", "🔍 windows API: 找到 ${windows.size} 个窗口")
                
                // 1. 优先选择 isActive 且 isFocused 的窗口
                for (window in windows) {
                    if (window.isActive && window.isFocused) {
                        window.root?.let { root ->
                            Log.d("Agent", "✅ 使用活动焦点窗口 (类型: ${window.type}, 包: ${root.packageName})")
                            return root
                        }
                    }
                }
                
                // 2. 其次选择 isActive 的应用窗口（TYPE_APPLICATION = 1）
                for (window in windows) {
                    if (window.isActive && window.type == 1) {
                        window.root?.let { root ->
                            Log.d("Agent", "✅ 使用活动应用窗口 (包: ${root.packageName})")
                            return root
                        }
                    }
                }
                
                // 3. 选择任何 isActive 的窗口
                for (window in windows) {
                    if (window.isActive) {
                        window.root?.let { root ->
                            Log.d("Agent", "✅ 使用活动窗口 (类型: ${window.type}, 包: ${root.packageName})")
                            return root
                        }
                    }
                }
                
                // 4. 最后降级：选择任何有 root 的应用窗口
                val appWindow = windows.find { it.type == 1 && it.root != null }
                if (appWindow != null) {
                    Log.d("Agent", "⚠️ 降级：使用首个应用窗口 (包: ${appWindow.root?.packageName})")
                    return appWindow.root
                }
                
                // 5. 兜底：任何有 root 的窗口
                for (window in windows) {
                    window.root?.let { root ->
                        Log.d("Agent", "⚠️ 兜底：使用窗口 (类型: ${window.type}, 包: ${root.packageName})")
                        return root
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("Agent", "windows API 获取失败: ${e.message}")
        }
        
        return null
    }
    
    // 🆕 AI 自主执行引擎 (需要 API Key)
    private var aiEngine: AIAutonomousEngine? = null
    
    // 🆕 脚本引擎
    private var scriptEngine: ScriptEngine? = null
    
    // API Key 配置 (用户的腾讯混元 Key)
    private var apiKey: String = ""
    
    // SharedPreferences 常量 - 与 AgentConfigActivity 保持一致
    private companion object {
        const val PREF_NAME = "agent_config"  // 与 AgentConfigActivity 一致
        const val KEY_API_KEY = "hunyuan_api_key"  // 腾讯混元 API Key
    }
    
    /**
     * 设置 API Key 并持久化保存
     */
    fun setApiKey(key: String) {
        apiKey = key
        if (key.isNotBlank()) {
            // 保存到 SharedPreferences
            saveApiKey(key)
            
            aiEngine = AIAutonomousEngine(service, key)
            scriptEngine = ScriptEngine(service, key)  // 🆕 初始化脚本引擎
            Log.i("Agent", "AI 引擎和脚本引擎已初始化，API Key 已保存")
        }
    }
    
    /**
     * 保存 API Key 到 SharedPreferences
     */
    private fun saveApiKey(key: String) {
        try {
            val prefs = service.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            prefs.edit().putString(KEY_API_KEY, key).apply()
            Log.i("Agent", "API Key 已持久化保存")
        } catch (e: Exception) {
            Log.e("Agent", "保存 API Key 失败", e)
        }
    }
    
    /**
     * 从 SharedPreferences 加载 API Key
     */
    fun loadSavedApiKey() {
        try {
            val prefs = service.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            val savedKey = prefs.getString(KEY_API_KEY, "") ?: ""
            if (savedKey.isNotBlank()) {
                apiKey = savedKey
                aiEngine = AIAutonomousEngine(service, savedKey)
                scriptEngine = ScriptEngine(service, savedKey)  // 🆕 初始化脚本引擎
                Log.i("Agent", "已加载保存的 API Key，AI 引擎和脚本引擎已就绪")
            } else {
                Log.i("Agent", "没有保存的 API Key")
            }
        } catch (e: Exception) {
            Log.e("Agent", "加载 API Key 失败", e)
        }
    }

    fun start(port: Int) {
        isRunning = true
        Thread {
            try {
                serverSocket = ServerSocket(port)
                Log.d("Agent", "Server started on port $port")
                while (isRunning) {
                    val client = serverSocket?.accept()
                    client?.let {
                        executor.submit { handleClient(it) }
                    }
                }
            } catch (e: Exception) {
                Log.e("Agent", "Server error", e)
            }
        }.start()
    }

    fun stop() {
        isRunning = false
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            Log.e("Agent", "Error closing server", e)
        }
    }

    private fun handleClient(socket: Socket) {
        try {
            val input = BufferedReader(InputStreamReader(socket.getInputStream()))
            val output = PrintWriter(socket.getOutputStream(), true)

            val command = input.readLine()?.trim() ?: ""
            Log.d("Agent", "Received command: $command")

            when {
                command == "DUMP" -> {
                    // 原有的 UI 树 dump（使用改进的 getRootNode）
                    val root = getRootNode()
                    if (root != null) {
                        val dump = serializeNode(root)
                        output.println(gson.toJson(dump))
                    } else {
                        output.println("ERROR: No root window")
                    }
                }
                command == "ANALYZE" || command == "ANALYZE_SCREEN" -> {
                    // 🆕 智能屏幕分析
                    handleAnalyzeScreen(output)
                }
                command.startsWith("GENERATE_SCRIPT:") -> {
                    // 🆕 生成脚本
                    val goal = command.removePrefix("GENERATE_SCRIPT:").trim()
                    handleGenerateScript(goal, output)
                }
                command.startsWith("SET_API_KEY:") -> {
                    // 🆕 设置 API Key
                    val key = command.removePrefix("SET_API_KEY:").trim()
                    handleSetApiKey(key, output)
                }
                command.startsWith("RUN_AI_GOAL:") -> {
                    // 🆕 AI 自主执行目标
                    val goal = command.removePrefix("RUN_AI_GOAL:").trim()
                    handleRunAIGoal(goal, output, socket)
                    return  // 特殊处理，不要关闭 socket
                }
                command == "STOP_AI" -> {
                    // 🆕 停止 AI 执行
                    handleStopAI(output)
                }
                // ========== 🆕 脚本系统命令 ==========
                command.startsWith("SMART_EXECUTE:") -> {
                    // 🧠 智能执行：先分析意图，再决定走脚本还是聊天
                    val userInput = command.removePrefix("SMART_EXECUTE:").trim()
                    handleSmartExecute(userInput, output, socket)
                    return
                }
                command.startsWith("ANALYZE_INTENT:") -> {
                    // 仅分析意图，不执行
                    val userInput = command.removePrefix("ANALYZE_INTENT:").trim()
                    handleAnalyzeIntent(userInput, output)
                }
                command.startsWith("SCRIPT_GENERATE:") -> {
                    val goal = command.removePrefix("SCRIPT_GENERATE:").trim()
                    handleScriptGenerate(goal, output, socket)
                    return
                }
                command.startsWith("SCRIPT_EXECUTE:") -> {
                    val scriptId = command.removePrefix("SCRIPT_EXECUTE:").trim()
                    handleScriptExecute(scriptId, output, socket)
                    return
                }
                command.startsWith("SCRIPT_EXECUTE_AUTO:") -> {
                    val scriptId = command.removePrefix("SCRIPT_EXECUTE_AUTO:").trim()
                    handleScriptExecuteAuto(scriptId, output, socket)
                    return
                }
                command.startsWith("SCRIPT_IMPROVE:") -> {
                    val scriptId = command.removePrefix("SCRIPT_IMPROVE:").trim()
                    handleScriptImprove(scriptId, output)
                }
                command.startsWith("SCRIPT_GET:") -> {
                    val scriptId = command.removePrefix("SCRIPT_GET:").trim()
                    handleScriptGet(scriptId, output)
                }
                command == "SCRIPT_LIST" -> {
                    handleScriptList(output)
                }
                command.startsWith("SCRIPT_DELETE:") -> {
                    val scriptId = command.removePrefix("SCRIPT_DELETE:").trim()
                    handleScriptDelete(scriptId, output)
                }
                // ========== 🔧 调试接口命令 ==========
                command == "DEBUG_STATUS" -> {
                    handleDebugStatus(output)
                }
                command == "DEBUG_LAST_ERROR" -> {
                    handleDebugLastError(output)
                }
                command == "DEBUG_ERROR_HISTORY" || command.startsWith("DEBUG_ERROR_HISTORY:") -> {
                    val limit = command.removePrefix("DEBUG_ERROR_HISTORY:").toIntOrNull() ?: 10
                    handleDebugErrorHistory(limit, output)
                }
                command == "DEBUG_EXECUTION_HISTORY" || command.startsWith("DEBUG_EXECUTION_HISTORY:") -> {
                    val limit = command.removePrefix("DEBUG_EXECUTION_HISTORY:").toIntOrNull() ?: 20
                    handleDebugExecutionHistory(limit, output)
                }
                command == "DEBUG_LOGS" || command.startsWith("DEBUG_LOGS:") -> {
                    val limit = command.removePrefix("DEBUG_LOGS:").toIntOrNull() ?: 50
                    handleDebugLogs(limit, output)
                }
                command == "DEBUG_HEALTH" -> {
                    handleDebugHealth(output)
                }
                command == "DEBUG_SCREEN" -> {
                    handleDebugScreen(output)
                }
                command == "DEBUG_CLEAR" -> {
                    handleDebugClear(output)
                }
                command == "DEBUG_HELP" -> {
                    handleDebugHelp(output)
                }
                // ========== 状态检查 ==========
                command == "STATUS" -> {
                    // 状态检查
                    val hasAI = aiEngine != null
                    val hasScript = scriptEngine != null
                    val scriptCount = scriptEngine?.listScripts()?.size ?: 0
                    val currentExecMode = scriptEngine?.executionMode?.name ?: "SMART"
                    val currentScreenMode = getSmartScreenReader()?.currentMode?.name ?: "FULL_DUMP"
                    output.println("""{"status":"ok","version":"3.4","ai_enabled":$hasAI,"script_enabled":$hasScript,"script_count":$scriptCount,"execution_mode":"$currentExecMode","screen_mode":"$currentScreenMode","features":["SMART_EXECUTE","ANALYZE_INTENT","DUMP","ANALYZE","SET_API_KEY","RUN_AI_GOAL","STOP_AI","SCRIPT_GENERATE","SCRIPT_EXECUTE","SCRIPT_EXECUTE_AUTO","SCRIPT_IMPROVE","SCRIPT_GET","SCRIPT_LIST","SCRIPT_DELETE","SET_EXECUTION_MODE","GET_EXECUTION_MODE","LIST_EXECUTION_MODES","TEST_POPUP_DISMISS","SET_SCREEN_MODE","GET_SCREEN_MODE","LIST_SCREEN_MODES","SCREEN_DIFF","SCREEN_CHANGES","SCREEN_SNAPSHOT","SCREEN_STATS","DEBUG_STATUS","DEBUG_LAST_ERROR","DEBUG_ERROR_HISTORY","DEBUG_EXECUTION_HISTORY","DEBUG_LOGS","DEBUG_HEALTH","DEBUG_SCREEN","DEBUG_CLEAR","DEBUG_HELP"]}""")
                }
                // ========== 🎮 执行模式命令 ==========
                command.startsWith("SET_EXECUTION_MODE:") -> {
                    val modeName = command.removePrefix("SET_EXECUTION_MODE:").trim().uppercase()
                    handleSetExecutionMode(modeName, output)
                }
                command == "GET_EXECUTION_MODE" -> {
                    handleGetExecutionMode(output)
                }
                command == "LIST_EXECUTION_MODES" -> {
                    handleListExecutionModes(output)
                }
                command == "TEST_POPUP_DISMISS" -> {
                    handleTestPopupDismiss(output)
                }
                command.startsWith("SCRIPT_EXECUTE_WITH_MODE:") -> {
                    // 格式: SCRIPT_EXECUTE_WITH_MODE:scriptId:MODE
                    val params = command.removePrefix("SCRIPT_EXECUTE_WITH_MODE:").trim()
                    handleScriptExecuteWithMode(params, output, socket)
                    return
                }
                // ========== 📸 屏幕获取模式命令 ==========
                command.startsWith("SET_SCREEN_MODE:") -> {
                    val modeName = command.removePrefix("SET_SCREEN_MODE:").trim().uppercase()
                    handleSetScreenMode(modeName, output)
                }
                command == "GET_SCREEN_MODE" -> {
                    handleGetScreenMode(output)
                }
                command == "LIST_SCREEN_MODES" -> {
                    handleListScreenModes(output)
                }
                command == "SCREEN_DIFF" -> {
                    handleScreenDiff(output)
                }
                command == "SCREEN_CHANGES" -> {
                    handleScreenChanges(output)
                }
                command == "SCREEN_SNAPSHOT" -> {
                    handleScreenSnapshot(output)
                }
                command == "SCREEN_STATS" -> {
                    handleScreenStats(output)
                }
                command == "DEBUG_WINDOWS" -> {
                    handleDebugWindows(output)
                }
                else -> {
                    output.println("""{"error":"UNKNOWN_COMMAND","message":"Unknown command: $command","hint":"发送 DEBUG_HELP 获取所有可用命令"}""")
                }
            }

            socket.close()
        } catch (e: Exception) {
            Log.e("Agent", "Client handling error", e)
        }
    }
    
    // ==================== 🎮 执行模式命令处理 ====================
    
    /**
     * 设置执行模式
     */
    private fun handleSetExecutionMode(modeName: String, output: PrintWriter) {
        try {
            val engine = scriptEngine
            if (engine == null) {
                output.println("""{"error":"NO_ENGINE","message":"脚本引擎未初始化，请先设置 API Key"}""")
                return
            }
            
            val mode = try {
                com.employee.agent.domain.execution.ExecutionMode.valueOf(modeName)
            } catch (e: Exception) {
                output.println("""{"error":"INVALID_MODE","message":"无效的执行模式: $modeName","valid_modes":["FAST","SMART","MONITOR","AGENT"]}""")
                return
            }
            
            engine.executionMode = mode
            Log.i("Agent", "执行模式已切换为: ${mode.emoji} ${mode.displayName}")
            
            output.println(gson.toJson(mapOf(
                "success" to true,
                "mode" to mode.name,
                "display_name" to mode.displayName,
                "emoji" to mode.emoji,
                "description" to mode.description,
                "token_cost" to mode.tokenCostLevel.displayName
            )))
        } catch (e: Exception) {
            Log.e("Agent", "SET_EXECUTION_MODE 失败", e)
            output.println("""{"error":"SET_MODE_FAILED","message":"${escapeJson(e.message ?: "Unknown")}"}""")
        }
    }
    
    /**
     * 获取当前执行模式
     */
    private fun handleGetExecutionMode(output: PrintWriter) {
        try {
            val engine = scriptEngine
            if (engine == null) {
                output.println("""{"error":"NO_ENGINE","message":"脚本引擎未初始化"}""")
                return
            }
            
            val mode = engine.executionMode
            output.println(gson.toJson(mapOf(
                "success" to true,
                "mode" to mode.name,
                "display_name" to mode.displayName,
                "emoji" to mode.emoji,
                "description" to mode.description,
                "token_cost" to mode.tokenCostLevel.displayName
            )))
        } catch (e: Exception) {
            output.println("""{"error":"GET_MODE_FAILED","message":"${escapeJson(e.message ?: "Unknown")}"}""")
        }
    }
    
    /**
     * 列出所有可用执行模式
     */
    private fun handleListExecutionModes(output: PrintWriter) {
        try {
            val modes = com.employee.agent.domain.execution.ExecutionMode.values().map { mode ->
                mapOf(
                    "name" to mode.name,
                    "display_name" to mode.displayName,
                    "emoji" to mode.emoji,
                    "description" to mode.description,
                    "token_cost" to mode.tokenCostLevel.displayName,
                    "is_current" to (scriptEngine?.executionMode == mode)
                )
            }
            
            output.println(gson.toJson(mapOf(
                "success" to true,
                "modes" to modes,
                "current" to (scriptEngine?.executionMode?.name ?: "SMART")
            )))
        } catch (e: Exception) {
            output.println("""{"error":"LIST_MODES_FAILED","message":"${escapeJson(e.message ?: "Unknown")}"}""")
        }
    }
    
    /**
     * 测试弹窗清理功能
     */
    private fun handleTestPopupDismiss(output: PrintWriter) {
        try {
            val popupDismisser = com.employee.agent.infrastructure.popup.PopupDismisser(service)
            
            // 先检测弹窗
            val detection = popupDismisser.detectPopup()
            
            if (!detection.hasPopup) {
                output.println(gson.toJson(mapOf(
                    "success" to true,
                    "has_popup" to false,
                    "message" to "当前屏幕没有检测到弹窗"
                )))
                return
            }
            
            // 尝试关闭弹窗
            val dismissed = popupDismisser.dismissPopupOnce()
            
            output.println(gson.toJson(mapOf(
                "success" to true,
                "has_popup" to true,
                "popup_type" to detection.popupType,
                "close_button" to detection.closeButtonText,
                "confidence" to detection.confidence,
                "dismissed" to dismissed,
                "message" to if (dismissed) "成功关闭弹窗" else "检测到弹窗但关闭失败"
            )))
        } catch (e: Exception) {
            Log.e("Agent", "TEST_POPUP_DISMISS 失败", e)
            output.println("""{"error":"POPUP_TEST_FAILED","message":"${escapeJson(e.message ?: "Unknown")}"}""")
        }
    }
    
    /**
     * 使用指定模式执行脚本
     */
    private fun handleScriptExecuteWithMode(params: String, output: PrintWriter, socket: Socket) {
        val parts = params.split(":")
        if (parts.size < 2) {
            output.println("""{"error":"INVALID_PARAMS","message":"格式: SCRIPT_EXECUTE_WITH_MODE:scriptId:MODE"}""")
            return
        }
        
        val scriptId = parts[0]
        val modeName = parts[1].uppercase()
        
        val engine = scriptEngine
        if (engine == null) {
            output.println("""{"error":"NO_ENGINE","message":"脚本引擎未初始化，请先设置 API Key"}""")
            return
        }
        
        val mode = try {
            com.employee.agent.domain.execution.ExecutionMode.valueOf(modeName)
        } catch (e: Exception) {
            output.println("""{"error":"INVALID_MODE","message":"无效的执行模式: $modeName"}""")
            return
        }
        
        output.println("""{"status":"STARTED","script_id":"$scriptId","mode":"${mode.name}","mode_display":"${mode.emoji} ${mode.displayName}"}""")
        output.flush()
        
        scope.launch {
            try {
                val result = engine.executeScriptWithMode(scriptId, mode) { current, total, desc ->
                    val progress = mapOf(
                        "status" to "PROGRESS",
                        "current" to current,
                        "total" to total,
                        "description" to desc,
                        "mode" to mode.name
                    )
                    output.println(gson.toJson(progress))
                    output.flush()
                }
                
                val finalResult = mapOf(
                    "status" to if (result.success) "COMPLETED" else "FAILED",
                    "success" to result.success,
                    "steps_executed" to result.stepsExecuted,
                    "total_steps" to result.totalSteps,
                    "error" to result.error,
                    "mode" to mode.name,
                    "popups_dismissed" to result.popupsDismissed,
                    "ai_interventions" to result.aiInterventions,
                    "logs" to result.logs
                )
                output.println(gson.toJson(finalResult))
                output.flush()
                
            } catch (e: Exception) {
                Log.e("Agent", "SCRIPT_EXECUTE_WITH_MODE 失败", e)
                output.println("""{"status":"ERROR","error":"${escapeJson(e.message ?: "Unknown")}"}""")
                output.flush()
            } finally {
                socket.close()
            }
        }
    }
    
    // ==================== 🔧 调试命令处理 ====================
    
    /**
     * 获取完整调试状态
     */
    private fun handleDebugStatus(output: PrintWriter) {
        try {
            val debugInterface = com.employee.agent.infrastructure.debug.DebugInterface.getInstance()
            output.println(debugInterface.getFullStatus(service, scriptEngine))
        } catch (e: Exception) {
            Log.e("Agent", "DEBUG_STATUS 失败", e)
            output.println("""{"error":"DEBUG_FAILED","message":"${escapeJson(e.message ?: "Unknown")}"}""")
        }
    }
    
    /**
     * 获取最后一个错误
     */
    private fun handleDebugLastError(output: PrintWriter) {
        try {
            val debugInterface = com.employee.agent.infrastructure.debug.DebugInterface.getInstance()
            output.println(debugInterface.getLastError())
        } catch (e: Exception) {
            output.println("""{"error":"DEBUG_FAILED","message":"${escapeJson(e.message ?: "Unknown")}"}""")
        }
    }
    
    /**
     * 获取错误历史
     */
    private fun handleDebugErrorHistory(limit: Int, output: PrintWriter) {
        try {
            val debugInterface = com.employee.agent.infrastructure.debug.DebugInterface.getInstance()
            output.println(debugInterface.getErrorHistory(limit))
        } catch (e: Exception) {
            output.println("""{"error":"DEBUG_FAILED","message":"${escapeJson(e.message ?: "Unknown")}"}""")
        }
    }
    
    /**
     * 获取执行历史
     */
    private fun handleDebugExecutionHistory(limit: Int, output: PrintWriter) {
        try {
            val debugInterface = com.employee.agent.infrastructure.debug.DebugInterface.getInstance()
            output.println(debugInterface.getExecutionHistory(limit))
        } catch (e: Exception) {
            output.println("""{"error":"DEBUG_FAILED","message":"${escapeJson(e.message ?: "Unknown")}"}""")
        }
    }
    
    /**
     * 获取调试日志
     */
    private fun handleDebugLogs(limit: Int, output: PrintWriter) {
        try {
            val debugInterface = com.employee.agent.infrastructure.debug.DebugInterface.getInstance()
            output.println(debugInterface.getRecentLogs(limit))
        } catch (e: Exception) {
            output.println("""{"error":"DEBUG_FAILED","message":"${escapeJson(e.message ?: "Unknown")}"}""")
        }
    }
    
    /**
     * 健康检查
     */
    private fun handleDebugHealth(output: PrintWriter) {
        try {
            val debugInterface = com.employee.agent.infrastructure.debug.DebugInterface.getInstance()
            output.println(debugInterface.getHealthCheck(service, scriptEngine))
        } catch (e: Exception) {
            output.println("""{"error":"DEBUG_FAILED","message":"${escapeJson(e.message ?: "Unknown")}"}""")
        }
    }
    
    /**
     * 获取当前屏幕信息（使用改进的 getRootNode）
     */
    private fun handleDebugScreen(output: PrintWriter) {
        try {
            val root = getRootNode()
            if (root == null) {
                output.println("""{"error":"NO_ROOT","message":"无法获取 UI 树，请确保目标应用在前台，或尝试重新开启无障碍服务"}""")
                return
            }
            
            val packageName = root.packageName?.toString() ?: "unknown"
            val className = root.className?.toString() ?: "unknown"
            
            // 收集基本元素信息
            val elements = mutableListOf<Map<String, Any?>>()
            collectScreenElements(root, elements, 0, 20) // 最多收集 20 个元素
            
            val response = mapOf(
                "success" to true,
                "package" to packageName,
                "activity" to className,
                "element_count" to elements.size,
                "elements" to elements
            )
            
            output.println(gson.toJson(response))
        } catch (e: Exception) {
            Log.e("Agent", "DEBUG_SCREEN 失败", e)
            output.println("""{"error":"DEBUG_FAILED","message":"${escapeJson(e.message ?: "Unknown")}"}""")
        }
    }
    
    /**
     * 🆕 调试：获取所有窗口信息
     */
    private fun handleDebugWindows(output: PrintWriter) {
        try {
            val windowInfos = mutableListOf<Map<String, Any?>>()
            
            // 检查 rootInActiveWindow
            val rootActive = service.rootInActiveWindow
            val rootActiveInfo = if (rootActive != null) {
                mapOf(
                    "source" to "rootInActiveWindow",
                    "package" to rootActive.packageName?.toString(),
                    "class" to rootActive.className?.toString(),
                    "child_count" to rootActive.childCount
                )
            } else {
                mapOf("source" to "rootInActiveWindow", "status" to "NULL")
            }
            windowInfos.add(rootActiveInfo)
            
            // 检查 windows API
            val windows = service.windows
            if (windows != null) {
                for ((index, window) in windows.withIndex()) {
                    val root = window.root
                    windowInfos.add(mapOf(
                        "source" to "windows[$index]",
                        "type" to window.type,
                        "type_name" to when(window.type) {
                            1 -> "APPLICATION"
                            2 -> "INPUT_METHOD"
                            3 -> "SYSTEM"
                            4 -> "ACCESSIBILITY_OVERLAY"
                            else -> "UNKNOWN(${window.type})"
                        },
                        "is_active" to window.isActive,
                        "is_focused" to window.isFocused,
                        "has_root" to (root != null),
                        "package" to root?.packageName?.toString(),
                        "child_count" to (root?.childCount ?: 0)
                    ))
                }
            } else {
                windowInfos.add(mapOf("source" to "windows", "status" to "NULL"))
            }
            
            output.println(gson.toJson(mapOf(
                "success" to true,
                "window_count" to windows?.size,
                "windows" to windowInfos
            )))
        } catch (e: Exception) {
            Log.e("Agent", "DEBUG_WINDOWS 失败", e)
            output.println("""{"error":"DEBUG_FAILED","message":"${escapeJson(e.message ?: "Unknown")}"}""")
        }
    }
    
    /**
     * 递归收集屏幕元素
     */
    private fun collectScreenElements(
        node: AccessibilityNodeInfo, 
        elements: MutableList<Map<String, Any?>>,
        depth: Int,
        maxElements: Int
    ) {
        if (elements.size >= maxElements) return
        
        val text = node.text?.toString()
        val desc = node.contentDescription?.toString()
        val resourceId = node.viewIdResourceName
        
        // 只收集有文本或可点击的元素
        if (!text.isNullOrBlank() || !desc.isNullOrBlank() || node.isClickable) {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            
            elements.add(mapOf(
                "text" to text,
                "description" to desc,
                "resource_id" to resourceId,
                "class" to node.className?.toString()?.substringAfterLast('.'),
                "clickable" to node.isClickable,
                "bounds" to "${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}",
                "depth" to depth
            ))
        }
        
        // 递归子节点
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectScreenElements(child, elements, depth + 1, maxElements)
        }
    }
    
    /**
     * 清除调试历史
     */
    private fun handleDebugClear(output: PrintWriter) {
        try {
            val debugInterface = com.employee.agent.infrastructure.debug.DebugInterface.getInstance()
            debugInterface.clearHistory()
            output.println("""{"success":true,"message":"调试历史已清除"}""")
        } catch (e: Exception) {
            output.println("""{"error":"DEBUG_FAILED","message":"${escapeJson(e.message ?: "Unknown")}"}""")
        }
    }
    
    /**
     * 调试命令帮助
     */
    private fun handleDebugHelp(output: PrintWriter) {
        val help = mapOf(
            "version" to "3.1",
            "description" to "Agent 调试接口 - 为外部 AI (如 Copilot) 提供实时状态查询",
            "commands" to mapOf(
                "DEBUG_STATUS" to "获取完整运行状态（推荐首选）",
                "DEBUG_LAST_ERROR" to "获取最后一个错误详情",
                "DEBUG_ERROR_HISTORY" to "获取错误历史 (可选 :limit，如 DEBUG_ERROR_HISTORY:5)",
                "DEBUG_EXECUTION_HISTORY" to "获取执行历史 (可选 :limit)",
                "DEBUG_LOGS" to "获取最近调试日志 (可选 :limit)",
                "DEBUG_HEALTH" to "系统健康检查",
                "DEBUG_SCREEN" to "获取当前屏幕元素信息",
                "DEBUG_CLEAR" to "清除调试历史",
                "DEBUG_HELP" to "显示此帮助"
            ),
            "other_commands" to listOf(
                "STATUS - 系统状态",
                "DUMP - 获取完整 UI 树",
                "ANALYZE - 智能屏幕分析",
                "SCRIPT_LIST - 脚本列表",
                "SCRIPT_EXECUTE:id - 执行脚本",
                "SET_API_KEY:key - 设置 API Key"
            ),
            "usage_example" to "echo 'DEBUG_STATUS' | nc localhost 11451"
        )
        output.println(gson.toJson(help))
    }
    
    // ==================== 🧠 智能意图处理 ====================
    
    /**
     * 🧠 智能执行：先分析意图，再决定走脚本还是聊天
     */
    private fun handleSmartExecute(userInput: String, output: PrintWriter, socket: Socket) {
        val engine = scriptEngine
        if (engine == null) {
            output.println("""{"error":"NO_ENGINE","message":"请先设置 API Key"}""")
            return
        }
        
        output.println("""{"status":"analyzing","input":"${escapeJson(userInput)}"}""")
        output.flush()
        
        scope.launch {
            try {
                // 第一步：分析意图
                val intentResult = engine.analyzeIntent(userInput)
                
                when (intentResult.intent) {
                    com.employee.agent.application.ScriptEngine.UserIntent.CHAT -> {
                        // 聊天意图 - 直接返回 AI 回复
                        output.println("""{"status":"chat","response":"${escapeJson(intentResult.chatResponse ?: "我可以帮你操作手机，比如'打开小红书'、'搜索CPU价格'等。")}","confidence":${intentResult.confidence}}""")
                        output.flush()
                        socket.close()
                    }
                    
                    com.employee.agent.application.ScriptEngine.UserIntent.PHONE_OPERATION -> {
                        // 操作意图 - 走脚本流程
                        val goal = intentResult.operationGoal ?: userInput
                        output.println("""{"status":"operation","goal":"${escapeJson(goal)}","confidence":${intentResult.confidence}}""")
                        output.flush()
                        
                        // 继续脚本生成和执行流程
                        handleScriptGenerateInternal(goal, output, socket, engine)
                    }
                    
                    else -> {
                        // 不确定 - 默认当作操作处理
                        output.println("""{"status":"operation","goal":"${escapeJson(userInput)}","confidence":${intentResult.confidence},"note":"意图不明确，尝试作为操作处理"}""")
                        output.flush()
                        handleScriptGenerateInternal(userInput, output, socket, engine)
                    }
                }
            } catch (e: Exception) {
                Log.e("Agent", "智能执行失败", e)
                output.println("""{"status":"error","error":"${escapeJson(e.message ?: "Unknown")}"}""")
                output.flush()
                socket.close()
            }
        }
    }
    
    /**
     * 仅分析意图（不执行）
     */
    private fun handleAnalyzeIntent(userInput: String, output: PrintWriter) {
        val engine = scriptEngine
        if (engine == null) {
            output.println("""{"error":"NO_ENGINE","message":"请先设置 API Key"}""")
            return
        }
        
        scope.launch {
            try {
                val result = engine.analyzeIntent(userInput)
                output.println("""{
                    "success": true,
                    "intent": "${result.intent}",
                    "confidence": ${result.confidence},
                    "chat_response": ${if (result.chatResponse != null) "\"${escapeJson(result.chatResponse)}\"" else "null"},
                    "operation_goal": ${if (result.operationGoal != null) "\"${escapeJson(result.operationGoal)}\"" else "null"}
                }""".trimIndent().replace("\n", "").replace("  ", ""))
            } catch (e: Exception) {
                output.println("""{"error":"ANALYZE_FAILED","message":"${escapeJson(e.message ?: "Unknown")}"}""")
            }
        }
    }
    
    /**
     * 脚本生成内部实现（供智能执行调用）
     */
    private suspend fun handleScriptGenerateInternal(
        goal: String, 
        output: PrintWriter, 
        socket: Socket, 
        engine: com.employee.agent.application.ScriptEngine
    ) {
        engine.onLog = { log ->
            output.println("""{"log":"${escapeJson(log)}"}""")
            output.flush()
        }
        
        val result = engine.generateScript(goal)
        result.onSuccess { script ->
            output.println("""{"status":"generated","script_id":"${script.id}","name":"${escapeJson(script.name)}","steps":${script.steps.size}}""")
            output.flush()
            
            // 自动执行
            output.println("""{"status":"executing"}""")
            output.flush()
            
            val execResult = engine.executeWithAutoImprove(script.id) { current, total, desc ->
                output.println("""{"progress":$current,"total":$total,"step":"${escapeJson(desc)}"}""")
                output.flush()
            }
            
            if (execResult.success) {
                output.println("""{"status":"completed","steps_executed":${execResult.stepsExecuted},"extracted_data":${gson.toJson(execResult.extractedData)}}""")
            } else {
                output.println("""{"status":"failed","error":"${escapeJson(execResult.error ?: "Unknown")}","steps_executed":${execResult.stepsExecuted},"failed_step":${execResult.failedStepIndex ?: -1}}""")
            }
            output.flush()
        }
        result.onFailure { e ->
            output.println("""{"status":"error","error":"${escapeJson(e.message ?: "Unknown")}"}""")
            output.flush()
        }
        
        socket.close()
    }

    /**
     * 🆕 智能屏幕分析（使用改进的 getRootNode）
     */
    private fun handleAnalyzeScreen(output: PrintWriter) {
        try {
            val root = getRootNode()
            if (root == null) {
                output.println("""{"error":"NO_ROOT","message":"No root window available"}""")
                return
            }
            
            // 将 AccessibilityNodeInfo 转换为 UINode
            val uiNode = convertToUINode(root)
            
            // 使用 ScreenAnalyzer 进行智能分析
            val analysis = screenAnalyzer.analyze(uiNode)
            
            // 构建 JSON 响应
            val response = buildString {
                append("{")
                append("\"success\":true,")
                append("\"app_context\":\"${analysis.appContext}\",")
                append("\"page_type\":\"${analysis.pageType}\",")
                append("\"summary\":\"${escapeJson(analysis.summary)}\",")
                append("\"interactive_count\":${analysis.interactiveElements.size},")
                append("\"data_count\":${analysis.dataElements.size},")
                append("\"hot_count\":${analysis.hotContent.size},")
                append("\"interactive_elements\":[")
                append(analysis.interactiveElements.take(10).joinToString(",") { elem ->
                    "{\"text\":\"${escapeJson(elem.text)}\",\"class\":\"${elem.className}\",\"bounds\":\"${elem.bounds}\"}"
                })
                append("],")
                append("\"hot_content\":[")
                append(analysis.hotContent.joinToString(",") { hot ->
                    "{\"text\":\"${escapeJson(hot.text)}\",\"value\":${hot.value}}"
                })
                append("],")
                append("\"ai_summary\":\"${escapeJson(screenAnalyzer.generateAISummary(analysis))}\"")
                append("}")
            }
            
            output.println(response)
            Log.i("Agent", "分析完成: ${analysis.interactiveElements.size} 个交互元素, ${analysis.hotContent.size} 个热门内容")
            
        } catch (e: Exception) {
            Log.e("Agent", "分析失败", e)
            output.println("""{"error":"ANALYZE_FAILED","message":"${escapeJson(e.message ?: "Unknown error")}"}""")
        }
    }
    
    /**
     * 🆕 生成执行脚本（使用改进的 getRootNode）
     */
    private fun handleGenerateScript(goal: String, output: PrintWriter) {
        try {
            if (goal.isBlank()) {
                output.println("""{"error":"EMPTY_GOAL","message":"Goal cannot be empty"}""")
                return
            }
            
            val root = getRootNode()
            if (root == null) {
                output.println("""{"error":"NO_ROOT","message":"No root window available"}""")
                return
            }
            
            // 先分析屏幕
            val uiNode = convertToUINode(root)
            val analysis = screenAnalyzer.analyze(uiNode)
            
            // 生成脚本
            val script = scriptGenerator.generateScript(goal, analysis)
            val scriptJson = scriptGenerator.toJson(script)
            
            output.println(scriptJson)
            Log.i("Agent", "脚本生成完成: ${script.steps.size} 步骤")
            
        } catch (e: Exception) {
            Log.e("Agent", "脚本生成失败", e)
            output.println("""{"error":"SCRIPT_FAILED","message":"${escapeJson(e.message ?: "Unknown error")}"}""")
        }
    }
    
    /**
     * 🆕 设置 API Key
     */
    private fun handleSetApiKey(key: String, output: PrintWriter) {
        if (key.isBlank()) {
            output.println("""{"error":"EMPTY_KEY","message":"API key cannot be empty"}""")
            return
        }
        
        setApiKey(key)
        output.println("""{"success":true,"message":"API Key 已设置，AI 引擎已就绪"}""")
    }
    
    /**
     * 🆕 AI 自主执行目标 (流式返回执行日志)
     */
    private fun handleRunAIGoal(goal: String, output: PrintWriter, socket: Socket) {
        if (goal.isBlank()) {
            output.println("""{"error":"EMPTY_GOAL","message":"Goal cannot be empty"}""")
            socket.close()
            return
        }
        
        val engine = aiEngine
        if (engine == null) {
            output.println("""{"error":"NO_AI_ENGINE","message":"请先使用 SET_API_KEY:your_key 设置 API Key"}""")
            socket.close()
            return
        }
        
        // 启动协程执行
        scope.launch {
            try {
                output.println("""{"status":"started","goal":"${escapeJson(goal)}"}""")
                output.flush()
                
                val result = engine.executeGoal(goal)
                
                // 返回执行结果
                val logsJson = result.logs.joinToString(",") { log ->
                    """{"time":${log.timestamp},"type":"${log.type}","content":"${escapeJson(log.content)}"}"""
                }
                
                output.println("""{"status":"completed","success":${result.success},"message":"${escapeJson(result.message)}","steps_executed":${result.stepsExecuted},"logs":[$logsJson]}""")
                output.flush()
                
            } catch (e: Exception) {
                Log.e("Agent", "AI 执行失败", e)
                output.println("""{"status":"error","message":"${escapeJson(e.message ?: "Unknown error")}"}""")
            } finally {
                socket.close()
            }
        }
    }
    
    /**
     * 🆕 停止 AI 执行
     */
    private fun handleStopAI(output: PrintWriter) {
        aiEngine?.stop()
        output.println("""{"success":true,"message":"已发送停止信号"}""")
    }
    
    /**
     * 将 AccessibilityNodeInfo 转换为 UINode
     */
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
    
    /**
     * JSON 字符串转义
     */
    private fun escapeJson(text: String): String {
        return text
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }

    private fun serializeNode(node: AccessibilityNodeInfo): NodeData {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        
        val data = NodeData(
            className = node.className?.toString() ?: "",
            text = node.text?.toString(),
            contentDescription = node.contentDescription?.toString(),
            resourceId = node.viewIdResourceName,
            bounds = "${rect.left},${rect.top},${rect.right},${rect.bottom}",
            children = mutableListOf()
        )

        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                data.children.add(serializeNode(child))
            }
        }
        return data
    }
    
    // ========== 🆕 脚本系统命令处理 ==========
    
    /**
     * 生成脚本
     */
    private fun handleScriptGenerate(goal: String, output: PrintWriter, socket: Socket) {
        val engine = scriptEngine
        if (engine == null) {
            output.println("""{"error":"NO_SCRIPT_ENGINE","message":"请先设置 API Key"}""")
            socket.close()
            return
        }
        
        output.println("""{"status":"generating","goal":"$goal"}""")
        output.flush()
        
        scope.launch {
            try {
                engine.onLog = { log ->
                    output.println("""{"log":"${escapeJson(log)}"}""")
                    output.flush()
                }
                
                val result = engine.generateScript(goal)
                
                result.fold(
                    onSuccess = { script ->
                        output.println("""{"status":"success","script":${gson.toJson(script)}}""")
                    },
                    onFailure = { error ->
                        output.println("""{"status":"error","error":"${escapeJson(error.message ?: "Unknown error")}"}""")
                    }
                )
            } catch (e: Exception) {
                output.println("""{"status":"error","error":"${escapeJson(e.message ?: "Unknown error")}"}""")
            } finally {
                engine.onLog = null
                socket.close()
            }
        }
    }
    
    /**
     * 执行脚本
     */
    private fun handleScriptExecute(scriptId: String, output: PrintWriter, socket: Socket) {
        val engine = scriptEngine
        if (engine == null) {
            output.println("""{"error":"NO_SCRIPT_ENGINE","message":"请先设置 API Key"}""")
            socket.close()
            return
        }
        
        output.println("""{"status":"executing","script_id":"$scriptId"}""")
        output.flush()
        
        scope.launch {
            try {
                engine.onLog = { log ->
                    output.println("""{"log":"${escapeJson(log)}"}""")
                    output.flush()
                }
                
                val result = engine.executeScript(scriptId) { step, total, desc ->
                    output.println("""{"progress":{"step":$step,"total":$total,"description":"${escapeJson(desc)}"}}""")
                    output.flush()
                }
                
                output.println("""{"status":"complete","result":${gson.toJson(result)}}""")
            } catch (e: Exception) {
                output.println("""{"status":"error","error":"${escapeJson(e.message ?: "Unknown error")}"}""")
            } finally {
                engine.onLog = null
                socket.close()
            }
        }
    }
    
    /**
     * 执行脚本（自动改进模式）
     */
    private fun handleScriptExecuteAuto(scriptId: String, output: PrintWriter, socket: Socket) {
        val engine = scriptEngine
        if (engine == null) {
            output.println("""{"error":"NO_SCRIPT_ENGINE","message":"请先设置 API Key"}""")
            socket.close()
            return
        }
        
        output.println("""{"status":"executing_auto","script_id":"$scriptId"}""")
        output.flush()
        
        scope.launch {
            try {
                engine.onLog = { log ->
                    output.println("""{"log":"${escapeJson(log)}"}""")
                    output.flush()
                }
                
                val result = engine.executeWithAutoImprove(scriptId) { step, total, desc ->
                    output.println("""{"progress":{"step":$step,"total":$total,"description":"${escapeJson(desc)}"}}""")
                    output.flush()
                }
                
                output.println("""{"status":"complete","result":${gson.toJson(result)}}""")
            } catch (e: Exception) {
                output.println("""{"status":"error","error":"${escapeJson(e.message ?: "Unknown error")}"}""")
            } finally {
                engine.onLog = null
                socket.close()
            }
        }
    }
    
    /**
     * 手动改进脚本
     */
    private fun handleScriptImprove(scriptId: String, output: PrintWriter) {
        val engine = scriptEngine
        if (engine == null) {
            output.println("""{"error":"NO_SCRIPT_ENGINE","message":"请先设置 API Key"}""")
            return
        }
        
        val script = engine.loadScript(scriptId)
        if (script == null) {
            output.println("""{"error":"SCRIPT_NOT_FOUND","message":"脚本不存在: $scriptId"}""")
            return
        }
        
        // 创建一个模拟的失败结果用于改进
        val mockFailResult = com.employee.agent.domain.script.ScriptExecutionResult(
            success = false,
            stepsExecuted = 0,
            totalSteps = script.steps.size,
            error = "手动触发改进",
            logs = listOf("用户请求改进脚本")
        )
        
        scope.launch {
            val improvedScript = engine.improveScript(script, mockFailResult)
            if (improvedScript != null) {
                engine.saveScript(improvedScript)
                output.println("""{"status":"improved","script":${gson.toJson(improvedScript)}}""")
            } else {
                output.println("""{"status":"no_improvement","message":"AI 未提供改进建议"}""")
            }
        }
    }
    
    /**
     * 获取脚本详情
     */
    private fun handleScriptGet(scriptId: String, output: PrintWriter) {
        val engine = scriptEngine
        if (engine == null) {
            output.println("""{"error":"NO_SCRIPT_ENGINE","message":"请先设置 API Key"}""")
            return
        }
        
        val script = engine.loadScript(scriptId)
        if (script != null) {
            output.println(gson.toJson(script))
        } else {
            output.println("""{"error":"SCRIPT_NOT_FOUND","message":"脚本不存在: $scriptId"}""")
        }
    }
    
    /**
     * 列出所有脚本
     */
    private fun handleScriptList(output: PrintWriter) {
        val engine = scriptEngine
        if (engine == null) {
            output.println("""{"error":"NO_SCRIPT_ENGINE","message":"请先设置 API Key"}""")
            return
        }
        
        val scripts = engine.listScripts()
        val summaries = scripts.map { script ->
            mapOf(
                "id" to script.id,
                "name" to script.name,
                "goal" to script.goal,
                "version" to script.version,
                "steps_count" to script.steps.size,
                "success_count" to script.successCount,
                "fail_count" to script.failCount,
                "created_at" to script.createdAt
            )
        }
        output.println(gson.toJson(summaries))
    }
    
    /**
     * 删除脚本
     */
    private fun handleScriptDelete(scriptId: String, output: PrintWriter) {
        val engine = scriptEngine
        if (engine == null) {
            output.println("""{"error":"NO_SCRIPT_ENGINE","message":"请先设置 API Key"}""")
            return
        }
        
        val success = engine.deleteScript(scriptId)
        if (success) {
            output.println("""{"status":"deleted","script_id":"$scriptId"}""")
        } else {
            output.println("""{"error":"DELETE_FAILED","message":"删除失败: $scriptId"}""")
        }
    }
    
    // ==================== 📸 屏幕获取模式命令处理 ====================
    
    /**
     * 获取 SmartScreenReader（从 AgentService）
     */
    private fun getSmartScreenReader(): com.employee.agent.infrastructure.accessibility.SmartScreenReader? {
        return (service as? AgentService)?.smartScreenReader
    }
    
    /**
     * 设置屏幕获取模式
     */
    private fun handleSetScreenMode(modeName: String, output: PrintWriter) {
        try {
            val reader = getSmartScreenReader()
            if (reader == null) {
                output.println("""{"error":"NO_READER","message":"SmartScreenReader 未初始化"}""")
                return
            }
            
            val mode = com.employee.agent.domain.screen.ScreenCaptureMode.fromString(modeName)
            reader.setMode(mode)
            
            Log.i("Agent", "屏幕获取模式已切换为: ${mode.emoji} ${mode.displayName}")
            
            output.println(gson.toJson(mapOf(
                "success" to true,
                "mode" to mode.name,
                "display_name" to mode.displayName,
                "emoji" to mode.emoji,
                "description" to mode.description,
                "token_cost" to mode.tokenCost
            )))
        } catch (e: Exception) {
            Log.e("Agent", "SET_SCREEN_MODE 失败", e)
            output.println("""{"error":"SET_MODE_FAILED","message":"${escapeJson(e.message ?: "Unknown")}"}""")
        }
    }
    
    /**
     * 获取当前屏幕获取模式
     */
    private fun handleGetScreenMode(output: PrintWriter) {
        try {
            val reader = getSmartScreenReader()
            if (reader == null) {
                output.println("""{"error":"NO_READER","message":"SmartScreenReader 未初始化"}""")
                return
            }
            
            val mode = reader.currentMode
            output.println(gson.toJson(mapOf(
                "success" to true,
                "mode" to mode.name,
                "display_name" to mode.displayName,
                "emoji" to mode.emoji,
                "description" to mode.description,
                "token_cost" to mode.tokenCost
            )))
        } catch (e: Exception) {
            output.println("""{"error":"GET_MODE_FAILED","message":"${escapeJson(e.message ?: "Unknown")}"}""")
        }
    }
    
    /**
     * 列出所有屏幕获取模式
     */
    private fun handleListScreenModes(output: PrintWriter) {
        try {
            val reader = getSmartScreenReader()
            val currentMode = reader?.currentMode
            
            val modes = com.employee.agent.domain.screen.ScreenCaptureMode.values().map { mode ->
                mapOf(
                    "name" to mode.name,
                    "display_name" to mode.displayName,
                    "emoji" to mode.emoji,
                    "description" to mode.description,
                    "token_cost" to mode.tokenCost,
                    "is_current" to (mode == currentMode)
                )
            }
            
            output.println(gson.toJson(mapOf(
                "success" to true,
                "modes" to modes,
                "current" to (currentMode?.name ?: "FULL_DUMP")
            )))
        } catch (e: Exception) {
            output.println("""{"error":"LIST_MODES_FAILED","message":"${escapeJson(e.message ?: "Unknown")}"}""")
        }
    }
    
    /**
     * 获取屏幕差异（与基准快照对比）
     */
    private fun handleScreenDiff(output: PrintWriter) {
        try {
            val reader = getSmartScreenReader()
            if (reader == null) {
                output.println("""{"error":"NO_READER","message":"SmartScreenReader 未初始化"}""")
                return
            }
            
            val diff = reader.getDiffFromBaseline()
            val summary = reader.getDiffSummaryForAI()
            
            output.println(gson.toJson(mapOf(
                "success" to true,
                "has_changes" to diff.hasChanges,
                "summary" to diff.summary,
                "ai_summary" to summary,
                "added_count" to diff.addedNodes.size,
                "removed_count" to diff.removedNodes.size,
                "modified_count" to diff.modifiedNodes.size,
                "added_preview" to diff.addedNodes.take(5).map { it.text ?: it.className },
                "removed_preview" to diff.removedNodes.take(5).map { it.text ?: it.className }
            )))
        } catch (e: Exception) {
            Log.e("Agent", "SCREEN_DIFF 失败", e)
            output.println("""{"error":"DIFF_FAILED","message":"${escapeJson(e.message ?: "Unknown")}"}""")
        }
    }
    
    /**
     * 获取增量变化（自上次查询以来）
     */
    private fun handleScreenChanges(output: PrintWriter) {
        try {
            val reader = getSmartScreenReader()
            if (reader == null) {
                output.println("""{"error":"NO_READER","message":"SmartScreenReader 未初始化"}""")
                return
            }
            
            val changes = reader.getIncrementalChanges()
            val summary = reader.getChangesSummary()
            
            output.println(gson.toJson(mapOf(
                "success" to true,
                "has_changes" to changes.isNotEmpty(),
                "change_count" to changes.size,
                "summary" to summary,
                "changes" to changes.map { event ->
                    mapOf(
                        "type" to event.eventType.name,
                        "timestamp" to event.timestamp,
                        "package" to event.packageName,
                        "description" to event.description,
                        "node_text" to event.changedNode?.text
                    )
                }
            )))
        } catch (e: Exception) {
            Log.e("Agent", "SCREEN_CHANGES 失败", e)
            output.println("""{"error":"CHANGES_FAILED","message":"${escapeJson(e.message ?: "Unknown")}"}""")
        }
    }
    
    /**
     * 拍摄基准快照（用于 DIFF 模式）
     */
    private fun handleScreenSnapshot(output: PrintWriter) {
        try {
            val reader = getSmartScreenReader()
            if (reader == null) {
                output.println("""{"error":"NO_READER","message":"SmartScreenReader 未初始化"}""")
                return
            }
            
            reader.takeBaselineSnapshot()
            
            output.println(gson.toJson(mapOf(
                "success" to true,
                "message" to "基准快照已拍摄",
                "timestamp" to System.currentTimeMillis()
            )))
        } catch (e: Exception) {
            Log.e("Agent", "SCREEN_SNAPSHOT 失败", e)
            output.println("""{"error":"SNAPSHOT_FAILED","message":"${escapeJson(e.message ?: "Unknown")}"}""")
        }
    }
    
    /**
     * 获取屏幕读取统计
     */
    private fun handleScreenStats(output: PrintWriter) {
        try {
            val reader = getSmartScreenReader()
            if (reader == null) {
                output.println("""{"error":"NO_READER","message":"SmartScreenReader 未初始化"}""")
                return
            }
            
            val stats = reader.getStats()
            output.println(stats.toJson())
        } catch (e: Exception) {
            Log.e("Agent", "SCREEN_STATS 失败", e)
            output.println("""{"error":"STATS_FAILED","message":"${escapeJson(e.message ?: "Unknown")}"}""")
        }
    }
}
