package com.mobileaiagent.service

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.mobileaiagent.MobileAIAgentApp
import com.mobileaiagent.core.log.AgentLog
import com.mobileaiagent.domain.model.TaskStatus

/**
 * WorkManager entry point for post-reboot / post-update recovery.
 *
 * WorkManager is the right tool for the *nudge* (it survives reboots and is
 * guaranteed to run eventually), but not for the long AI work itself — that runs
 * in the foreground service. This worker therefore only starts the service when
 * resumable work exists, which keeps Android's background-execution rules happy:
 * the user-visible notification appears before any model call is made.
 */
class RecoveryWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? MobileAIAgentApp ?: return Result.failure()
        return try {
            app.initialize()
            val tasks = app.taskManager.stores.tasks
            val candidates = tasks.listByStatus(
                TaskStatus.QUEUED.name,
                TaskStatus.RUNNING.name,
                TaskStatus.INTERRUPTED.name,
            )
            AgentLog.i("RecoveryWorker", "Found ${candidates.size} task(s) to restore")
            if (candidates.isNotEmpty()) {
                app.startForegroundServiceIfNeeded()
                app.taskManager.dispatch()
            }
            Result.success(workDataOf("restored" to candidates.size))
        } catch (e: Exception) {
            AgentLog.e("RecoveryWorker", "Recovery failed: ${e.message}")
            Result.retry()
        }
    }

    companion object {
        private const val UNIQUE_NAME = "mobileaiagent-recovery"

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<RecoveryWorker>().build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_NAME,
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }
    }
}

/**
 * Periodic watchdog: if tasks look runnable but no process is executing them
 * (e.g. the service was killed by a battery optimizer), nudge the service back
 * to life. Interval is deliberately long to avoid wake-up churn (spec §1.4).
 */
class WatchdogWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as? MobileAIAgentApp ?: return Result.failure()
        return try {
            app.initialize()
            val runnable = app.taskManager.stores.tasks.listByStatus(
                TaskStatus.QUEUED.name,
                TaskStatus.INTERRUPTED.name,
            )
            if (runnable.isNotEmpty() && app.taskManager.live.runIds().isEmpty()) {
                AgentLog.i("WatchdogWorker", "Restarting agent work for ${runnable.size} task(s)")
                app.startForegroundServiceIfNeeded()
                app.taskManager.dispatch()
            }
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        private const val UNIQUE_NAME = "mobileaiagent-watchdog"

        fun enqueuePeriodic(context: Context, intervalMinutes: Long = 30) {
            val request = androidx.work.PeriodicWorkRequestBuilder<WatchdogWorker>(
                intervalMinutes,
                java.util.concurrent.TimeUnit.MINUTES,
            ).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                androidx.work.ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
