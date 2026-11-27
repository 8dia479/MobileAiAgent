package com.mobileaiagent.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.mobileaiagent.MobileAIAgentApp
import com.mobileaiagent.core.log.AgentLog

/**
 * Restores the agent after a reboot or an app update.
 *
 * Interrupted tasks are marked recoverable by [com.mobileaiagent.agent.recovery.RecoveryEngine]
 * on the next process start; this receiver's job is simply to ensure that start
 * happens (and to restart the foreground service if work was in flight).
 */
class BootRecoveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> {
                AgentLog.i("BootReceiver", "Boot completed; scheduling agent recovery")
                val app = context.applicationContext as? MobileAIAgentApp ?: return
                app.scheduleRecoveryWork()
            }
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                AgentLog.i("BootReceiver", "App updated; scheduling agent recovery")
                val app = context.applicationContext as? MobileAIAgentApp ?: return
                app.scheduleRecoveryWork()
            }
        }
    }
}
