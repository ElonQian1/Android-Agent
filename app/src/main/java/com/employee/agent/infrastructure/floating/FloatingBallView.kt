// infrastructure/floating/FloatingBallView.kt
// module: infrastructure/floating | layer: infrastructure | role: floating-ball-view
// summary: 悬浮球视图 - 可拖拽、支持单击/双击检测、状态动画

package com.employee.agent.infrastructure.floating

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import kotlin.math.abs

/**
 * 🎈 悬浮球视图
 * 
 * 功能：
 * - 可拖拽移动
 * - 单击检测 -> 语音输入
 * - 双击检测 -> 文字输入
 * - 状态动画（空闲/执行中/错误）
 */
@SuppressLint("ViewConstructor")
class FloatingBallView(context: Context) : FrameLayout(context) {
    
    companion object {
        private const val TAG = "FloatingBallView"
        
        // 尺寸
        private const val BALL_SIZE = 120  // dp -> px 会在代码中转换
        
        // 点击检测
        private const val CLICK_THRESHOLD = 15  // 移动阈值，小于此值视为点击
        private const val DOUBLE_CLICK_TIMEOUT = 300L  // 双击间隔
        private const val LONG_PRESS_TIMEOUT = 500L    // 长按超时
    }
    
    // 回调
    var onSingleClick: (() -> Unit)? = null
    var onDoubleClick: (() -> Unit)? = null
    
    // 视图组件
    private val ballView: TextView
    private val pulseView: View
    
    // 触摸状态
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var isDragging = false
    
    // 点击检测
    private var clickCount = 0
    private val clickHandler = Handler(Looper.getMainLooper())
    private var pendingClickRunnable: Runnable? = null
    
    // 动画
    private var rotationAnimator: ObjectAnimator? = null
    private var pulseAnimator: ValueAnimator? = null
    
    // 当前状态
    private var currentState = FloatingBallState.IDLE
    
    init {
        val density = context.resources.displayMetrics.density
        val ballSizePx = (BALL_SIZE * density).toInt()
        
        // 创建脉冲背景（用于监听状态动画）
        pulseView = View(context).apply {
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#4A90D9"))
            }
            background = bg
            alpha = 0f
        }
        addView(pulseView, LayoutParams(ballSizePx, ballSizePx).apply {
            gravity = Gravity.CENTER
        })
        
        // 创建球体
        ballView = TextView(context).apply {
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#4CAF50"))  // 默认绿色
                setStroke((2 * density).toInt(), Color.WHITE)
            }
            background = bg
            
            text = "🤖"
            textSize = 28f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            
            // 阴影效果
            elevation = 8 * density
        }
        addView(ballView, LayoutParams(ballSizePx, ballSizePx).apply {
            gravity = Gravity.CENTER
        })
        
        // 设置触摸监听
        setupTouchListener()
        
        Log.i(TAG, "悬浮球视图已创建")
    }
    
    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouchListener() {
        setOnTouchListener { _, event ->
            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val layoutParams = layoutParams as WindowManager.LayoutParams
            
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = layoutParams.x
                    initialY = layoutParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    true
                }
                
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    
                    // 判断是否开始拖拽
                    if (abs(dx) > CLICK_THRESHOLD || abs(dy) > CLICK_THRESHOLD) {
                        isDragging = true
                        
                        // 更新位置
                        layoutParams.x = initialX + dx.toInt()
                        layoutParams.y = initialY + dy.toInt()
                        
                        try {
                            windowManager.updateViewLayout(this, layoutParams)
                        } catch (e: Exception) {
                            Log.e(TAG, "更新位置失败", e)
                        }
                    }
                    true
                }
                
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        handleClick()
                    }
                    true
                }
                
                else -> false
            }
        }
    }
    
    private fun handleClick() {
        clickCount++
        
        // 清除之前的待处理点击
        pendingClickRunnable?.let { clickHandler.removeCallbacks(it) }
        
        if (clickCount == 1) {
            // 第一次点击，等待可能的第二次点击
            pendingClickRunnable = Runnable {
                if (clickCount == 1) {
                    Log.i(TAG, "单击检测")
                    onSingleClick?.invoke()
                }
                clickCount = 0
            }
            clickHandler.postDelayed(pendingClickRunnable!!, DOUBLE_CLICK_TIMEOUT)
            
        } else if (clickCount >= 2) {
            // 双击
            Log.i(TAG, "双击检测")
            clickHandler.removeCallbacks(pendingClickRunnable!!)
            clickCount = 0
            onDoubleClick?.invoke()
        }
    }
    
    // ==================== 状态管理 ====================
    
    fun setState(state: FloatingBallState) {
        if (currentState == state) return
        currentState = state
        
        post {
            stopAllAnimations()
            
            when (state) {
                FloatingBallState.IDLE -> {
                    setColor("#4CAF50")  // 绿色
                    ballView.text = "🤖"
                }
                
                FloatingBallState.LISTENING -> {
                    setColor("#2196F3")  // 蓝色
                    ballView.text = "🎤"
                    startPulseAnimation()
                }
                
                FloatingBallState.EXECUTING -> {
                    setColor("#2196F3")  // 蓝色
                    ballView.text = "⚙️"
                    startRotationAnimation()
                }
                
                FloatingBallState.ERROR -> {
                    setColor("#F44336")  // 红色
                    ballView.text = "❌"
                }
            }
        }
    }
    
    private fun setColor(colorHex: String) {
        val bg = ballView.background as? GradientDrawable
        bg?.setColor(Color.parseColor(colorHex))
    }
    
    // ==================== 动画 ====================
    
    private fun startRotationAnimation() {
        rotationAnimator = ObjectAnimator.ofFloat(ballView, "rotation", 0f, 360f).apply {
            duration = 1500
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            start()
        }
    }
    
    private fun startPulseAnimation() {
        pulseAnimator = ValueAnimator.ofFloat(0.3f, 0.8f).apply {
            duration = 800
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            addUpdateListener { 
                pulseView.alpha = it.animatedValue as Float
                pulseView.scaleX = 1f + (it.animatedValue as Float) * 0.3f
                pulseView.scaleY = 1f + (it.animatedValue as Float) * 0.3f
            }
            start()
        }
    }
    
    private fun stopAllAnimations() {
        rotationAnimator?.cancel()
        rotationAnimator = null
        ballView.rotation = 0f
        
        pulseAnimator?.cancel()
        pulseAnimator = null
        pulseView.alpha = 0f
        pulseView.scaleX = 1f
        pulseView.scaleY = 1f
    }
    
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopAllAnimations()
        pendingClickRunnable?.let { clickHandler.removeCallbacks(it) }
    }
}
