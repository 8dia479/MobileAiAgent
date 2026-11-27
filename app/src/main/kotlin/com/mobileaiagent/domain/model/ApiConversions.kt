package com.mobileaiagent.domain.model

import com.mobileaiagent.core.json.JsonParser
import com.mobileaiagent.providers.ApiMessage
import com.mobileaiagent.providers.ApiToolCall

/** Maps persisted chat messages onto the provider wire model (incl. tool calls). */
object ApiConversions {

    fun toApi(msg: ChatMessage): ApiMessage = when (msg.role) {
        ChatMessage.Role.SYSTEM -> ApiMessage(role = "system", content = msg.content)
        ChatMessage.Role.USER -> ApiMessage(role = "user", content = msg.content)
        ChatMessage.Role.ASSISTANT -> ApiMessage(
            role = "assistant",
            content = msg.content,
            toolCalls = parseToolCalls(msg.toolCallsJson),
        )
        ChatMessage.Role.TOOL -> ApiMessage(
            role = "tool",
            content = msg.content,
            toolCallId = msg.toolCallId,
            name = msg.toolName,
        )
    }

    fun parseToolCalls(json: String?): List<ApiToolCall> {
        if (json.isNullOrBlank()) return emptyList()
        val value = JsonParser.parseOrNull(json)?.asArrayOrNull() ?: return emptyList()
        return value.mapNotNull { item ->
            val id = item["id"].asStringOrNull() ?: return@mapNotNull null
            val name = item["name"].asStringOrNull() ?: return@mapNotNull null
            ApiToolCall(id, name, item["arguments"].asStringOrNull() ?: "{}")
        }
    }

    fun encodeToolCalls(calls: List<ApiToolCall>): String = com.mobileaiagent.core.json.JsonValue.Arr(
        calls.map {
            com.mobileaiagent.core.json.JsonValue.obj(
                "id" to com.mobileaiagent.core.json.JsonValue.of(it.id),
                "name" to com.mobileaiagent.core.json.JsonValue.of(it.name),
                "arguments" to com.mobileaiagent.core.json.JsonValue.of(it.arguments),
            )
        }
    ).toJson()
}
