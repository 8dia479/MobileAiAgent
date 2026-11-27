package com.mobileaiagent

import com.mobileaiagent.core.json.JsonParser
import com.mobileaiagent.core.json.JsonValue
import com.mobileaiagent.core.json.JsonWriter
import com.mobileaiagent.domain.model.ReasoningEffort
import com.mobileaiagent.providers.ApiMessage
import com.mobileaiagent.providers.ApiToolCall
import com.mobileaiagent.providers.ApiToolSpec
import com.mobileaiagent.providers.ChatRequest
import com.mobileaiagent.providers.FinishReason
import com.mobileaiagent.providers.InputImage
import com.mobileaiagent.providers.ReasoningMapping
import com.mobileaiagent.providers.ReasoningMechanism
import com.mobileaiagent.providers.transport.OpenAiWire
import com.mobileaiagent.providers.transport.SseReader
import org.junit.Assert
import org.junit.Test

/** Convenience: unescape a JSON snippet typed as a raw string (avoids Kotlin escaping). */
private fun j(raw: String): JsonValue = JsonParser.parse(raw.trimIndent())

private const val CONTENT_HEL = """{"choices":[{"delta":{"content":"Hel"}}]}"""
private const val CONTENT_LO = """{"choices":[{"delta":{"content":"lo"}}]}"""
private const val CONTENT_ANSWER = """{"choices":[{"delta":{"content":"answer"}}]}"""
private const val REASONING_DELTA = """{"choices":[{"delta":{"reasoning_content":"thinking..."}}]}"""
private const val ERROR_BODY = """{"error":{"message":"model overloaded"}}"""
private const val FINISH_TOOLCALLS =
    """{"choices":[{"finish_reason":"tool_calls","delta":{}}],"usage":{"prompt_tokens":12,"completion_tokens":5,"total_tokens":17}}"""
private const val TOOL_FRAGMENT_1 =
    """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"c1","function":{"name":"file_list","arguments":"{\"pa"}}]}}]}"""
private const val TOOL_FRAGMENT_2 =
    """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"th\":\".\"}"}}]}}]}"""
private const val MODEL_LIST =
    """{"data":[{"id":"meta/llama-3.1-8b-instruct"},{"id":"nvidia/nemotron","context_window":128000},{"object":"x"}]}"""

class JsonParserTest {
    @Test fun parsesNestedStructures() {
        val json = j(
            """
            {"a":[1,2,{"b":"te\"xt"}],"c":null,"d":true,"e":-3.5,"f":{"g":[]}}
            """
        )
        Assert.assertEquals(1.0, json["a"][0].asDouble(), 0.0001)
        Assert.assertEquals(2.0, json["a"][1].asDouble(), 0.0001)
        Assert.assertEquals("te\"xt", json["a"][2]["b"].asString())
        Assert.assertTrue(json["c"].isNull)
        Assert.assertTrue(json["d"].asBoolean())
        Assert.assertEquals(-3.5, json["e"].asDouble(), 0.0001)
        Assert.assertEquals(0, json["f"]["g"].asArray().size)
        Assert.assertEquals("te\"xt", json.path("a")[2].stringOr("b", ""))
    }

    @Test fun unicodeEscapesAndSurrogates() {
        val json = j("""{"s":"caf\u00e9 \ud83d\ude00"}""")
        Assert.assertEquals("café 😀", json["s"].asString())
    }

    @Test fun rejectsTrailingGarbage() {
        Assert.assertNull(JsonParser.parseOrNull("""{"a":1} x"""))
        Assert.assertNull(JsonParser.parseOrNull("{"))
        Assert.assertNull(JsonParser.parseOrNull(""))
    }

    @Test fun rejectsDeepNesting() {
        val deep = "[".repeat(500) + "]".repeat(500)
        Assert.assertNull(JsonParser.parseOrNull(deep))
    }

    @Test fun roundTripsThroughWriter() {
        val original = j("""{"n":1.5,"i":42,"s":"a\nb","arr":[true,false,null],"obj":{"k":"v"}}""")
        val reparsed = JsonParser.parse(original.toJson())
        Assert.assertEquals(42.0, reparsed["i"].asDouble(), 0.0)
        Assert.assertEquals("a\nb", reparsed["s"].asString())
        Assert.assertEquals(1.5, reparsed["n"].asDouble(), 0.0)
        Assert.assertEquals("v", reparsed["obj"]["k"].asString())
    }

    @Test fun escapesControlCharacters() {
        val value = JsonValue.of("tab\there\nnewline")
        val text = value.toJson()
        Assert.assertTrue(text.contains("\\t"))
        Assert.assertEquals("tab\there\nnewline", JsonParser.parse(text).asString())
    }

