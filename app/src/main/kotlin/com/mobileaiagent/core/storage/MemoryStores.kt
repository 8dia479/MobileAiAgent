package com.mobileaiagent.core.storage

import com.mobileaiagent.domain.model.ChatMessage
import com.mobileaiagent.domain.model.ExecutionEvent
import com.mobileaiagent.domain.model.RunRecord
import com.mobileaiagent.domain.model.TaskRecord
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * In-memory implementations of every store. They define the reference semantics
 * used by the agent tests; the Room implementations must match them (see
 * RoomStoreParityTest, which runs the same recovery scenarios through Room via
 * Robolectric once an emulator/device is available).
 */
class MemoryTaskStore : TaskStore {
    private val items = MutableStateFlow<Map<String, TaskRecord>>(emptyMap())

    override suspend fun upsert(task: TaskRecord) {
        items.value = items.value + (task.id to task)
    }

    override suspend fun get(id: String): TaskRecord? = items.value[id]

    override fun observe(id: String): Flow<TaskRecord?> = items.map { it[id] }

    override fun observeAll(): Flow<List<TaskRecord>> =
        items.map { it.values.sortedByDescending { t -> t.createdAt } }

    override suspend fun listAll(): List<TaskRecord> = items.value.values.sortedByDescending { it.createdAt }

    override suspend fun listByStatus(vararg statuses: String): List<TaskRecord> {
        val set = statuses.toSet()
        return items.value.values.filter { it.status.name in set }.sortedBy { it.createdAt }
    }

    override suspend fun delete(id: String) {
        items.value = items.value - id
    }

    override suspend fun countRunning(): Int =
        items.value.values.count { it.status == com.mobileaiagent.domain.model.TaskStatus.RUNNING }
}

class MemoryEventStore : EventStore {
    private val items = MutableStateFlow<List<ExecutionEvent>>(emptyList())
    private val seqs = ConcurrentHashMap<String, Long>()

    override suspend fun append(event: ExecutionEvent) {
        items.value = items.value + event
    }

    override fun observe(taskId: String, limit: Int): Flow<List<ExecutionEvent>> =
        items.map { list -> list.filter { it.taskId == taskId }.sortedBy { it.seq }.takeLast(limit) }

    override suspend fun list(taskId: String, limit: Int): List<ExecutionEvent> =
        items.value.filter { it.taskId == taskId }.sortedBy { it.seq }.takeLast(limit)

    override suspend fun lastSeq(taskId: String): Long = items.value.filter { it.taskId == taskId }.maxOfOrNull { it.seq } ?: 0L

    override suspend fun deleteForTask(taskId: String) {
        items.value = items.value.filter { it.taskId != taskId }
    }
}

class MemoryRunStore : RunStore {
    private val runs = MutableStateFlow<Map<String, RunRecord>>(emptyMap())

    override suspend fun start(run: RunRecord) { runs.value = runs.value + (run.id to run) }

    override suspend fun finish(runId: String, endedAt: Long, exitReason: String, steps: Int, lastStepSummary: String?) {
        runs.value[runId]?.let {
            runs.value = runs.value + (runId to it.copy(
                endedAt = endedAt, exitReason = exitReason, steps = steps, lastStepSummary = lastStepSummary,
            ))
        }
    }

    override suspend fun get(runId: String): RunRecord? = runs.value[runId]

    override suspend fun listForTask(taskId: String): List<RunRecord> =
        runs.value.values.filter { it.taskId == taskId }.sortedBy { it.startedAt }

    override suspend fun findOrphanedRuns(aliveRunIds: Set<String>): List<RunRecord> =
        runs.value.values.filter { it.endedAt == null && it.id !in aliveRunIds }
}

class MemoryMessageStore : MessageStore {
    private val messages = MutableStateFlow<Map<String, List<ChatMessage>>>(emptyMap())

    override suspend fun append(message: ChatMessage, taskId: String) {
        messages.value = messages.value + (taskId to ((messages.value[taskId] ?: emptyList()) + message))
    }

    override suspend fun list(taskId: String, limit: Int): List<ChatMessage> =
        (messages.value[taskId] ?: emptyList()).takeLast(limit)

    override fun observe(taskId: String): Flow<List<ChatMessage>> = messages.map { it[taskId] ?: emptyList() }

    override suspend fun replace(taskId: String, messages2: List<ChatMessage>) {
        messages.value = messages.value + (taskId to messages2)
    }

    override suspend fun deleteForTask(taskId: String) {
        messages.value = messages.value - taskId
    }
}

class MemoryToolJournal : ToolJournal {
    private val entries = MutableStateFlow<Map<String, ToolJournalEntry>>(emptyMap())

    override suspend fun started(taskId: String, runId: String, callId: String, toolId: String, argsJson: String, mutagenic: Boolean) {
        entries.value = entries.value + (callId to ToolJournalEntry(
            callId, taskId, runId, toolId, argsJson, mutagenic, "started", System.currentTimeMillis(), null, null,
        ))
    }

    override suspend fun finished(callId: String, status: String, resultSummary: String) {
        entries.value[callId]?.let {
            entries.value = entries.value + (callId to it.copy(status = status, finishedAt = System.currentTimeMillis(), resultSummary = resultSummary))
        }
    }

    override suspend fun pendingMutagenic(taskId: String): List<ToolJournalEntry> =
        entries.value.values.filter { it.taskId == taskId && it.mutagenic && it.status == "started" }

    override suspend fun list(taskId: String, limit: Int): List<ToolJournalEntry> =
        entries.value.values.filter { it.taskId == taskId }.sortedBy { it.startedAt }.takeLast(limit)

    override suspend fun deleteForTask(taskId: String) {
        entries.value = entries.value.filter { it.value.taskId != taskId }
    }
}

class MemoryCheckpointStore : CheckpointStore {
    private val items = ConcurrentHashMap<String, TaskCheckpoint>()
    override suspend fun save(checkpoint: TaskCheckpoint) { items[checkpoint.taskId] = checkpoint }
    override suspend fun get(taskId: String): TaskCheckpoint? = items[taskId]
    override suspend fun delete(taskId: String) { items.remove(taskId) }
}

/** Bundle of every store, so tasks can be wired up in one constructor call. */
class AgentStores(
    val tasks: TaskStore,
    val events: EventStore,
    val runs: RunStore,
    val messages: MessageStore,
    val journal: ToolJournal,
    val checkpoints: CheckpointStore,
) {
    companion object {
        fun inMemory() = AgentStores(
            MemoryTaskStore(), MemoryEventStore(), MemoryRunStore(), MemoryMessageStore(),
            MemoryToolJournal(), MemoryCheckpointStore(),
        )
    }
}
