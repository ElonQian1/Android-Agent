// domain/screen/UINode.kt
package com.employee.agent.domain.screen

import android.graphics.Rect

/**
 * UI 节点模型（领域层，不依赖 Android API）
 */
data class UINode(
    val className: String,
    val text: String?,
    val contentDescription: String?,
    val resourceId: String?,
    val bounds: Rect,
    val isClickable: Boolean = false,
    val isEnabled: Boolean = true,
    val isPassword: Boolean = false,
    val children: List<UINode> = emptyList()
) {
    /**
     * 查找包含指定文本的节点
     */
    fun findByText(text: String, exact: Boolean = false): UINode? {
        if (exact) {
            if (this.text == text) return this
        } else {
            if (this.text?.contains(text) == true) return this
        }
        
        for (child in children) {
            child.findByText(text, exact)?.let { return it }
        }
        return null
    }
    
    /**
     * 获取中心坐标
     */
    fun centerPoint(): Pair<Int, Int> {
        return Pair(bounds.centerX(), bounds.centerY())
    }
    
    /**
     * 提取所有可见文本
     */
    fun getAllTexts(): List<String> {
        val texts = mutableListOf<String>()
        text?.let { if (it.isNotBlank()) texts.add(it) }
        children.forEach { texts.addAll(it.getAllTexts()) }
        return texts
    }
    
    /**
     * 提取可点击元素摘要
     */
    fun getClickableElementsSummary(): String {
        val clickables = mutableListOf<String>()
        collectClickables(clickables)
        return clickables.joinToString("\n")
    }
    
    private fun collectClickables(result: MutableList<String>) {
        if (isClickable && !text.isNullOrBlank()) {
            val className = this.className.substringAfterLast('.')
            result.add("🔘 [$className] \"$text\"")
        }
        children.forEach { it.collectClickables(result) }
    }
}