    @Test fun prettyWriterIsStable() {
        val value = JsonValue.obj("a" to JsonValue.of(1), "b" to JsonValue.Arr(listOf(JsonValue.of("x"))))
        val pretty = JsonWriter.write(value, pretty = true)
        Assert.assertTrue(pretty.contains("\n  \"a\": 1"))
        Assert.assertEquals(value.toJson(), JsonParser.parse(pretty).toJson())
    }

    @Test fun numbersKeepIntegersPlain() {
        Assert.assertEquals("1", JsonWriter.formatNumber(1.0))
        Assert.assertEquals("0.5", JsonWriter.formatNumber(0.5))
        Assert.assertEquals("1000000", JsonWriter.formatNumber(1_000_000.0))
    }
}

class ReasoningEffortTest {
    @Test fun parsesAllSixLevelsExactly() {
        Assert.assertEquals(ReasoningEffort.NONE, ReasoningEffort.fromId("none"))
        Assert.assertEquals(ReasoningEffort.LOW, ReasoningEffort.fromId("LOW"))
        Assert.assertEquals(ReasoningEffort.MEDIUM, ReasoningEffort.fromId("medium"))
        Assert.assertEquals(ReasoningEffort.HIGH, ReasoningEffort.fromId("high"))
        Assert.assertEquals(ReasoningEffort.XHIGH, ReasoningEffort.fromId("xHigh"))
        Assert.assertEquals(ReasoningEffort.XHIGH, ReasoningEffort.fromId("x-high"))
        Assert.assertEquals(ReasoningEffort.MAX, ReasoningEffort.fromId("max"))
    }

    @Test fun unknownLevelsAreNotSilentlyMapped() {
        Assert.assertNull(ReasoningEffort.fromId("ultra"))
        Assert.assertNull(ReasoningEffort.fromId("minimal_max"))
        Assert.assertNull(ReasoningEffort.fromId(null))
    }

    @Test fun orderingIsStable() {
        Assert.assertEquals(
            listOf("none", "low", "medium", "high", "xhigh", "max"),
            ReasoningEffort.all.map { it.id },
        )
    }
}

class OpenAiWireTest {
    private fun baseRequest(effort: ReasoningEffort? = null) = ChatRequest(
        model = "test/model",
        reasoningEffort = effort,
        messages = listOf(
            ApiMessage(role = "system", content = "sys"),
            ApiMessage(role = "user", content = "hello"),
        ),
        tools = listOf(
            ApiToolSpec("file_list", "list files", JsonValue.obj("type" to JsonValue.of("object"))),
        ),
        maxOutputTokens = 512,
        temperature = 0.3,
    )

    @Test fun encodesStreamingToolsAndSampling() {
        val body = OpenAiWire.buildRequestBody(baseRequest(), null)
        Assert.assertTrue(body["stream"].asBoolean())
        Assert.assertEquals(512, body["max_tokens"].asInt())
        Assert.assertEquals(0.3, body["temperature"].asDouble(), 0.001)
        Assert.assertEquals(1, body["tools"].asArray().size)
        Assert.assertEquals("file_list", body.path("tools")[0].path("function").stringOr("name", ""))
        Assert.assertEquals("auto", body["tool_choice"].asString())
    }

    @Test fun unsupportedEffortIsOmittedNotSubstituted() {
        val mapping = ReasoningMapping(
            supported = setOf(ReasoningEffort.LOW, ReasoningEffort.MEDIUM),
            wireValues = mapOf(ReasoningEffort.LOW to "low", ReasoningEffort.MEDIUM to "medium"),
            mechanism = ReasoningMechanism.EFFORT_ENUM,
        )
        val supported = OpenAiWire.buildRequestBody(baseRequest(ReasoningEffort.MEDIUM), mapping)
        Assert.assertEquals("medium", supported["reasoning_effort"].asString())

        val unsupported = OpenAiWire.buildRequestBody(baseRequest(ReasoningEffort.MAX), mapping)
        Assert.assertTrue("reasoning_effort must be absent, not downgraded", unsupported["reasoning_effort"].isNull)
        Assert.assertTrue(unsupported["reasoning"].isNull)
    }

    @Test fun encodesAssistantToolCallsAndToolResults() {
        val assistant = OpenAiWire.encodeMessage(
            ApiMessage(
                role = "assistant", content = "",
                toolCalls = listOf(ApiToolCall("call_1", "file_list", "{\"path\":\".\"}")),
            )
        )
        Assert.assertEquals("call_1", assistant.path("tool_calls")[0].stringOr("id", ""))
        Assert.assertEquals("file_list", assistant.path("tool_calls")[0].path("function").stringOr("name", ""))
        Assert.assertEquals("{\"path\":\".\"}", assistant.path("tool_calls")[0].path("function").stringOr("arguments", ""))

        val tool = OpenAiWire.encodeMessage(
            ApiMessage(role = "tool", content = "result", toolCallId = "call_1", name = "file_list")
        )
        Assert.assertEquals("call_1", tool["tool_call_id"].asString())
    }

