package com.mobileaiagent.tools.builtin

import com.mobileaiagent.agent.AgentPrompts
import com.mobileaiagent.agent.plan.PlanManager
import com.mobileaiagent.core.json.JsonParser
import com.mobileaiagent.core.json.JsonValue
import com.mobileaiagent.domain.model.Plan
import com.mobileaiagent.domain.model.PlanTaskStatus
import com.mobileaiagent.tools.spec.*

/**
 * `plan_generate` — creates or revises the structured plan file. Available in all
 * modes (PLAN needs it; BUILD uses it for replanning). Never touches artifacts.
 */
class PlanGenerateTool(
    private val planManager: PlanManager,
    private val onPlanChanged: suspend (taskId: String, plan: Plan) -> Unit = { _, _ -> },
) : AgentTool {
    override val id = "plan_generate"
    override val name = "Create or revise plan"
    override val description =
        "Write the structured JSON plan for this project (Plans/active.plan.json) and archive the previous revision. " +
            "Provide tasks with concrete, verifiable steps. Use mode='replan' to revise an existing plan while keeping completed tasks."
    override val version = "1.0.0"
    override val category = ToolCategory.PLANNING
    override val inputSchema = schema {
        requiredString("title", "Plan title.")
        requiredString("goal", "One-paragraph statement of the end result.")
        requiredArray("tasks", "Array of task objects: {id, title, description, verification, depends_on[], subtasks[{title}]}.")
        optionalString("mode", "create | replan (default create).", listOf("create", "replan"))
        optionalString("notes", "Optional planning notes.")
    }
    override val executionEnvironment = ExecutionEnvironment.LOCAL_ANDROID
    override val permissions = listOf(ToolPermission.FILES_WRITE)
    override val mutagenic = true
    override val cancellationSupported = false
    override val maxResultChars = 6_000

    override suspend fun execute(input: JsonValue, ctx: ToolContext): ToolResult {
        val args = ToolArgs(input)
        val root = ctx.projectRoot ?: return FileListTool.NoWorkspace
        val mode = args.optionalString("mode") ?: "create"
        val payload = JsonValue.obj(
            "title" to JsonValue.of(args.string("title")),
            "goal" to JsonValue.of(args.string("goal")),
            "tasks" to args.objectArg("tasks"),
            "notes" to JsonValue.of(args.optionalString("notes")),
        )
        if (payload["tasks"].asArrayOrNull()?.isEmpty() != false) {
            return ToolResult.Failure("'tasks' must be a non-empty array", errorKind = "invalid_arguments")
        }
        return try {
            val previous = planManager.readActive(root)
            val plan = if (mode == "replan") {
                planManager.replan(root, ctx.projectId, ctx.taskId, payload, previous)
            } else {
                planManager.createFromModelJson(root, ctx.projectId, ctx.taskId, payload, previous)
            }
            onPlanChanged(ctx.taskId, plan)
            ToolResult.Success(
                buildString {
                    appendLine("Plan saved: ${plan.planId} revision ${plan.revision} (${plan.tasks.size} tasks)")
                    appendLine(AgentPrompts.renderPlanChecklist(plan))
                },
                structured = plan.toJson(),
            )
        } catch (e: Exception) {
            ToolResult.Failure("Could not save plan: ${e.message}", errorKind = "io")
        }
    }
}

class PlanGetTool(private val planManager: PlanManager) : AgentTool {
    override val id = "plan_get"
    override val name = "Read plan"
    override val description = "Read the current plan (active.plan.json) and its checklist state."
    override val version = "1.0.0"
    override val category = ToolCategory.PLANNING
    override val inputSchema = schema { }
    override val executionEnvironment = ExecutionEnvironment.LOCAL_ANDROID
    override val permissions = listOf(ToolPermission.FILES_READ)
    override val mutagenic = false
    override val cancellationSupported = false
    override val maxResultChars = 20_000

