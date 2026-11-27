package com.mobileaiagent.data.db

import com.mobileaiagent.core.storage.*
import com.mobileaiagent.core.util.IsoTime
import com.mobileaiagent.domain.model.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Room-backed implementations of the agent core's persistence contracts.
 * Mapping functions are the only place DB rows and domain objects meet.
 */
class RoomTaskStore(private val dao: TaskDao) : TaskStore {
    override suspend fun upsert(task: TaskRecord) = dao.upsert(task.toEntity())
    override suspend fun get(id: String): TaskRecord? = dao.get(id)?.toDomain()
    override fun observe(id: String): Flow<TaskRecord?> = dao.observe(id).map { it?.toDomain() }
    override fun observeAll(): Flow<List<TaskRecord>> = dao.observeAll().map { list -> list.map { it.toDomain() } }
    override suspend fun listAll(): List<TaskRecord> = dao.listAll().map { it.toDomain() }
    override suspend fun listByStatus(vararg statuses: String): List<TaskRecord> =
        dao.listByStatus(statuses.toList()).map { it.toDomain() }
    override suspend fun delete(id: String) = dao.delete(id)
    override suspend fun countRunning(): Int = dao.countRunning()
}

class RoomEventStore(private val dao: EventDao) : EventStore {
    override suspend fun append(event: ExecutionEvent) = dao.insert(event.toEntity())
    override fun observe(taskId: String, limit: Int): Flow<List<ExecutionEvent>> =
        dao.observe(taskId, limit).map { rows -> rows.sortedBy { it.seq }.map { it.toDomain() } }
    override suspend fun list(taskId: String, limit: Int): List<ExecutionEvent> =
        dao.list(taskId, limit).sortedBy { it.seq }.map { it.toDomain() }
    override suspend fun lastSeq(taskId: String): Long = dao.maxSeq(taskId)
    override suspend fun deleteForTask(taskId: String) = dao.deleteForTask(taskId)
}

class RoomRunStore(private val dao: RunDao) : RunStore {
    override suspend fun start(run: RunRecord) = dao.insert(run.toEntity())
    override suspend fun finish(runId: String, endedAt: Long, exitReason: String, steps: Int, lastStepSummary: String?) =
        dao.finish(runId, endedAt, exitReason, steps, lastStepSummary)
    override suspend fun get(runId: String): RunRecord? = dao.get(runId)?.toDomain()
    override suspend fun listForTask(taskId: String): List<RunRecord> = dao.listForTask(taskId).map { it.toDomain() }
    override suspend fun findOrphanedRuns(aliveRunIds: Set<String>): List<RunRecord> =
        dao.allOpen().filter { it.id !in aliveRunIds }.map { it.toDomain() }
}

class RoomMessageStore(private val dao: MessageDao) : MessageStore {
    override suspend fun append(message: ChatMessage, taskId: String) = dao.insert(message.toEntity(taskId))
    override suspend fun list(taskId: String, limit: Int): List<ChatMessage> = dao.list(taskId, limit).map { it.toDomain() }
    override fun observe(taskId: String): Flow<List<ChatMessage>> = dao.observe(taskId).map { rows -> rows.map { it.toDomain() } }
    override suspend fun replace(taskId: String, messages: List<ChatMessage>) {
        dao.deleteForTask(taskId)
        messages.forEach { dao.insert(it.toEntity(taskId)) }
    }
    override suspend fun deleteForTask(taskId: String) = dao.deleteForTask(taskId)
}

class RoomToolJournal(private val dao: JournalDao) : ToolJournal {
    override suspend fun started(taskId: String, runId: String, callId: String, toolId: String, argsJson: String, mutagenic: Boolean) =
        dao.insert(
            JournalEntity(
                callId = callId, taskId = taskId, runId = runId, toolId = toolId, argsJson = argsJson,
                mutagenic = mutagenic, status = "started", startedAt = System.currentTimeMillis(),
                finishedAt = null, resultSummary = null,
            )
        )

    override suspend fun finished(callId: String, status: String, resultSummary: String) =
        dao.finish(callId, status, System.currentTimeMillis(), resultSummary)

    override suspend fun pendingMutagenic(taskId: String): List<ToolJournalEntry> =
        dao.pendingMutagenic(taskId).map { it.toDomain() }

    override suspend fun list(taskId: String, limit: Int): List<ToolJournalEntry> =
        dao.list(taskId, limit).sortedBy { it.startedAt }.map { it.toDomain() }

    override suspend fun deleteForTask(taskId: String) = dao.deleteForTask(taskId)
}

class RoomCheckpointStore(private val dao: CheckpointDao) : CheckpointStore {
    override suspend fun save(checkpoint: TaskCheckpoint) = dao.save(
        CheckpointEntity(
            checkpoint.taskId, checkpoint.createdAt, checkpoint.lastPlanTaskId,
            checkpoint.lastCompletedStepSummary, checkpoint.assistantDraft,
            checkpoint.planRevision, checkpoint.schemaVersion,
        )
    )

    override suspend fun get(taskId: String): TaskCheckpoint? = dao.get(taskId)?.let {
        TaskCheckpoint(
            it.taskId, it.createdAt, it.lastPlanTaskId, it.lastCompletedStepSummary,
            it.assistantDraft, it.planRevision, it.schemaVersion,
        )
    }

    override suspend fun delete(taskId: String) = dao.delete(taskId)
}

// ------------------------------------------------------------------ mappers

