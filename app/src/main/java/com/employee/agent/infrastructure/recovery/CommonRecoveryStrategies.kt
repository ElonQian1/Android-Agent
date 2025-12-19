// infrastructure/recovery/CommonRecoveryStrategies.kt
// module: infrastructure/recovery | layer: infrastructure | role: recovery-strategies
// summary: 常见错误的恢复策略实现

package com.employee.agent.infrastructure.recovery

import android.util.Log
import com.employee.agent.domain.recovery.*
import com.employee.agent.infrastructure.accessibility.AccessibilityGestureExecutor
import kotlinx.coroutines.delay

/**
 * 弹窗处理策略
 * 
 * 处理常见的系统弹窗和 App 弹窗
 */
class DialogDismissStrategy(
    private val gestureExecutor: AccessibilityGestureExecutor
) : RecoveryStrategy {
    
    override val name = "DialogDismiss"
    override val priority = 10
    
    // 常见的弹窗关闭按钮文本
    private val dismissTexts = listOf(
        // 中文
        "取消", "关闭", "确定", "知道了", "我知道了", "暂不", "以后再说", 
        "跳过", "不了", "下次", "稍后", "忽略", "拒绝", "不允许",
        // 英文
        "Cancel", "Close", "OK", "Got it", "Skip", "Later", "Dismiss",
        "No thanks", "Not now", "Deny", "Don't allow"
    )
    
    // 常见的广告关闭按钮资源 ID
    private val closeResourceIds = listOf(
        "close", "btn_close", "iv_close", "ib_close", "dismiss",
        "skip", "btn_skip", "ad_close"
    )
    
    override fun isApplicable(context: RecoveryContext): Boolean {
        return context.errorType == ErrorType.UNEXPECTED_DIALOG ||
               context.currentScreen.hasDialog ||
               hasDialogIndicator(context.currentScreen)
    }
    
    override suspend fun recover(context: RecoveryContext): RecoveryResult {
        Log.d("Recovery", "尝试关闭弹窗")
        
        val screen = context.currentScreen
        
        // 1. 查找明确的关闭按钮
        val closeButton = screen.clickableElements.find { element ->
            dismissTexts.any { text -> 
                element.contains(text, ignoreCase = true) 
            }
        }
        
        if (closeButton != null) {
            try {
                val result = gestureExecutor.tapElement(closeButton)
                if (result.success) {
                    delay(500)
                    return RecoveryResult.Success(
                        message = "成功关闭弹窗: $closeButton",
                        shouldRetry = true
                    )
                }
            } catch (e: Exception) {
                Log.w("Recovery", "点击关闭按钮失败", e)
            }
        }
        
        // 2. 尝试点击空白区域关闭
        try {
            // 点击屏幕左上角（通常是安全区域）
            val result = gestureExecutor.tap(50, 100)
            if (result.success) {
                delay(300)
                return RecoveryResult.Success(
                    message = "点击空白区域关闭弹窗",
                    shouldRetry = true
                )
            }
        } catch (e: Exception) {
            Log.w("Recovery", "点击空白区域失败", e)
        }
        
        // 3. 尝试返回键
        try {
            val result = gestureExecutor.pressBack()
            if (result.success) {
                delay(300)
                return RecoveryResult.Success(
                    message = "使用返回键关闭弹窗",
                    shouldRetry = true
                )
            }
        } catch (e: Exception) {
            Log.w("Recovery", "返回键失败", e)
        }
        
        return RecoveryResult.Failure("无法关闭弹窗")
    }
    
    private fun hasDialogIndicator(screen: ScreenContext): Boolean {
        val dialogIndicators = listOf(
            "弹窗", "提示", "通知", "确认", "警告",
            "Dialog", "Alert", "Popup", "Modal"
        )
        return screen.visibleTexts.any { text ->
            dialogIndicators.any { indicator ->
                text.contains(indicator, ignoreCase = true)
            }
        }
    }
}

/**
 * 权限请求处理策略
 */
