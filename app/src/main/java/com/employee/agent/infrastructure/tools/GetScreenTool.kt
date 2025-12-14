// infrastructure/tools/GetScreenTool.kt
package com.employee.agent.infrastructure.tools

import com.employee.agent.domain.agent.ActionResult
import com.employee.agent.domain.tool.Tool
import com.employee.agent.domain.tool.ToolParameter
import com.employee.agent.infrastructure.accessibility.UITreeParser

/**
 * 获取屏幕工具
 */
class GetScreenTool(
    private val parser: UITreeParser
) : Tool {
    override val name = "get_screen"
    override val description = "获取当前屏幕UI结构"
    override val parameters = emptyList<ToolParameter>()
    
    override suspend fun execute(params: Map<String, Any>): ActionResult {
        val screen = parser.parseCurrentScreen()
            ?: return ActionResult(false, "无法读取屏幕")
        
        val summary = screen.getClickableElementsSummary()
        return ActionResult(true, summary)
    }
}
