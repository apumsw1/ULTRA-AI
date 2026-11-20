package com.ultra.assistant.memory

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MemoryDao {
    @Query("SELECT * FROM memories ORDER BY createdAtMillis DESC")
    fun observeAll(): Flow<List<MemoryRecordEntity>>

    @Query("SELECT * FROM memories WHERE expiresAtMillis = 0 OR expiresAtMillis > :nowMillis ORDER BY createdAtMillis DESC LIMIT :limit")
    suspend fun getActiveMemories(nowMillis: Long, limit: Int = 25): List<MemoryRecordEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(record: MemoryRecordEntity)

    @Query("DELETE FROM memories WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM memories WHERE expiresAtMillis > 0 AND expiresAtMillis <= :nowMillis")
    suspend fun purgeExpired(nowMillis: Long): Int

    @Query("DELETE FROM memories")
    suspend fun clearAll()
}

@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversation_messages ORDER BY timestampMillis ASC LIMIT :limit")
    fun observeRecent(limit: Int = 60): Flow<List<ConversationMessageEntity>>

    @Query("SELECT * FROM conversation_messages ORDER BY timestampMillis DESC LIMIT :limit")
    suspend fun getLatest(limit: Int = 12): List<ConversationMessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: ConversationMessageEntity)

    @Query("DELETE FROM conversation_messages")
    suspend fun clearAll()
}

@Dao
interface AuditLogDao {
    @Query("SELECT * FROM audit_logs ORDER BY timestampMillis DESC LIMIT :limit")
    fun observeRecent(limit: Int = 100): Flow<List<AuditLogEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: AuditLogEntity)

    @Query("DELETE FROM audit_logs")
    suspend fun clearAll()
}

@Dao
interface AutomationRuleDao {
    @Query("SELECT * FROM automation_rules ORDER BY createdAtMillis DESC")
    fun observeAll(): Flow<List<AutomationRuleEntity>>

    @Query("SELECT * FROM automation_rules WHERE enabled = 1")
    suspend fun getEnabledRules(): List<AutomationRuleEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(rule: AutomationRuleEntity)

    @Query("DELETE FROM automation_rules WHERE id = :id")
    suspend fun deleteById(id: String)
}
