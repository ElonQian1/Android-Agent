// infrastructure/tools/WaitTool.kt
package com.employee.agent.infrastructure.tools

import com.employee.agent.domain.agent.ActionResult
import com.employee.agent.domain.tool.Tool
import com.employee.agent.domain.tool.ToolParameter
import com.employee.agent.domain.tool.ParameterType
import kotlinx.coroutines.delay

/**
 * 等待工具
 */
class WaitTool : Tool {
    override val name = "wait"
    override val description = "等待指定时间"
    override val parameters = listOf(
        ToolParameter("milliseconds", ParameterType.INT, "等待时间（毫秒）")
    )
    
    override suspend fun execute(params: Map<String, Any>): ActionResult {
        val ms = (params["milliseconds"] as? Number)?.toLong() ?: return ActionResult(false, "缺少 milliseconds 参数")
        delay(ms)
        return ActionResult(true, "✅ 已等待 ${ms}ms")
    }
}
