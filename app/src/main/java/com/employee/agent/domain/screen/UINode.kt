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
    
    /**
     * 转换为简化的字符串表示（供 AI 分析）
     * 只保留关键信息，减少 Token 消耗
     */
    fun toSimpleString(depth: Int = 0, maxDepth: Int = 5): String {
        if (depth > maxDepth) return ""
        
        val indent = "  ".repeat(depth)
        val builder = StringBuilder()
        
        // 只输出有意义的节点
        val hasText = !text.isNullOrBlank()
        val hasDesc = !contentDescription.isNullOrBlank()
        val hasId = !resourceId.isNullOrBlank()
        val isInteractive = isClickable
        
        if (hasText || hasDesc || hasId || isInteractive) {
            val className = this.className.substringAfterLast('.')
            builder.append(indent)
            builder.append("[$className]")
            
            if (hasText) builder.append(" text=\"$text\"")
            if (hasDesc) builder.append(" desc=\"$contentDescription\"")
            if (hasId) builder.append(" id=\"${resourceId?.substringAfterLast('/')}\"")
            if (isClickable) builder.append(" [可点击]")
            
            builder.append("\n")
        }
        
        // 递归处理子节点
        for (child in children) {
            builder.append(child.toSimpleString(depth + 1, maxDepth))
        }
        
        return builder.toString()
    }
    
    /**
     * 获取节点总数（用于判断树的大小）
     */
    fun getNodeCount(): Int {
        return 1 + children.sumOf { it.getNodeCount() }
    }
}
