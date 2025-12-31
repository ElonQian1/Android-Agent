// infrastructure/popup/PopupDismisser.kt
// module: infrastructure/popup | layer: infrastructure | role: popup-dismisser
// summary: 弹窗规则库 - 自动检测并关闭常见弹窗广告

package com.employee.agent.infrastructure.popup

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.delay

/**
 * 🛡️ 弹窗自动关闭器
 * 
 * 基于规则库自动检测并关闭常见的弹窗、广告、提示框
 * 无需 AI，零 Token 消耗
 */
class PopupDismisser(private val service: AccessibilityService) {
    
    companion object {
        private const val TAG = "PopupDismisser"
        
        // ==================== 关闭按钮文字规则库 ====================
        
        /** 通用关闭按钮文字 */
        private val CLOSE_BUTTON_TEXTS = listOf(
            // 明确的关闭词
            "关闭", "×", "✕", "X", "╳", "x",
            "取消", "跳过", "暂不", "暂时不",
            
            // 拒绝词
            "不了", "不用了", "不需要", "不感兴趣",
            "残忍拒绝", "狠心离开", "忍痛拒绝",
            "放弃", "算了",
            
            // 确认词（单按钮弹窗）
            "我知道了", "知道了", "好的", "好", "确定", "确认",
            "了解", "明白了", "收到",
            
            // 延迟词
            "以后再说", "下次再说", "稍后", "不再提醒", "不再显示",
            
            // 广告相关
            "跳过广告", "跳过 ", "Skip", "skip", "SKIP",
            "免费试用", // 通常是拒绝广告的按钮旁边
            
            // 权限相关
            "拒绝", "禁止", "不允许", "稍后开启",
            
            // 更新相关
            "暂不更新", "以后更新", "忽略此版本", "下次提醒"
        )
        
        /** 弹窗标题特征（用于识别是否是弹窗） */
        private val POPUP_TITLE_PATTERNS = listOf(
            "温馨提示", "提示", "通知", "公告",
            "新用户专享", "专属福利", "限时优惠", "特惠",
            "开启通知", "获取权限", "申请权限",
            "版本更新", "发现新版本", "升级",
            "签到", "打卡", "领取",
            "红包", "优惠券", "折扣",
            "活动", "邀请", "分享"
        )
        
        /** 不应点击的按钮（避免误触） */
        private val AVOID_BUTTON_TEXTS = listOf(
            "立即更新", "马上更新", "立即升级",
            "立即领取", "马上领取", "去领取",
            "去看看", "查看详情", "了解更多",
            "开启", "允许", "同意", "确认领取",
            "购买", "付款", "支付", "充值",
            "分享到", "转发", "邀请好友"
        )
        
        // ==================== APP 特定规则 ====================
        
        /** 京东 APP 特定弹窗 */
        private val JD_POPUP_CLOSE = listOf(
            "关闭弹窗", "不感兴趣", "下次再看",
            "暂不领取", "残忍离开"
        )
        
        /** 淘宝 APP 特定弹窗 */
        private val TAOBAO_POPUP_CLOSE = listOf(
            "狠心拒绝", "关闭浮层", "不再提醒"
        )
        
        /** 小红书 APP 特定弹窗 */
        private val XHS_POPUP_CLOSE = listOf(
            "暂不开启", "以后再说", "不感兴趣"
        )
        
        /** 抖音 APP 特定弹窗 */
        private val DOUYIN_POPUP_CLOSE = listOf(
            "暂不", "下次再说", "不感兴趣", "拒绝"
        )
        
        /** 微信 APP 特定弹窗 */
        private val WECHAT_POPUP_CLOSE = listOf(
            "取消", "我知道了", "忽略"
        )
    }
    
    /**
     * 弹窗检测结果
     */
    data class PopupDetectionResult(
        val hasPopup: Boolean,
        val popupType: String? = null,
        val closeButtonNode: AccessibilityNodeInfo? = null,
        val closeButtonText: String? = null,
        val confidence: Float = 0f
    )
    
    /**
     * 弹窗关闭结果
     */
    data class DismissResult(
        val dismissed: Boolean,
        val popupsCleared: Int = 0,
        val details: List<String> = emptyList()
    )
    
