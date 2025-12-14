# 📱 Android AI Agent 架构设计

## 🎯 设计原则

1. **长期主义**：模块化、可扩展、易维护
2. **分层架构**：Domain → Application → Infrastructure → Interface
3. **单一职责**：每个类只做一件事
4. **依赖倒置**：核心逻辑不依赖外部实现

---

## 📂 目录结构

```
app/src/main/java/com/employee/agent/
├── 📁 domain/                          # 领域层（核心业务逻辑）
│   ├── agent/
│   │   ├── AgentGoal.kt               # 目标定义
│   │   ├── AgentState.kt              # 状态机
│   │   ├── AgentMemory.kt             # 记忆系统
│   │   └── AgentAction.kt             # 动作类型
│   ├── screen/
│   │   ├── UINode.kt                  # UI 节点模型
│   │   └── ScreenSnapshot.kt          # 屏幕快照
│   └── tool/
│       └── ToolDefinition.kt          # 工具定义接口
│
├── 📁 application/                     # 应用层（用例编排）
│   ├── AgentRuntime.kt                # Agent 运行时
│   ├── AgentLoop.kt                   # 主循环逻辑
│   └── usecases/
│       ├── ExecuteGoalUseCase.kt      # 执行目标
│       └── RecoverFromErrorUseCase.kt # 错误恢复
│
├── 📁 infrastructure/                  # 基础设施层（外部依赖）
│   ├── accessibility/
│   │   ├── AccessibilityGestureExecutor.kt  # 手势执行
│   │   └── UITreeParser.kt            # UI 树解析
│   ├── ai/
│   │   ├── HunyuanAIClient.kt         # 混元 API 客户端
│   │   └── PromptBuilder.kt           # 提示词构建
│   ├── network/
│   │   ├── SocketCommandServer.kt     # Socket 服务器（兼容旧版）
│   │   └── WebSocketServer.kt         # WebSocket 服务器（新版）
│   └── storage/
│       ├── AgentDatabase.kt           # 本地数据库
│       └── LogStorage.kt              # 日志存储
│
└── 📁 interface/                       # 接口层（UI 和对外接口）
    ├── AgentService.kt                # 无障碍服务入口
    ├── AgentActivity.kt               # 配置界面
    └── notification/
        └── AgentNotification.kt       # 前台通知

```

---

## 🏗️ 核心模块职责

### 1️⃣ Domain Layer（领域层）- 纯业务逻辑

**不依赖 Android API，可单元测试**

```kotlin
// domain/agent/AgentState.kt
enum class AgentRunState {
    IDLE, THINKING, EXECUTING, OBSERVING, PAUSED, RECOVERING, STOPPED
}

// domain/agent/AgentGoal.kt
data class Goal(
    val id: String,
    val description: String,
    val completionCondition: CompletionCondition
)

// domain/tool/ToolDefinition.kt
interface Tool {
    val name: String
    val description: String
    suspend fun execute(params: Map<String, Any>): ToolResult
}
```

### 2️⃣ Application Layer（应用层）- 用例编排

```kotlin
// application/AgentRuntime.kt
class AgentRuntime(
    private val aiClient: AIClient,
    private val toolExecutor: ToolExecutor,
    private val screenReader: ScreenReader
) {
    suspend fun executeGoal(goal: Goal) {
        var state = AgentRunState.THINKING
        
        while (state != AgentRunState.STOPPED) {
            state = when (state) {
                THINKING -> thinkNextAction(goal)
                EXECUTING -> executeAction()
                OBSERVING -> observeResult()
                else -> state
            }
        }
    }
}
```

### 3️⃣ Infrastructure Layer（基础设施）- 技术实现

```kotlin
// infrastructure/accessibility/AccessibilityGestureExecutor.kt
class AccessibilityGestureExecutor(private val service: AccessibilityService) : ToolExecutor {
    override suspend fun tap(x: Int, y: Int): ToolResult {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        // dispatchGesture...
    }
}

// infrastructure/ai/HunyuanAIClient.kt
class HunyuanAIClient(private val apiKey: String) : AIClient {
    override suspend fun chat(messages: List<Message>): String {
        // HTTP 请求...
    }
}
```

