// infrastructure/tools/SwipeTool.kt
package com.employee.agent.infrastructure.tools

import com.employee.agent.domain.agent.ActionResult
import com.employee.agent.domain.agent.SwipeDirection
import com.employee.agent.domain.agent.SwipeDistance
import com.employee.agent.domain.tool.Tool
import com.employee.agent.domain.tool.ToolParameter
import com.employee.agent.domain.tool.ParameterType
import com.employee.agent.infrastructure.accessibility.AccessibilityGestureExecutor

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
        val directionStr = params["direction"] as? String ?: return ActionResult(false, "缺少 direction 参数")
        val distanceStr = params["distance"] as? String ?: "medium"
        
        val direction = when (directionStr.lowercase()) {
            "up" -> SwipeDirection.UP
            "down" -> SwipeDirection.DOWN
            "left" -> SwipeDirection.LEFT
            "right" -> SwipeDirection.RIGHT
            else -> return ActionResult(false, "无效的方向: $directionStr")
        }
        
        val distance = when (distanceStr.lowercase()) {
            "short" -> SwipeDistance.SHORT
            "long" -> SwipeDistance.LONG
            else -> SwipeDistance.MEDIUM
        }
        
        val (startX, startY, endX, endY) = calculateSwipeCoordinates(direction, distance)
        return executor.swipe(startX, startY, endX, endY)
    }
    
    private fun calculateSwipeCoordinates(
        direction: SwipeDirection,
        distance: SwipeDistance
    ): List<Int> {
        val centerX = 540
        val centerY = 960
        
        val offset = when (distance) {
            SwipeDistance.SHORT -> 200
            SwipeDistance.LONG -> 600
            SwipeDistance.MEDIUM -> 400
        }
        
        return when (direction) {
            SwipeDirection.UP -> listOf(centerX, centerY + offset, centerX, centerY - offset)
            SwipeDirection.DOWN -> listOf(centerX, centerY - offset, centerX, centerY + offset)
            SwipeDirection.LEFT -> listOf(centerX + offset, centerY, centerX - offset, centerY)
            SwipeDirection.RIGHT -> listOf(centerX - offset, centerY, centerX + offset, centerY)
        }
    }
}
