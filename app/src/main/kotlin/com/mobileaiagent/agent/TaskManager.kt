package com.mobileaiagent.agent

import com.mobileaiagent.core.json.JsonValue
import com.mobileaiagent.core.log.AgentLog
import com.mobileaiagent.core.storage.AgentStores
import com.mobileaiagent.core.util.CancellationToken
import com.mobileaiagent.core.util.Clock
import com.mobileaiagent.core.util.IdGenerator
import com.mobileaiagent.core.util.SystemClock
import com.mobileaiagent.domain.model.*
import com.mobileaiagent.service.LiveRunRegistry
import com.mobileaiagent.service.TaskQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * TaskManager — the single owner of task lifecycles.
 *
 * Nothing in this class references an Activity, Fragment, ViewModel or Compose
 * composition. A task's coroutine runs on the application-scoped [scope], so
 * navigating, backgrounding or even destroying the UI cannot cancel it.
 *
 * Responsibilities:
 *  - create tasks (immediately persisted before any work starts)
 *  - queue + dispatch to the TaskRunner
 *  - pause / resume / cancel transitions with honest persisted states
 *  - append timeline events
 *  - hand out restore snapshots for the UI on reattach
 */
class TaskManager(
    val stores: AgentStores,
    val runner: TaskRunner,
    private val clock: Clock = SystemClock,
    private val ids: IdGenerator = IdGenerator.random(),
    /** Application-scoped: must outlive every Activity. */
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val onRunningChanged: (Boolean) -> Unit = {},
    private val onTaskFinished: (TaskRecord) -> Unit = {},
) {
    val queue = TaskQueue()
    val live = LiveRunRegistry()

    private val dispatchMutex = Mutex()
    private val seqCounters = ConcurrentHashMap<String, Long>()
    private val _events = MutableSharedFlow<ExecutionEvent>(extraBufferCapacity = 512)
    val events: SharedFlow<ExecutionEvent> = _events.asSharedFlow()

    /** UI hints: a task changed state and should be re-read. */
    private val _changes = MutableSharedFlow<String>(extraBufferCapacity = 256)
    val changes: SharedFlow<String> = _changes.asSharedFlow()

    // ------------------------------------------------------------------ create

    suspend fun createTask(params: NewTaskParams): TaskRecord {
        val now = clock.nowMillis()
        val taskId = ids.newId("task")
        val task = TaskRecord(
            id = taskId,
            projectId = params.projectId,
            projectName = params.projectName,
            title = params.title.ifBlank { params.request.take(60).replace("\n", " ") },
            request = params.request,
            mode = params.mode,
            mainModel = params.mainModel,
            mainProvider = params.mainProvider,
            mainReasoningEffort = params.mainReasoningEffort,
            secondaryModel = params.secondaryModel,
            secondaryProvider = params.secondaryProvider,
            secondaryReasoningEffort = params.secondaryReasoningEffort,
            status = TaskStatus.QUEUED,
            planId = null,
            activeRunId = null,
            lastError = null,
            errorKind = null,
            attemptCount = 0,
            autoResumeOnBoot = params.autoResumeOnBoot,
            createdAt = now,
            updatedAt = now,
            startedAt = null,
            finishedAt = null,
            tokensIn = 0,
            tokensOut = 0,
            costEstimate = 0.0,
        )
        stores.tasks.upsert(task)
        stores.messages.append(
            ChatMessage(
                id = ids.newId("msg"), role = ChatMessage.Role.USER, content = params.request,
                timestamp = now, attachments = params.attachments,
            ),
            taskId,
        )
        emit(taskId, null, EventType.TASK_CREATED, "Task created", params.request.take(500))
        emit(taskId, null, EventType.TASK_QUEUED, "Queued", "Mode: ${params.mode.label}")
        queue.enqueue(taskId)
        _changes.tryEmit(taskId)
        // Dispatch synchronously through the mutex: the task record is claimed
        // (RUNNING) only after the caller has the persisted record back, and the
        // actual run still happens on the application scope, never the caller's.
        dispatchMutex.withLock { claimNext() }
        return task
    }

    // ---------------------------------------------------------------- dispatch

    /** Starts the next queued task if nothing is running. Idempotent. */
    fun dispatch() {
        scope.launch { dispatchNow() }
    }

    private suspend fun dispatchNow() = dispatchMutex.withLock { claimNext() }

    /** Must be called while holding [dispatchMutex]. */
    private suspend fun claimNext() {
        if (queue.active != null && live.runIds().isNotEmpty()) return
        val nextId = queue.poll() ?: run {
            onRunningChanged(false)
            return
        }
        val task = stores.tasks.get(nextId)
        if (task == null) {
            queue.complete(nextId)
            return
        }
        if (task.status == TaskStatus.PAUSED || task.status == TaskStatus.WAITING_FOR_USER) {
            // Not runnable right now; leave it out of the queue.
            queue.complete(nextId)
            return
        }
        onRunningChanged(true)
        scope.launch {
            try {
                executeTask(task)
            } catch (e: Exception) {
                AgentLog.e("TaskManager", "Unhandled failure in task $nextId", e)
                markStatus(nextId, TaskStatus.FAILED, ErrorKind.INTERNAL, e.message)
            } finally {
                queue.complete(nextId)
                dispatch()
            }
        }
    }

    private suspend fun executeTask(task: TaskRecord) {
        val cancellation = CancellationToken()
        stores.tasks.upsert(
            task.copy(
                status = TaskStatus.RUNNING,
                startedAt = task.startedAt ?: clock.nowMillis(),
                updatedAt = clock.nowMillis(),
                attemptCount = task.attemptCount + (if (task.status == TaskStatus.QUEUED) 1 else 0),
            )
        )
        _changes.tryEmit(task.id)
        emit(task.id, task.activeRunId, EventType.RUN_STARTED, "Run started", "Attempt ${task.attemptCount + 1}")
        val outcome = runner.run(task, cancellation, live)
        when (outcome.kind) {
            RunOutcome.Kind.COMPLETED -> {
                markStatus(task.id, TaskStatus.COMPLETED, null, null)
                onTaskFinished(stores.tasks.get(task.id) ?: task)
            }
            RunOutcome.Kind.CANCELLED -> {
                markStatus(task.id, TaskStatus.CANCELLED, ErrorKind.CANCELLED, outcome.message)
                onTaskFinished(stores.tasks.get(task.id) ?: task)
            }
            RunOutcome.Kind.NEEDS_USER -> {
                val updated = markStatus(task.id, TaskStatus.WAITING_FOR_USER, null, outcome.message)
                stores.tasks.upsert(
                    (updated ?: task).copy(
                        pendingApprovalJson = outcome.pendingApprovalJson,
                        updatedAt = clock.nowMillis(),
                    )
                )
            }
            RunOutcome.Kind.WAITING_NETWORK -> {
                markStatus(task.id, TaskStatus.WAITING_FOR_NETWORK, ErrorKind.NETWORK, outcome.message)
            }
            RunOutcome.Kind.PAUSED -> {
                markStatus(task.id, TaskStatus.PAUSED, null, null)
            }
            RunOutcome.Kind.FAILED -> {
                markStatus(task.id, TaskStatus.FAILED, outcome.errorKind ?: ErrorKind.INTERNAL, outcome.message)
                onTaskFinished(stores.tasks.get(task.id) ?: task)
            }
        }
        _changes.tryEmit(task.id)
    }

    private suspend fun markStatus(
        taskId: String,
        status: TaskStatus,
        errorKind: ErrorKind?,
        message: String?,
    ): TaskRecord? {
        val current = stores.tasks.get(taskId) ?: return null
        val updated = current.copy(
            status = status,
            errorKind = errorKind,
            lastError = message,
            activeRunId = if (status.isTerminal || status == TaskStatus.PAUSED) null else current.activeRunId,
            updatedAt = clock.nowMillis(),
            finishedAt = if (status.isTerminal) clock.nowMillis() else current.finishedAt,
        )
        stores.tasks.upsert(updated)
        val type = when (status) {
            TaskStatus.COMPLETED -> EventType.TASK_COMPLETED
            TaskStatus.FAILED -> EventType.TASK_FAILED
            TaskStatus.CANCELLED -> EventType.TASK_CANCELLED
            TaskStatus.PAUSED -> EventType.TASK_PAUSED
            else -> null
        }
        if (type != null) emit(taskId, updated.activeRunId, type, status.label, message)
        return updated
    }

    // ------------------------------------------------------------- user actions

    suspend fun pause(taskId: String): Boolean {
        val task = stores.tasks.get(taskId) ?: return false
        if (task.status == TaskStatus.QUEUED) {
            queue.remove(taskId)
            markStatus(taskId, TaskStatus.PAUSED, null, "Paused by user before starting")
            _changes.tryEmit(taskId)
            return true
        }
        if (task.status != TaskStatus.RUNNING) return false
        live.cancel(taskId)
        // The runner observes cancellation and returns PAUSED (see TaskRunner.cancelAsPause)
        runner.requestPause(taskId)
        emit(taskId, task.activeRunId, EventType.TASK_PAUSED, "Pause requested", null)
        _changes.tryEmit(taskId)
        return true
    }

    suspend fun cancel(taskId: String): Boolean {
        val task = stores.tasks.get(taskId) ?: return false
        if (task.status.isTerminal) return false
        queue.remove(taskId)
        live.cancel(taskId)
        runner.requestCancel(taskId)
        if (stores.tasks.get(taskId)?.status != TaskStatus.RUNNING) {
            markStatus(taskId, TaskStatus.CANCELLED, ErrorKind.CANCELLED, "Cancelled by user")
        } else {
            emit(taskId, task.activeRunId, EventType.TASK_CANCELLED, "Cancellation requested", null)
        }
        _changes.tryEmit(taskId)
        dispatch()
        return true
    }

    suspend fun resume(taskId: String): Boolean {
        val task = stores.tasks.get(taskId) ?: return false
        if (!com.mobileaiagent.service.TaskTransitions.canResume(task.status)) return false
        val updated = task.copy(
            status = TaskStatus.QUEUED,
            lastError = null,
            errorKind = null,
            autoResumeOnBoot = true,
            updatedAt = clock.nowMillis(),
            finishedAt = null,
        )
        stores.tasks.upsert(updated)
        queue.enqueueFront(taskId)
        emit(taskId, null, EventType.TASK_RESUMED, "Resumed", "Continuing from last checkpoint")
        _changes.tryEmit(taskId)
        dispatch()
        return true
    }

    /** Re-attaches to a task the UI is showing; returns its live state. */
    suspend fun snapshot(taskId: String): TaskSnapshot? {
        val task = stores.tasks.get(taskId) ?: return null
        return TaskSnapshot(
            task = task,
            events = stores.events.list(taskId, 400),
            messages = stores.messages.list(taskId, 400),
            journal = stores.journal.list(taskId, 300),
            isLive = live.runForTask(taskId) != null,
        )
    }

    fun observeTask(taskId: String): Flow<TaskRecord?> = stores.tasks.observe(taskId)
    fun observeAll(): Flow<List<TaskRecord>> = stores.tasks.observeAll()
    fun observeEvents(taskId: String): Flow<List<ExecutionEvent>> = stores.events.observe(taskId)
    fun observeMessages(taskId: String): Flow<List<ChatMessage>> = stores.messages.observe(taskId)

    // ----------------------------------------------------------------- events

    suspend fun emit(
        taskId: String,
        runId: String?,
        type: EventType,
        title: String,
        body: String? = null,
        metadata: JsonValue? = null,
    ) {
        val persisted = stores.events.lastSeq(taskId)
        val seq = synchronized(seqCounters) {
            val next = maxOf(seqCounters[taskId] ?: 0L, persisted) + 1
            seqCounters[taskId] = next
            next
        }
        val event = ExecutionEvent(
            id = ids.newId("evt"),
            taskId = taskId,
            runId = runId,
            seq = seq,
            timestamp = clock.nowMillis(),
            type = type,
            title = title,
            body = body,
            metadataJson = metadata?.toJson(),
        )
        stores.events.append(event)
        _events.tryEmit(event)
    }

    /** Startup recovery hook. Must be called once per process start. */
    suspend fun onProcessStart(recovery: com.mobileaiagent.agent.recovery.RecoveryEngine) {
        val report = recovery.recover(live.runIds())
        report.notes.forEach { AgentLog.i("TaskManager", it) }
        report.resumable.forEach { taskId ->
            queue.enqueueFront(taskId)
            emit(taskId, null, EventType.RECOVERY_RESUMED, "Restored after app restart", null)
        }
        if (report.resumable.isNotEmpty()) dispatch()
        _changes.tryEmit("*")
    }
}

