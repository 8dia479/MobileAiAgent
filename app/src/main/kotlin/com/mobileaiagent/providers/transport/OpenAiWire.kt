package com.mobileaiagent.providers.transport

import com.mobileaiagent.core.json.JsonValue
import com.mobileaiagent.domain.model.ReasoningEffort
import com.mobileaiagent.providers.ApiMessage
import com.mobileaiagent.providers.ApiToolCall
import com.mobileaiagent.providers.ApiToolSpec
import com.mobileaiagent.providers.ChatRequest
import com.mobileaiagent.providers.FinishReason
import com.mobileaiagent.providers.ReasoningMapping
import com.mobileaiagent.providers.ReasoningMechanism
import com.mobileaiagent.providers.Usage

/**
 * Encodes ChatRequest -> OpenAI-compatible request body and decodes
 * streaming/non-streaming responses. Shared by NVIDIA NIM, OpenAI, and any
 * OpenAI-compatible endpoint, because they all speak this wire format.
 */
object OpenAiWire {

    fun buildRequestBody(request: ChatRequest, mapping: ReasoningMapping?): JsonValue {
        val messages = request.messages.map { msg -> encodeMessage(msg) }
        val body = linkedMapOf<String, JsonValue>(
            "model" to JsonValue.of(request.model),
            "messages" to JsonValue.Arr(messages),
            "stream" to JsonValue.of(true),
            "stream_options" to JsonValue.obj("include_usage" to JsonValue.of(true)),
        )
        if (request.maxOutputTokens > 0) body["max_tokens"] = JsonValue.of(request.maxOutputTokens)
        request.temperature?.let { body["temperature"] = JsonValue.of(it) }
        request.topP?.let { body["top_p"] = JsonValue.of(it) }
        if (request.stop.isNotEmpty()) body["stop"] = JsonValue.Arr(request.stop.map { JsonValue.of(it) })

        if (request.tools.isNotEmpty()) {
            body["tools"] = JsonValue.Arr(request.tools.map { encodeTool(it) })
            body["tool_choice"] = JsonValue.of("auto")
        }

        request.responseJsonSchema?.let { schema ->
            body["response_format"] = JsonValue.obj(
                "type" to JsonValue.of("json_schema"),
                "json_schema" to JsonValue.obj(
                    "name" to JsonValue.of("agent_output"),
                    "strict" to JsonValue.of(false),
                    "schema" to schema,
                ),
            )
        }

        if (mapping != null && request.reasoningEffort != null) {
            when (mapping.mechanism) {
                ReasoningMechanism.EFFORT_ENUM -> {
                    val wire = mapping.wireValues[request.reasoningEffort]
                    if (wire != null) body["reasoning_effort"] = JsonValue.of(wire)
                    // Level unsupported -> omit the field entirely; the caller has
                    // already surfaced this to the user, we never substitute a level.
                }
                ReasoningMechanism.NATIVE_PARAM -> {
                    val wire = mapping.wireValues[request.reasoningEffort]
                    if (wire != null) body["reasoning"] = JsonValue.obj("effort" to JsonValue.of(wire))
                }
                else -> Unit
            }
        }
        request.extra.forEach { (k, v) -> body[k] = v }
        return JsonValue.Obj(body)
    }

    fun encodeMessage(msg: ApiMessage): JsonValue {
        val map = linkedMapOf<String, JsonValue>("role" to JsonValue.of(msg.role))
        if (msg.images.isNotEmpty() && msg.role == "user") {
            val parts = mutableListOf<JsonValue>()
            if (msg.content.isNotBlank()) {
                parts.add(JsonValue.obj("type" to JsonValue.of("text"), "text" to JsonValue.of(msg.content)))
            }
            msg.images.forEach { img ->
                val urlValue = when {
                    img.url != null -> img.url
                    img.base64 != null -> "data:${img.mimeType};base64,${img.base64}"
                    else -> null
                }
                if (urlValue != null) {
                    parts.add(
                        JsonValue.obj(
                            "type" to JsonValue.of("image_url"),
                            "image_url" to JsonValue.obj("url" to JsonValue.of(urlValue)),
                        )
                    )
                }
            }
            map["content"] = JsonValue.Arr(parts)
        } else {
            map["content"] = JsonValue.of(msg.content)
        }
        msg.name?.let { map["name"] = JsonValue.of(it) }
        if (msg.toolCalls.isNotEmpty()) {
            map["tool_calls"] = JsonValue.Arr(
                msg.toolCalls.map { call ->
                    JsonValue.obj(
                        "id" to JsonValue.of(call.id),
                        "type" to JsonValue.of("function"),
                        "function" to JsonValue.obj(
                            "name" to JsonValue.of(call.name),
                            "arguments" to JsonValue.of(call.arguments),
                        ),
                    )
                }
            )
        }
        msg.toolCallId?.let { map["tool_call_id"] = JsonValue.of(it) }
        return JsonValue.Obj(map)
    }