    override suspend fun execute(input: JsonValue, ctx: ToolContext): ToolResult {
        val root = ctx.projectRoot ?: return FileListTool.NoWorkspace
        val plan = planManager.readActive(root)
            ?: return ToolResult.Success("No plan exists for this project yet.")
        return ToolResult.Success(plan.toJson().toJson(), structured = plan.toJson())
    }
}

/**
 * `plan_update_task` — the only way checklist state changes. The tool refuses to
 * mark a task completed without a verification note, enforcing the spec rule that
 * "completed" reflects verified reality, not the model's claim.
 */
class PlanUpdateTaskTool(
    private val planManager: PlanManager,
    private val onPlanChanged: suspend (taskId: String, plan: Plan) -> Unit = { _, _ -> },
) : AgentTool {
    override val id = "plan_update_task"
    override val name = "Update checklist"
    override val description =
        "Update one plan task's status. status='completed' requires evidence in 'result' (what you verified). " +
            "Allowed statuses: in_progress, completed, failed, blocked, cancelled, pending."
    override val version = "1.0.0"
    override val category = ToolCategory.PLANNING
    override val inputSchema = schema {
        requiredString("task_id", "Plan task id, e.g. T2.")
        requiredString(
            "status",
            "New status.",
            listOf("pending", "in_progress", "completed", "failed", "blocked", "cancelled"),
        )
        optionalString("result", "Evidence/output for the task. Required when marking completed.")
        optionalString("subtask", "Optional subtask title to also mark completed.")
    }
    override val executionEnvironment = ExecutionEnvironment.LOCAL_ANDROID
    override val permissions = listOf(ToolPermission.FILES_WRITE)
    override val mutagenic = true
    override val cancellationSupported = false
    override val maxResultChars = 6_000

    override suspend fun execute(input: JsonValue, ctx: ToolContext): ToolResult {
        val args = ToolArgs(input)
        val root = ctx.projectRoot ?: return FileListTool.NoWorkspace
        val taskId = args.string("task_id")
        val statusRaw = args.string("status").lowercase()
        val status = PlanTaskStatus.fromId(statusRaw)
        if (status.id != statusRaw) {
            return ToolResult.Failure("Unknown status '$statusRaw'", errorKind = "invalid_arguments")
        }
        val result = args.optionalString("result")
        if (status == PlanTaskStatus.COMPLETED && result.isNullOrBlank()) {
            return ToolResult.Failure(
                "Cannot mark $taskId completed without evidence. Pass 'result' describing what you verified " +
                    "(e.g. 'file_list shows model.stl, 240 KB').",
                errorKind = "verification_required",
            )
        }
        val plan = planManager.updateTaskStatus(root, taskId, status, result, args.optionalString("subtask"))
            ?: return ToolResult.Failure("No active plan", errorKind = "not_found")
        if (plan.task(taskId) == null) {
            return ToolResult.Failure("No task '$taskId' in plan. Ids: ${plan.tasks.joinToString { it.id }}", errorKind = "not_found")
        }
        onPlanChanged(ctx.taskId, plan)
        return ToolResult.Success(
            "Updated $taskId → ${status.id}. Plan progress: ${plan.completedCount}/${plan.totalCount}.\n" +
                AgentPrompts.renderPlanChecklist(plan),
            structured = plan.toJson(),
        )
    }
}

/**
 * `request_approval` — pauses the task until the user approves. The runner turns
 * a successful call into TaskStatus.WAITING_FOR_USER.
 */
class RequestApprovalTool : AgentTool {
    override val id = "request_approval"
    override val name = "Request user approval"
    override val description =
        "Stop and ask the user to approve a sensitive or destructive next step. Use before deleting user data, " +
            "overwriting existing documents, uploading private files, or any action with irreversible side effects."
    override val version = "1.0.0"
    override val category = ToolCategory.SYSTEM
    override val inputSchema = schema {
        requiredString("summary", "What you want to do and why.")
        optionalString("details", "Concrete details: files affected, commands, irreversible consequences.")
        optionalString(
            "risk",
            "low | medium | high | critical (default medium).",
            listOf("low", "medium", "high", "critical"),
        )
    }
    override val executionEnvironment = ExecutionEnvironment.LOCAL_WITH_APPROVAL
    override val permissions = emptyList<ToolPermission>()
    override val mutagenic = false
    override val cancellationSupported = false
    override val maxResultChars = 2_000

