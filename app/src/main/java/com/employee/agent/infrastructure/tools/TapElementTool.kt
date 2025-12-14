// infrastructure/tools/TapElementTool.kt
package com.employee.agent.infrastructure.tools

import com.employee.agent.domain.agent.ActionResult
import com.employee.agent.domain.tool.Tool
import com.employee.agent.domain.tool.ToolParameter
import com.employee.agent.domain.tool.ParameterType
import com.employee.agent.infrastructure.accessibility.AccessibilityGestureExecutor
import com.employee.agent.infrastructure.accessibility.UITreeParser

/**
 * 点击元素工具（通过文本查找）
 */
class TapElementTool(
    private val executor: AccessibilityGestureExecutor,
    private val parser: UITreeParser
) : Tool {
    override val name = "tap_element"
    override val description = "点击包含指定文本的元素"
    override val parameters = listOf(
        ToolParameter("text", ParameterType.STRING, "元素文本")
    )
    
    override suspend fun execute(params: Map<String, Any>): ActionResult {
        val text = params["text"] as? String ?: return ActionResult(false, "缺少 text 参数")
        
        val center = parser.findElementCenter(text)
            ?: return ActionResult(false, "未找到包含 '$text' 的元素")
        
        return executor.tap(center.first, center.second)
    }
}
