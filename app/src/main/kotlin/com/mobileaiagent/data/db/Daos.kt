package com.mobileaiagent.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(task: TaskEntity)

    @Query("SELECT * FROM tasks WHERE id = :id")
    suspend fun get(id: String): TaskEntity?

    @Query("SELECT * FROM tasks WHERE id = :id")
    fun observe(id: String): Flow<TaskEntity?>

    @Query("SELECT * FROM tasks ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks ORDER BY createdAt DESC")
    suspend fun listAll(): List<TaskEntity>

    @Query("SELECT * FROM tasks WHERE status IN (:statuses) ORDER BY createdAt ASC")
    suspend fun listByStatus(statuses: List<String>): List<TaskEntity>

    @Query("DELETE FROM tasks WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT COUNT(*) FROM tasks WHERE status = 'RUNNING'")
    suspend fun countRunning(): Int

    @Query("SELECT * FROM tasks WHERE projectId = :projectId ORDER BY createdAt DESC")
    fun observeForProject(projectId: String): Flow<List<TaskEntity>>
}

@Dao
interface EventDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(event: EventEntity)

    @Query("SELECT * FROM execution_events WHERE taskId = :taskId ORDER BY seq DESC LIMIT :limit")
    fun observe(taskId: String, limit: Int): Flow<List<EventEntity>>

    @Query("SELECT * FROM execution_events WHERE taskId = :taskId ORDER BY seq DESC LIMIT :limit")
    suspend fun list(taskId: String, limit: Int): List<EventEntity>

    @Query("SELECT COALESCE(MAX(seq), 0) FROM execution_events WHERE taskId = :taskId")
    suspend fun maxSeq(taskId: String): Long

    @Query("DELETE FROM execution_events WHERE taskId = :taskId")
    suspend fun deleteForTask(taskId: String)

    @Query("DELETE FROM execution_events WHERE taskId = :taskId AND seq < :keepAbove")
    suspend fun trim(taskId: String, keepAbove: Long)
}

@Dao
interface RunDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(run: RunEntity)

    @Query("UPDATE runs SET endedAt = :endedAt, exitReason = :reason, steps = :steps, lastStepSummary = :summary WHERE id = :id")
    suspend fun finish(id: String, endedAt: Long, reason: String, steps: Int, summary: String?)

    @Query("SELECT * FROM runs WHERE id = :id")
    suspend fun get(id: String): RunEntity?

    @Query("SELECT * FROM runs WHERE taskId = :taskId ORDER BY startedAt ASC")
    suspend fun listForTask(taskId: String): List<RunEntity>

    @Query("SELECT * FROM runs WHERE endedAt IS NULL")
    suspend fun allOpen(): List<RunEntity>
}

@Dao
interface MessageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: MessageEntity)

    @Query("SELECT * FROM messages WHERE taskId = :taskId ORDER BY timestamp ASC, id ASC")
    fun observe(taskId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE taskId = :taskId ORDER BY timestamp ASC, id ASC LIMIT :limit")
    suspend fun list(taskId: String, limit: Int): List<MessageEntity>

    @Query("DELETE FROM messages WHERE taskId = :taskId")
    suspend fun deleteForTask(taskId: String)
}

@Dao
interface JournalDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: JournalEntity)

    @Query("UPDATE tool_journal SET status = :status, finishedAt = :finishedAt, resultSummary = :summary WHERE callId = :callId")
    suspend fun finish(callId: String, status: String, finishedAt: Long, summary: String?)

    @Query("SELECT * FROM tool_journal WHERE taskId = :taskId AND mutagenic = 1 AND status = 'started'")
    suspend fun pendingMutagenic(taskId: String): List<JournalEntity>

    @Query("SELECT * FROM tool_journal WHERE taskId = :taskId ORDER BY startedAt DESC LIMIT :limit")
    suspend fun list(taskId: String, limit: Int): List<JournalEntity>

    @Query("DELETE FROM tool_journal WHERE taskId = :taskId")
    suspend fun deleteForTask(taskId: String)
}

@Dao
interface CheckpointDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(checkpoint: CheckpointEntity)

    @Query("SELECT * FROM checkpoints WHERE taskId = :taskId")
    suspend fun get(taskId: String): CheckpointEntity?

    @Query("DELETE FROM checkpoints WHERE taskId = :taskId")
    suspend fun delete(taskId: String)
}

@Dao
interface ModelDao {
    @Query("SELECT * FROM model_cache WHERE providerId = :providerId ORDER BY modelId")
    suspend fun list(providerId: String): List<ModelEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(models: List<ModelEntity>)

    @Query("DELETE FROM model_cache WHERE providerId = :providerId AND isManual = 0")
    suspend fun deleteDiscovered(providerId: String)

    @Query("SELECT MAX(fetchedAt) FROM model_cache WHERE providerId = :providerId")
    suspend fun lastRefresh(providerId: String): Long?

    @Query("SELECT * FROM capability_overrides WHERE providerId = :providerId AND modelId = :modelId")
    suspend fun override(providerId: String, modelId: String): CapabilityOverrideEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertOverride(entity: CapabilityOverrideEntity)
}

@Dao
interface ProjectDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(project: ProjectEntity)

    @Query("SELECT * FROM projects ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects ORDER BY updatedAt DESC")
    suspend fun listAll(): List<ProjectEntity>

    @Query("SELECT * FROM projects WHERE id = :id")
    suspend fun get(id: String): ProjectEntity?

    @Query("DELETE FROM projects WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface PluginDao {
    @Query("SELECT * FROM plugins ORDER BY installedAt DESC")
    fun observeAll(): Flow<List<PluginEntity>>

    @Query("SELECT * FROM plugins")
    suspend fun listAll(): List<PluginEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(plugin: PluginEntity)

    @Query("DELETE FROM plugins WHERE id = :id")
    suspend fun delete(id: String)
}
