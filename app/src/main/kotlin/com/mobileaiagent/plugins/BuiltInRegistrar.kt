package com.mobileaiagent.plugins

import com.mobileaiagent.agent.plan.PlanManager
import com.mobileaiagent.providers.transport.HttpTransport
import com.mobileaiagent.tools.builtin.*
import com.mobileaiagent.tools.spec.ToolRegistry

/**
 * Registers every implemented built-in tool. Anything not listed here genuinely
 * does not exist yet, and the agent is told so by its tool manifest.
 */
class BuiltInRegistrar(
    private val registry: ToolRegistry,
    private val planManager: PlanManager,
    private val transport: HttpTransport,
) {
    fun registerDefaults() {
        // Files & terminal
        registry.registerAll(
            listOf(
                FileListTool(),
                FileReadTool(),
                FileWriteTool(),
                FileAppendTool(),
                FileMkdirTool(),
                FileDeleteTool(),
                FileMoveTool(),
                FileCopyTool(),
                FileSearchTool(),
                FileInfoTool(),
                TerminalTool(),
                ZipArchiveTool(extract = false),
                ZipArchiveTool(extract = true),
            )
        )
        // Planning & meta
        registry.registerAll(
            listOf(
                PlanGenerateTool(planManager),
                PlanGetTool(planManager),
                PlanUpdateTaskTool(planManager),
                RequestApprovalTool(),
                TaskNoteTool(),
                ProjectSummaryTool(),
            )
        )
        // Research
        registry.registerAll(listOf(WebSearchTool(transport), WebFetchTool(transport)))
        // Productivity
        registry.registerAll(listOf(CsvTool(), JsonTool(), DocumentReadTool(), TextTool()))
        // Creative (real geometry/image generation)
        registry.registerAll(
            listOf(
                MeshCreateTool(),
                MeshTransformTool(),
                MeshInfoTool(),
                CadExtrudeTool(),
                CadDxfTool(),
                CadExportTool(),
                ImageInfoTool(),
                ImageCreateTool(),
                ImageResizeTool(),
            )
        )
    }
}
