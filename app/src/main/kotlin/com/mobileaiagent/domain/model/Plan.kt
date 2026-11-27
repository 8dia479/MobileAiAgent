package com.mobileaiagent.domain.model

import com.mobileaiagent.core.json.JsonValue

/**
 * Structured plan JSON (see docs/ARCHITECTURE.md §Plan schema).
 * Plans are stored as real files under Projects/<project>/Plans/active.plan.json,
 * with revision history under Plans/history/.
 */
data class Plan(
    val planVersion: Int,
    val planId: String,
    val title: String,
    val status: PlanStatus,
    val createdAt: String,
    val updatedAt: String,
    val revision: Int,
    val projectId: String?,
    val taskId: String?,
    val goal: String,
    val tasks: List<PlanTask>,
    val notes: String? = null,
) {
    val allTasks: List<PlanTask> get() = tasks

    fun task(id: String): PlanTask? = tasks.firstOrNull { it.id == id }

    val completedCount: Int get() = tasks.count { it.status == PlanTaskStatus.COMPLETED }
    val totalCount: Int get() = tasks.size
    val progressFraction: Float get() = if (totalCount == 0) 0f else completedCount.toFloat() / totalCount

    fun toJson(): JsonValue = JsonValue.Obj(
        linkedMapOf(
            "plan_version" to JsonValue.of(planVersion),
            "plan_id" to JsonValue.of(planId),
            "title" to JsonValue.of(title),
            "status" to JsonValue.of(status.id),
            "created_at" to JsonValue.of(createdAt),
            "updated_at" to JsonValue.of(updatedAt),
            "revision" to JsonValue.of(revision),
            "project_id" to JsonValue.of(projectId),
            "task_id" to JsonValue.of(taskId),
            "goal" to JsonValue.of(goal),
            "notes" to JsonValue.of(notes),
            "tasks" to JsonValue.Arr(tasks.map { it.toJson() }),
        )
    )

    companion object {
        fun fromJson(json: JsonValue): Plan = Plan(
            planVersion = json.intOr("plan_version", 1),
            planId = json.stringOr("plan_id", ""),
            title = json.stringOr("title", "Untitled plan"),
            status = PlanStatus.fromId(json.stringOr("status", "pending")),
            createdAt = json.stringOr("created_at", ""),
            updatedAt = json.stringOr("updated_at", ""),
            revision = json.intOr("revision", 1),
            projectId = json["project_id"].asStringOrNull(),
            taskId = json["task_id"].asStringOrNull(),
            goal = json.stringOr("goal", ""),
            tasks = json["tasks"].asArrayOrNull()?.map { PlanTask.fromJson(it) } ?: emptyList(),
            notes = json["notes"].asStringOrNull(),
        )
    }
}

enum class PlanStatus(val id: String) {
    DRAFT("draft"),
    ACTIVE("active"),
    COMPLETED("completed"),
    FAILED("failed"),
    CANCELLED("cancelled"),
    SUPERSEDED("superseded");

    companion object {
        fun fromId(id: String): PlanStatus = entries.firstOrNull { it.id == id } ?: DRAFT
    }
}

data class PlanTask(
    val id: String,
    val title: String,
    val description: String,
    val status: PlanTaskStatus,
    val subtasks: List<PlanSubtask>,
    val verification: String? = null,
    val result: String? = null,
    val startedAt: String? = null,
    val completedAt: String? = null,
    val attemptCount: Int = 0,
    val dependsOn: List<String> = emptyList(),
) {
    val isFinished: Boolean
        get() = status == PlanTaskStatus.COMPLETED || status == PlanTaskStatus.CANCELLED

    fun toJson(): JsonValue = JsonValue.Obj(
        linkedMapOf(
            "id" to JsonValue.of(id),
            "title" to JsonValue.of(title),
            "description" to JsonValue.of(description),
            "status" to JsonValue.of(status.id),
            "verification" to JsonValue.of(verification),
            "result" to JsonValue.of(result),
            "started_at" to JsonValue.of(startedAt),
            "completed_at" to JsonValue.of(completedAt),
            "attempt_count" to JsonValue.of(attemptCount),
            "depends_on" to JsonValue.Arr(dependsOn.map { JsonValue.of(it) }),
            "subtasks" to JsonValue.Arr(subtasks.map { it.toJson() }),
        )
    )

    companion object {
        fun fromJson(json: JsonValue): PlanTask = PlanTask(
            id = json.stringOr("id", ""),
            title = json.stringOr("title", ""),
            description = json.stringOr("description", ""),
            status = PlanTaskStatus.fromId(json.stringOr("status", "pending")),
            subtasks = json["subtasks"].asArrayOrNull()?.map { PlanSubtask.fromJson(it) } ?: emptyList(),
            verification = json["verification"].asStringOrNull(),
            result = json["result"].asStringOrNull(),
            startedAt = json["started_at"].asStringOrNull(),
            completedAt = json["completed_at"].asStringOrNull(),
            attemptCount = json.intOr("attempt_count", 0),
            dependsOn = json.stringList("depends_on"),
        )
    }
}

data class PlanSubtask(
    val id: String,
    val title: String,
    val status: PlanTaskStatus,
) {
    fun toJson(): JsonValue = JsonValue.Obj(
        linkedMapOf(
            "id" to JsonValue.of(id),
            "title" to JsonValue.of(title),
            "status" to JsonValue.of(status.id),
        )
    )

    companion object {
        fun fromJson(json: JsonValue): PlanSubtask = PlanSubtask(
            id = json.stringOr("id", ""),
            title = json.stringOr("title", ""),
            status = PlanTaskStatus.fromId(json.stringOr("status", "pending")),
        )
    }
}

enum class PlanTaskStatus(val id: String, val isDone: Boolean) {
    PENDING("pending", false),
    IN_PROGRESS("in_progress", false),
    COMPLETED("completed", true),
    FAILED("failed", false),
    BLOCKED("blocked", false),
    CANCELLED("cancelled", true);

    companion object {
        fun fromId(id: String): PlanTaskStatus = entries.firstOrNull { it.id == id } ?: PENDING
    }
}