    @Test fun encodesImagesAsDataUrls() {
        val msg = OpenAiWire.encodeMessage(
            ApiMessage(
                role = "user", content = "see image",
                images = listOf(InputImage("image/png", base64 = "AAAA")),
            )
        )
        val parts = msg["content"].asArray()
        Assert.assertEquals(2, parts.size)
        Assert.assertEquals("image_url", parts[1].stringOr("type", ""))
        Assert.assertTrue(parts[1].path("image_url").stringOr("url", "").startsWith("data:image/png;base64,"))
    }

    @Test fun accumulatorAssemblesStreamedToolCallFragments() {
        val accum = OpenAiWire.StreamAccumulator()
        accum.absorb(j(CONTENT_HEL))
        accum.absorb(j(CONTENT_LO))
        accum.absorb(j(TOOL_FRAGMENT_1))
        accum.absorb(j(TOOL_FRAGMENT_2))
        accum.absorb(j(FINISH_TOOLCALLS))
        val result = accum.result()
        Assert.assertEquals("Hello", result.text)
        Assert.assertEquals(1, result.toolCalls.size)
        Assert.assertEquals("file_list", result.toolCalls[0].name)
        Assert.assertEquals("{\"path\":\".\"}", result.toolCalls[0].arguments)
        Assert.assertEquals(FinishReason.TOOL_CALLS, result.finishReason)
        Assert.assertEquals(17L, result.usage.totalTokens)
    }

    @Test fun accumulatorCapturesReasoningDeltas() {
        val accum = OpenAiWire.StreamAccumulator()
        accum.absorb(j(REASONING_DELTA))
        accum.absorb(j(CONTENT_ANSWER))
        val result = accum.result()
        Assert.assertEquals("thinking...", result.reasoning)
        Assert.assertEquals("answer", result.text)
    }

    @Test fun parsesProviderErrorObjects() {
        val accum = OpenAiWire.StreamAccumulator()
        val events = accum.absorb(j(ERROR_BODY))
        Assert.assertEquals(1, events.size)
        Assert.assertTrue(events[0] is OpenAiWire.DeltaEvent.Error)
        Assert.assertEquals("model overloaded", accum.firstErrorMessage)
    }

    @Test fun parsesModelLists() {
        val models = OpenAiWire.parseModelList(j(MODEL_LIST))
        Assert.assertEquals(2, models.size)
        Assert.assertEquals("nvidia/nemotron", models[1].id)
        Assert.assertEquals(128000, models[1].contextWindow)
    }

    @Test fun parsesFullResponse() {
        val json = j(
            """
            {"choices":[{"message":{"content":"done","tool_calls":[{"id":"x","function":{"name":"file_read","arguments":"{\"path\":\"a.txt\"}"}}]},"finish_reason":"tool_calls"}],
             "usage":{"prompt_tokens":5,"completion_tokens":3,"total_tokens":8}}
            """
        )
        val result = OpenAiWire.parseFullResponse(json)
        Assert.assertEquals("done", result.text)
        Assert.assertEquals(1, result.toolCalls.size)
        Assert.assertEquals(FinishReason.TOOL_CALLS, result.finishReason)
        Assert.assertEquals(8L, result.usage.totalTokens)
    }
}

class SseReaderTest {
    private fun reader(text: String) = SseReader(text.reader().buffered())

    @Test fun parsesEventsWithMultilineData() {
        val events = reader("event: message\ndata: {\"a\":1}\n\ndata: second\n\ndata: [DONE]\n\n").toList()
        Assert.assertEquals(3, events.size)
        Assert.assertEquals("{\"a\":1}", events[0].data)
        Assert.assertEquals("message", events[0].event)
        Assert.assertEquals("second", events[1].data)
        Assert.assertEquals("[DONE]", events[2].data)
    }

    @Test fun handlesCrLfAndComments() {
        val events = reader(": keep-alive\r\ndata: a\r\n\r\ndata: b\r\n\r\n").toList()
        Assert.assertEquals(2, events.size)
        Assert.assertEquals("a", events[0].data)
        Assert.assertEquals("b", events[1].data)
    }

    @Test fun handlesMissingTrailingBlankLine() {
        val events = reader("data: last").toList()
        Assert.assertEquals(1, events.size)
        Assert.assertEquals("last", events[0].data)
    }

    @Test fun joinsMultipleDataLines() {
        val events = reader("data: line1\ndata: line2\n\n").toList()
        Assert.assertEquals(1, events.size)
        Assert.assertEquals("line1\nline2", events[0].data)
    }
}
