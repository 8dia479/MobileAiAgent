package com.mobileaiagent.data.db

import com.mobileaiagent.domain.model.ProjectInfo
import com.mobileaiagent.domain.repo.ModelCatalogRepository
import com.mobileaiagent.domain.repo.ProjectRepository
import com.mobileaiagent.domain.repo.RefreshOutcome
import com.mobileaiagent.providers.CapabilitySource
import com.mobileaiagent.providers.ModelCapabilities
import com.mobileaiagent.providers.ModelDescriptor
import com.mobileaiagent.core.util.safePathSegment
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Dynamic model discovery cache (spec §3.1): discovery → cache → manual entry. */
class RoomModelCatalog(
    private val dao: ModelDao,
    private val providerFor: suspend (String) -> com.mobileaiagent.providers.AiProvider?,
) : ModelCatalogRepository {

    override suspend fun cachedModels(providerId: String): List<ModelDescriptor> {
        return dao.list(providerId).map { e ->
            val override = dao.override(providerId, e.modelId)
            ModelDescriptor(
                id = e.modelId, displayName = e.displayName, providerId = e.providerId,
                contextWindow = e.contextWindow, maxOutputTokens = e.maxOutputTokens,
                capabilities = if (override != null) {
                    ModelCapabilities(
                        override.vision, override.toolCalling, override.reasoning,
                        override.structuredOutput, override.fileInput,
                    )
                } else {
                    ModelCapabilities(e.vision, e.toolCalling, e.reasoning, e.structuredOutput, e.fileInput)
                },
                capabilitiesSource = if (override != null) CapabilitySource.VERIFIED_MANUAL
                else runCatching { CapabilitySource.valueOf(e.capabilitySource) }.getOrDefault(CapabilitySource.UNKNOWN),
                retired = e.retired, fetchedAt = e.fetchedAt,
                raw = e.rawJson?.let { com.mobileaiagent.core.json.JsonParser.parseOrNull(it) },
            )
        }
    }

    override suspend fun refreshModels(providerId: String): RefreshOutcome {
        val provider = providerFor(providerId) ?: return RefreshOutcome.Failed("Provider $providerId not found", usedCache = false)
        if (!provider.isConfigured()) return RefreshOutcome.NoCredentials
        return when (val result = provider.discoverModels()) {
            is com.mobileaiagent.providers.ModelDiscoveryResult.Success -> {
                val manual = dao.list(providerId).filter { it.isManual }
                dao.deleteDiscovered(providerId)
                val now = System.currentTimeMillis()
                dao.upsertAll(
                    result.models.map { m ->
                        ModelEntity(
                            providerId = providerId, modelId = m.id, displayName = m.displayName,
                            contextWindow = m.contextWindow, maxOutputTokens = m.maxOutputTokens,
                            vision = m.capabilities.vision, toolCalling = m.capabilities.toolCalling,
                            reasoning = m.capabilities.reasoning, structuredOutput = m.capabilities.structuredOutput,
                            fileInput = m.capabilities.fileInput, capabilitySource = m.capabilitiesSource.name,
                            retired = false, fetchedAt = now, isManual = false, rawJson = m.raw?.toJson(),
                        )
                    } + manual
                )
                RefreshOutcome.Refreshed(result.models.size)
            }
            is com.mobileaiagent.providers.ModelDiscoveryResult.Failure ->
                RefreshOutcome.Failed(result.message, usedCache = dao.list(providerId).isNotEmpty())
        }
    }

    override suspend fun upsertManualModel(model: ModelDescriptor) {
        dao.upsertAll(
            listOf(
                ModelEntity(
                    providerId = model.providerId, modelId = model.id, displayName = model.displayName,
                    contextWindow = model.contextWindow, maxOutputTokens = model.maxOutputTokens,
                    vision = model.capabilities.vision, toolCalling = model.capabilities.toolCalling,
                    reasoning = model.capabilities.reasoning, structuredOutput = model.capabilities.structuredOutput,
                    fileInput = model.capabilities.fileInput, capabilitySource = CapabilitySource.VERIFIED_MANUAL.name,
                    retired = false, fetchedAt = System.currentTimeMillis(), isManual = true, rawJson = null,
                )
            )
        )
    }

    override suspend fun setCapabilitiesOverride(providerId: String, modelId: String, caps: ModelCapabilities) {
        dao.upsertOverride(
            CapabilityOverrideEntity(providerId, modelId, caps.vision, caps.toolCalling, caps.reasoning, caps.structuredOutput, caps.fileInput)
        )
    }

    override suspend fun capabilitiesOverride(providerId: String, modelId: String): ModelCapabilities? {
        val o = dao.override(providerId, modelId) ?: return null
        return ModelCapabilities(o.vision, o.toolCalling, o.reasoning, o.structuredOutput, o.fileInput)
    }

    override suspend fun lastRefresh(providerId: String): Long? = dao.lastRefresh(providerId)
}

