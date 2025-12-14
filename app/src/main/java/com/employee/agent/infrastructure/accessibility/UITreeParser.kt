// infrastructure/accessibility/UITreeParser.kt
package com.employee.agent.infrastructure.accessibility

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.employee.agent.domain.screen.UINode
import com.employee.agent.application.ScreenReader

/**
 * UI 树解析器
 * 将 Android AccessibilityNodeInfo 转换为领域层的 UINode
 */
class UITreeParser(
    private val service: AccessibilityService
) : ScreenReader {
    
    override suspend fun readCurrentScreen(): UINode {
        val root = service.rootInActiveWindow
            ?: throw IllegalStateException("无法获取屏幕根节点")
        
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
