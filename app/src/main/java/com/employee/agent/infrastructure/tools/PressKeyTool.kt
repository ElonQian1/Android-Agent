// infrastructure/tools/PressKeyTool.kt
package com.employee.agent.infrastructure.tools

import com.employee.agent.domain.agent.ActionResult
import com.employee.agent.domain.agent.KeyCode
import com.employee.agent.domain.tool.Tool
import com.employee.agent.domain.tool.ToolParameter
import com.employee.agent.domain.tool.ParameterType
import com.employee.agent.infrastructure.accessibility.AccessibilityGestureExecutor

/**
 * 按键工具
 */
class PressKeyTool(
    private val executor: AccessibilityGestureExecutor
) : Tool {
    override val name = "press_key"
    override val description = "按下系统按键"
    override val parameters = listOf(
        ToolParameter("key", ParameterType.STRING, "按键: back/home/menu/enter/delete")
    )
    
    override suspend fun execute(params: Map<String, Any>): ActionResult {
        val keyStr = params["key"] as? String ?: return ActionResult(false, "缺少 key 参数")
        
        val keyCode = when (keyStr.lowercase()) {
            "back" -> KeyCode.BACK
            "home" -> KeyCode.HOME
            "menu" -> KeyCode.MENU
            "enter" -> KeyCode.ENTER
            "delete" -> KeyCode.DELETE
            else -> return ActionResult(false, "无效的按键: $keyStr")
        }
        
        return executor.pressKey(keyCode)
    }
}