    /**
     * 🔍 检测当前屏幕是否有弹窗
     */
    fun detectPopup(): PopupDetectionResult {
        val root = service.rootInActiveWindow ?: return PopupDetectionResult(false)
        
        try {
            // 获取当前 APP 包名
            val packageName = root.packageName?.toString() ?: ""
            
            // 获取该 APP 的特定规则
            val appSpecificCloseTexts = getAppSpecificCloseTexts(packageName)
            val allCloseTexts = CLOSE_BUTTON_TEXTS + appSpecificCloseTexts
            
            // 遍历查找关闭按钮
            val closeButton = findCloseButton(root, allCloseTexts)
            
            if (closeButton != null) {
                val buttonText = closeButton.text?.toString() 
                    ?: closeButton.contentDescription?.toString() 
                    ?: "unknown"
                
                Log.d(TAG, "🎯 检测到弹窗关闭按钮: $buttonText")
                
                return PopupDetectionResult(
                    hasPopup = true,
                    popupType = detectPopupType(root),
                    closeButtonNode = closeButton,
                    closeButtonText = buttonText,
                    confidence = 0.9f
                )
            }
            
            // 检查是否有弹窗特征但没找到关闭按钮
            val hasPopupFeatures = hasPopupFeatures(root)
            if (hasPopupFeatures) {
                Log.d(TAG, "⚠️ 检测到弹窗特征，但未找到关闭按钮")
                return PopupDetectionResult(
                    hasPopup = true,
                    popupType = "unknown",
                    confidence = 0.6f
                )
            }
            
            return PopupDetectionResult(false)
            
        } catch (e: Exception) {
            Log.e(TAG, "弹窗检测异常: ${e.message}")
            return PopupDetectionResult(false)
        }
    }
    
    /**
     * 🛡️ 尝试关闭弹窗（单次）
     * 
     * @return 是否成功关闭了弹窗
     */
    fun dismissPopupOnce(): Boolean {
        val detection = detectPopup()
        
        if (!detection.hasPopup) {
            return false
        }
        
        val closeButton = detection.closeButtonNode
        if (closeButton != null) {
            val success = clickNode(closeButton)
            if (success) {
                Log.i(TAG, "✅ 已关闭弹窗: ${detection.closeButtonText}")
            }
            return success
        }
        
        return false
    }
    
    /**
     * 🛡️ 循环清理所有弹窗
     * 
     * @param maxAttempts 最大尝试次数（防止死循环）
     * @param delayMs 每次关闭后的等待时间
     */
    suspend fun dismissAllPopups(
        maxAttempts: Int = 5,
        delayMs: Long = 300
    ): DismissResult {
        val details = mutableListOf<String>()
        var clearedCount = 0
        
        repeat(maxAttempts) { attempt ->
            val dismissed = dismissPopupOnce()
            if (dismissed) {
                clearedCount++
                details.add("第${attempt + 1}次：关闭成功")
                delay(delayMs)
            } else {
                // 没有检测到弹窗，清理完成
                return DismissResult(
                    dismissed = clearedCount > 0,
                    popupsCleared = clearedCount,
                    details = details
                )
            }
        }
        
        // 达到最大次数
        details.add("达到最大尝试次数 $maxAttempts")
        return DismissResult(
            dismissed = clearedCount > 0,
            popupsCleared = clearedCount,
            details = details
        )
    }
    
    /**
     * 获取 APP 特定的关闭按钮文字
     */
    private fun getAppSpecificCloseTexts(packageName: String): List<String> {
        return when {
            packageName.contains("jd") || packageName.contains("jingdong") -> JD_POPUP_CLOSE
            packageName.contains("taobao") || packageName.contains("tmall") -> TAOBAO_POPUP_CLOSE
            packageName.contains("xingin") || packageName.contains("xhs") -> XHS_POPUP_CLOSE
            packageName.contains("douyin") || packageName.contains("aweme") -> DOUYIN_POPUP_CLOSE
            packageName.contains("tencent.mm") || packageName.contains("weixin") -> WECHAT_POPUP_CLOSE
            else -> emptyList()
        }
    }
    
