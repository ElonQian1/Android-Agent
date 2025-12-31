// domain/screen/ScreenCaptureMode.kt
// module: domain/screen | layer: domain | role: screen-capture-mode
// summary: 屏幕获取模式定义 - 全量/增量/差异三种模式

package com.employee.agent.domain.screen

/**
 * 屏幕获取模式
 * 
 * 不同模式对 AI Token 消耗影响巨大：
 * - FULL_DUMP: 完整树 500-2000 节点 → 约 5000-20000 tokens
 * - INCREMENTAL: 仅变化节点 1-50 个 → 约 100-500 tokens  
 * - DIFF: 差异描述 → 约 200-1000 tokens
 */
enum class ScreenCaptureMode {
    /**
     * 全量模式 - 获取完整 UI 树
     * 
     * 优点：信息最完整，AI 理解上下文准确
     * 缺点：Token 消耗大，适合首次分析或复杂场景
     * 
     * 使用场景：
     * - 脚本首次执行，需要完整理解页面
     * - AI 需要做复杂决策
     * - 调试问题时需要完整信息
     */
    FULL_DUMP,
    
    /**
     * 增量模式 - 只获取变化的节点
     * 
     * 优点：Token 消耗极低，实时性好
     * 缺点：缺少全局上下文，AI 可能误判
     * 
     * 使用场景：
     * - 等待特定元素出现
     * - 监控页面是否变化
     * - 高频检测（如弹窗检测）
     */
    INCREMENTAL,
    
    /**
     * 差异模式 - 比较两次快照的差异
     * 
     * 优点：精确知道什么变了，Token 消耗中等
     * 缺点：需要维护基准快照
     * 
     * 使用场景：
     * - 验证操作是否生效
     * - 检测页面状态变化
     * - 判断是否进入了新页面
     */
    DIFF;
    
    companion object {
        fun fromString(value: String): ScreenCaptureMode {
            return when (value.uppercase()) {
                "FULL", "FULL_DUMP" -> FULL_DUMP
                "INCR", "INCREMENTAL" -> INCREMENTAL
                "DIFF", "DIFFERENCE" -> DIFF
                else -> FULL_DUMP
            }
        }
    }
    
    val displayName: String
        get() = when (this) {
            FULL_DUMP -> "全量模式"
            INCREMENTAL -> "增量模式"
            DIFF -> "差异模式"
        }
    
    val emoji: String
        get() = when (this) {
            FULL_DUMP -> "📸"
            INCREMENTAL -> "⚡"
            DIFF -> "🔄"
        }
    
    val tokenCost: String
        get() = when (this) {
            FULL_DUMP -> "高 (5K-20K tokens)"
            INCREMENTAL -> "极低 (100-500 tokens)"
            DIFF -> "中等 (200-1K tokens)"
        }
    
    val description: String
        get() = when (this) {
            FULL_DUMP -> "获取完整 UI 树，信息最全但 Token 消耗大"
            INCREMENTAL -> "仅获取变化节点，实时高效但缺少上下文"
            DIFF -> "比较两次快照差异，精确检测变化"
        }
}

/**
 * 屏幕变化事件
 */
data class ScreenChangeEvent(
    val eventType: ChangeType,
    val timestamp: Long,
    val packageName: String?,
    val changedNode: UINode?,
    val description: String
)

/**
 * 变化类型
 */
enum class ChangeType {
    WINDOW_CHANGED,      // 窗口切换
    CONTENT_CHANGED,     // 内容变化
    VIEW_CLICKED,        // 点击事件
    VIEW_SCROLLED,       // 滚动事件
    TEXT_CHANGED,        // 文本变化
    FOCUS_CHANGED,       // 焦点变化
    UNKNOWN
}

/**
 * 屏幕差异结果
 */
data class ScreenDiff(
    val hasChanges: Boolean,
    val addedNodes: List<UINode>,      // 新增的节点
    val removedNodes: List<UINode>,    // 消失的节点
    val modifiedNodes: List<NodeChange>, // 修改的节点
    val summary: String                 // 差异摘要（给 AI 看）
)

/**
 * 节点变化详情
 */
data class NodeChange(
    val node: UINode,
    val changeType: String,  // "text", "visibility", "bounds" 等
    val oldValue: String?,
    val newValue: String?
)