/**
 * Projects live on the filesystem under filesDir/projects/<id>/; the DB stores
 * metadata only. Every project gets the standard layout from PlanManager.
 */
class FilesystemProjectRepository(
    private val dao: ProjectDao,
    private val filesRoot: File,
) : ProjectRepository {

    override fun projectsRoot(): File = File(filesRoot, "projects").apply { mkdirs() }

    override fun projectDir(projectId: String): File = File(projectsRoot(), projectId)

    override suspend fun createProject(name: String, description: String?): ProjectInfo = withContext(Dispatchers.IO) {
        val id = "proj_" + System.currentTimeMillis().toString(36) + "_" +
            java.util.UUID.randomUUID().toString().substring(0, 4)
        val dir = File(projectsRoot(), id)
        dir.mkdirs()
        com.mobileaiagent.agent.plan.PlanManager.projectLayout(dir).forEach { File(dir, it).mkdirs() }
        val now = System.currentTimeMillis()
        File(dir, "project.json").writeText(
            com.mobileaiagent.core.json.JsonValue.obj(
                "id" to com.mobileaiagent.core.json.JsonValue.of(id),
                "name" to com.mobileaiagent.core.json.JsonValue.of(name),
                "description" to com.mobileaiagent.core.json.JsonValue.of(description),
                "created_at" to com.mobileaiagent.core.json.JsonValue.of(now),
            ).toJson(true)
        )
        dao.upsert(ProjectEntity(id, safePathSegment(name), description, dir.absolutePath, now, now))
        ProjectInfo(id, name, description, dir.absolutePath, now, now)
    }

    override suspend fun listProjects(): List<ProjectInfo> = withContext(Dispatchers.IO) {
        dao.listAll().map { e ->
            val dir = File(e.rootPath)
            val (count, size) = countFiles(dir)
            ProjectInfo(
                id = e.id, name = e.name, description = e.description, rootPath = e.rootPath,
                createdAt = e.createdAt, updatedAt = e.updatedAt, fileCount = count, sizeBytes = size,
                hasActivePlan = File(dir, "Plans/active.plan.json").isFile,
            )
        }
    }

    override suspend fun renameProject(projectId: String, newName: String): Boolean = withContext(Dispatchers.IO) {
        val existing = dao.get(projectId) ?: return@withContext false
        dao.upsert(existing.copy(name = safePathSegment(newName), updatedAt = System.currentTimeMillis()))
        true
    }

    override suspend fun deleteProject(projectId: String): Boolean = withContext(Dispatchers.IO) {
        val existing = dao.get(projectId) ?: return@withContext false
        val dir = File(existing.rootPath)
        val canonicalRoot = projectsRoot().canonicalPath
        if (!dir.canonicalPath.startsWith(canonicalRoot)) return@withContext false
        dir.deleteRecursively()
        dao.delete(projectId)
        true
    }

    override suspend fun projectInfo(projectId: String): ProjectInfo? = withContext(Dispatchers.IO) {
        dao.get(projectId)?.let { e ->
            ProjectInfo(
                e.id, e.name, e.description, e.rootPath, e.createdAt, e.updatedAt,
                hasActivePlan = File(e.rootPath, "Plans/active.plan.json").isFile,
            )
        }
    }

    private fun countFiles(dir: File): Pair<Int, Long> {
        if (!dir.isDirectory) return 0 to 0L
        var count = 0
        var size = 0L
        dir.walkTopDown().forEach { if (it.isFile) { count++; size += it.length() } }
        return count to size
    }
}