class PermissionRequestStrategy(
    private val gestureExecutor: AccessibilityGestureExecutor,
    private val autoGrant: Boolean = false  // 是否自动授予权限
) : RecoveryStrategy {
    
    override val name = "PermissionRequest"
    override val priority = 5
    
    private val permissionIndicators = listOf(
        // 中文
        "允许", "权限", "访问", "使用", "获取位置", "拍照", "录音",
        "通讯录", "存储", "通知", "悬浮窗",
        // 英文
        "Allow", "Permission", "Access", "Grant", "While using",
        "Only this time", "Don't allow"
    )
    
    private val allowTexts = listOf(
        "允许", "始终允许", "仅在使用时允许", "仅此一次",
        "Allow", "While using the app", "Only this time"
    )
    
    private val denyTexts = listOf(
        "拒绝", "不允许", "禁止",
        "Deny", "Don't allow"
    )
    
    override fun isApplicable(context: RecoveryContext): Boolean {
        return context.errorType == ErrorType.PERMISSION_DENIED ||
               isPermissionDialog(context.currentScreen)
    }
    
    override suspend fun recover(context: RecoveryContext): RecoveryResult {
        Log.d("Recovery", "处理权限请求")
        
        val screen = context.currentScreen
        
        if (!autoGrant) {
            // 不自动授权，请求人工干预
            return RecoveryResult.NeedHumanIntervention(
                reason = "需要授予权限",
                instructions = "请手动点击允许按钮授予必要权限"
            )
        }
        
        // 查找允许按钮
        val allowButton = screen.clickableElements.find { element ->
            allowTexts.any { text ->
                element.contains(text, ignoreCase = true)
            }
        }
        
        if (allowButton != null) {
            try {
                val result = gestureExecutor.tapElement(allowButton)
                if (result.success) {
                    delay(500)
                    return RecoveryResult.Success(
                        message = "已授予权限: $allowButton",
                        shouldRetry = true
                    )
                }
            } catch (e: Exception) {
                Log.w("Recovery", "点击允许按钮失败", e)
            }
        }
        
        return RecoveryResult.NeedHumanIntervention(
            reason = "无法自动处理权限请求",
            instructions = "请手动授予权限后继续"
        )
    }
    
    private fun isPermissionDialog(screen: ScreenContext): Boolean {
        return screen.visibleTexts.any { text ->
            permissionIndicators.any { indicator ->
                text.contains(indicator, ignoreCase = true)
            }
        } && (
            screen.clickableElements.any { it.contains("允许") || it.contains("Allow") }
        )
    }
}

/**
 * 元素未找到恢复策略
 */
class ElementNotFoundStrategy(
    private val gestureExecutor: AccessibilityGestureExecutor
) : RecoveryStrategy {
    
    override val name = "ElementNotFound"
    override val priority = 20
    
    override fun isApplicable(context: RecoveryContext): Boolean {
        return context.errorType == ErrorType.ELEMENT_NOT_FOUND
    }
    
    override suspend fun recover(context: RecoveryContext): RecoveryResult {
        Log.d("Recovery", "处理元素未找到")
        
        val retryCount = context.retryCount
        
        when {
            retryCount == 0 -> {
                // 第一次：等待并重试
                delay(1000)
                return RecoveryResult.Success(
                    message = "等待页面加载",
                    shouldRetry = true
                )
            }
            retryCount == 1 -> {
                // 第二次：尝试向下滑动
                try {
                    gestureExecutor.swipe(
                        com.employee.agent.domain.agent.SwipeDirection.UP,
                        com.employee.agent.domain.agent.SwipeDistance.MEDIUM
                    )
                    delay(500)
                    return RecoveryResult.Success(
                        message = "向下滚动查找元素",
                        shouldRetry = true
                    )
                } catch (e: Exception) {
                    Log.w("Recovery", "滑动失败", e)
                }
            }
            retryCount == 2 -> {
                // 第三次：尝试向上滑动
                try {
                    gestureExecutor.swipe(
                        com.employee.agent.domain.agent.SwipeDirection.DOWN,
                        com.employee.agent.domain.agent.SwipeDistance.LONG
                    )
                    delay(500)
                    return RecoveryResult.Success(
                        message = "向上滚动查找元素",
                        shouldRetry = true
                    )
                } catch (e: Exception) {
                    Log.w("Recovery", "滑动失败", e)
                }
            }
        }
        
        return RecoveryResult.Failure("多次尝试后仍未找到元素")
    }
}

/**
 * 应用崩溃恢复策略
 */
