// infrastructure/storage/MemoryRepository.kt
// module: infrastructure/storage | layer: infrastructure | role: memory-repository
// summary: 记忆仓库，提供记忆的存取和检索能力

package com.employee.agent.infrastructure.storage

import android.util.Log
import com.employee.agent.domain.agent.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 记忆仓库
 * 
 * 职责：
 * - 持久化记忆到数据库
 * - 检索相关记忆
 * - 学习模式管理
 */
class MemoryRepository(
    private val database: AgentDatabase
) {
    companion object {
        private const val TAG = "MemoryRepository"
        private const val MAX_SHORT_TERM = 50
        private const val MAX_LONG_TERM = 500
    }
    
    private val goalDao = database.goalDao()
    private val actionLogDao = database.actionLogDao()
    private val patternDao = database.learnedPatternDao()
    private val memoryDao = database.memoryDao()
    
    // ============ 目标管理 ============
    
    /**
     * 保存目标执行开始
     */
    suspend fun startGoal(goal: Goal): GoalEntity = withContext(Dispatchers.IO) {
        val entity = GoalEntity(
            id = goal.id,
            description = goal.description,
            status = GoalStatus.RUNNING,
            startTime = System.currentTimeMillis()
        )
        goalDao.insert(entity)
        Log.d(TAG, "目标开始: ${goal.description}")
        entity
    }
    
    /**
     * 更新目标完成状态
     */
    suspend fun completeGoal(
        goalId: String,
        success: Boolean,
        stepsExecuted: Int,
        errorMessage: String? = null
    ) = withContext(Dispatchers.IO) {
        val goal = goalDao.getById(goalId) ?: return@withContext
        goalDao.update(goal.copy(
            status = if (success) GoalStatus.COMPLETED else GoalStatus.FAILED,
            endTime = System.currentTimeMillis(),
            stepsExecuted = stepsExecuted,
            success = success,
            errorMessage = errorMessage
        ))
        Log.d(TAG, "目标完成: $goalId, 成功: $success")
    }
    
    /**
     * 获取相似的历史目标
     */
    suspend fun findSimilarGoals(description: String, limit: Int = 5): List<GoalEntity> =
        withContext(Dispatchers.IO) {
            // 简单关键词匹配，可以升级为向量相似度
            val keywords = description.split(" ", "，", "、").filter { it.length > 1 }
            val results = mutableSetOf<GoalEntity>()
            
            keywords.forEach { keyword ->
                results.addAll(goalDao.searchByDescription(keyword))
            }
            
            results.toList()
                .sortedByDescending { it.success }
                .take(limit)
        }
    
    // ============ 动作日志 ============
    
    /**
     * 记录动作执行
     */
    suspend fun logAction(
        goalId: String,
        stepNumber: Int,
        toolName: String,
        parameters: Map<String, Any>,
        success: Boolean,
        resultMessage: String?,
        screenBefore: String? = null,
        screenAfter: String? = null,
        aiReasoning: String? = null
    ) = withContext(Dispatchers.IO) {
        val log = ActionLogEntity(
            goalId = goalId,
            stepNumber = stepNumber,
            timestamp = System.currentTimeMillis(),
            toolName = toolName,
            parameters = com.google.gson.Gson().toJson(parameters),
            success = success,
            resultMessage = resultMessage,
            screenBefore = screenBefore,
            screenAfter = screenAfter,
            aiReasoning = aiReasoning
        )
        actionLogDao.insert(log)
    }
    
    /**
     * 获取目标的动作历史
     */
    suspend fun getActionHistory(goalId: String): List<ActionLogEntity> =
        withContext(Dispatchers.IO) {
            actionLogDao.getByGoalId(goalId)
        }
    
    // ============ 学习模式 ============
    
    /**
     * 从成功目标学习模式
     */
    suspend fun learnFromSuccess(goalId: String) = withContext(Dispatchers.IO) {
        val goal = goalDao.getById(goalId) ?: return@withContext
        if (!goal.success) return@withContext
        
        val actions = actionLogDao.getByGoalId(goalId)
        if (actions.isEmpty()) return@withContext
        
        // 提取目标模式（简化版：使用目标描述的关键词）
        val goalPattern = extractGoalPattern(goal.description)
        
        // 检查是否已有类似模式
        val existingPatterns = patternDao.findByGoalPattern(goalPattern, 1)
        
        if (existingPatterns.isNotEmpty()) {
            // 更新现有模式
            patternDao.recordSuccess(existingPatterns[0].id)
            Log.d(TAG, "更新模式成功: $goalPattern")
        } else {
            // 创建新模式
            val actionSequence = actions.map { action ->
                ActionStep(
                    toolName = action.toolName,
                    parameters = action.parameters,
                    screenContext = action.screenBefore
                )
            }
            
            val pattern = LearnedPatternEntity(
                goalPattern = goalPattern,
                actionSequence = com.google.gson.Gson().toJson(actionSequence),
                successCount = 1,
                lastUsed = System.currentTimeMillis(),
                confidence = 0.5f  // 初始可信度
            )
            patternDao.insert(pattern)
            Log.d(TAG, "学习新模式: $goalPattern")
        }
    }
    
    /**
     * 记录模式失败
     */
    suspend fun recordPatternFailure(patternId: String) = withContext(Dispatchers.IO) {
        patternDao.recordFailure(patternId)
    }
    
    /**
     * 查找适用的学习模式
     */
    suspend fun findApplicablePattern(goalDescription: String): LearnedPatternEntity? =
        withContext(Dispatchers.IO) {
            val pattern = extractGoalPattern(goalDescription)
            patternDao.findByGoalPattern(pattern, 1)
                .firstOrNull { it.confidence >= 0.6f }
        }
    
    /**
     * 获取模式的动作序列
     */
    fun getPatternActions(pattern: LearnedPatternEntity): List<ActionStep> {
        return try {
            com.google.gson.Gson().fromJson(
                pattern.actionSequence,
                Array<ActionStep>::class.java
            ).toList()
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    // ============ 通用记忆 ============
    
    /**
     * 保存记忆
     */
    suspend fun remember(
        content: String,
        type: MemoryType,
        importance: Float = 0.5f,
        tags: List<String> = emptyList(),
        relatedGoalId: String? = null
    ) = withContext(Dispatchers.IO) {
        val memory = MemoryEntity(
            type = type,
            content = content,
            importance = importance,
            tags = tags,
            relatedGoalId = relatedGoalId
        )
        memoryDao.insert(memory)
        Log.d(TAG, "保存记忆: $content")
    }
    
    /**
     * 检索相关记忆
     */
    suspend fun recall(
        query: String,
        type: MemoryType? = null,
        limit: Int = 10
    ): List<MemoryEntity> = withContext(Dispatchers.IO) {
        val memories = if (type != null) {
            memoryDao.getByType(type, limit * 2)
        } else {
            memoryDao.getImportantMemories(limit * 2)
        }
        
        // 简单关键词匹配
        val keywords = query.split(" ", "，", "、").filter { it.length > 1 }
        
        memories
            .filter { memory ->
                keywords.any { keyword -> memory.content.contains(keyword, ignoreCase = true) }
            }
            .take(limit)
            .onEach { memoryDao.recordAccess(it.id) }
    }
    
    /**
     * 获取策略记忆
     */
    suspend fun getStrategies(limit: Int = 10): List<MemoryEntity> =
        withContext(Dispatchers.IO) {
            memoryDao.getByType(MemoryType.STRATEGY, limit)
        }
    
    /**
     * 获取错误模式记忆
     */
    suspend fun getErrorPatterns(limit: Int = 10): List<MemoryEntity> =
        withContext(Dispatchers.IO) {
            memoryDao.getByType(MemoryType.ERROR_PATTERN, limit)
        }
    
    // ============ 统计和清理 ============
    
    /**
     * 获取成功率
     */
    suspend fun getSuccessRate(): Float = withContext(Dispatchers.IO) {
        val total = goalDao.getTotalCount()
        if (total == 0) return@withContext 0f
        goalDao.getSuccessCount().toFloat() / total
    }
    
    /**
     * 清理过期数据
     */
    suspend fun cleanup(
        keepDays: Int = 30,
        keepMinImportance: Float = 0.3f
    ) = withContext(Dispatchers.IO) {
        val cutoffTime = System.currentTimeMillis() - keepDays * 24 * 60 * 60 * 1000L
        
        // 清理旧目标
        goalDao.deleteOlderThan(cutoffTime)
        
        // 清理不可靠的模式
        patternDao.pruneUnreliablePatterns()
        
        // 清理不重要的记忆
        memoryDao.pruneUnimportantMemories(cutoffTime)
        
        Log.d(TAG, "清理完成: 删除 $keepDays 天前的数据")
    }
    
    // ============ 辅助方法 ============
    
    private fun extractGoalPattern(description: String): String {
        // 简化版：提取动词+名词模式
        // 例如："打开微信并发送消息" -> "打开*发送*"
        val verbs = listOf("打开", "点击", "输入", "发送", "查看", "搜索", "滑动", "返回", "关闭")
        val pattern = StringBuilder()
        
        verbs.forEach { verb ->
            if (description.contains(verb)) {
                if (pattern.isNotEmpty()) pattern.append("*")
                pattern.append(verb)
            }
        }
        
        return if (pattern.isEmpty()) description.take(20) else pattern.toString()
    }
}

/**
 * 动作步骤（用于序列化）
 */
data class ActionStep(
    val toolName: String,
    val parameters: String,
    val screenContext: String? = null
)
