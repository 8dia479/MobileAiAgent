package com.mobileaiagent.agent

import com.mobileaiagent.core.json.JsonValue
import com.mobileaiagent.domain.model.ExecutionMode
import com.mobileaiagent.domain.model.Plan
import com.mobileaiagent.domain.model.PlanTaskStatus
import com.mobileaiagent.domain.model.ReasoningEffort

/**
 * Prompt assembly for the Main Model. Kept in one place so behaviour is
 * auditable and testable (see AgentPromptsTest).
 */
object AgentPrompts {

    fun systemPrompt(spec: AgentSpec): String = buildString {
        appendLine(IDENTITY)
        appendLine()
        appendLine("## Execution mode: ${spec.mode.label}")
        appendLine(modeGuidance(spec.mode))
        appendLine()
        appendLine("## Environment")
        appendLine("- Platform: Android ${spec.androidRelease ?: ""} (${spec.deviceName ?: "device"})".trimEnd())
        appendLine("- Workspace project: ${spec.projectName} (id: ${spec.projectId})")
        appendLine("- Reasoning effort selected for this task: ${spec.reasoningEffort.label}")
        if (spec.secondaryModel != null) {
            appendLine("- Delegation model available via `use_model`: ${spec.secondaryModel} (${spec.secondaryReasoningEffort?.label ?: "default"})")
        } else {
            appendLine("- Delegation: no secondary model configured; `use_model` will report unavailable.")
        }
        appendLine("- Date (UTC): ${spec.nowIso.take(10)}")
        appendLine()
        appendLine("## Tools")
        appendLine("Call tools through the native tool-calling interface. Only these tools exist:")
        appendLine(spec.toolManifest)
        appendLine()
        if (spec.plan != null) {
            appendLine("## Active plan (current revision ${spec.plan.revision})")
            appendLine(renderPlanChecklist(spec.plan))
            appendLine()
            appendLine(
                "Follow this plan unless the user asks otherwise. Update task status with `plan_update_task` " +
                    "only after the result is verified. If the request changes scope, replan with `plan_generate`."
            )
            appendLine()
        }
        appendLine(RULES)
    }

    fun renderPlanChecklist(plan: Plan): String = buildString {
        appendLine("Plan \"${plan.title}\" — status ${plan.status.id}, ${plan.completedCount}/${plan.totalCount} tasks completed")
        plan.tasks.forEach { t ->
            val mark = when (t.status) {
                PlanTaskStatus.COMPLETED -> "[x]"
                PlanTaskStatus.IN_PROGRESS -> "[~]"
                PlanTaskStatus.FAILED -> "[!]"
                PlanTaskStatus.BLOCKED -> "[#]"
                PlanTaskStatus.CANCELLED -> "[-]"
                PlanTaskStatus.PENDING -> "[ ]"
            }
            appendLine("- $mark ${t.id}: ${t.title}${if (t.description.isNotBlank()) " — ${t.description.take(200)}" else ""}")
            t.subtasks.forEach { sub ->
                appendLine("    - ${if (sub.status == PlanTaskStatus.COMPLETED) "[x]" else "[ ]"} ${sub.title}")
            }
        }
    }

    private fun modeGuidance(mode: ExecutionMode): String = when (mode) {
        ExecutionMode.BUILD -> """
            |Execute the user's request now using tools, and report what you actually did.
            |If a valid plan exists, work through it in order and keep checklist state current.
        """.trimMargin()
        ExecutionMode.PLAN -> """
            |PLANNING ONLY. Do not create, modify, delete or render project artifacts.
            |Read-only inspection (listing files, reading small text files, inspecting existing
            |assets) is allowed to make the plan accurate. Produce the plan with `plan_generate`
            |and then stop; the user will execute it later.
        """.trimMargin()
        ExecutionMode.PLAN_BUILD -> """
            |First produce a plan with `plan_generate`, then execute it task by task using tools.
            |After each task, verify the real output (inspect files/results) before calling
            |`plan_update_task` with status completed. Finish with a short report.
        """.trimMargin()
    }

    val IDENTITY = """
        |You are MobileAIAgent, a general-purpose engineering agent running inside an Android app.
        |You can write software, create and edit documents, process data, build CAD/3D assets,
        |edit images, research the web, and manage project files — but only through the tools listed
        |below. Anything that requires a tool you do not have must be reported as unsupported
        |rather than attempted or pretended.
    """.trimMargin()

