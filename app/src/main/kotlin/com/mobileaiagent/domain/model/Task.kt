package com.mobileaiagent.domain.model

/**
 * Persisted, UI-independent task record.
 *
 * The task is the unit of background execution: it owns its request, plan
 * reference, checkpoints and execution history. Nothing here references an
 * Activity, Compose state or ViewModel, so a task survives process death and
 * UI destruction by construction.
 */
data class TaskRecord(
    val id: String,
    val projectId: String?,
    val projectName: String?,
    val title: String,
    /** The exact, unmodified user request. Never rewritten by recovery. */
    val request: String,
    val mode: ExecutionMode,
    val mainModel: String,
    val mainProvider: String,
    val mainReasoningEffort: ReasoningEffort,
    val secondaryModel: String?,
    val secondaryProvider: String?,
    val secondaryReasoningEffort: ReasoningEffort?,
    val status: TaskStatus,
    val planId: String?,
    val activeRunId: String?,
    val lastError: String?,
    val errorKind: ErrorKind?,
    val attemptCount: Int,
    val autoResumeOnBoot: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    val startedAt: Long?,
    val finishedAt: Long?,
    val tokensIn: Long,
    val tokensOut: Long,
    val costEstimate: Double,
    /** Set when a run must stop and wait for the user (approval/question). */
    val pendingApprovalJson: String? = null,
)

enum class ExecutionMode(val label: String, val description: String) {
    BUILD("BUILD", "Execute the request now"),
    PLAN("PLAN", "Produce a structured plan only"),
    PLAN_BUILD("PLAN + BUILD", "Plan, then execute the plan"),
}

enum class TaskStatus(val label: String, val isTerminal: Boolean, val isActive: Boolean) {
    QUEUED("Queued", false, false),
    RUNNING("Running", false, true),
    PAUSED("Paused", false, false),
    WAITING_FOR_USER("Needs input", false, false),
    WAITING_FOR_NETWORK("Waiting for network", false, false),
    COMPLETED("Completed", true, false),
    FAILED("Failed", true, false),
    CANCELLED("Cancelled", true, false),
    INTERRUPTED("Interrupted", false, false),
}

enum class ErrorKind {
    NETWORK,
    AUTH,
    RATE_LIMIT,
    PROVIDER,
    TOOL,
    CANCELLED,
    TIMEOUT,
    INTERNAL,
    RECOVERY,
}

enum class ReasoningEffort(val id: String, val label: String, val order: Int) {
    NONE("none", "None", 0),
    LOW("low", "Low", 1),
    MEDIUM("medium", "Medium", 2),
    HIGH("high", "High", 3),
    XHIGH("xhigh", "xHigh", 4),
    MAX("max", "Max", 5);

    companion object {
        /**
         * Maps provider-reported strings to our enum without silently upgrading
         * or downgrading: unknown values yield null so callers can surface them.
         */
        fun fromId(raw: String?): ReasoningEffort? {
            if (raw == null) return null
            val normalized = raw.trim().lowercase().replace("-", "").replace("_", "")
            return when (normalized) {
                "none", "off", "disabled", "minimal", "0", "false" -> NONE
                "low", "1" -> LOW
                "medium", "med", "2" -> MEDIUM
                "high", "3" -> HIGH
                "xhigh", "extrahigh", "veryhigh", "4" -> XHIGH
                "max", "maximum", "5" -> MAX
                else -> null
            }
        }

        val all: List<ReasoningEffort> = entries.sortedBy { it.order }
    }
}

data class RunRecord(
    val id: String,
    val taskId: String,
    val phase: RunPhase,
    val startedAt: Long,
    val endedAt: Long?,
    val exitReason: String?,
    val steps: Int,
    val lastStepSummary: String?,
    val serviceInstanceId: String?,
    val hostBootId: String?,
)

enum class RunPhase { AGENT_LOOP, TOOL, PLAN_GENERATION, RECOVERY }

data class ExecutionEvent(
    val id: String,
    val taskId: String,
    val runId: String?,
    val seq: Long,
    val timestamp: Long,
    val type: EventType,
    val title: String,
    val body: String? = null,
    val metadataJson: String? = null,
)

enum class EventType {
    TASK_CREATED,
    TASK_QUEUED,
    RUN_STARTED,
    MODEL_REQUEST,
    MODEL_STREAM_CHUNK,
    MODEL_RESPONSE,
    REASONING_TEXT,
    TOOL_CALL_STARTED,
    TOOL_CALL_PROGRESS,
    TOOL_CALL_FINISHED,
    TOOL_CALL_FAILED,
    USE_MODEL_STARTED,
    USE_MODEL_FINISHED,
    PLAN_CREATED,
    PLAN_UPDATED,
    CHECKLIST_UPDATED,
    CHECKPOINT_SAVED,
    APPROVAL_REQUESTED,
    APPROVAL_GRANTED,
    APPROVAL_DENIED,
    RECOVERY_RESUMED,
    TASK_PAUSED,
    TASK_RESUMED,
    TASK_COMPLETED,
    TASK_FAILED,
    TASK_CANCELLED,
    WARNING,
    ERROR,
    ASSISTANT_MESSAGE,
    USER_MESSAGE,
}

/** A single chat/agent message; persisted as JSON on the task. */
data class ChatMessage(
    val id: String,
    val role: Role,
    val content: String,
    val timestamp: Long,
    val toolCallId: String? = null,
    val toolName: String? = null,
    val attachments: List<AttachmentRef> = emptyList(),
    val reasoning: String? = null,
    val isPartial: Boolean = false,
    /** Assistant tool calls as JSON array, so the transcript round-trips to the provider. */
    val toolCallsJson: String? = null,
) {
    enum class Role { SYSTEM, USER, ASSISTANT, TOOL }
}

data class AttachmentRef(
    val id: String,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    /** Absolute path inside the app sandbox or a content:// URI granted by SAF. */
    val location: String,
    val isContentUri: Boolean,
)
