package com.mobileaiagent.core.storage

import com.mobileaiagent.domain.model.ExecutionEvent
import com.mobileaiagent.domain.model.RunRecord
import com.mobileaiagent.domain.model.TaskRecord
import kotlinx.coroutines.flow.Flow

/**
 * Persistence contracts implemented by Room on Android and in-memory fakes in
 * tests. The agent core depends only on these, which is what lets recovery,
 * queueing and resumption be unit-tested without an emulator.
 */
interface TaskStore {
    suspend fun upsert(task: TaskRecord)
    suspend fun get(id: String): TaskRecord?
    fun observe(id: String): Flow<TaskRecord?>
    fun observeAll(): Flow<List<TaskRecord>>
    suspend fun listAll(): List<TaskRecord>
    suspend fun listByStatus(vararg statuses: String): List<TaskRecord>
    suspend fun delete(id: String)
    suspend fun countRunning(): Int
}

interface EventStore {
    suspend fun append(event: ExecutionEvent)
    fun observe(taskId: String, limit: Int = 500): Flow<List<ExecutionEvent>>
    suspend fun list(taskId: String, limit: Int = 500): List<ExecutionEvent>
    suspend fun lastSeq(taskId: String): Long
    suspend fun deleteForTask(taskId: String)
}

interface RunStore {
    suspend fun start(run: RunRecord)
    suspend fun finish(runId: String, endedAt: Long, exitReason: String, steps: Int, lastStepSummary: String?)
    suspend fun get(runId: String): RunRecord?
    suspend fun listForTask(taskId: String): List<RunRecord>
    /** Runs that started under a previous process and never finished. */
    suspend fun findOrphanedRuns(aliveRunIds: Set<String>): List<RunRecord>
}

interface MessageStore {
    suspend fun append(message: com.mobileaiagent.domain.model.ChatMessage, taskId: String)
    suspend fun list(taskId: String, limit: Int = 400): List<com.mobileaiagent.domain.model.ChatMessage>
    fun observe(taskId: String): Flow<List<com.mobileaiagent.domain.model.ChatMessage>>
    suspend fun replace(taskId: String, messages: List<com.mobileaiagent.domain.model.ChatMessage>)
    suspend fun deleteForTask(taskId: String)
}

/**
 * Two-phase tool journal: a call is recorded as STARTED before execution and
 * finished as SUCCEEDED/FAILED. Recovery uses this to decide what may be replayed
 * (read-only calls may) and what must not (mutagenic calls that started).
 */
interface ToolJournal {
    suspend fun started(taskId: String, runId: String, callId: String, toolId: String, argsJson: String, mutagenic: Boolean)
    suspend fun finished(callId: String, status: String, resultSummary: String)
    suspend fun pendingMutagenic(taskId: String): List<ToolJournalEntry>
    suspend fun list(taskId: String, limit: Int = 300): List<ToolJournalEntry>
    suspend fun deleteForTask(taskId: String)
}

data class ToolJournalEntry(
    val callId: String,
    val taskId: String,
    val runId: String,
    val toolId: String,
    val argsJson: String,
    val mutagenic: Boolean,
    val status: String, // started | succeeded | failed | cancelled
    val startedAt: Long,
    val finishedAt: Long?,
    val resultSummary: String?,
)

interface CheckpointStore {
    suspend fun save(checkpoint: TaskCheckpoint)
    suspend fun get(taskId: String): TaskCheckpoint?
    suspend fun delete(taskId: String)
}

/**
 * Snapshot written at safe points so the agent can resume without re-running
 * destructive work. `pendingUserFacingWork` carries anything half-finished.
 */
data class TaskCheckpoint(
    val taskId: String,
    val createdAt: Long,
    val lastPlanTaskId: String?,
    val lastCompletedStepSummary: String?,
    val assistantDraft: String?,
    val planRevision: Int?,
    val schemaVersion: Int = 1,
)
