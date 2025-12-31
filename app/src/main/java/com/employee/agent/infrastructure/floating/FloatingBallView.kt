// infrastructure/floating/FloatingBallView.kt
// module: infrastructure/floating | layer: infrastructure | role: floating-ball-view
// summary: 悬浮球视图 - 可拖拽、支持单击/双击检测、状态动画、玻璃拟态设计

package com.employee.agent.infrastructure.floating

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import kotlin.math.abs

/**
 * 🎈 悬浮球视图 - 玻璃拟态设计
 * 
 * 功能：
 * - 可拖拽移动
 * - 单击检测 -> 语音输入
 * - 双击检测 -> 文字输入
 * - 状态动画（空闲/执行中/错误）
 * - 玻璃拟态 + 光晕效果
 */
@SuppressLint("ViewConstructor")
class FloatingBallView(context: Context) : FrameLayout(context) {
    
    companion object {
        private const val TAG = "FloatingBallView"
        
        // 尺寸
        private const val BALL_SIZE = 56  // 更紧凑的尺寸 (dp)
        private const val OUTER_GLOW_SIZE = 72  // 外发光尺寸 (dp)
        
        // 点击检测
        private const val CLICK_THRESHOLD = 15  // 移动阈值，小于此值视为点击
        private const val DOUBLE_CLICK_TIMEOUT = 300L  // 双击间隔
        private const val LONG_PRESS_TIMEOUT = 500L    // 长按超时
        
        // 颜色主题
        private val COLOR_IDLE_START = Color.parseColor("#667eea")      // 紫蓝渐变起点
        private val COLOR_IDLE_END = Color.parseColor("#764ba2")        // 紫蓝渐变终点
        private val COLOR_LISTENING_START = Color.parseColor("#11998e") // 青绿渐变
        private val COLOR_LISTENING_END = Color.parseColor("#38ef7d")
        private val COLOR_EXECUTING_START = Color.parseColor("#4facfe") // 天蓝渐变
        private val COLOR_EXECUTING_END = Color.parseColor("#00f2fe")
        private val COLOR_ERROR_START = Color.parseColor("#ff416c")     // 红粉渐变
        private val COLOR_ERROR_END = Color.parseColor("#ff4b2b")
    }
    
    // 回调
    var onSingleClick: (() -> Unit)? = null
    var onDoubleClick: (() -> Unit)? = null
    
    // 视图组件
    private val outerGlowView: View        // 外发光层
    private val innerGlowView: View        // 内发光层
    private val ballView: View             // 主球体
    private val iconView: TextView         // 图标层
    private val highlightView: View        // 高光层
    
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
    private var breatheAnimator: AnimatorSet? = null
    private var glowAnimator: ValueAnimator? = null
    
    // 当前状态
    private var currentState = FloatingBallState.IDLE
    
