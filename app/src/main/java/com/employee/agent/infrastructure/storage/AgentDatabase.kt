// infrastructure/storage/AgentDatabase.kt
// module: infrastructure/storage | layer: infrastructure | role: database
// summary: Agent 持久化数据库，存储目标、记忆、学习模式

package com.employee.agent.infrastructure.storage

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow
import java.util.UUID

/**
 * Agent 数据库
 */
@Database(
    entities = [
        GoalEntity::class,
        ActionLogEntity::class,
        LearnedPatternEntity::class,
        MemoryEntity::class
    ],
    version = 1,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AgentDatabase : RoomDatabase() {
    
    abstract fun goalDao(): GoalDao
    abstract fun actionLogDao(): ActionLogDao
    abstract fun learnedPatternDao(): LearnedPatternDao
    abstract fun memoryDao(): MemoryDao
    
    companion object {
        @Volatile
        private var INSTANCE: AgentDatabase? = null
        
        fun getInstance(context: Context): AgentDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    AgentDatabase::class.java,
                    "agent_database"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}

// ============ 实体定义 ============

/**
 * 目标记录
 */
@Entity(tableName = "goals")
data class GoalEntity(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    
    val description: String,
    val status: GoalStatus,
    val startTime: Long,
    val endTime: Long? = null,
    val stepsExecuted: Int = 0,
    val success: Boolean = false,
    val errorMessage: String? = null,
    
    // 元数据
    val appPackage: String? = null,
    val tags: List<String> = emptyList()
)

enum class GoalStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED
}

/**
 * 动作日志
 */
@Entity(
    tableName = "action_logs",
    foreignKeys = [
        ForeignKey(
            entity = GoalEntity::class,
            parentColumns = ["id"],
            childColumns = ["goalId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("goalId")]
)
data class ActionLogEntity(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    
    val goalId: String,
    val stepNumber: Int,
    val timestamp: Long,
    
    // 动作信息
    val toolName: String,
    val parameters: String,  // JSON
    val success: Boolean,
    val resultMessage: String? = null,
    
    // 上下文
    val screenBefore: String? = null,  // 简化的屏幕描述
    val screenAfter: String? = null,
    val aiReasoning: String? = null
)

/**
 * 学习到的模式
 */
@Entity(tableName = "learned_patterns")
data class LearnedPatternEntity(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    
    // 模式匹配
    val goalPattern: String,          // 目标模式（如 "打开*" 匹配 "打开微信"）
    val contextPattern: String? = null, // 上下文模式（如 "在桌面"）
    
    // 成功策略
    val actionSequence: String,       // JSON: List<ActionStep>
    
    // 统计
    val successCount: Int = 0,
    val failureCount: Int = 0,
    val lastUsed: Long = 0,
    val avgSteps: Float = 0f,
    
    // 可信度
    val confidence: Float = 0f        // 0-1，基于成功率和使用次数
) {
    val successRate: Float
        get() = if (successCount + failureCount > 0) {
            successCount.toFloat() / (successCount + failureCount)
        } else 0f
}

/**
 * 通用记忆条目（长期记忆）
 */
@Entity(tableName = "memories")
data class MemoryEntity(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    
    val type: MemoryType,
    val content: String,
    val embedding: FloatArray? = null,  // 向量嵌入（用于相似度检索）
    
    val importance: Float = 0.5f,       // 重要性 0-1
    val accessCount: Int = 0,
    val lastAccess: Long = 0,
    val createdAt: Long = System.currentTimeMillis(),
    
    // 关联
    val relatedGoalId: String? = null,
    val tags: List<String> = emptyList()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as MemoryEntity
        return id == other.id
    }
    
    override fun hashCode(): Int = id.hashCode()
}

enum class MemoryType {
    FACT,           // 事实（如 "微信图标在桌面第一页"）
    STRATEGY,       // 策略（如 "打开 App 的通用方法"）
    PREFERENCE,     // 偏好（如 "用户喜欢向上滑动"）
    ERROR_PATTERN   // 错误模式（如 "支付宝需要输入密码"）
}

// ============ DAO 定义 ============

@Dao
interface GoalDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(goal: GoalEntity)
    
    @Update
    suspend fun update(goal: GoalEntity)
    
    @Query("SELECT * FROM goals WHERE id = :id")
    suspend fun getById(id: String): GoalEntity?
    
    @Query("SELECT * FROM goals ORDER BY startTime DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 20): List<GoalEntity>
    
    @Query("SELECT * FROM goals WHERE status = :status ORDER BY startTime DESC")
    suspend fun getByStatus(status: GoalStatus): List<GoalEntity>
    
    @Query("SELECT * FROM goals WHERE description LIKE '%' || :keyword || '%' ORDER BY startTime DESC")
    suspend fun searchByDescription(keyword: String): List<GoalEntity>
    
    @Query("SELECT COUNT(*) FROM goals WHERE success = 1")
    suspend fun getSuccessCount(): Int
    
    @Query("SELECT COUNT(*) FROM goals")
    suspend fun getTotalCount(): Int
    
    @Query("DELETE FROM goals WHERE startTime < :beforeTime")
    suspend fun deleteOlderThan(beforeTime: Long)
}

@Dao
interface ActionLogDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(log: ActionLogEntity)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(logs: List<ActionLogEntity>)
    
    @Query("SELECT * FROM action_logs WHERE goalId = :goalId ORDER BY stepNumber")
    suspend fun getByGoalId(goalId: String): List<ActionLogEntity>
    
    @Query("SELECT * FROM action_logs WHERE toolName = :toolName ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getByToolName(toolName: String, limit: Int = 50): List<ActionLogEntity>
    
    @Query("SELECT toolName, COUNT(*) as count, SUM(CASE WHEN success THEN 1 ELSE 0 END) as successCount FROM action_logs GROUP BY toolName")
    suspend fun getToolStats(): List<ToolStat>
    
    @Query("DELETE FROM action_logs WHERE goalId = :goalId")
    suspend fun deleteByGoalId(goalId: String)
}

data class ToolStat(
    val toolName: String,
    val count: Int,
    val successCount: Int
)

@Dao
interface LearnedPatternDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(pattern: LearnedPatternEntity)
    
    @Update
    suspend fun update(pattern: LearnedPatternEntity)
    
    @Query("SELECT * FROM learned_patterns WHERE goalPattern LIKE '%' || :keyword || '%' ORDER BY confidence DESC LIMIT :limit")
    suspend fun findByGoalPattern(keyword: String, limit: Int = 5): List<LearnedPatternEntity>
    
    @Query("SELECT * FROM learned_patterns ORDER BY confidence DESC, lastUsed DESC LIMIT :limit")
    suspend fun getTopPatterns(limit: Int = 20): List<LearnedPatternEntity>
    
    @Query("SELECT * FROM learned_patterns WHERE confidence >= :minConfidence ORDER BY lastUsed DESC")
    suspend fun getConfidentPatterns(minConfidence: Float = 0.7f): List<LearnedPatternEntity>
    
    @Query("UPDATE learned_patterns SET successCount = successCount + 1, lastUsed = :time, confidence = (successCount + 1.0) / (successCount + failureCount + 1.0) WHERE id = :id")
    suspend fun recordSuccess(id: String, time: Long = System.currentTimeMillis())
    
    @Query("UPDATE learned_patterns SET failureCount = failureCount + 1, confidence = successCount / (successCount + failureCount + 1.0) WHERE id = :id")
    suspend fun recordFailure(id: String)
    
    @Query("DELETE FROM learned_patterns WHERE confidence < 0.2 AND (successCount + failureCount) > 5")
    suspend fun pruneUnreliablePatterns()
}

@Dao
interface MemoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(memory: MemoryEntity)
    
    @Update
    suspend fun update(memory: MemoryEntity)
    
    @Query("SELECT * FROM memories WHERE type = :type ORDER BY importance DESC, lastAccess DESC LIMIT :limit")
    suspend fun getByType(type: MemoryType, limit: Int = 20): List<MemoryEntity>
    
    @Query("SELECT * FROM memories WHERE content LIKE '%' || :keyword || '%' ORDER BY importance DESC LIMIT :limit")
    suspend fun searchByContent(keyword: String, limit: Int = 10): List<MemoryEntity>
    
    @Query("SELECT * FROM memories WHERE tags LIKE '%' || :tag || '%' ORDER BY importance DESC")
    suspend fun getByTag(tag: String): List<MemoryEntity>
    
    @Query("UPDATE memories SET accessCount = accessCount + 1, lastAccess = :time WHERE id = :id")
    suspend fun recordAccess(id: String, time: Long = System.currentTimeMillis())
    
    @Query("DELETE FROM memories WHERE importance < 0.3 AND accessCount < 3 AND createdAt < :beforeTime")
    suspend fun pruneUnimportantMemories(beforeTime: Long)
    
    @Query("SELECT * FROM memories ORDER BY importance DESC, accessCount DESC LIMIT :limit")
    suspend fun getImportantMemories(limit: Int = 50): List<MemoryEntity>
}

// ============ 类型转换器 ============

class Converters {
    private val gson = com.google.gson.Gson()
    
    @TypeConverter
    fun fromStringList(value: List<String>): String = gson.toJson(value)
    
    @TypeConverter
    fun toStringList(value: String): List<String> =
        gson.fromJson(value, Array<String>::class.java)?.toList() ?: emptyList()
    
    @TypeConverter
    fun fromFloatArray(value: FloatArray?): String? = value?.let { gson.toJson(it) }
    
    @TypeConverter
    fun toFloatArray(value: String?): FloatArray? =
        value?.let { gson.fromJson(it, FloatArray::class.java) }
    
    @TypeConverter
    fun fromGoalStatus(status: GoalStatus): String = status.name
    
    @TypeConverter
    fun toGoalStatus(value: String): GoalStatus = GoalStatus.valueOf(value)
    
    @TypeConverter
    fun fromMemoryType(type: MemoryType): String = type.name
    
    @TypeConverter
    fun toMemoryType(value: String): MemoryType = MemoryType.valueOf(value)
}