    override suspend fun execute(input: JsonValue, ctx: ToolContext): ToolResult {
        val args = ToolArgs(input)
        val summary = args.string("summary")
        val risk = when (args.optionalString("risk")?.lowercase()) {
            "low" -> RiskLevel.LOW
            "high" -> RiskLevel.HIGH
            "critical" -> RiskLevel.CRITICAL
            else -> RiskLevel.MEDIUM
        }
        return ToolResult.Success(
            "Approval requested: $summary (risk: ${risk.name.lowercase()}). " +
                "The task is now paused until the user responds.",
            structured = JsonValue.obj(
                "status" to JsonValue.of("awaiting_user"),
                "summary" to JsonValue.of(summary),
                "details" to JsonValue.of(args.optionalString("details")),
                "risk" to JsonValue.of(risk.name.lowercase()),
            ),
        )
    }
}

class TaskNoteTool : AgentTool {
    override val id = "task_note"
    override val name = "Record note"
    override val description = "Record a short note/memo in the task timeline (useful for progress commentary without ending the turn)."
    override val version = "1.0.0"
    override val category = ToolCategory.SYSTEM
    override val inputSchema = schema { requiredString("note", "Text to record.") }
    override val executionEnvironment = ExecutionEnvironment.LOCAL_ANDROID
    override val permissions = emptyList<ToolPermission>()
    override val mutagenic = false
    override val cancellationSupported = false
    override val maxResultChars = 500

    override suspend fun execute(input: JsonValue, ctx: ToolContext): ToolResult {
        val note = ToolArgs(input).string("note")
        ctx.log("note: $note")
        return ToolResult.Success("Note recorded.")
    }
}

class ProjectSummaryTool : AgentTool {
    override val id = "project_summary"
    override val name = "Project summary"
    override val description = "Summarise the workspace: folder structure, file counts by type, total size, and whether a plan exists."
    override val version = "1.0.0"
    override val category = ToolCategory.WORKSPACE
    override val inputSchema = schema { }
    override val executionEnvironment = ExecutionEnvironment.LOCAL_ANDROID
    override val permissions = listOf(ToolPermission.FILES_READ)
    override val mutagenic = false
    override val cancellationSupported = false
    override val maxResultChars = 12_000

    override suspend fun execute(input: JsonValue, ctx: ToolContext): ToolResult {
        val ws = ctx.workspace ?: return FileListTool.NoWorkspace
        val entries = ws.list("", recursive = true)
        val byExt = entries.filter { !it.isDirectory }.groupBy { it.name.substringAfterLast('.', "none").lowercase() }
            .mapValues { it.value.size }
            .toList().sortedByDescending { it.second }
        val totalBytes = entries.sumOf { it.sizeBytes }
        val topDirs = entries.filter { it.isDirectory }.groupBy { it.path.substringBefore('/') }
        val sb = StringBuilder()
        sb.appendLine("Project: ${ctx.projectId}")
        sb.appendLine("Files: ${entries.count { !it.isDirectory }}, folders: ${entries.count { it.isDirectory }}, total ${com.mobileaiagent.core.util.humanBytes(totalBytes)}")
        sb.appendLine("By type: ${byExt.take(12).joinToString { (k, v) -> "$k:$v" }}")
        sb.appendLine("Top-level folders: ${topDirs.keys.filter { it.isNotEmpty() }.joinToString()}")
        val plan = ctx.projectRoot?.let { java.io.File(it, "Plans/active.plan.json") }
        sb.appendLine("Plan file: ${if (plan?.isFile == true) "present" else "none"}")
        return ToolResult.Success(sb.toString())
    }
}
