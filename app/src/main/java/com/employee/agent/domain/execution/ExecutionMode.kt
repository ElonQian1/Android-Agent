// domain/execution/ExecutionMode.kt
// module: domain/execution | layer: domain | role: execution-mode-types
// summary: 脚本执行模式定义 - 不同的 AI 介入程度

package com.employee.agent.domain.execution

/**
 * 🎮 脚本执行模式
 * 
 * 用户可以根据任务复杂度和对准确性的要求，选择不同的执行模式
 */
enum class ExecutionMode(
    val displayName: String,
    val emoji: String,
    val description: String,
    val tokenCostLevel: TokenCostLevel
) {
    /**
     * 🚀 极速模式
     * - 纯脚本执行，不做任何额外检测
     * - 失败就失败，不尝试恢复
     * - Token消耗：0
     */
    FAST(
        displayName = "极速模式",
        emoji = "🚀",
        description = "纯脚本执行，不做额外检测，最快速度",
        tokenCostLevel = TokenCostLevel.ZERO
    ),
    
    /**
     * 🛡️ 智能模式（推荐）
     * - 执行前：规则库自动清理弹窗
     * - 执行中：检测是否成功
     * - 异常时：调用 AI 分析并恢复
     * - Token消耗：仅异常时
     */
    SMART(
        displayName = "智能模式",
        emoji = "🛡️",
        description = "自动处理弹窗，出错时 AI 介入恢复（推荐）",
        tokenCostLevel = TokenCostLevel.LOW
    ),
    
    /**
     * 👁️ 监控模式
     * - 每步执行后截图让 AI 确认
     * - AI 判断是否符合预期
     * - 不符合则自动调整
     * - Token消耗：每步 ~500-1000
     */
    MONITOR(
        displayName = "监控模式",
        emoji = "👁️",
        description = "每步执行后 AI 验证，适合重要任务",
        tokenCostLevel = TokenCostLevel.MEDIUM
    ),
    
    /**
     * 🤖 全程代理模式
     * - AI 实时观察屏幕
     * - AI 决定下一步做什么
     * - 脚本只是参考，AI 可以即兴发挥
     * - Token消耗：每步 ~1000-2000
     */
    AGENT(
        displayName = "全程代理",
        emoji = "🤖",
        description = "AI 全程决策控制，适合复杂或探索性任务",
        tokenCostLevel = TokenCostLevel.HIGH
    );
    
    companion object {
        /** 默认模式 */
        val DEFAULT = SMART
        
        /** 根据名称获取模式 */
        fun fromName(name: String): ExecutionMode {
            return values().find { it.name.equals(name, ignoreCase = true) } ?: DEFAULT
        }
    }
}

/**
 * 💰 Token 消耗等级
 */
enum class TokenCostLevel(
    val displayName: String,
    val estimatedTokensPerStep: IntRange
) {
    ZERO("零消耗", 0..0),
    LOW("低消耗", 0..500),
    MEDIUM("中等消耗", 500..1000),
    HIGH("高消耗", 1000..2000)
}

/**
 * ⚙️ 执行配置
 */
data class ExecutionConfig(
    /** 执行模式 */
    val mode: ExecutionMode = ExecutionMode.DEFAULT,
    
    /** 最大重试次数 */
    val maxRetries: Int = 3,
    
    /** 是否启用弹窗自动清理（SMART/MONITOR/AGENT 模式下有效） */
    val popupDismissEnabled: Boolean = true,
    
    /** 出错时是否截图保存 */
    val screenshotOnError: Boolean = true,
    
    /** 是否启用 AI 恢复（SMART 模式下有效） */
    val aiRecoveryEnabled: Boolean = true,
    
    /** AI 验证置信度阈值（MONITOR 模式下有效，低于此值会重试） */
    val aiVerifyThreshold: Float = 0.8f,
    
    /** 每步执行后的等待时间（毫秒） */
    val stepDelayMs: Long = 500,
    
    /** 弹窗清理后的等待时间（毫秒） */
    val popupDismissDelayMs: Long = 300
) {
    companion object {
        /** 极速模式默认配置 */
        val FAST_DEFAULT = ExecutionConfig(
            mode = ExecutionMode.FAST,
            popupDismissEnabled = false,
            aiRecoveryEnabled = false,
            stepDelayMs = 300
        )
        
        /** 智能模式默认配置（推荐） */
        val SMART_DEFAULT = ExecutionConfig(
            mode = ExecutionMode.SMART,
            popupDismissEnabled = true,
            aiRecoveryEnabled = true
        )
        
        /** 监控模式默认配置 */
        val MONITOR_DEFAULT = ExecutionConfig(
            mode = ExecutionMode.MONITOR,
            popupDismissEnabled = true,
            aiRecoveryEnabled = true,
            stepDelayMs = 800
        )
        
        /** 全程代理模式默认配置 */
        val AGENT_DEFAULT = ExecutionConfig(
            mode = ExecutionMode.AGENT,
            popupDismissEnabled = true,
            aiRecoveryEnabled = true,
            stepDelayMs = 1000
        )
        
        /** 根据模式获取默认配置 */
        fun forMode(mode: ExecutionMode): ExecutionConfig {
            return when (mode) {
                ExecutionMode.FAST -> FAST_DEFAULT
                ExecutionMode.SMART -> SMART_DEFAULT
                ExecutionMode.MONITOR -> MONITOR_DEFAULT
                ExecutionMode.AGENT -> AGENT_DEFAULT
            }
        }
    }
}
