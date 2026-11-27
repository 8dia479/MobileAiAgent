package com.mobileaiagent.providers.transport

import com.mobileaiagent.core.json.JsonParser
import com.mobileaiagent.core.json.JsonValue

/** Indirection so provider adapters don't import the JSON parser everywhere. */
object JsonParserRef {
    fun parseOrNull(text: String): JsonValue? = JsonParser.parseOrNull(text)
    fun parse(text: String): JsonValue = JsonParser.parse(text)
}
