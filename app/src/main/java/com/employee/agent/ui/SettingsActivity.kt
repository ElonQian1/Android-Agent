// ui/SettingsActivity.kt
// module: ui | layer: presentation | role: 设置页面
// summary: 用户设置、账号管理（登出/切换账号）- 程序化布局

package com.employee.agent.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import com.employee.agent.AgentConfigActivity
import com.employee.agent.infrastructure.auth.AuthService

/**
 * 设置页面（程序化布局）
 */
class SettingsActivity : Activity() {
    
    private lateinit var authService: AuthService
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        authService = AuthService(this)
        setContentView(createLayout())
    }
    
    private fun createLayout(): View {
        return ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#F5F5F5"))
            
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                
                // 标题栏
                addView(createHeader())
                
                // 账号信息卡片
                addView(createAccountInfoCard())
                
                // 功能设置卡片
                addView(createFunctionCard())
                
                // 账号操作卡片
                addView(createAccountActionsCard())
                
                // 版本信息
                addView(createVersionInfo())
            })
        }
    }
    
    private fun createHeader(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(32, 32, 32, 32)
            elevation = 4f
            
            addView(Button(context).apply {
                text = "← 返回"
                setBackgroundColor(Color.TRANSPARENT)
                setTextColor(Color.parseColor("#2196F3"))
                setOnClickListener { finish() }
            })
            
            addView(TextView(context).apply {
                text = "设置"
                textSize = 18f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#333333"))
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            
            // 占位保持标题居中
            addView(View(context).apply {
                layoutParams = LinearLayout.LayoutParams(120, 1)
            })
        }
    }
    
    private fun createAccountInfoCard(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(32, 32, 32, 32)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 24 }
            
            addView(TextView(context).apply {
                text = "账号信息"
                textSize = 14f
                setTextColor(Color.GRAY)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = 24 }
            })
            
            val user = authService.getCurrentUser()
            
            addView(TextView(context).apply {
                text = "账号: ${user?.username ?: "未登录"}"
                textSize = 16f
                setTextColor(Color.parseColor("#333333"))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = 12 }
            })
            
            addView(TextView(context).apply {
                text = "昵称: ${user?.nickname ?: user?.username ?: "-"}"
                textSize = 16f
                setTextColor(Color.parseColor("#333333"))
            })
        }
    }
    
    private fun createFunctionCard(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(32, 32, 32, 32)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 24 }
            
            addView(TextView(context).apply {
                text = "功能设置"
                textSize = 14f
                setTextColor(Color.GRAY)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = 24 }
            })
            
            addView(Button(context).apply {
                text = "🤖 AI 配置"
                textSize = 16f
                setBackgroundColor(Color.parseColor("#E8F5E9"))
                setTextColor(Color.parseColor("#4CAF50"))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    140
                )
                setOnClickListener {
                    startActivity(Intent(context, AgentConfigActivity::class.java))
                }
            })
        }
    }
    
    private fun createAccountActionsCard(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(32, 32, 32, 32)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 24 }
            
            addView(TextView(context).apply {
                text = "账号操作"
                textSize = 14f
                setTextColor(Color.GRAY)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = 24 }
            })
            
            // 切换账号按钮
            addView(Button(context).apply {
                text = "切换账号"
                textSize = 16f
                setBackgroundColor(Color.parseColor("#E3F2FD"))
                setTextColor(Color.parseColor("#2196F3"))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    140
                ).apply { bottomMargin = 16 }
                setOnClickListener { showSwitchAccountDialog() }
            })
            
            // 退出登录按钮
            addView(Button(context).apply {
                text = "退出登录"
                textSize = 16f
                setBackgroundColor(Color.parseColor("#FFEBEE"))
                setTextColor(Color.parseColor("#F44336"))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    140
                )
                setOnClickListener { showLogoutDialog() }
            })
        }
    }
    
    private fun createVersionInfo(): TextView {
        return TextView(this).apply {
            text = "营销助手 v1.0.0"
            textSize = 12f
            setTextColor(Color.GRAY)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 48 }
        }
    }
    
    private fun showSwitchAccountDialog() {
        AlertDialog.Builder(this)
            .setTitle("切换账号")
            .setMessage("确定要切换到其他账号吗？当前账号将被登出。")
            .setPositiveButton("确定") { _, _ -> switchAccount() }
            .setNegativeButton("取消", null)
            .show()
    }
    
    private fun showLogoutDialog() {
        AlertDialog.Builder(this)
            .setTitle("退出登录")
            .setMessage("确定要退出当前账号吗？")
            .setPositiveButton("确定") { _, _ -> logout() }
            .setNegativeButton("取消", null)
            .show()
    }
    
    private fun switchAccount() {
        authService.logout()
        Toast.makeText(this, "已登出，请登录新账号", Toast.LENGTH_SHORT).show()
        goToLogin()
    }
    
    private fun logout() {
        authService.logout()
        Toast.makeText(this, "已退出登录", Toast.LENGTH_SHORT).show()
        goToLogin()
    }
    
    private fun goToLogin() {
        val intent = Intent(this, LoginActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }
}