    fun encodeTool(tool: ApiToolSpec): JsonValue = JsonValue.obj(
        "type" to JsonValue.of("function"),
        "function" to JsonValue.obj(
            "name" to JsonValue.of(tool.name),
            "description" to JsonValue.of(tool.description),
            "parameters" to tool.parameters,
        ),
    )

    /** Mutable accumulator used while decoding an SSE stream. */
    class StreamAccumulator {
        private val text = StringBuilder()
        private val reasoning = StringBuilder()
        private val toolCalls = LinkedHashMap<Int, ToolCallAccum>()
        var finishReason: String? = null
        var usage: Usage = Usage()
        var sawDone = false
        var firstErrorMessage: String? = null

        class ToolCallAccum(
            var id: String = "",
            var name: String = "",
            val arguments: StringBuilder = StringBuilder(),
        )

        fun absorb(json: JsonValue): List<DeltaEvent> {
            val out = mutableListOf<DeltaEvent>()
            val error = json["error"]
            if (error !is JsonValue.Null) {
                val msg = error.path("message").asStringOrNull() ?: error.asStringOrNull() ?: error.toJson()
                firstErrorMessage = msg
                out.add(DeltaEvent.Error(msg))
                return out
            }
            val choices = json["choices"].asArrayOrNull() ?: emptyList()
            for (choice in choices) {
                val delta = choice["delta"]
                val content = delta["content"].asStringOrNull()
                if (!content.isNullOrEmpty()) {
                    text.append(content)
                    out.add(DeltaEvent.Text(content))
                }
                val thought = delta["reasoning_content"].asStringOrNull()
                    ?: delta["reasoning"].asStringOrNull()
                if (!thought.isNullOrEmpty()) {
                    reasoning.append(thought)
                    out.add(DeltaEvent.Reasoning(thought))
                }
                val calls = delta["tool_calls"].asArrayOrNull() ?: emptyList()
                for ((fallbackIdx, call) in calls.withIndex()) {
                    val idx = call["index"].asIntOrNull() ?: fallbackIdx
                    val accum = toolCalls.getOrPut(idx) { ToolCallAccum() }
                    val id = call["id"].asStringOrNull()
                    if (!id.isNullOrEmpty()) accum.id = id
                    val fn = call["function"]
                    val name = fn["name"].asStringOrNull()
                    if (!name.isNullOrEmpty()) accum.name = name
                    val argsDelta = fn["arguments"].asStringOrNull() ?: ""
                    if (argsDelta.isNotEmpty()) accum.arguments.append(argsDelta)
                    out.add(DeltaEvent.ToolCall(idx, accum.id, accum.name, argsDelta))
                }
                choice["finish_reason"].asStringOrNull()?.let { finishReason = it }
            }
            val usageJson = json["usage"]
            if (usageJson !is JsonValue.Null) {
                usage = Usage(
                    inputTokens = usageJson.intOr("prompt_tokens", 0).toLong(),
                    outputTokens = usageJson.intOr("completion_tokens", 0).toLong(),
                    totalTokens = usageJson.intOr("total_tokens", 0).toLong(),
                    cachedInputTokens = usageJson.path("prompt_tokens_details")["cached_tokens"].asIntOrNull()?.toLong() ?: 0,
                    reasoningTokens = usageJson.path("completion_tokens_details")["reasoning_tokens"].asIntOrNull()?.toLong() ?: 0,
                )
            }
            return out
        }