data class NewTaskParams(
    val request: String,
    val title: String = "",
    val projectId: String?,
    val projectName: String?,
    val mode: ExecutionMode,
    val mainModel: String,
    val mainProvider: String,
    val mainReasoningEffort: ReasoningEffort,
    val secondaryModel: String? = null,
    val secondaryProvider: String? = null,
    val secondaryReasoningEffort: ReasoningEffort? = null,
    val attachments: List<AttachmentRef> = emptyList(),
    val autoResumeOnBoot: Boolean = true,
)

data class TaskSnapshot(
    val task: TaskRecord,
    val events: List<ExecutionEvent>,
    val messages: List<ChatMessage>,
    val journal: List<com.mobileaiagent.core.storage.ToolJournalEntry>,
    val isLive: Boolean,
)

data class RunOutcome(
    val kind: Kind,
    val message: String? = null,
    val errorKind: ErrorKind? = null,
    val pendingApprovalJson: String? = null,
) {
    enum class Kind { COMPLETED, FAILED, CANCELLED, PAUSED, NEEDS_USER, WAITING_NETWORK }
}

/** Access to the on-disk project for a task (used by the runner for plans/files). */
interface ProjectLocator {
    fun projectRootFor(task: TaskRecord): File?
    fun ensureProjectFor(task: TaskRecord): File
}