class AppCrashStrategy(
    private val gestureExecutor: AccessibilityGestureExecutor
) : RecoveryStrategy {
    
    override val name = "AppCrash"
    override val priority = 1
    
    private val crashIndicators = listOf(
        "已停止运行", "停止运行", "无响应", "崩溃",
        "has stopped", "stopped working", "not responding", "crashed",
        "Unfortunately", "keeps stopping"
    )
    
    override fun isApplicable(context: RecoveryContext): Boolean {
        return context.errorType == ErrorType.APP_CRASH ||
               hasCrashIndicator(context.currentScreen)
    }
    
    override suspend fun recover(context: RecoveryContext): RecoveryResult {
        Log.d("Recovery", "处理应用崩溃")
        
        val screen = context.currentScreen
        
        // 查找关闭/确定按钮
        val closeButton = screen.clickableElements.find { element ->
            listOf("关闭", "确定", "OK", "Close").any { 
                element.contains(it, ignoreCase = true) 
            }
        }
        
        if (closeButton != null) {
            try {
                gestureExecutor.tapElement(closeButton)
                delay(500)
            } catch (e: Exception) {
                Log.w("Recovery", "关闭崩溃弹窗失败", e)
            }
        }
        
        // 返回桌面
        try {
            gestureExecutor.pressHome()
            delay(1000)
            
            return RecoveryResult.Success(
                message = "应用崩溃，已返回桌面",
                shouldRetry = false,
                suggestedAction = SuggestedAction(
                    toolName = "tap_element",
                    parameters = mapOf("text" to context.metadata["targetApp"] as? String ?: ""),
                    reason = "重新打开应用"
                )
            )
        } catch (e: Exception) {
            Log.e("Recovery", "返回桌面失败", e)
        }
        
        return RecoveryResult.Failure("无法从崩溃中恢复", fatal = true)
    }
    
    private fun hasCrashIndicator(screen: ScreenContext): Boolean {
        return screen.visibleTexts.any { text ->
            crashIndicators.any { indicator ->
                text.contains(indicator, ignoreCase = true)
            }
        }
    }
}

/**
 * 屏幕变化恢复策略
 */
class ScreenChangedStrategy(
    private val gestureExecutor: AccessibilityGestureExecutor
) : RecoveryStrategy {
    
    override val name = "ScreenChanged"
    override val priority = 15
    
    override fun isApplicable(context: RecoveryContext): Boolean {
        return context.errorType == ErrorType.SCREEN_CHANGED
    }
    
    override suspend fun recover(context: RecoveryContext): RecoveryResult {
        Log.d("Recovery", "处理屏幕意外变化")
        
        val screen = context.currentScreen
        
        // 检查是否是加载页面
        val loadingIndicators = listOf("加载", "Loading", "请稍候", "Please wait")
        val isLoading = screen.visibleTexts.any { text ->
            loadingIndicators.any { it.contains(text, ignoreCase = true) }
        }
        
        if (isLoading) {
            // 等待加载完成
            delay(2000)
            return RecoveryResult.Success(
                message = "等待页面加载完成",
                shouldRetry = true
            )
        }
        
        // 检查是否误入其他页面，尝试返回
        try {
            gestureExecutor.pressBack()
            delay(500)
            return RecoveryResult.Success(
                message = "返回上一页",
                shouldRetry = true
            )
        } catch (e: Exception) {
            Log.w("Recovery", "返回失败", e)
        }
        
        return RecoveryResult.Failure("屏幕状态异常，需要重新规划")
    }
}

/**
 * 网络错误恢复策略
 */
class NetworkErrorStrategy : RecoveryStrategy {
    
    override val name = "NetworkError"
    override val priority = 25
    
    private val networkIndicators = listOf(
        "网络", "连接", "超时", "加载失败", "重试",
        "Network", "Connection", "Timeout", "Failed to load", "Retry"
    )
    
    override fun isApplicable(context: RecoveryContext): Boolean {
        return context.errorType == ErrorType.NETWORK_ERROR ||
               hasNetworkErrorIndicator(context.currentScreen)
    }
    
    override suspend fun recover(context: RecoveryContext): RecoveryResult {
        Log.d("Recovery", "处理网络错误")
        
        // 等待并建议重试
        delay(3000)
        
        return RecoveryResult.Success(
            message = "等待网络恢复",
            shouldRetry = true
        )
    }
    
    private fun hasNetworkErrorIndicator(screen: ScreenContext): Boolean {
        return screen.visibleTexts.any { text ->
            networkIndicators.any { indicator ->
                text.contains(indicator, ignoreCase = true)
            }
        }
    }
}

/**
 * 创建默认的恢复策略注册表
 */
fun createDefaultRecoveryRegistry(
    gestureExecutor: AccessibilityGestureExecutor,
    autoGrantPermissions: Boolean = false
): RecoveryStrategyRegistry {
    return RecoveryStrategyRegistry().apply {
        register(AppCrashStrategy(gestureExecutor))
        register(PermissionRequestStrategy(gestureExecutor, autoGrantPermissions))
        register(DialogDismissStrategy(gestureExecutor))
        register(ScreenChangedStrategy(gestureExecutor))
        register(ElementNotFoundStrategy(gestureExecutor))
        register(NetworkErrorStrategy())
    }
}
