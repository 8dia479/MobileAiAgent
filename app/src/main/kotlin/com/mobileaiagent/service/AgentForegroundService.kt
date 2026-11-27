package com.mobileaiagent.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.mobileaiagent.MainActivity
import com.mobileaiagent.MobileAIAgentApp
import com.mobileaiagent.R
import com.mobileaiagent.core.log.AgentLog
import com.mobileaiagent.domain.model.TaskStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps agent work alive when the UI is gone.
 *
 * Key properties:
 *  - `dataSync` foreground service type (declared in the manifest, required on
 *    Android 14+); no permission is requested beyond FOREGROUND_SERVICE_DATA_SYNC.
 *  - STOP_NOT_REMOVE: swiping the app from recents does not kill a running task.
 *  - A single persistent notification shows the active task + queue depth and
 *    offers Open / Pause / Cancel actions.
 *  - A partial wake lock is held ONLY while a task is actively running and is
 *    released on stop, on completion and on destroy.
 *  - The service is best-effort: Android may still kill the process under memory
 *    pressure. Recovery is handled by [com.mobileaiagent.agent.recovery.RecoveryEngine]
 *    on the next start, so nothing is silently lost.
 */
class AgentForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var notificationJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var app: MobileAIAgentApp

    override fun onCreate() {
        super.onCreate()
        app = application as MobileAIAgentApp
        createChannels()
        AgentLog.i("ForegroundService", "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Android requires startForeground() within ~5 s of *every* startForegroundService()
        // call, including the ones that only carry a Pause/Stop action. Promoting the
        // service first and handling the action second avoids
        // ForegroundServiceDidNotStartInTimeException.
        val action = intent?.action
        if (action != ACTION_STOP_SERVICE) {
            ensureForeground()
        }
        when (action) {
            ACTION_CANCEL -> {
                val taskId = intent.getStringExtra(EXTRA_TASK_ID)
                if (taskId != null) scope.launch { app.taskManager.cancel(taskId) }
            }
            ACTION_PAUSE -> {
                val taskId = intent.getStringExtra(EXTRA_TASK_ID)
                if (taskId != null) scope.launch { app.taskManager.pause(taskId) }
            }
            ACTION_STOP_SERVICE -> {
                stopForegroundAndMaybeStop()
                return START_NOT_STICKY
            }
            else -> observeTasks()
        }
        return START_STICKY
    }

    /** Idempotent: only the first call actually promotes the service. */
    private fun ensureForeground() {
        if (promoted) return
        promoted = true
        startAsForeground(getString(R.string.notification_running_title))
    }

    @Volatile
    private var promoted = false

    private fun startAsForeground(text: String) {
        val notification = buildNotification(text, "Preparing…", null, 0, 0)
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                } else 0,
            )
        } catch (e: Exception) {
            // Android 12+ throws ForegroundServiceStartNotAllowedException when
            // started from the background. Report and let WorkManager retry.
            AgentLog.e("ForegroundService", "startForeground failed: ${e.message}")
            stopSelf()
        }
    }

    private fun observeTasks() {
        if (notificationJob != null) return
        notificationJob = scope.launch {
            app.taskManager.observeAll().collectLatest { tasks ->
                val running = tasks.filter { it.status == TaskStatus.RUNNING }
                val queued = tasks.filter { it.status == TaskStatus.QUEUED }
                val active = running.firstOrNull()
                if (active == null && queued.isEmpty()) {
                    stopForegroundAndMaybeStop()
                } else if (active != null) {
                    acquireWakeLock()
                    val text = if (queued.isNotEmpty()) "${queued.size} more queued" else active.title.take(80)
                    notify(buildNotification(getString(R.string.notification_running_title), text, active.id, 0, 0))
                } else {
                    releaseWakeLock()
                    notify(buildNotification("Queued", "${queued.size} task(s) waiting", queued.firstOrNull()?.id, 0, 0))
                }
            }
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun buildNotification(
        title: String,
        text: String,
        taskId: String?,
        progress: Int,
        max: Int,
    ): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                if (taskId != null) putExtra(MainActivity.EXTRA_OPEN_TASK_ID, taskId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_RUNNING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        if (max > 0) builder.setProgress(max, progress, false)

        if (taskId != null) {
            builder.addAction(
                0, getString(R.string.notification_action_pause),
                PendingIntent.getService(
                    this, 1,
                    Intent(this, AgentForegroundService::class.java).apply {
                        action = ACTION_PAUSE
                        putExtra(EXTRA_TASK_ID, taskId)
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            builder.addAction(
                0, getString(R.string.notification_action_cancel),
                PendingIntent.getService(
                    this, 2,
                    Intent(this, AgentForegroundService::class.java).apply {
                        action = ACTION_CANCEL
                        putExtra(EXTRA_TASK_ID, taskId)
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        }
        return builder.build()
    }

    private fun notify(notification: Notification) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_RUNNING,
                getString(R.string.notification_channel_agent),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.notification_channel_agent_desc)
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_EVENTS,
                "Task events",
                NotificationManager.IMPORTANCE_DEFAULT,
            )
        )
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MobileAIAgent:agent").apply {
            setReferenceCounted(false)
            try {
                acquire(10 * 60 * 1000L) // 10 min timeout; renewed by notifications
            } catch (e: Exception) {
                AgentLog.w("ForegroundService", "Wake lock acquire failed: ${e.message}")
            }
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (e: Exception) {
            AgentLog.w("ForegroundService", "Wake lock release failed: ${e.message}")
        } finally {
            wakeLock = null
        }
    }

    private fun stopForegroundAndMaybeStop() {
        releaseWakeLock()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiping the app away must not kill running work (STOP_NOT_REMOVE is set
        // in the manifest); nothing to do, but log for diagnostics.
        AgentLog.i("ForegroundService", "Task removed from recents; agent work continues")
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        AgentLog.i("ForegroundService", "Service destroyed")
        releaseWakeLock()
        notificationJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val CHANNEL_RUNNING = "agent_tasks"
        const val CHANNEL_EVENTS = "agent_events"
        const val NOTIFICATION_ID = 1001
        const val ACTION_CANCEL = "com.mobileaiagent.action.CANCEL_TASK"
        const val ACTION_PAUSE = "com.mobileaiagent.action.PAUSE_TASK"
        const val ACTION_STOP_SERVICE = "com.mobileaiagent.action.STOP_SERVICE"
        const val EXTRA_TASK_ID = "task_id"

        fun start(context: Context) {
            val intent = Intent(context, AgentForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AgentForegroundService::class.java))
        }
    }
}
