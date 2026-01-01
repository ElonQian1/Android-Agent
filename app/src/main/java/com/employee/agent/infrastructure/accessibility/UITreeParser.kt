// infrastructure/accessibility/UITreeParser.kt
package com.employee.agent.infrastructure.accessibility

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.employee.agent.domain.screen.UINode
import com.employee.agent.application.ScreenReader

/**
 * UI 树解析器
 * 将 Android AccessibilityNodeInfo 转换为领域层的 UINode
 */
class UITreeParser(
    private val service: AccessibilityService
) : ScreenReader {
    
    /**
     * 🆕 获取 Root Window 的辅助函数
     */
    private fun getRootNode(): AccessibilityNodeInfo? {
        service.rootInActiveWindow?.let { return it }
        
        try {
            val windows = service.windows
            if (windows != null && windows.isNotEmpty()) {
                for (window in windows) {
                    if (window.isActive && window.isFocused) {
                        window.root?.let { return it }
                    }
                }
                for (window in windows) {
                    if (window.isActive && window.type == AccessibilityWindowInfo.TYPE_APPLICATION) {
                        window.root?.let { return it }
                    }
                }
                for (window in windows) {
                    if (window.isActive) {
                        window.root?.let { return it }
                    }
                }
                windows.find { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.root != null }?.root?.let { return it }
                for (window in windows) {
                    window.root?.let { return it }
                }
            }
        } catch (_: Exception) {}
        
        return null
    }
    
    override suspend fun readCurrentScreen(): UINode {
        return readCurrentScreenSync()
    }
    
    /**
     * 同步版本（内部使用）
     */
    fun readCurrentScreenSync(): UINode {
        val root = getRootNode()
            ?: return UINode(
                className = "Empty",
                text = "无法获取屏幕根节点",
                contentDescription = null,
                resourceId = null,
                bounds = Rect(),
                children = emptyList()
            )
        
        return try {
            parseNode(root)
        } finally {
            root.recycle()
        }
    }
    
    /**
     * 递归解析节点
     */
    private fun parseNode(node: AccessibilityNodeInfo): UINode {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        
        val children = mutableListOf<UINode>()
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                try {
                    children.add(parseNode(child))
                } finally {
                    child.recycle()
                }
            }
        }
        
        return UINode(
            className = node.className?.toString() ?: "Unknown",
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
}