        fun result(): OpenAiResult = OpenAiResult(
            text = text.toString(),
            reasoning = reasoning.toString().takeIf { it.isNotEmpty() },
            toolCalls = toolCalls.entries.sortedBy { it.key }.map { (_, a) ->
                ApiToolCall(
                    id = a.id.ifEmpty { "call_${a.name.ifEmpty { "tool" }}_${System.nanoTime()}" },
                    name = a.name,
                    arguments = a.arguments.toString().ifEmpty { "{}" },
                )
            }.filter { it.name.isNotEmpty() },
            finishReason = mapFinishReason(finishReason),
            usage = usage,
        )
    }

    fun mapFinishReason(raw: String?): FinishReason = when (raw) {
        null -> FinishReason.STOP
        "stop" -> FinishReason.STOP
        "length", "max_tokens" -> FinishReason.LENGTH
        "tool_calls", "function_call" -> FinishReason.TOOL_CALLS
        "content_filter" -> FinishReason.CONTENT_FILTER
        else -> FinishReason.STOP
    }

    sealed class DeltaEvent {
        data class Text(val text: String) : DeltaEvent()
        data class Reasoning(val text: String) : DeltaEvent()
        data class ToolCall(val index: Int, val id: String, val name: String, val argsDelta: String) : DeltaEvent()
        data class Error(val message: String) : DeltaEvent()
    }

    data class OpenAiResult(
        val text: String,
        val reasoning: String?,
        val toolCalls: List<ApiToolCall>,
        val finishReason: FinishReason,
        val usage: Usage,
    )

    /** Parses a full (non-streaming) chat completion response. */
    fun parseFullResponse(json: JsonValue): OpenAiResult {
        val choice = json["choices"][0]
        val message = choice["message"]
        val calls = message["tool_calls"].asArrayOrNull()?.map { call ->
            ApiToolCall(
                id = call.stringOr("id", ""),
                name = call.path("function").stringOr("name", ""),
                arguments = call.path("function").stringOr("arguments", "{}"),
            )
        } ?: emptyList()
        val usage = json["usage"]
        return OpenAiResult(
            text = message["content"].asStringOrNull() ?: "",
            reasoning = message["reasoning_content"].asStringOrNull(),
            toolCalls = calls.filter { it.name.isNotEmpty() },
            finishReason = mapFinishReason(choice["finish_reason"].asStringOrNull()),
            usage = Usage(
                inputTokens = usage.intOr("prompt_tokens", 0).toLong(),
                outputTokens = usage.intOr("completion_tokens", 0).toLong(),
                totalTokens = usage.intOr("total_tokens", 0).toLong(),
            ),
        )
    }

    /** Parses `GET /models`-style responses from any OpenAI-compatible endpoint. */
    fun parseModelList(json: JsonValue): List<ParsedModel> {
        val data = json["data"].asArrayOrNull()
            ?: json["models"].asArrayOrNull()
            ?: json.asArrayOrNull()
            ?: return emptyList()
        return data.mapNotNull { m ->
            val id = m["id"].asStringOrNull() ?: m["name"].asStringOrNull() ?: return@mapNotNull null
            ParsedModel(
                id = id,
                displayName = m["display_name"].asStringOrNull() ?: m["name"].asStringOrNull() ?: id,
                contextWindow = m["context_window"].asIntOrNull()
                    ?: m.path("context_length").asIntOrNull()
                    ?: m.path("limits")["max_context_window_tokens"].asIntOrNull(),
                maxOutputTokens = m["max_output_tokens"].asIntOrNull()
                    ?: m.path("limits")["max_output_tokens"].asIntOrNull(),
                raw = m,
            )
        }.distinctBy { it.id }
    }

    data class ParsedModel(
        val id: String,
        val displayName: String,
        val contextWindow: Int? = null,
        val maxOutputTokens: Int? = null,
        val raw: JsonValue? = null,
    )

    fun effortOrNull(e: ReasoningEffort?): String? = e?.id
}