    init {
        val density = context.resources.displayMetrics.density
        val ballSizePx = (BALL_SIZE * density).toInt()
        val outerGlowSizePx = (OUTER_GLOW_SIZE * density).toInt()
        
        // === 1. 外发光层 (最底层) ===
        outerGlowView = View(context).apply {
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.TRANSPARENT)
                // 使用渐变模拟发光效果
                colors = intArrayOf(
                    Color.parseColor("#40667eea"),  // 半透明紫色
                    Color.parseColor("#00667eea")   // 完全透明
                )
                gradientType = GradientDrawable.RADIAL_GRADIENT
                gradientRadius = outerGlowSizePx / 2f
            }
            background = bg
            alpha = 0.6f
        }
        addView(outerGlowView, LayoutParams(outerGlowSizePx, outerGlowSizePx).apply {
            gravity = Gravity.CENTER
        })
        
        // === 2. 内发光层 ===
        innerGlowView = View(context).apply {
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                colors = intArrayOf(
                    Color.parseColor("#50667eea"),
                    Color.parseColor("#20764ba2")
                )
                gradientType = GradientDrawable.RADIAL_GRADIENT
                gradientRadius = ballSizePx * 0.6f
            }
            background = bg
            alpha = 0.8f
        }
        val innerGlowSize = (ballSizePx * 1.15f).toInt()
        addView(innerGlowView, LayoutParams(innerGlowSize, innerGlowSize).apply {
            gravity = Gravity.CENTER
        })
        
        // === 3. 主球体 (玻璃拟态) ===
        ballView = View(context).apply {
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                // 渐变背景
                colors = intArrayOf(COLOR_IDLE_START, COLOR_IDLE_END)
                orientation = GradientDrawable.Orientation.TL_BR
                // 细边框
                setStroke((1.5f * density).toInt(), Color.parseColor("#40FFFFFF"))
            }
            background = bg
            elevation = 12 * density  // 增强阴影
        }
        addView(ballView, LayoutParams(ballSizePx, ballSizePx).apply {
            gravity = Gravity.CENTER
        })
        
        // === 4. 高光层 (玻璃反光效果) ===
        highlightView = View(context).apply {
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                colors = intArrayOf(
                    Color.parseColor("#50FFFFFF"),  // 顶部高亮
                    Color.parseColor("#00FFFFFF")   // 底部透明
                )
                orientation = GradientDrawable.Orientation.TOP_BOTTOM
            }
            background = bg
        }
        val highlightSize = (ballSizePx * 0.85f).toInt()
        val highlightOffset = -(ballSizePx * 0.08f).toInt()
        addView(highlightView, LayoutParams(highlightSize, (highlightSize * 0.5f).toInt()).apply {
            gravity = Gravity.CENTER_HORIZONTAL or Gravity.TOP
            topMargin = ((outerGlowSizePx - ballSizePx) / 2) + (ballSizePx * 0.08f).toInt()
        })
        
        // === 5. 图标层 (最上层) ===
        iconView = TextView(context).apply {
            text = "✨"  // 默认空闲状态图标
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            // 添加文字阴影增强立体感
            setShadowLayer(4f, 0f, 2f, Color.parseColor("#40000000"))
        }
        addView(iconView, LayoutParams(ballSizePx, ballSizePx).apply {
            gravity = Gravity.CENTER
        })
        
        // 设置整体布局尺寸
        layoutParams = LayoutParams(outerGlowSizePx, outerGlowSizePx)
        
        // 设置触摸监听
        setupTouchListener()
        
        // 启动呼吸动画
        startBreatheAnimation()
        
        Log.i(TAG, "悬浮球视图已创建 (玻璃拟态设计)")
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
                    setGradientColors(COLOR_IDLE_START, COLOR_IDLE_END)
                    setGlowColor("#667eea")
                    iconView.text = "✨"
                    startBreatheAnimation()
                }
                
                FloatingBallState.LISTENING -> {
                    setGradientColors(COLOR_LISTENING_START, COLOR_LISTENING_END)
                    setGlowColor("#11998e")
                    iconView.text = "🎙️"
                    startPulseAnimation()
                    startGlowAnimation()
                }
                
                FloatingBallState.EXECUTING -> {
                    setGradientColors(COLOR_EXECUTING_START, COLOR_EXECUTING_END)
                    setGlowColor("#4facfe")
                    iconView.text = "⚡"
                    startRotationAnimation()
                    startGlowAnimation()
                }
                
                FloatingBallState.ERROR -> {
                    setGradientColors(COLOR_ERROR_START, COLOR_ERROR_END)
                    setGlowColor("#ff416c")
                    iconView.text = "⚠️"
                    startShakeAnimation()
                }
            }
        }
    }
    
    private fun setGradientColors(startColor: Int, endColor: Int) {
        val bg = ballView.background as? GradientDrawable
        bg?.colors = intArrayOf(startColor, endColor)
    }
    
    private fun setGlowColor(colorHex: String) {
        val color = Color.parseColor(colorHex)
        
        // 更新外发光
        val outerBg = outerGlowView.background as? GradientDrawable
        outerBg?.colors = intArrayOf(
            Color.argb(64, Color.red(color), Color.green(color), Color.blue(color)),
            Color.argb(0, Color.red(color), Color.green(color), Color.blue(color))
        )
        
        // 更新内发光
        val innerBg = innerGlowView.background as? GradientDrawable
        innerBg?.colors = intArrayOf(
            Color.argb(80, Color.red(color), Color.green(color), Color.blue(color)),
            Color.argb(32, Color.red(color), Color.green(color), Color.blue(color))
        )
    }
    
    // ==================== 动画效果 ====================
    
    /** 呼吸动画 - 空闲状态 */
    private fun startBreatheAnimation() {
        breatheAnimator = AnimatorSet().apply {
            val scaleX = ObjectAnimator.ofFloat(ballView, "scaleX", 1f, 1.05f, 1f)
            val scaleY = ObjectAnimator.ofFloat(ballView, "scaleY", 1f, 1.05f, 1f)
            val alpha = ObjectAnimator.ofFloat(outerGlowView, "alpha", 0.4f, 0.7f, 0.4f)
            
            playTogether(scaleX, scaleY, alpha)
            duration = 2500
            interpolator = AccelerateDecelerateInterpolator()
            
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (currentState == FloatingBallState.IDLE) {
                        start()
                    }
                }
            })
            start()
        }
    }
    
    /** 旋转动画 - 执行中状态 */
    private fun startRotationAnimation() {
        rotationAnimator = ObjectAnimator.ofFloat(iconView, "rotation", 0f, 360f).apply {
            duration = 1200
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            start()
        }
    }
    
    /** 脉冲动画 - 监听状态 */
    private fun startPulseAnimation() {
        pulseAnimator = ValueAnimator.ofFloat(1f, 1.2f).apply {
            duration = 600
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { 
                val scale = it.animatedValue as Float
                innerGlowView.scaleX = scale
                innerGlowView.scaleY = scale
                innerGlowView.alpha = 1.5f - scale  // 放大时变淡
            }
            start()
        }
    }
    
    /** 发光动画 - 活跃状态 */
    private fun startGlowAnimation() {
        glowAnimator = ValueAnimator.ofFloat(0.5f, 1f).apply {
            duration = 800
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            addUpdateListener {
                outerGlowView.alpha = it.animatedValue as Float
            }
            start()
        }
    }
    
    /** 抖动动画 - 错误状态 */
    private fun startShakeAnimation() {
        ObjectAnimator.ofFloat(ballView, "translationX", 0f, -10f, 10f, -10f, 10f, -5f, 5f, 0f).apply {
            duration = 500
            interpolator = LinearInterpolator()
            start()
        }
    }
    
    private fun stopAllAnimations() {
        rotationAnimator?.cancel()
        rotationAnimator = null
        iconView.rotation = 0f
        
        pulseAnimator?.cancel()
        pulseAnimator = null
        innerGlowView.scaleX = 1f
        innerGlowView.scaleY = 1f
        innerGlowView.alpha = 0.8f
        
        breatheAnimator?.cancel()
        breatheAnimator = null
        ballView.scaleX = 1f
        ballView.scaleY = 1f
        
        glowAnimator?.cancel()
        glowAnimator = null
        outerGlowView.alpha = 0.6f
    }
    
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopAllAnimations()
        pendingClickRunnable?.let { clickHandler.removeCallbacks(it) }
    }
}
