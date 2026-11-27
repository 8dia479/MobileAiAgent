package com.mobileaiagent

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.mobileaiagent.ui.AppRoot
import com.mobileaiagent.ui.theme.MobileAIAgentTheme

/**
 * The only Activity. It is intentionally thin: it hosts Compose, requests the
 * notification permission (required to show the foreground-service notification
 * on Android 13+) and forwards "open task" intents. All agent state lives in
 * [MobileAIAgentApp]; destroying this Activity never cancels a task.
 */
class MainActivity : ComponentActivity() {

    private var pendingTaskId by mutableStateOf<String?>(null)

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* optional; task still runs */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        (application as MobileAIAgentApp).initialize()
        pendingTaskId = intent?.getStringExtra(EXTRA_OPEN_TASK_ID)
        requestNotificationPermissionIfNeeded()

        setContent {
            MobileAIAgentTheme {
                AppRoot(
                    initialTaskId = pendingTaskId,
                    onTaskOpened = { pendingTaskId = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingTaskId = intent.getStringExtra(EXTRA_OPEN_TASK_ID)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    companion object {
        const val EXTRA_OPEN_TASK_ID = "open_task_id"
    }
}
