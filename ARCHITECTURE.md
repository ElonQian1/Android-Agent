# 📱 Android AI Agent 架构设计

> **Version 2.0** - 长期主义架构，支持多模态、智能记忆、任务规划、错误恢复、PC协同

## 🎯 设计原则

1. **长期主义**：模块化、可扩展、易维护
2. **分层架构**：Domain → Application → Infrastructure → Interface
3. **单一职责**：每个类只做一件事
4. **依赖倒置**：核心逻辑不依赖外部实现
5. **多模态优先**：文本 UI 树 + 视觉截图双通道理解
6. **自我进化**：从成功中学习，持续优化策略

---

## 📂 目录结构

```
app/src/main/java/com/employee/agent/
├── 📁 domain/                          # 领域层（核心业务逻辑，纯 Kotlin）
│   ├── agent/
│   │   ├── AgentGoal.kt               # 目标定义
│   │   ├── AgentState.kt              # 状态机（7种状态）
│   │   ├── AgentMemory.kt             # 记忆系统（短期/工作/长期）
│   │   └── AgentAction.kt             # 动作类型
│   ├── screen/
│   │   ├── UINode.kt                  # UI 节点模型
│   │   └── ScreenSnapshot.kt          # 屏幕快照
│   ├── tool/
│   │   └── ToolDefinition.kt          # 工具定义接口
│   ├── planning/                      # 🆕 任务规划
│   │   └── TaskPlanner.kt             # 层次化任务分解 (HTA)
│   └── recovery/                      # 🆕 错误恢复
│       └── RecoveryStrategy.kt        # 恢复策略接口
│
├── 📁 application/                     # 应用层（用例编排）
│   ├── AgentRuntime.kt                # Agent 运行时
│   └── planning/                      # 🆕 规划执行
│       ├── AITaskPlanner.kt           # AI 驱动的任务规划
│       └── PlanExecutor.kt            # 计划执行器
│
├── 📁 infrastructure/                  # 基础设施层（外部依赖）
│   ├── accessibility/
│   │   ├── AccessibilityGestureExecutor.kt  # 手势执行
│   │   └── UITreeParser.kt            # UI 树解析
│   ├── ai/
│   │   ├── HunyuanAIClient.kt         # 混元 API 客户端
│   │   └── PromptBuilder.kt           # 提示词构建
│   ├── vision/                        # 🆕 多模态视觉
│   │   ├── ScreenshotCapture.kt       # 屏幕截图（Android 11+无需授权）
│   │   ├── VisionClient.kt            # Vision API（GPT-4V/Qwen-VL）
│   │   └── MultimodalScreenAnalyzer.kt # 融合分析器
│   ├── network/
│   │   ├── SocketServer.kt            # Socket 服务器（兼容旧版）
│   │   ├── WebSocketServer.kt         # 🆕 WebSocket 双向通信
│   │   ├── AgentProtocol.kt           # 🆕 通信协议定义
│   │   └── PCAgentBridge.kt           # 🆕 PC-手机协同桥接
│   ├── storage/                       # 🆕 持久化
│   │   ├── AgentDatabase.kt           # Room 数据库（目标/日志/模式/记忆）
│   │   └── MemoryRepository.kt        # 记忆仓库（学习与检索）
│   ├── recovery/                      # 🆕 恢复策略实现
│   │   └── CommonRecoveryStrategies.kt # 弹窗/权限/崩溃/网络错误处理
│   └── tools/
│       ├── TapTool.kt
│       ├── TapElementTool.kt
│       ├── SwipeTool.kt
│       ├── InputTextTool.kt
│       ├── PressKeyTool.kt
│       ├── WaitTool.kt
│       └── GetScreenTool.kt
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

## 🆕 V2.0 新增核心能力

### 1️⃣ 多模态屏幕理解

```kotlin
// 融合 UI 树 + 截图分析
val analyzer = MultimodalScreenAnalyzer(
    screenshotCapture,   // Android 11+ 无需授权截图
    qwenVLClient,        // 通义千问 VL / GPT-4V
    uiTreeParser
)

// 自动选择最佳分析模式
val result = analyzer.analyzeScreen(AnalysisMode.HYBRID)
// result.elements     - UI 元素列表（精确坐标）
// result.visionDescription - 视觉描述（语义理解）
```

**适用场景**：
- WebView 内容识别
- 图片/广告识别
- 复杂布局理解
- UI 树缺失时的兜底

### 2️⃣ 智能记忆系统

```kotlin
val memoryRepo = MemoryRepository(database)

