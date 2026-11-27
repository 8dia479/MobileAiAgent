package com.mobileaiagent.agent

import com.mobileaiagent.core.util.CancellationToken
import com.mobileaiagent.domain.model.TaskRecord
import com.mobileaiagent.service.LiveRunRegistry

/**
 * Executes a task's agent loop. Implemented by [DefaultTaskRunner]; kept as an
 * interface so TaskManager's lifecycle logic can be tested with a scripted runner.
 */
interface TaskRunner {
    suspend fun run(task: TaskRecord, cancellation: CancellationToken, live: LiveRunRegistry): RunOutcome
    /** User asked to pause: stop gracefully and report PAUSED. */
    fun requestPause(taskId: String)
    /** User asked to cancel: stop gracefully and report CANCELLED. */
    fun requestCancel(taskId: String)
}
