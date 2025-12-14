// infrastructure/tools/TapTool.kt
package com.employee.agent.infrastructure.tools

import com.employee.agent.domain.agent.ActionResult
import com.employee.agent.domain.tool.Tool
import com.employee.agent.domain.tool.ToolParameter
import com.employee.agent.domain.tool.ParameterType
import com.employee.agent.infrastructure.accessibility.AccessibilityGestureExecutor

/**
 * 点击坐标工具
 */
class TapTool(
    private val executor: AccessibilityGestureExecutor
) : Tool {
    override val name = "tap"
    override val description = "点击屏幕指定坐标"
    override val parameters = listOf(
        ToolParameter("x", ParameterType.INT, "X坐标"),
        ToolParameter("y", ParameterType.INT, "Y坐标")
    )
    
    override suspend fun execute(params: Map<String, Any>): ActionResult {
        val x = (params["x"] as? Number)?.toInt() ?: return ActionResult(false, "缺少 x 参数")
        val y = (params["y"] as? Number)?.toInt() ?: return ActionResult(false, "缺少 y 参数")
        return executor.tap(x, y)
    }
}
