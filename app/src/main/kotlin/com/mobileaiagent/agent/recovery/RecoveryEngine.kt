package com.mobileaiagent.agent.recovery

import com.mobileaiagent.core.log.AgentLog
import com.mobileaiagent.core.storage.AgentStores
import com.mobileaiagent.domain.model.ErrorKind
import com.mobileaiagent.domain.model.ExecutionMode
import com.mobileaiagent.domain.model.TaskRecord
import com.mobileaiagent.domain.model.TaskStatus

/**
 * Brings persisted tasks back to a consistent state after a process restart.
 *
 * Policy (deliberately conservative):
 *  - RUNNING tasks that no live run owns are marked INTERRUPTED, not silently
 *    restarted, and their orphaned runs are closed so the timeline stays honest.
 *  - Tasks flagged `autoResumeOnBoot` are added to the resume list; the runner
 *    injects a "continue from here" notice and the pending-mutagenic journal so
 *    the model does not repeat destructive work.
 *  - Tasks that were mid-mutation keep a journal entry with status `started`;
 *    recovery reports them and never auto-replays them.
 *  - Paused / waiting tasks are left untouched.
 */
class RecoveryEngine(
    private val stores: AgentStores,
    private val now: () -> Long = System::currentTimeMillis,
) {

    data class RecoveryReport(
        val scanned: Int,
        val interrupted: List<String>,
        val resumable: List<String>,
        val orphanedRuns: List<String>,
        val tasksNeedingAttention: List<TaskRecord>,
        val notes: List<String>,
    )

    suspend fun recover(aliveRunIds: Set<String>): RecoveryReport {
        val all = stores.tasks.listAll()
        val interrupted = mutableListOf<String>()
        val resumable = mutableListOf<String>()
        val notes = mutableListOf<String>()

        val orphaned = stores.runs.findOrphanedRuns(aliveRunIds)
        orphaned.forEach { run ->
            stores.runs.finish(
                runId = run.id,
                endedAt = now(),
                exitReason = "process_terminated",
                steps = run.steps,
                lastStepSummary = run.lastStepSummary,
            )
        }
        if (orphaned.isNotEmpty()) {
            notes.add("Closed ${orphaned.size} orphaned run(s) from a previous process")
        }

        val needsAttention = mutableListOf<TaskRecord>()
        for (task in all) {
            when (task.status) {
                TaskStatus.RUNNING -> {
                    val pending = stores.journal.pendingMutagenic(task.id)
                    val note = if (pending.isNotEmpty()) {
                        "Task '${task.title}' was interrupted during ${pending.size} file-changing operation(s): " +
                            pending.joinToString { it.toolId } + ". These were NOT replayed automatically."
                    } else {
                        "Task '${task.title}' was interrupted; progress is preserved and can be resumed."
                    }
                    notes.add(note)
                    val updated = task.copy(
                        status = TaskStatus.INTERRUPTED,
                        activeRunId = null,
                        lastError = "App process ended while running",
                        errorKind = ErrorKind.RECOVERY,
                        updatedAt = now(),
                    )
                    stores.tasks.upsert(updated)
                    interrupted.add(task.id)
                    if (task.autoResumeOnBoot && task.mode != ExecutionMode.PLAN) {
                        resumable.add(task.id)
                    } else {
                        needsAttention.add(updated)
                    }
                }
                TaskStatus.INTERRUPTED -> {
                    if (task.autoResumeOnBoot && task.mode != ExecutionMode.PLAN) resumable.add(task.id)
                    else needsAttention.add(task)
                }
                TaskStatus.QUEUED -> {
                    // A queued task never started because the process died; resume it.
                    resumable.add(task.id)
                }
                else -> Unit
            }
        }

        AgentLog.i(
            "RecoveryEngine",
            "Recovery scanned ${all.size} task(s): ${interrupted.size} interrupted, ${resumable.size} resumable, " +
                "${orphaned.size} orphaned run(s)",
        )
        return RecoveryReport(
            scanned = all.size,
            interrupted = interrupted,
            resumable = resumable,
            orphanedRuns = orphaned.map { it.id },
            tasksNeedingAttention = needsAttention,
            notes = notes,
        )
    }

    /**
     * Context handed to the model when a task resumes, derived from the plan
     * checklist + journal so completed work is not repeated.
     */
    suspend fun resumeContext(taskId: String): ResumeContext {
        val checkpoint = stores.checkpoints.get(taskId)
        val journal = stores.journal.list(taskId, 200)
        val completed = journal.filter { it.status == "succeeded" }
        val pending = journal.filter { it.status == "started" }
        return ResumeContext(
            checkpoint = checkpoint,
            completedSummary = buildString {
                if (completed.isEmpty()) {
                    append("No tool operations completed yet.")
                } else {
                    appendLine("Completed tool operations (do not repeat blindly):")
                    completed.takeLast(15).forEach { entry ->
                        appendLine("- ${entry.toolId}: ${(entry.resultSummary ?: "").take(160)}")
                    }
                }
            },
            pendingOperations = pending.map { "${it.toolId} was in progress (args: ${it.argsJson.take(200)})" },
        )
    }

    data class ResumeContext(
        val checkpoint: com.mobileaiagent.core.storage.TaskCheckpoint?,
        val completedSummary: String,
        val pendingOperations: List<String>,
    )
}
