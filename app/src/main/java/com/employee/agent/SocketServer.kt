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
    
    // 🆕 AI 自主执行引擎 (需要 API Key)
    private var aiEngine: AIAutonomousEngine? = null
    
    // 🆕 脚本引擎
    private var scriptEngine: ScriptEngine? = null
    
    // API Key 配置 (用户的腾讯混元 Key)
    private var apiKey: String = ""
    
    // SharedPreferences 常量
    private companion object {
        const val PREF_NAME = "agent_ai_config"
        const val KEY_API_KEY = "api_key"
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
                    // 原有的 UI 树 dump
                    val root = service.rootInActiveWindow
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
                // ========== 状态检查 ==========
                command == "STATUS" -> {
                    // 状态检查
                    val hasAI = aiEngine != null
                    val hasScript = scriptEngine != null
                    val scriptCount = scriptEngine?.listScripts()?.size ?: 0
                    output.println("""{"status":"ok","version":"3.0","ai_enabled":$hasAI,"script_enabled":$hasScript,"script_count":$scriptCount,"features":["DUMP","ANALYZE","SET_API_KEY","RUN_AI_GOAL","STOP_AI","SCRIPT_GENERATE","SCRIPT_EXECUTE","SCRIPT_EXECUTE_AUTO","SCRIPT_IMPROVE","SCRIPT_GET","SCRIPT_LIST","SCRIPT_DELETE"]}""")
                }
                else -> {
                    output.println("""{"error":"UNKNOWN_COMMAND","message":"Unknown command: $command","supported":["DUMP","ANALYZE","SET_API_KEY:key","RUN_AI_GOAL:goal","STOP_AI","SCRIPT_GENERATE:goal","SCRIPT_EXECUTE:id","SCRIPT_EXECUTE_AUTO:id","SCRIPT_LIST","SCRIPT_GET:id","SCRIPT_DELETE:id","STATUS"]}""")
                }
            }

            socket.close()
        } catch (e: Exception) {
            Log.e("Agent", "Client handling error", e)
        }
    }
    
    /**
     * 🆕 智能屏幕分析
     */
    private fun handleAnalyzeScreen(output: PrintWriter) {
        try {
            val root = service.rootInActiveWindow
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
     * 🆕 生成执行脚本
     */
    private fun handleGenerateScript(goal: String, output: PrintWriter) {
        try {
            if (goal.isBlank()) {
                output.println("""{"error":"EMPTY_GOAL","message":"Goal cannot be empty"}""")
                return
            }
            
            val root = service.rootInActiveWindow
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
}
