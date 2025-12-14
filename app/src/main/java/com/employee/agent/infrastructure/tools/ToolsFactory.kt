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
    override val description = "点击屏幕坐标"
    override val parameters = listOf(
        ToolParameter("x", ParameterType.INT, "X 坐标"),
        ToolParameter("y", ParameterType.INT, "Y 坐标")
    )
    
    override suspend fun execute(params: Map<String, Any>): ActionResult {
        val x = (params["x"] as? Number)?.toInt() ?: return ActionResult(false, "缺少参数 x")
        val y = (params["y"] as? Number)?.toInt() ?: return ActionResult(false, "缺少参数 y")
        return executor.tap(x, y)
    }
}

/**
 * 点击元素工具
 */
class TapElementTool(
    private val executor: AccessibilityGestureExecutor
) : Tool {
    override val name = "tap_element"
    override val description = "点击包含指定文本的元素"
    override val parameters = listOf(
        ToolParameter("text", ParameterType.STRING, "元素文本")
    )
    
    override suspend fun execute(params: Map<String, Any>): ActionResult {
        val text = params["text"] as? String ?: return ActionResult(false, "缺少参数 text")
        return executor.tapElement(text)
    }
}

/**
 * 滑动工具
 */
class SwipeTool(
    private val executor: AccessibilityGestureExecutor
) : Tool {
    override val name = "swipe"
    override val description = "滑动屏幕"
    override val parameters = listOf(
        ToolParameter("direction", ParameterType.STRING, "方向: up/down/left/right"),
        ToolParameter("distance", ParameterType.STRING, "距离: short/medium/long", required = false, defaultValue = "medium")
    )
    
    override suspend fun execute(params: Map<String, Any>): ActionResult {
        val directionStr = params["direction"] as? String ?: return ActionResult(false, "缺少参数 direction")
        val distanceStr = params["distance"] as? String ?: "medium"
        
        val direction = com.employee.agent.domain.agent.SwipeDirection.valueOf(directionStr.uppercase())
        val distance = com.employee.agent.domain.agent.SwipeDistance.valueOf(distanceStr.uppercase())
        
        return executor.swipe(direction, distance)
    }
}

/**
 * 输入文本工具
 */
class InputTextTool(
    private val executor: AccessibilityGestureExecutor
) : Tool {
    override val name = "input_text"
    override val description = "在当前焦点输入文本"
    override val parameters = listOf(
        ToolParameter("text", ParameterType.STRING, "要输入的文本")
    )
    
    override suspend fun execute(params: Map<String, Any>): ActionResult {
        val text = params["text"] as? String ?: return ActionResult(false, "缺少参数 text")
        return executor.inputText(text)
    }
}

/**
 * 按键工具
 */
class PressKeyTool(
    private val executor: AccessibilityGestureExecutor
) : Tool {
    override val name = "press_key"
    override val description = "按下设备按键"
    override val parameters = listOf(
        ToolParameter("key", ParameterType.STRING, "按键: back/home/menu")
    )
    
    override suspend fun execute(params: Map<String, Any>): ActionResult {
        val keyStr = params["key"] as? String ?: return ActionResult(false, "缺少参数 key")
        val key = com.employee.agent.domain.agent.KeyCode.valueOf(keyStr.uppercase())
        return executor.pressKey(key)
    }
}

/**
 * 等待工具
 */
class WaitTool : Tool {
    override val name = "wait"
    override val description = "等待指定时间"
    override val parameters = listOf(
        ToolParameter("milliseconds", ParameterType.INT, "等待毫秒数")
    )
    
    override suspend fun execute(params: Map<String, Any>): ActionResult {
        val ms = (params["milliseconds"] as? Number)?.toLong() ?: return ActionResult(false, "缺少参数 milliseconds")
        kotlinx.coroutines.delay(ms)
        return ActionResult(true, "等待 ${ms}ms 完成")
    }
}
