# 📱 Android AI Agent

基于无障碍服务 (AccessibilityService) 的自主手机 AI Agent。

## 🎯 功能特性

- ✅ **无需 Root**：基于 AccessibilityService，普通用户可用
- ✅ **AI 驱动**：集成混元 API，自主决策和执行
- ✅ **完整工具系统**：tap、swipe、input、press_key 等
- ✅ **DDD 架构**：Domain → Application → Infrastructure → Interface
- ✅ **前台服务**：防止被系统杀死
- ✅ **Socket 兼容**：保持与 PC 端的 Socket 通信

## 🏗️ 架构

```
domain/          # 领域层（纯业务逻辑）
  ├── agent/     # Agent 状态、目标、记忆
  ├── screen/    # UI 节点模型
  └── tool/      # 工具接口定义

application/     # 应用层（用例编排）
  └── AgentRuntime.kt  # Agent 主循环

infrastructure/  # 基础设施层（技术实现）
  ├── accessibility/  # 无障碍服务封装
  ├── ai/            # AI 客户端
  └── tools/         # 具体工具实现

interface/       # 接口层
  └── AgentService.kt  # 无障碍服务入口
```

## 🚀 快速开始

### 1. 配置 API Key

编辑 `AgentService.kt`：

```kotlin
val apiKey = "your_hunyuan_api_key_here"
```

### 2. 启用无障碍服务

```
设置 → 辅助功能 → 无障碍 → AndroidAgent → 开启
```

### 3. 测试

服务启动后会自动执行测试目标（打开微信）。查看 Logcat：

```bash
adb logcat | grep Agent
```

## 🛠️ 可用工具

| 工具 | 参数 | 说明 |
|------|------|------|
| `tap` | `x: Int, y: Int` | 点击坐标 |
| `tap_element` | `text: String` | 点击元素（通过文本） |
| `swipe` | `direction: String, distance: String` | 滑动 |
| `press_key` | `key: String` | 按键 (back/home/enter) |
| `wait` | `milliseconds: Long` | 等待 |
| `get_screen` | - | 获取屏幕 UI |

## 📝 自定义目标

```kotlin
val goal = Goal(
    description = "打开微信并发送消息给张三",
    completionCondition = CompletionCondition.AIDecided,
    maxSteps = 20,
    timeoutSeconds = 60
)

agentRuntime?.executeGoal(goal)
```

## 🔧 添加新工具

1. 实现 `Tool` 接口：

```kotlin
class MyCustomTool : Tool {
    override val name = "my_tool"
    override val description = "我的自定义工具"
    override val parameters = listOf(...)
    
    override suspend fun execute(params: Map<String, Any>): ActionResult {
        // 实现逻辑
    }
}
```

2. 在 `AgentService` 中注册：

```kotlin
toolRegistry.register(MyCustomTool())
```

## 📊 日志查看

```bash
# 查看所有 Agent 日志
adb logcat | grep "Agent"

# 查看 AI 响应
adb logcat | grep "AgentRuntime"

# 查看手势执行
adb logcat | grep "GestureExecutor"
```

## ⚠️ 已知限制

1. **AI API Key**：需要自行配置混元 API Key
2. **厂商限制**：部分国产 ROM 会限制无障碍服务
3. **网络请求**：需要 INTERNET 权限
4. **前台通知**：会常驻通知栏

## 🔄 与 PC 端集成

保留了原有的 Socket 服务器（端口 11451），可继续使用 PC 端程序控制：

```bash
# PC 端发送命令
echo "DUMP" | nc <phone_ip> 11451
```

## 📦 依赖

- Kotlin 1.8.22
- Kotlin Coroutines 1.7.3
- Gson 2.10.1
- AndroidX Core/AppCompat
- Material Design

## 📄 License

MIT License
