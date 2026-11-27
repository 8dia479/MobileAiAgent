package com.mobileaiagent.tools.spec

import com.mobileaiagent.core.json.JsonValue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Central catalogue of every tool the agent may call. Implementations register
 * themselves; the registry also computes the model-facing tool manifest.
 */
class ToolRegistry {
    private val tools = ConcurrentHashMap<String, AgentTool>()
    private val listeners = CopyOnWriteArrayList<(List<AgentTool>) -> Unit>()

    @Volatile
    var enabledIds: Set<String>? = null // null == all enabled

    fun register(tool: AgentTool) {
        val previous = tools.put(tool.id, tool)
        if (previous != null) {
            com.mobileaiagent.core.log.AgentLog.w("ToolRegistry", "Tool ${tool.id} replaced (${previous.version} -> ${tool.version})")
        }
        notifyListeners()
    }

    fun registerAll(list: List<AgentTool>) {
        list.forEach { tools[it.id] = it }
        notifyListeners()
    }

    fun unregister(id: String) {
        tools.remove(id)
        notifyListeners()
    }

    fun get(id: String): AgentTool? = tools[id]

    fun all(): List<AgentTool> = tools.values.sortedBy { it.id }

    fun enabled(): List<AgentTool> {
        val allow = enabledIds ?: return all()
        return all().filter { it.id in allow }
    }

    fun byCategory(category: ToolCategory): List<AgentTool> = all().filter { it.category == category }

    fun addListener(listener: (List<AgentTool>) -> Unit) {
        listeners.add(listener)
        listener(all())
    }

    private fun notifyListeners() {
        val snapshot = all()
        listeners.forEach { runCatching { it(snapshot) } }
    }

    /**
     * Tool manifest for the model prompt. Compact but complete: id, description,
     * schema and a capability note so the model does not assume unimplemented
     * formats.
     */
    fun manifestJson(filter: Set<String>? = null): JsonValue.Arr {
        val list = enabled().filter { filter == null || it.id in filter }
        return JsonValue.Arr(
            list.map { tool ->
                JsonValue.Obj(
                    linkedMapOf(
                        "id" to JsonValue.of(tool.id),
                        "name" to JsonValue.of(tool.name),
                        "category" to JsonValue.of(tool.category.label),
                        "description" to JsonValue.of(tool.description),
                        "mutagenic" to JsonValue.of(tool.mutagenic),
                        "input_schema" to tool.inputSchema,
                    )
                )
            }
        )
    }

    fun describeEnabled(): String = buildString {
        appendLine("Registered tools (${enabled().size}):")
        enabled().groupBy { it.category }.forEach { (cat, list) ->
            appendLine("- ${cat.label}: ${list.joinToString { it.id }}")
        }
    }

    companion object {
        fun default(): ToolRegistry = ToolRegistry()
    }
}