    val RULES = """
        |## Operating rules
        |1. Never claim a file or artifact exists unless a tool result confirmed it. No fabricated outputs.
        |2. Prefer small, verifiable steps. After a write, read back or list to verify.
        |3. Give complete file contents to `file_write`; it overwrites. Use `file_append` to extend.
        |4. Keep the plan checklist honest: mark a task completed only when verification passed.
        |5. Ask the user (finish your turn with a question) before destructive actions such as
        |   deleting project files or overwriting user documents, unless they explicitly requested it.
        |6. Do not invent tool names or argument fields; invalid calls are rejected.
        |7. Do not assume a model or tool supports images/vision; check the tool list.
        |8. When the request is ambiguous, make a reasonable assumption, state it, and continue —
        |   except when the ambiguity changes what gets destroyed.
        |9. Use `use_model` only for sharply-scoped subtasks (e.g. "summarise this transcript"),
        |   passing the exact context needed. You remain responsible for verifying its output.
        |10. Finish each turn with either a tool call or a final user-facing answer.
    """.trimMargin()

    /**
     * Extra instruction appended to a provider error retry so the model can
     * self-correct malformed tool calls instead of looping.
     */
    fun toolErrorFeedback(toolName: String, error: String): String = buildString {
        appendLine("Tool `$toolName` failed or was rejected:")
        appendLine("```")
        appendLine(error.take(1500))
        appendLine("```")
        appendLine("Fix the arguments or choose a different approach, then continue. Do not repeat identical failing calls.")
    }

    fun recoveryContinuation(completedSummary: String, nextTaskTitle: String?): String = buildString {
        appendLine("System notice: this task was resumed after the app process was restarted.")
        appendLine("Verified progress so far:")
        appendLine(completedSummary.ifBlank { "(none recorded)" })
        if (nextTaskTitle != null) appendLine("Next plan task: $nextTaskTitle")
        appendLine("Continue from here. Do NOT repeat side-effecting operations listed as completed.")
    }

    /** The structured output schema the plan generator requires. */
    val PLAN_JSON_SCHEMA: JsonValue = run {
        fun status() = JsonValue.obj(
            "type" to JsonValue.of("string"),
            "enum" to JsonValue.Arr(
                listOf("pending", "in_progress", "completed", "failed", "blocked", "cancelled")
                    .map { JsonValue.of(it) }
            ),
        )
        fun subtask() = JsonValue.obj(
            "type" to JsonValue.of("object"),
            "properties" to JsonValue.obj(
                "id" to JsonValue.obj("type" to JsonValue.of("string")),
                "title" to JsonValue.obj("type" to JsonValue.of("string")),
                "status" to status(),
            ),
            "required" to JsonValue.Arr(listOf("id", "title", "status").map { JsonValue.of(it) }),
        )
        JsonValue.obj(
            "type" to JsonValue.of("object"),
            "properties" to JsonValue.obj(
                "title" to JsonValue.obj("type" to JsonValue.of("string")),
                "goal" to JsonValue.obj("type" to JsonValue.of("string")),
                "tasks" to JsonValue.obj(
                    "type" to JsonValue.of("array"),
                    "items" to JsonValue.obj(
                        "type" to JsonValue.of("object"),
                        "properties" to JsonValue.obj(
                            "id" to JsonValue.obj("type" to JsonValue.of("string")),
                            "title" to JsonValue.obj("type" to JsonValue.of("string")),
                            "description" to JsonValue.obj("type" to JsonValue.of("string")),
                            "verification" to JsonValue.obj("type" to JsonValue.of("string")),
                            "status" to status(),
                            "depends_on" to JsonValue.obj(
                                "type" to JsonValue.of("array"),
                                "items" to JsonValue.obj("type" to JsonValue.of("string")),
                            ),
                            "subtasks" to JsonValue.obj(
                                "type" to JsonValue.of("array"),
                                "items" to subtask(),
                            ),
                        ),
                        "required" to JsonValue.Arr(
                            listOf("id", "title", "description", "status").map { JsonValue.of(it) }
                        ),
                    ),
                ),
            ),
            "required" to JsonValue.Arr(listOf("title", "goal", "tasks").map { JsonValue.of(it) }),
        )
    }
}

/** Immutable description of one task run handed to the agent loop. */
data class AgentSpec(
    val taskId: String,
    val runId: String,
    val projectId: String,
    val projectName: String,
    val mode: ExecutionMode,
    val reasoningEffort: ReasoningEffort,
    val secondaryModel: String?,
    val secondaryReasoningEffort: ReasoningEffort?,
    val toolManifest: String,
    val plan: Plan?,
    val deviceName: String? = null,
    val androidRelease: String? = null,
    val nowIso: String = java.time.Instant.now().toString(),
)