### 4️⃣ Interface Layer（接口层）- 对外入口

```kotlin
// interface/AgentService.kt
class AgentService : AccessibilityService() {
    private lateinit var runtime: AgentRuntime
    
    override fun onServiceConnected() {
        // 依赖注入
        val gestureExecutor = AccessibilityGestureExecutor(this)
        val aiClient = HunyuanAIClient(apiKey)
        runtime = AgentRuntime(aiClient, gestureExecutor, ...)
    }
}
```

---

## 🔄 数据流向

```
用户设定目标
    ↓
AgentRuntime.executeGoal()
    ↓
┌─────────── Agent Loop ───────────┐
│  Thinking:  AI 分析 → 决定动作   │
│     ↓                            │
│  Executing: 调用 Tool 执行       │
│     ↓                            │
│  Observing: 获取屏幕状态         │
│     ↓                            │
│  [循环直到目标完成]              │
└──────────────────────────────────┘
```

---

## 🛠️ 工具系统设计

```kotlin
// domain/tool/ToolDefinition.kt
interface Tool {
    val name: String
    val description: String
    val parameters: List<ToolParameter>
    suspend fun execute(params: Map<String, Any>): ToolResult
}

// 内置工具
class TapTool(private val executor: GestureExecutor) : Tool {
    override val name = "tap"
    override val description = "点击屏幕坐标"
    override suspend fun execute(params: Map<String, Any>) = ...
}

class SwipeTool(...) : Tool { ... }
class InputTextTool(...) : Tool { ... }
```

---

## 💾 持久化设计

```kotlin
// infrastructure/storage/AgentDatabase.kt
@Database(entities = [GoalEntity::class, ActionLogEntity::class], version = 1)
abstract class AgentDatabase : RoomDatabase() {
    abstract fun goalDao(): GoalDao
    abstract fun actionLogDao(): ActionLogDao
}

// 存储内容：
// - 历史目标和完成情况
// - 执行日志（用于学习优化）
// - 错误记录（用于恢复策略）
```

---

## 🔐 安全设计

```kotlin
// infrastructure/security/PrivacyFilter.kt
class PrivacyFilter {
    fun shouldFilter(node: UINode): Boolean {
        // 过滤密码框、支付界面、敏感信息
        return node.isPassword || 
               node.packageName in sensitiveApps ||
               node.text?.contains(Regex("\\d{16}")) == true // 银行卡号
    }
}
```

---

## 📊 可观测性

```kotlin
// infrastructure/monitoring/AgentMonitor.kt
class AgentMonitor {
    fun logEvent(event: AgentEvent) { ... }
    fun getMetrics(): AgentMetrics { ... }
    fun exportLogs(): String { ... }
}

data class AgentMetrics(
    val totalGoals: Int,
    val successRate: Float,
    val averageSteps: Int,
    val errorRate: Float
)
```

---

## 🚀 扩展点

### 1. 新增工具
```kotlin
// 在 infrastructure/tools/ 下创建新类
class ReadNotificationTool(...) : Tool { ... }
// 在 ToolRegistry 中注册即可
```

### 2. 新增 AI 提供商
```kotlin
// 实现 AIClient 接口
class OpenAIClient(...) : AIClient { ... }
// 通过依赖注入切换
```

### 3. 新增通信协议
```kotlin
// 实现 CommandServer 接口
class gRPCServer(...) : CommandServer { ... }
```

---

## 📝 下一步实施计划

1. ✅ 定义 Domain 层核心模型
2. ✅ 实现 Application 层 AgentRuntime
3. ✅ 重构 Infrastructure 层（拆分 SocketServer）
4. ✅ 添加 AI 客户端集成
5. ✅ 实现完整工具系统
6. ✅ 添加持久化和日志
7. ✅ UI 和配置界面

---

这个架构的核心优势：
- 🧩 **模块化**：每层职责清晰，互不耦合
- 🧪 **可测试**：Domain 层纯逻辑，易于单元测试
- 🔄 **可替换**：基础设施层可随时更换实现
- 📈 **可扩展**：新增功能无需修改核心代码
