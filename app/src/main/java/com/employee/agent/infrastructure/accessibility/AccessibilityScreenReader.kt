// infrastructure/accessibility/AccessibilityScreenReader.kt
package com.employee.agent.infrastructure.accessibility

import android.accessibilityservice.AccessibilityService
import com.employee.agent.application.ScreenReader
import com.employee.agent.domain.screen.UINode

/**
 * 基于无障碍服务的屏幕读取器
 */
class AccessibilityScreenReader(
    private val service: AccessibilityService
) : ScreenReader {
    
    private val parser = UITreeParser(service)
    
    override suspend fun readCurrentScreen(): UINode {
        return parser.parseCurrentScreen() 
            ?: UINode(
                className = "Error",
                text = "无法读取屏幕",
                contentDescription = null,
                resourceId = null,
                bounds = android.graphics.Rect(),
                children = emptyList()
            )
    }
}
