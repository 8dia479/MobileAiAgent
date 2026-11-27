package com.mobileaiagent.data.db

import androidx.room.*

@Entity(tableName = "tasks")
data class TaskEntity(
    @PrimaryKey val id: String,
    val projectId: String?,
    val projectName: String?,
    val title: String,
    val request: String,
    val mode: String,
    val mainModel: String,
    val mainProvider: String,
    val mainReasoningEffort: String,
    val secondaryModel: String?,
    val secondaryProvider: String?,
    val secondaryReasoningEffort: String?,
    val status: String,
    val planId: String?,
    val activeRunId: String?,
    val lastError: String?,
    val errorKind: String?,
    val attemptCount: Int,
    val autoResumeOnBoot: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    val startedAt: Long?,
    val finishedAt: Long?,
    val tokensIn: Long,
    val tokensOut: Long,
    val costEstimate: Double,
    val pendingApprovalJson: String?,
)

@Entity(tableName = "execution_events", indices = [Index("taskId"), Index("timestamp")])
data class EventEntity(
    @PrimaryKey val id: String,
    val taskId: String,
    val runId: String?,
    val seq: Long,
    val timestamp: Long,
    val type: String,
    val title: String,
    val body: String?,
    val metadataJson: String?,
)

@Entity(tableName = "runs", indices = [Index("taskId")])
data class RunEntity(
    @PrimaryKey val id: String,
    val taskId: String,
    val phase: String,
    val startedAt: Long,
    val endedAt: Long?,
    val exitReason: String?,
    val steps: Int,
    val lastStepSummary: String?,
    val serviceInstanceId: String?,
    val hostBootId: String?,
)

@Entity(tableName = "messages", indices = [Index("taskId"), Index("timestamp")])
data class MessageEntity(
    @PrimaryKey val id: String,
    val taskId: String,
    val role: String,
    val content: String,
    val timestamp: Long,
    val toolCallId: String?,
    val toolName: String?,
    val attachmentsJson: String?,
    val reasoning: String?,
    val isPartial: Boolean,
    val toolCallsJson: String?,
)

@Entity(tableName = "tool_journal", indices = [Index("taskId"), Index("callId")])
data class JournalEntity(
    @PrimaryKey val callId: String,
    val taskId: String,
    val runId: String,
    val toolId: String,
    val argsJson: String,
    val mutagenic: Boolean,
    val status: String,
    val startedAt: Long,
    val finishedAt: Long?,
    val resultSummary: String?,
)

@Entity(tableName = "checkpoints")
data class CheckpointEntity(
    @PrimaryKey val taskId: String,
    val createdAt: Long,
    val lastPlanTaskId: String?,
    val lastCompletedStepSummary: String?,
    val assistantDraft: String?,
    val planRevision: Int?,
    val schemaVersion: Int,
)

@Entity(tableName = "model_cache", primaryKeys = ["providerId", "modelId"])
data class ModelEntity(
    val providerId: String,
    val modelId: String,
    val displayName: String,
    val contextWindow: Int?,
    val maxOutputTokens: Int?,
    val vision: Boolean?,
    val toolCalling: Boolean?,
    val reasoning: Boolean?,
    val structuredOutput: Boolean?,
    val fileInput: Boolean?,
    val capabilitySource: String,
    val retired: Boolean,
    val fetchedAt: Long,
    val isManual: Boolean,
    val rawJson: String?,
)

@Entity(tableName = "capability_overrides", primaryKeys = ["providerId", "modelId"])
data class CapabilityOverrideEntity(
    val providerId: String,
    val modelId: String,
    val vision: Boolean?,
    val toolCalling: Boolean?,
    val reasoning: Boolean?,
    val structuredOutput: Boolean?,
    val fileInput: Boolean?,
)

@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String?,
    val rootPath: String,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(tableName = "plugins")
data class PluginEntity(
    @PrimaryKey val id: String,
    val name: String,
    val version: String,
    val kind: String,
    val enabled: Boolean,
    val permissionsCsv: String,
    val manifestJson: String,
    val installedAt: Long,
    val sha256: String?,
)