    /**
     * 查找关闭按钮
     */
    private fun findCloseButton(
        root: AccessibilityNodeInfo,
        closeTexts: List<String>
    ): AccessibilityNodeInfo? {
        return findCloseButtonRecursive(root, closeTexts, AVOID_BUTTON_TEXTS)
    }
    
    private fun findCloseButtonRecursive(
        node: AccessibilityNodeInfo,
        closeTexts: List<String>,
        avoidTexts: List<String>
    ): AccessibilityNodeInfo? {
        // 获取节点文字
        val nodeText = node.text?.toString()?.trim() ?: ""
        val contentDesc = node.contentDescription?.toString()?.trim() ?: ""
        val combinedText = "$nodeText $contentDesc".lowercase()
        
        // 检查是否应该避免点击
        val shouldAvoid = avoidTexts.any { avoid ->
            combinedText.contains(avoid.lowercase())
        }
        
        if (!shouldAvoid) {
            // 检查是否匹配关闭按钮
            val isCloseButton = closeTexts.any { close ->
                nodeText.equals(close, ignoreCase = true) ||
                contentDesc.equals(close, ignoreCase = true) ||
                (close.length == 1 && (nodeText == close || contentDesc == close)) // 单字符精确匹配
            }
            
            if (isCloseButton && node.isClickable) {
                return node
            }
        }
        
        // 递归检查子节点
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findCloseButtonRecursive(child, closeTexts, avoidTexts)
            if (result != null) {
                return result
            }
        }
        
        return null
    }
    
    /**
     * 检测弹窗类型
     */
    private fun detectPopupType(root: AccessibilityNodeInfo): String {
        val allText = extractAllText(root).lowercase()
        
        return when {
            allText.contains("更新") || allText.contains("版本") -> "update"
            allText.contains("权限") || allText.contains("允许") -> "permission"
            allText.contains("红包") || allText.contains("优惠") || allText.contains("福利") -> "promotion"
            allText.contains("签到") || allText.contains("打卡") -> "checkin"
            allText.contains("通知") || allText.contains("消息") -> "notification"
            allText.contains("广告") || allText.contains("推广") -> "ad"
            else -> "generic"
        }
    }
    
    /**
     * 检查是否有弹窗特征
     */
    private fun hasPopupFeatures(root: AccessibilityNodeInfo): Boolean {
        val allText = extractAllText(root).lowercase()
        return POPUP_TITLE_PATTERNS.any { pattern ->
            allText.contains(pattern.lowercase())
        }
    }
    
    /**
     * 提取所有文字
     */
    private fun extractAllText(node: AccessibilityNodeInfo): String {
        val sb = StringBuilder()
        extractTextRecursive(node, sb)
        return sb.toString()
    }
    
    private fun extractTextRecursive(node: AccessibilityNodeInfo, sb: StringBuilder) {
        node.text?.let { sb.append(it).append(" ") }
        node.contentDescription?.let { sb.append(it).append(" ") }
        
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { extractTextRecursive(it, sb) }
        }
    }
    
    /**
     * 点击节点
     */
    private fun clickNode(node: AccessibilityNodeInfo): Boolean {
        return try {
            if (node.isClickable) {
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            } else {
                // 节点不可点击，尝试点击其坐标
                val rect = Rect()
                node.getBoundsInScreen(rect)
                performTapGesture(rect.centerX(), rect.centerY())
            }
        } catch (e: Exception) {
            Log.e(TAG, "点击节点失败: ${e.message}")
            false
        }
    }
    
    /**
     * 执行点击手势
     */
    private fun performTapGesture(x: Int, y: Int): Boolean {
        return try {
            val path = android.graphics.Path().apply {
                moveTo(x.toFloat(), y.toFloat())
            }
            val gesture = android.accessibilityservice.GestureDescription.Builder()
                .addStroke(
                    android.accessibilityservice.GestureDescription.StrokeDescription(
                        path, 0, 100
                    )
                )
                .build()
            
            service.dispatchGesture(gesture, null, null)
            true
        } catch (e: Exception) {
            Log.e(TAG, "执行点击手势失败: ${e.message}")
            false
        }
    }
}
