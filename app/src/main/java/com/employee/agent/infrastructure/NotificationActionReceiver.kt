// infrastructure/NotificationActionReceiver.kt
package com.employee.agent.infrastructure

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.employee.agent.AgentExecuteActivity
import com.employee.agent.infrastructure.floating.FloatingVoiceActivity
import com.employee.agent.infrastructure.floating.FloatingInputActivity

/**
 * 📬 通知栏快捷操作接收器
 * 
 * 处理通知栏按钮点击事件
 */
class NotificationActionReceiver : BroadcastReceiver() {
    
    companion object {
        private const val TAG = "NotificationAction"
        
        const val ACTION_OPEN_APP = "com.employee.agent.ACTION_OPEN_APP"
        const val ACTION_QUICK_TASK = "com.employee.agent.ACTION_QUICK_TASK"
        const val ACTION_STOP = "com.employee.agent.ACTION_STOP"
        // 🆕 语音/文字输入动作
        const val ACTION_VOICE_INPUT = "com.employee.agent.ACTION_VOICE_INPUT"
        const val ACTION_TEXT_INPUT = "com.employee.agent.ACTION_TEXT_INPUT"
        
        // 预设任务
        const val TASK_OPEN_XHS = "打开小红书"
        const val TASK_HOT_NOTES = "打开小红书，找到点赞过万的热门笔记"
        const val TASK_CUSTOM = "custom"
    }
    
    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        
        Log.i(TAG, "收到通知操作: ${intent.action}")
        
        when (intent.action) {
            ACTION_OPEN_APP -> {
                // 打开执行界面
                AgentExecuteActivity.start(context)
            }
            
            // 🆕 语音输入（最方便的方式）
            ACTION_VOICE_INPUT -> {
                Log.i(TAG, "🎤 打开语音输入")
                val voiceIntent = Intent(context, FloatingVoiceActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                context.startActivity(voiceIntent)
            }
            
            // 🆕 文字输入
            ACTION_TEXT_INPUT -> {
                Log.i(TAG, "⌨️ 打开文字输入")
                val textIntent = Intent(context, FloatingInputActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                context.startActivity(textIntent)
            }
            
            ACTION_QUICK_TASK -> {
                val task = intent.getStringExtra("task") ?: return
                Log.i(TAG, "执行快捷任务: $task")
                
                when (task) {
                    TASK_CUSTOM -> {
                        // 打开界面让用户输入
                        AgentExecuteActivity.start(context)
                    }
                    else -> {
                        // 直接执行预设任务
                        AgentExecuteActivity.start(context, goal = task, autoExecute = true)
                    }
                }
            }
            
            ACTION_STOP -> {
                // 发送停止广播
                LocalBroadcastManager.getInstance(context).sendBroadcast(
                    Intent("agent.stop")
                )
                Log.i(TAG, "已发送停止命令")
            }
        }
    }
}
