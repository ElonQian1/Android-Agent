# Android Agent - Copilot 项目内规

---

## 🚨 独立仓库警告

**本仓库是独立的 Git 仓库！**

### 仓库信息

| 属性 | 值 |
|------|-----|
| **仓库名称** | Android-Agent |
| **GitHub** | `ElonQian1/Android-Agent` |
| **技术栈** | Kotlin + Android SDK |
| **角色** | 📱 手机端 Agent 应用 |
| **与主项目关系** | 作为 Submodule 被 `marketing-automation-desktop` 引用 |

---

## ⚠️ AI 代理必读规则

1. **独立 Git 操作**：
   - 本仓库有独立的 Git 历史
   - 所有修改必须在本目录下 `git commit` 和 `git push`
   - **不要**在父目录（employeeGUI）执行 git 操作来提交本仓库的修改

2. **正确的提交流程**：
   ```powershell
   # 确保在 android-agent 目录下
   cd android-agent  # 或已经在此目录
   git add .
   git commit -m "feat: 功能描述"
   git push origin main
   ```

3. **与桌面端的通信**：
   - 通过 WebSocket 与桌面端通信
   - 协议定义在 `infrastructure/network/AgentProtocol.kt`
   - PC 端桥接在 `infrastructure/network/PCAgentBridge.kt`

---

## 📁 项目架构 (DDD 分层)

```
app/src/main/java/com/employee/agent/
├── domain/                    # 领域层 - 纯业务逻辑
│   ├── agent/                 # Agent 核心领域模型
│   ├── planning/              # 任务规划领域
│   ├── recovery/              # 错误恢复领域
│   └── screen/                # 屏幕/UI 领域模型
├── application/               # 应用层 - 用例编排
│   ├── AgentRuntime.kt        # Agent 运行时
│   ├── EnhancedAgentRuntime.kt # 增强运行时
│   └── planning/              # 规划器实现
├── infrastructure/            # 基础设施层 - 技术实现
│   ├── accessibility/         # 无障碍服务
│   ├── ai/                    # AI 客户端
│   ├── network/               # 网络通信
│   ├── recovery/              # 恢复策略实现
│   ├── storage/               # 数据存储
│   ├── tools/                 # 工具实现
│   └── vision/                # 视觉分析
└── AgentService.kt            # 主服务入口
```

---

## 🎯 核心组件

| 组件 | 文件 | 职责 |
|------|------|------|
| **Agent 服务** | `AgentService.kt` | 无障碍服务主入口 |
| **Agent 运行时** | `AgentRuntime.kt` | Agent 生命周期管理 |
| **任务规划器** | `AITaskPlanner.kt` | AI 驱动的任务分解 |
| **屏幕读取** | `AccessibilityScreenReader.kt` | UI 树解析 |
| **手势执行** | `AccessibilityGestureExecutor.kt` | 点击/滑动执行 |
| **视觉分析** | `MultimodalScreenAnalyzer.kt` | 截图 + AI 分析 |
| **PC 通信** | `PCAgentBridge.kt` | WebSocket 桥接 |

---

## 🔧 开发规范

### 1. 命名规范
- **文件名**：PascalCase，如 `AgentService.kt`
- **包名**：`com.employee.agent.<layer>.<module>`
- **类名**：PascalCase，职责明确

### 2. 分层约束
- ❌ `domain` 不得依赖 `infrastructure`
- ❌ `domain` 不得依赖 Android SDK（除基础类型）
- ✅ `infrastructure` 实现 `domain` 定义的接口

### 3. 无障碍服务注意事项
- 需要用户手动开启无障碍权限
- 配置在 `res/xml/accessibility_service_config.xml`
- 服务声明在 `AndroidManifest.xml`

---

## 📝 常用命令

```powershell
# 检查 Git 状态
git status

# 提交修改
git add .
git commit -m "feat: 功能描述"
git push

# 构建 APK (在 Android Studio 或命令行)
./gradlew assembleDebug

# 安装到设备
./gradlew installDebug
```

---

## 🔗 相关仓库

- **桌面端主仓库**: `ElonQian1/marketing-automation-desktop`
- 本仓库作为 Submodule 被引用在 `employeeGUI/android-agent/`
