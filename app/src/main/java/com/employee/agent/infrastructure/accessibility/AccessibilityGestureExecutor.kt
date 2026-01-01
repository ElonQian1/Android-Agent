// infrastructure/accessibility/AccessibilityGestureExecutor.kt
package com.employee.agent.infrastructure.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.annotation.RequiresApi
import com.employee.agent.domain.agent.*
import com.employee.agent.domain.tool.Tool
import com.employee.agent.domain.tool.ToolParameter
import com.employee.agent.domain.tool.ParameterType
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * 基于 AccessibilityService 的手势执行器
 */
@RequiresApi(Build.VERSION_CODES.N)
class AccessibilityGestureExecutor(
    private val service: AccessibilityService
) {
    
    /**
     * 🆕 获取 Root Window 的辅助函数
     */
    private fun getRootNode(): AccessibilityNodeInfo? {
        service.rootInActiveWindow?.let { return it }
        
        try {
            val windows = service.windows
            if (windows != null && windows.isNotEmpty()) {
                for (window in windows) {
                    if (window.isActive && window.isFocused) {
                        window.root?.let { return it }
                    }
                }
                for (window in windows) {
                    if (window.isActive && window.type == AccessibilityWindowInfo.TYPE_APPLICATION) {
                        window.root?.let { return it }
                    }
                }
                for (window in windows) {
                    if (window.isActive) {
                        window.root?.let { return it }
                    }
                }
                windows.find { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.root != null }?.root?.let { return it }
                for (window in windows) {
                    window.root?.let { return it }
                }
            }
        } catch (_: Exception) {}
        
        return null
    }
    
    /**
     * 点击坐标
     */
    suspend fun tap(x: Int, y: Int): ActionResult {
        return try {
            val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
                .build()
            
            val success = dispatchGestureAsync(gesture)
            if (success) {
                ActionResult(true, "点击 ($x, $y) 成功")
            } else {
                ActionResult(false, "点击 ($x, $y) 失败")
            }
        } catch (e: Exception) {
            Log.e("GestureExecutor", "点击失败", e)
            ActionResult(false, "点击异常: ${e.message}")
        }
    }
    
    /**
     * 点击元素（通过文本查找）
     */
    suspend fun tapElement(text: String): ActionResult {
        return try {
            val root = getRootNode()
            if (root == null) {
                return ActionResult(false, "无法获取屏幕内容")
            }
            
            val node = findNodeByText(root, text)
            if (node == null) {
                return ActionResult(false, "未找到包含 '$text' 的元素")
            }
            
            val rect = android.graphics.Rect()
            node.getBoundsInScreen(rect)
            val centerX = rect.centerX()
            val centerY = rect.centerY()
            
            node.recycle()
            root.recycle()
            
            tap(centerX, centerY)
        } catch (e: Exception) {
            Log.e("GestureExecutor", "点击元素失败", e)
            ActionResult(false, "点击元素异常: ${e.message}")
        }
    }
    
    /**
     * 滑动
     */
    suspend fun swipe(
        direction: SwipeDirection,
        distance: SwipeDistance = SwipeDistance.MEDIUM
    ): ActionResult {
        return try {
            val (startX, startY, endX, endY) = calculateSwipeCoords(direction, distance)
            
            val path = Path().apply {
                moveTo(startX, startY)
                lineTo(endX, endY)
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 300))
                .build()
            
            val success = dispatchGestureAsync(gesture)
            if (success) {
                ActionResult(true, "滑动 $direction 成功")
            } else {
                ActionResult(false, "滑动 $direction 失败")
            }
        } catch (e: Exception) {
            Log.e("GestureExecutor", "滑动失败", e)
            ActionResult(false, "滑动异常: ${e.message}")
        }
    }
    
    /**
     * 输入文本
     */
    fun inputText(text: String): ActionResult {
        return try {
            val root = getRootNode()
            if (root == null) {
                return ActionResult(false, "无法获取屏幕内容")
            }
            
            val focusedNode = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (focusedNode == null) {
                root.recycle()
                return ActionResult(false, "没有输入框获得焦点")
            }
            
            val success = focusedNode.performAction(
                AccessibilityNodeInfo.ACTION_SET_TEXT,
                android.os.Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
            )
            
            focusedNode.recycle()
            root.recycle()
            
            if (success) {
                ActionResult(true, "输入文本 '$text' 成功")
            } else {
                ActionResult(false, "输入文本失败")
            }
        } catch (e: Exception) {
            Log.e("GestureExecutor", "输入文本失败", e)
            ActionResult(false, "输入文本异常: ${e.message}")
        }
    }
    
    /**
     * 按键
     */
    fun pressKey(key: KeyCode): ActionResult {
        return try {
            val action = when (key) {
                KeyCode.BACK -> AccessibilityService.GLOBAL_ACTION_BACK
                KeyCode.HOME -> AccessibilityService.GLOBAL_ACTION_HOME
                KeyCode.MENU -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                    AccessibilityService.GLOBAL_ACTION_RECENTS
                } else {
                    return ActionResult(false, "系统不支持菜单键")
                }
                else -> return ActionResult(false, "不支持的按键: $key")
            }
            
            val success = service.performGlobalAction(action)
            if (success) {
                ActionResult(true, "按键 $key 成功")
            } else {
                ActionResult(false, "按键 $key 失败")
            }
        } catch (e: Exception) {
            Log.e("GestureExecutor", "按键失败", e)
            ActionResult(false, "按键异常: ${e.message}")
        }
    }
    
    /**
     * 异步分发手势
     */
    private suspend fun dispatchGestureAsync(gesture: GestureDescription): Boolean {
        return suspendCancellableCoroutine { continuation ->
            val callback = object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    continuation.resume(true)
                }
                
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    continuation.resume(false)
                }
            }
            
            val dispatched = service.dispatchGesture(gesture, callback, null)
            if (!dispatched) {
                continuation.resume(false)
            }
        }
    }
    
    /**
     * 查找包含指定文本的节点
     */
    private fun findNodeByText(root: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        if (root.text?.contains(text) == true || root.contentDescription?.contains(text) == true) {
            return root
        }
        
        for (i in 0 until root.childCount) {
            root.getChild(i)?.let { child ->
                findNodeByText(child, text)?.let { found ->
                    return found
                }
                child.recycle()
            }
        }
        
        return null
    }
    
    /**
     * 计算滑动坐标
     */
    private fun calculateSwipeCoords(
        direction: SwipeDirection,
        distance: SwipeDistance
    ): SwipeCoords {
        val centerX = 540f
        val centerY = 960f
        
        val offset = when (distance) {
            SwipeDistance.SHORT -> 200f
            SwipeDistance.LONG -> 600f
            SwipeDistance.MEDIUM -> 400f
        }
        
        return when (direction) {
            SwipeDirection.UP -> SwipeCoords(centerX, centerY + offset, centerX, centerY - offset)
            SwipeDirection.DOWN -> SwipeCoords(centerX, centerY - offset, centerX, centerY + offset)
            SwipeDirection.LEFT -> SwipeCoords(centerX + offset, centerY, centerX - offset, centerY)
            SwipeDirection.RIGHT -> SwipeCoords(centerX - offset, centerY, centerX + offset, centerY)
        }
    }
}

data class SwipeCoords(val startX: Float, val startY: Float, val endX: Float, val endY: Float)