// 保存记忆
memoryRepo.remember(
    content = "微信图标在桌面第一页左上角",
    type = MemoryType.FACT,
    importance = 0.8f
)

// 从成功执行中学习
memoryRepo.learnFromSuccess(goalId)

// 检索相关记忆
val memories = memoryRepo.recall("打开微信", MemoryType.STRATEGY)

// 查找适用模式
val pattern = memoryRepo.findApplicablePattern("打开微信发消息")
```

**记忆类型**：
- `FACT` - 事实（位置、状态）
- `STRATEGY` - 策略（成功的操作序列）
- `PREFERENCE` - 偏好（用户习惯）
- `ERROR_PATTERN` - 错误模式（需要特殊处理的场景）

### 3️⃣ 层次化任务规划

```kotlin
val planner = AITaskPlanner(aiClient)

// 复杂目标自动分解
val plan = planner.plan(
    goal = Goal("打开微信并给张三发送早安"),
    context = PlanningContext(currentScreen, learnedStrategies)
)

// plan.rootTask 包含层次化的子任务树：
// └── 打开微信并给张三发送早安
//     ├── 打开微信 App
//     │   └── 点击微信图标
//     ├── 进入聊天
//     │   ├── 搜索张三
//     │   └── 点击进入
//     └── 发送消息
//         ├── 输入"早安"
//         └── 点击发送

// 执行计划
val executor = PlanExecutor(toolRegistry, screenProvider, memoryRepo, planner)
val result = executor.execute(plan)
```

### 4️⃣ 自适应错误恢复

```kotlin
val recoveryRegistry = createDefaultRecoveryRegistry(gestureExecutor)

// 内置策略（按优先级）：
// 1. AppCrashStrategy      - 应用崩溃恢复
// 2. PermissionRequestStrategy - 权限弹窗处理
// 3. DialogDismissStrategy - 弹窗关闭
// 4. ScreenChangedStrategy - 屏幕变化处理
// 5. ElementNotFoundStrategy - 元素查找（滚动重试）
// 6. NetworkErrorStrategy  - 网络错误等待

// 自动尝试恢复
val result = recoveryRegistry.tryRecover(RecoveryContext(
    errorType = ErrorType.UNEXPECTED_DIALOG,
    currentScreen = screenContext
))
```

### 5️⃣ PC-手机协同

```kotlin
// 手机端启动 WebSocket 服务器
val wsServer = WebSocketServer(port = 11452)
val bridge = PCAgentBridge(wsServer, { agentRuntime }, uiParser, screenshotCapture)
bridge.initialize()

// PC 端可以：
// - 发送目标：{"type": "goal", "payload": {"description": "打开微信"}}
// - 发送命令：{"type": "command", "payload": {"command": "PAUSE"}}
// - 接收状态、进度、屏幕、日志、AI思考过程
```

**协议消息类型**：

| 方向 | 类型 | 说明 |
|------|------|------|
| PC→手机 | `goal` | 设置执行目标 |
| PC→手机 | `command` | 控制命令（暂停/恢复/停止） |
| PC→手机 | `query` | 查询状态 |
| 手机→PC | `status` | 状态更新 |
| 手机→PC | `progress` | 进度更新 |
| 手机→PC | `screen` | 屏幕内容（含截图） |
| 手机→PC | `thinking` | AI 思考过程 |
| 手机→PC | `result` | 执行结果 |
| 手机→PC | `error` | 错误信息 |

---

## 📝 实施进度

| 阶段 | 功能 | 状态 |
|------|------|------|
| Phase 1 | 多模态能力（截图+Vision） | ✅ 完成 |
| Phase 2 | 智能记忆系统（Room持久化） | ✅ 完成 |
| Phase 3 | 任务规划与分解（HTA） | ✅ 完成 |
| Phase 4 | 智能错误恢复 | ✅ 完成 |
| Phase 5 | PC-手机协同（WebSocket） | ✅ 完成 |

---

这个架构的核心优势：
- 🧩 **模块化**：每层职责清晰，互不耦合
- 🧪 **可测试**：Domain 层纯逻辑，易于单元测试
- 🔄 **可替换**：基础设施层可随时更换实现
- 📈 **可扩展**：新增功能无需修改核心代码
- 👁️ **多模态**：文本+视觉双通道，适应复杂场景
- 🧠 **自学习**：从成功中学习模式，越用越智能
- 🔗 **可协同**：PC端可实时监控和控制