fun TaskRecord.toEntity() = TaskEntity(
    id = id, projectId = projectId, projectName = projectName, title = title, request = request,
    mode = mode.name, mainModel = mainModel, mainProvider = mainProvider,
    mainReasoningEffort = mainReasoningEffort.id, secondaryModel = secondaryModel,
    secondaryProvider = secondaryProvider, secondaryReasoningEffort = secondaryReasoningEffort?.id,
    status = status.name, planId = planId, activeRunId = activeRunId, lastError = lastError,
    errorKind = errorKind?.name, attemptCount = attemptCount, autoResumeOnBoot = autoResumeOnBoot,
    createdAt = createdAt, updatedAt = updatedAt, startedAt = startedAt, finishedAt = finishedAt,
    tokensIn = tokensIn, tokensOut = tokensOut, costEstimate = costEstimate,
    pendingApprovalJson = pendingApprovalJson,
)

fun TaskEntity.toDomain() = TaskRecord(
    id = id, projectId = projectId, projectName = projectName, title = title, request = request,
    mode = runCatching { ExecutionMode.valueOf(mode) }.getOrDefault(ExecutionMode.BUILD),
    mainModel = mainModel, mainProvider = mainProvider,
    mainReasoningEffort = ReasoningEffort.fromId(mainReasoningEffort) ?: ReasoningEffort.MEDIUM,
    secondaryModel = secondaryModel, secondaryProvider = secondaryProvider,
    secondaryReasoningEffort = secondaryReasoningEffort?.let { ReasoningEffort.fromId(it) },
    status = runCatching { TaskStatus.valueOf(status) }.getOrDefault(TaskStatus.FAILED),
    planId = planId, activeRunId = activeRunId, lastError = lastError,
    errorKind = errorKind?.let { runCatching { ErrorKind.valueOf(it) }.getOrNull() },
    attemptCount = attemptCount, autoResumeOnBoot = autoResumeOnBoot,
    createdAt = createdAt, updatedAt = updatedAt, startedAt = startedAt, finishedAt = finishedAt,
    tokensIn = tokensIn, tokensOut = tokensOut, costEstimate = costEstimate,
    pendingApprovalJson = pendingApprovalJson,
)

fun ExecutionEvent.toEntity() = EventEntity(id, taskId, runId, seq, timestamp, type.name, title, body, metadataJson)

fun EventEntity.toDomain() = ExecutionEvent(
    id, taskId, runId, seq, timestamp,
    runCatching { EventType.valueOf(type) }.getOrDefault(EventType.WARNING),
    title, body, metadataJson,
)

fun RunRecord.toEntity() = RunEntity(id, taskId, phase.name, startedAt, endedAt, exitReason, steps, lastStepSummary, serviceInstanceId, hostBootId)

fun RunEntity.toDomain() = RunRecord(
    id, taskId, runCatching { RunPhase.valueOf(phase) }.getOrDefault(RunPhase.AGENT_LOOP),
    startedAt, endedAt, exitReason, steps, lastStepSummary, serviceInstanceId, hostBootId,
)

fun ChatMessage.toEntity(taskId: String) = MessageEntity(
    id = id, taskId = taskId, role = role.name, content = content, timestamp = timestamp,
    toolCallId = toolCallId, toolName = toolName,
    attachmentsJson = com.mobileaiagent.core.json.JsonValue.Arr(
        attachments.map { a ->
            com.mobileaiagent.core.json.JsonValue.obj(
                "id" to com.mobileaiagent.core.json.JsonValue.of(a.id),
                "name" to com.mobileaiagent.core.json.JsonValue.of(a.displayName),
                "mime" to com.mobileaiagent.core.json.JsonValue.of(a.mimeType),
                "size" to com.mobileaiagent.core.json.JsonValue.of(a.sizeBytes),
                "location" to com.mobileaiagent.core.json.JsonValue.of(a.location),
                "isContentUri" to com.mobileaiagent.core.json.JsonValue.of(a.isContentUri),
            )
        }
    ).toJson().takeIf { attachments.isNotEmpty() },
    reasoning = reasoning, isPartial = isPartial, toolCallsJson = toolCallsJson,
)

fun MessageEntity.toDomain() = ChatMessage(
    id = id, role = runCatching { ChatMessage.Role.valueOf(role) }.getOrDefault(ChatMessage.Role.ASSISTANT),
    content = content, timestamp = timestamp, toolCallId = toolCallId, toolName = toolName,
    attachments = attachmentsJson?.let { raw ->
        com.mobileaiagent.core.json.JsonParser.parseOrNull(raw)?.asArrayOrNull()?.map { a ->
            AttachmentRef(
                id = a.stringOr("id", ""), displayName = a.stringOr("name", ""),
                mimeType = a.stringOr("mime", "application/octet-stream"),
                sizeBytes = a["size"].asLongOrNull() ?: 0,
                location = a.stringOr("location", ""),
                isContentUri = a["isContentUri"].asBooleanOrNull() ?: false,
            )
        }
    } ?: emptyList(),
    reasoning = reasoning, isPartial = isPartial, toolCallsJson = toolCallsJson,
)

fun JournalEntity.toDomain() = ToolJournalEntry(
    callId, taskId, runId, toolId, argsJson, mutagenic, status, startedAt, finishedAt, resultSummary,
)

fun RunRecord.startedIso(): String = IsoTime.fromMillis(startedAt)
