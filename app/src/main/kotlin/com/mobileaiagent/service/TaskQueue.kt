package com.mobileaiagent.service

import com.mobileaiagent.domain.model.TaskStatus
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * Ordering policy for task execution: at most one actively running agent task at
 * a time (the model streams in a single thread of work), with the rest queued in
 * submission order. Pausing a task removes it from the active slot but keeps it
 * in history; resuming re-queues it at the front.
 */
class TaskQueue {
    private val queue = ConcurrentLinkedDeque<String>()
    @Volatile
    var active: String? = null
        private set

    fun enqueue(taskId: String): Boolean {
        if (taskId == active) return false
        if (queue.contains(taskId)) return false
        queue.addLast(taskId)
        return true
    }

    fun enqueueFront(taskId: String) {
        if (taskId == active) return
        queue.remove(taskId)
        queue.addFirst(taskId)
    }

    fun remove(taskId: String): Boolean {
        if (active == taskId) {
            active = null
            return true
        }
        return queue.remove(taskId)
    }

    /** Takes the next queued task (marks it active). Returns null when busy/empty. */
    fun poll(): String? {
        if (active != null) return null
        val next = queue.pollFirst() ?: return null
        active = next
        return next
    }

    fun complete(taskId: String) {
        if (active == taskId) active = null
        queue.remove(taskId)
    }

    fun snapshot(): QueueSnapshot = QueueSnapshot(active, queue.toList())

    fun clear() {
        active = null
        queue.clear()
    }

    data class QueueSnapshot(val active: String?, val pending: List<String>)
}

/**
 * Runtime-only bookkeeping of which tasks this process is actually executing.
 * Used by recovery to distinguish live runs from orphans after process death.
 */
class LiveRunRegistry {
    private val liveRuns = java.util.concurrent.ConcurrentHashMap<String, LiveRun>()

    data class LiveRun(
        val taskId: String,
        val runId: String,
        val startedAt: Long,
        val cancellation: com.mobileaiagent.core.util.CancellationToken,
        @Volatile var lastHeartbeat: Long = startedAt,
    )

    fun register(taskId: String, runId: String, cancellation: com.mobileaiagent.core.util.CancellationToken) {
        liveRuns[runId] = LiveRun(taskId, runId, System.currentTimeMillis(), cancellation)
    }

    fun unregister(runId: String) { liveRuns.remove(runId) }

    fun runIds(): Set<String> = liveRuns.keys.toSet()

    fun runForTask(taskId: String): LiveRun? = liveRuns.values.firstOrNull { it.taskId == taskId }

    fun heartbeat(runId: String) { liveRuns[runId]?.let { it.lastHeartbeat = System.currentTimeMillis() } }

    fun cancel(taskId: String): Boolean {
        val run = runForTask(taskId) ?: return false
        run.cancellation.cancel()
        return true
    }

    fun activeTaskIds(): Set<String> = liveRuns.values.map { it.taskId }.toSet()

    fun clear() = liveRuns.clear()
}

/**
 * Simple persisted-status helpers shared by the runner and UI actions.
 */
object TaskTransitions {
    fun canPause(status: TaskStatus): Boolean = status == TaskStatus.RUNNING

    fun canResume(status: TaskStatus): Boolean =
        status == TaskStatus.PAUSED || status == TaskStatus.INTERRUPTED ||
            status == TaskStatus.WAITING_FOR_USER || status == TaskStatus.WAITING_FOR_NETWORK ||
            status == TaskStatus.FAILED || status == TaskStatus.CANCELLED

    fun canCancel(status: TaskStatus): Boolean = !status.isTerminal

    fun isBusy(status: TaskStatus): Boolean = status.isActive || status == TaskStatus.QUEUED
}
