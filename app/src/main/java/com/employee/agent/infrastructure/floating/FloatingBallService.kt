// infrastructure/floating/FloatingBallService.kt
// module: infrastructure/floating | layer: infrastructure | role: floating-ball-service
// summary: 悬浮球服务 - 提供全局悬浮球，单击语音输入，双击文字输入

package com.employee.agent.infrastructure.floating

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat

/**
 * 🎈 悬浮球服务
 * 
 * 功能：
 * - 单击：语音输入任务
 * - 双击：文字输入任务
 * - 长按：拖拽移动
 * - 执行中：显示旋转动画
 */
class FloatingBallService : Service() {
    
    companion object {
        private const val TAG = "FloatingBall"
        private const val NOTIFICATION_ID = 2001
        private const val CHANNEL_ID = "floating_ball_channel"
        
        // 服务状态
        var isRunning = false
            private set
        
        // 任务执行回调（由 AgentService 设置）
        var onTaskSubmit: ((String) -> Unit)? = null
        
        /**
         * 启动悬浮球服务
         */
        fun start(context: Context) {
            Log.i(TAG, "尝试启动悬浮球服务...")
            
            if (!canDrawOverlays(context)) {
                Log.w(TAG, "没有悬浮窗权限")
                Toast.makeText(context, "请先授予悬浮窗权限", Toast.LENGTH_LONG).show()
                requestOverlayPermission(context)
                return
            }
            
            try {
                val intent = Intent(context, FloatingBallService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    Log.i(TAG, "使用 startForegroundService")
                    context.startForegroundService(intent)
                } else {
                    Log.i(TAG, "使用 startService")
                    context.startService(intent)
                }
                Log.i(TAG, "悬浮球服务启动命令已发送")
            } catch (e: Exception) {
                Log.e(TAG, "启动悬浮球服务失败", e)
                Toast.makeText(context, "启动失败: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
        
        /**
         * 停止悬浮球服务
         */
        fun stop(context: Context) {
            context.stopService(Intent(context, FloatingBallService::class.java))
        }
        
        /**
         * 检查是否有悬浮窗权限
         */
        fun canDrawOverlays(context: Context): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Settings.canDrawOverlays(context)
            } else {
                true
            }
        }
        
        /**
         * 请求悬浮窗权限
         */
        fun requestOverlayPermission(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:${context.packageName}")
                )
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            }
        }
    }
    
    private var windowManager: WindowManager? = null
    private var floatingBallView: FloatingBallView? = null
    
    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "=== 悬浮球服务 onCreate ===")
        isRunning = true
        
        try {
            createNotificationChannel()
            Log.i(TAG, "通知渠道已创建")
            
            startForeground(NOTIFICATION_ID, createNotification())
            Log.i(TAG, "前台服务已启动")
            
            showFloatingBall()
            Log.i(TAG, "悬浮球已显示")
        } catch (e: Exception) {
            Log.e(TAG, "onCreate 失败", e)
        }
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "=== 悬浮球服务 onStartCommand ===")
        return START_STICKY
    }
    
    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "=== 悬浮球服务 onDestroy ===")
        isRunning = false
        hideFloatingBall()
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    // ==================== 悬浮球显示 ====================
    
    private fun showFloatingBall() {
        if (floatingBallView != null) return
        
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        
        // 创建悬浮球视图
        floatingBallView = FloatingBallView(this).apply {
            // 单击 -> 语音输入
            onSingleClick = {
                Log.i(TAG, "单击 -> 启动语音输入")
                showVoiceInputDialog()
            }
            
            // 双击 -> 文字输入
            onDoubleClick = {
                Log.i(TAG, "双击 -> 显示文字输入")
                showTextInputDialog()
            }
        }
        
        // 悬浮窗参数
        val layoutParams = WindowManager.LayoutParams().apply {
            width = WindowManager.LayoutParams.WRAP_CONTENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
            
            type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
            
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            
            format = PixelFormat.TRANSLUCENT
            gravity = Gravity.TOP or Gravity.START
            
            // 初始位置：右侧中间
            val displayMetrics = resources.displayMetrics
            x = displayMetrics.widthPixels - 150
            y = displayMetrics.heightPixels / 3
        }
        
        try {
            windowManager?.addView(floatingBallView, layoutParams)
            Log.i(TAG, "悬浮球已显示")
        } catch (e: Exception) {
            Log.e(TAG, "显示悬浮球失败", e)
        }
    }
    
    private fun hideFloatingBall() {
        floatingBallView?.let {
            try {
                windowManager?.removeView(it)
                Log.i(TAG, "悬浮球已隐藏")
            } catch (e: Exception) {
                Log.e(TAG, "隐藏悬浮球失败", e)
            }
        }
        floatingBallView = null
    }
    
    // ==================== 语音输入 ====================
    
    private fun showVoiceInputDialog() {
        val intent = Intent(this, FloatingVoiceActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        startActivity(intent)
    }
    
    // ==================== 文字输入 ====================
    
    private fun showTextInputDialog() {
        val intent = Intent(this, FloatingInputActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        startActivity(intent)
    }
    
    // ==================== 状态更新 ====================
    
    /**
     * 更新悬浮球状态
     */
    fun updateState(state: FloatingBallState) {
        floatingBallView?.setState(state)
    }
    
    // ==================== 通知 ====================
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "悬浮球服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持悬浮球运行"
                setShowBadge(false)
            }
            
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
    
    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("🎈 悬浮球运行中")
            .setContentText("单击语音 | 双击文字 | 长按拖动")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }
}

/**
 * 悬浮球状态
 */
enum class FloatingBallState {
    IDLE,       // 空闲 - 绿色
    LISTENING,  // 监听中 - 蓝色脉冲
    EXECUTING,  // 执行中 - 蓝色旋转
    ERROR       // 错误 - 红色
}
