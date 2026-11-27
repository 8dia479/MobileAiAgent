package com.mobileaiagent.agent.plan

import com.mobileaiagent.core.json.JsonParser
import com.mobileaiagent.core.json.JsonValue
import com.mobileaiagent.core.log.AgentLog
import com.mobileaiagent.core.util.Clock
import com.mobileaiagent.core.util.IdGenerator
import com.mobileaiagent.core.util.SystemClock
import com.mobileaiagent.core.util.safePathSegment
import com.mobileaiagent.domain.model.Plan
import com.mobileaiagent.domain.model.PlanStatus
import com.mobileaiagent.domain.model.PlanSubtask
import com.mobileaiagent.domain.model.PlanTask
import com.mobileaiagent.domain.model.PlanTaskStatus
import java.io.File

/**
 * Owns the structured plan lifecycle for a project:
 *
 *   Projects/<project>/Plans/active.plan.json
 *   Projects/<project>/Plans/history/plan-<ts>-r<rev>.json
 *
 * Plans are real files (not DB blobs) so they are inspectable, portable and
 * recoverable after reinstalls. Writes are atomic (temp file + rename).
 */
class PlanManager(
    private val clock: Clock = SystemClock,
    private val ids: IdGenerator = IdGenerator.random(),
    private val maxHistory: Int = 30,
) {

    fun plansDir(projectRoot: File): File = File(projectRoot, "Plans").apply { mkdirs() }
    fun activePlanFile(projectRoot: File): File = File(plansDir(projectRoot), "active.plan.json")
    fun historyDir(projectRoot: File): File = File(plansDir(projectRoot), "history").apply { mkdirs() }

    fun readActive(projectRoot: File): Plan? {
        val file = activePlanFile(projectRoot)
        if (!file.isFile) return null
        return try {
            Plan.fromJson(JsonParser.parse(file.readText()))
        } catch (e: Exception) {
            AgentLog.e("PlanManager", "Active plan is unreadable: ${file.absolutePath}", e)
            null
        }
    }

    /**
     * Persists a plan from model-produced JSON (see AgentPrompts.PLAN_JSON_SCHEMA).
     * Assigns ids/timestamps itself; model-supplied ids are kept when unique.
     */
    fun createFromModelJson(
        projectRoot: File,
        projectId: String,
        taskId: String?,
        modelJson: JsonValue,
        previous: Plan? = null,
    ): Plan {
        val now = clock.nowIso()
        val seen = mutableSetOf<String>()
        val tasks = (modelJson["tasks"].asArrayOrNull() ?: emptyList()).mapIndexed { index, t ->
            var id = t.stringOr("id", "").ifBlank { "T${index + 1}" }
            while (!seen.add(id)) id = "${id}_${seen.size}"
            val taskStatus = PlanTaskStatus.fromId(t.stringOr("status", "pending"))
            PlanTask(
                id = id,
                title = t.stringOr("title", "Task ${index + 1}"),
                description = t.stringOr("description", ""),
                // A brand-new plan never starts with completed tasks: completion is
                // evidence-based and must be set through execution/verification.
                status = if (taskStatus == PlanTaskStatus.COMPLETED) PlanTaskStatus.PENDING else taskStatus,
                verification = t["verification"].asStringOrNull(),
                dependsOn = t.stringList("depends_on"),
                subtasks = (t["subtasks"].asArrayOrNull() ?: emptyList()).mapIndexed { subIndex, sub ->
                    PlanSubtask(
                        id = sub.stringOr("id", "S${index + 1}.${subIndex + 1}"),
                        title = sub.stringOr("title", "Step ${subIndex + 1}"),
                        status = PlanTaskStatus.PENDING,
                    )
                },
            )
        }.ifEmpty {
            listOf(
                PlanTask(
                    id = "T1",
                    title = "Analyse the request",
                    description = modelJson.stringOr("goal", ""),
                    status = PlanTaskStatus.PENDING,
                    subtasks = emptyList(),
                )
            )
        }

        val plan = Plan(
            planVersion = 1,
            planId = previous?.planId ?: ids.newId("plan"),
            title = modelJson.stringOr("title", "Plan for project"),
            status = PlanStatus.ACTIVE,
            createdAt = previous?.createdAt ?: now,
            updatedAt = now,
            revision = (previous?.revision ?: 0) + 1,
            projectId = projectId,
            taskId = taskId,
            goal = modelJson.stringOr("goal", ""),
            tasks = tasks,
        )
        if (previous != null) archive(projectRoot, previous)
        writeActive(projectRoot, plan)
        AgentLog.i("PlanManager", "Saved plan ${plan.planId} r${plan.revision} with ${plan.tasks.size} tasks")
        return plan
    }

    /**
     * Replan: keeps completed tasks (their evidence is real), rebuilds the rest.
     * The caller must confirm significant scope changes with the user in chat;
     * this method only guarantees completed work is never thrown away.
     */
    fun replan(
        projectRoot: File,
        projectId: String,
        taskId: String?,
        newModelJson: JsonValue,
        previous: Plan?,
    ): Plan {
        val previousPlan = previous ?: readActive(projectRoot)
        val completedTitles = previousPlan?.tasks
            ?.filter { it.status == PlanTaskStatus.COMPLETED }
            ?.associateBy { it.title.lowercase().trim() }
            ?: emptyMap()

        val now = clock.nowIso()
        val seen = mutableSetOf<String>()
        val tasks = (newModelJson["tasks"].asArrayOrNull() ?: emptyList()).mapIndexed { index, t ->
            var id = t.stringOr("id", "").ifBlank { "T${index + 1}" }
            while (!seen.add(id)) id = "${id}_${seen.size}"
            val title = t.stringOr("title", "Task ${index + 1}")
            val preserved = completedTitles[title.lowercase().trim()]
            PlanTask(
                id = id,
                title = title,
                description = t.stringOr("description", ""),
                status = preserved?.status ?: PlanTaskStatus.PENDING,
                verification = t["verification"].asStringOrNull() ?: preserved?.verification,
                result = preserved?.result,
                completedAt = preserved?.completedAt,
                dependsOn = t.stringList("depends_on"),
                subtasks = if (preserved != null) preserved.subtasks else (t["subtasks"].asArrayOrNull() ?: emptyList())
                    .mapIndexed { subIndex, sub ->
                        PlanSubtask(
                            id = sub.stringOr("id", "S${index + 1}.${subIndex + 1}"),
                            title = sub.stringOr("title", "Step ${subIndex + 1}"),
                            status = PlanTaskStatus.PENDING,
                        )
                    },
            )
        }
        val plan = Plan(
            planVersion = 1,
            planId = previousPlan?.planId ?: ids.newId("plan"),
            title = newModelJson.stringOr("title", previousPlan?.title ?: "Plan"),
            status = PlanStatus.ACTIVE,
            createdAt = previousPlan?.createdAt ?: now,
            updatedAt = now,
            revision = (previousPlan?.revision ?: 0) + 1,
            projectId = projectId,
            taskId = taskId,
            goal = newModelJson.stringOr("goal", previousPlan?.goal ?: ""),
            tasks = tasks,
            notes = "Replanned at $now",
        )
        if (previousPlan != null) archive(projectRoot, previousPlan)
        writeActive(projectRoot, plan)
        return plan
    }

    fun updateTaskStatus(
        projectRoot: File,
        taskIdInPlan: String,
        newStatus: PlanTaskStatus,
        result: String? = null,
        subtaskTitle: String? = null,
    ): Plan? {
        val plan = readActive(projectRoot) ?: return null
        val now = clock.nowIso()
        var found = false
        val tasks = plan.tasks.map { task ->
            if (task.id != taskIdInPlan) return@map task
            found = true
            val updatedSubs = if (subtaskTitle != null && newStatus == PlanTaskStatus.COMPLETED) {
                task.subtasks.map { sub ->
                    if (sub.title.equals(subtaskTitle, ignoreCase = true)) sub.copy(status = PlanTaskStatus.COMPLETED) else sub
                }
            } else task.subtasks
            task.copy(
                status = newStatus,
                result = result ?: task.result,
                startedAt = task.startedAt ?: if (newStatus == PlanTaskStatus.IN_PROGRESS) now else null,
                completedAt = if (newStatus == PlanTaskStatus.COMPLETED) now else task.completedAt,
                attemptCount = if (newStatus == PlanTaskStatus.IN_PROGRESS) task.attemptCount + 1 else task.attemptCount,
                subtasks = updatedSubs,
            )
        }
        if (!found) return plan
        val allDone = tasks.all { it.status == PlanTaskStatus.COMPLETED || it.status == PlanTaskStatus.CANCELLED }
        val anyFailed = tasks.any { it.status == PlanTaskStatus.FAILED }
        val status = when {
            allDone -> PlanStatus.COMPLETED
            anyFailed && tasks.none { it.status == PlanTaskStatus.PENDING || it.status == PlanTaskStatus.IN_PROGRESS } -> PlanStatus.FAILED
            else -> PlanStatus.ACTIVE
        }
        val newPlan = plan.copy(tasks = tasks, updatedAt = now, status = status, revision = plan.revision)
        // Task completion does not create a new plan revision (it's execution state,
        // not a scope change), but it is still persisted atomically.
        writeActive(projectRoot, newPlan)
        return newPlan
    }

    fun writeActive(projectRoot: File, plan: Plan) {
        val file = activePlanFile(projectRoot)
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(plan.toJson().toJson())
        if (file.exists()) file.delete()
        if (!tmp.renameTo(file)) {
            // Fallback when rename across volume fails (rare, but keep data).
            file.writeText(tmp.readText())
            tmp.delete()
        }
    }

    fun archive(projectRoot: File, plan: Plan) {
        val dir = historyDir(projectRoot)
        val stamp = java.time.Instant.now().toString().replace(":", "").take(15)
        val name = "plan-${stamp}-r${plan.revision}.json"
        runCatching {
            File(dir, name).writeText(plan.toJson().toJson())
            val files = dir.listFiles { f -> f.name.endsWith(".json") }?.sortedBy { it.name } ?: emptyList()
            if (files.size > maxHistory) files.take(files.size - maxHistory).forEach { it.delete() }
        }.onFailure { AgentLog.w("PlanManager", "Could not archive plan revision: ${it.message}") }
    }

    fun history(projectRoot: File): List<Plan> {
        val dir = historyDir(projectRoot)
        return (dir.listFiles { f -> f.name.endsWith(".json") } ?: emptyArray())
            .sortedByDescending { it.name }
            .mapNotNull { f ->
                runCatching { Plan.fromJson(JsonParser.parse(f.readText())) }.getOrNull()
            }
    }

    fun markPlanStatus(projectRoot: File, status: PlanStatus): Plan? {
        val plan = readActive(projectRoot) ?: return null
        val updated = plan.copy(status = status, updatedAt = clock.nowIso())
        writeActive(projectRoot, updated)
        return updated
    }

    companion object {
        fun projectLayout(projectRoot: File): List<String> = listOf(
            "Plans", "CAD", "3D", "Images", "Documents", "Animations", "Media", "Source", "Downloads",
        ).map { safePathSegment(it) }
    }
}
