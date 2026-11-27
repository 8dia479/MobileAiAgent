package com.mobileaiagent.core.json

class JsonParseException(message: String) : Exception(message)

/**
 * Strict RFC-8259 parser with a configurable nesting limit (protects the agent
 * from pathological provider output). Not thread-safe; create per parse.
 */
class JsonParser private constructor(private val text: String) {

    private var pos = 0
    private var depth = 0

    companion object {
        private const val MAX_DEPTH = 200

        fun parse(text: String): JsonValue {
            val p = JsonParser(text)
            p.skipWhitespace()
            val v = p.parseValue()
            p.skipWhitespace()
            if (p.pos != text.length) throw JsonParseException("Trailing characters at ${p.pos}")
            return v
        }

        fun parseOrNull(text: String): JsonValue? = try {
            parse(text)
        } catch (e: JsonParseException) {
            null
        }
    }

    private fun parseValue(): JsonValue {
        if (pos >= text.length) throw JsonParseException("Unexpected end of input")
        return when (val c = text[pos]) {
            '{' -> parseObject()
            '[' -> parseArray()
            '"' -> JsonValue.Str(parseString())
            't' -> { expectLiteral("true"); JsonValue.Bool(true) }
            'f' -> { expectLiteral("false"); JsonValue.Bool(false) }
            'n' -> { expectLiteral("null"); JsonValue.Null }
            else -> {
                if (c == '-' || c in '0'..'9') parseNumber()
                else throw JsonParseException("Unexpected character '$c' at $pos")
            }
        }
    }

    private fun parseObject(): JsonValue.Obj {
        enter()
        pos++ // {
        skipWhitespace()
        val map = LinkedHashMap<String, JsonValue>()
        if (peek() == '}') { pos++; exit(); return JsonValue.Obj(map) }
        while (true) {
            skipWhitespace()
            if (peek() != '"') throw JsonParseException("Expected string key at $pos")
            val key = parseString()
            skipWhitespace()
            expect(':')
            skipWhitespace()
            map[key] = parseValue()
            skipWhitespace()
            when (peek()) {
                ',' -> pos++
                '}' -> { pos++; exit(); return JsonValue.Obj(map) }
                else -> throw JsonParseException("Expected ',' or '}' at $pos")
            }
        }
    }

    private fun parseArray(): JsonValue.Arr {
        enter()
        pos++ // [
        skipWhitespace()
        val list = ArrayList<JsonValue>()
        if (peek() == ']') { pos++; exit(); return JsonValue.Arr(list) }
        while (true) {
            skipWhitespace()
            list.add(parseValue())
            skipWhitespace()
            when (peek()) {
                ',' -> pos++
                ']' -> { pos++; exit(); return JsonValue.Arr(list) }
                else -> throw JsonParseException("Expected ',' or ']' at $pos")
            }
        }
    }

    private fun parseString(): String {
        expect('"')
        val sb = StringBuilder()
        while (true) {
            if (pos >= text.length) throw JsonParseException("Unterminated string")
            when (val c = text[pos++]) {
                '"' -> return sb.toString()
                '\\' -> {
                    if (pos >= text.length) throw JsonParseException("Unterminated escape")
                    when (val e = text[pos++]) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            if (pos + 4 > text.length) throw JsonParseException("Bad unicode escape")
                            val hex = text.substring(pos, pos + 4)
                            pos += 4
                            sb.append(hex.toIntOrNull(16)?.toChar() ?: throw JsonParseException("Bad unicode escape '$hex'"))
                        }
                        else -> throw JsonParseException("Unknown escape '\\$e'")
                    }
                }
                else -> sb.append(c)
            }
        }
    }

    private fun parseNumber(): JsonValue.Num {
        val start = pos
        if (peek() == '-') pos++
        while (pos < text.length && text[pos].let { it in '0'..'9' || it == '.' || it == 'e' || it == 'E' || it == '+' || it == '-' }) pos++
        val raw = text.substring(start, pos)
        return JsonValue.Num(raw.toDoubleOrNull() ?: throw JsonParseException("Invalid number '$raw'"))
    }

    private fun expectLiteral(literal: String) {
        if (!text.startsWith(literal, pos)) throw JsonParseException("Expected '$literal' at $pos")
        pos += literal.length
    }

    private fun expect(c: Char) {
        if (peek() != c) throw JsonParseException("Expected '$c' at $pos but found '${peek()}'")
        pos++
    }

    private fun peek(): Char = if (pos < text.length) text[pos] else '\u0000'

    private fun skipWhitespace() {
        while (pos < text.length && text[pos].let { it == ' ' || it == '\t' || it == '\n' || it == '\r' }) pos++
    }

    private fun enter() {
        if (++depth > MAX_DEPTH) throw JsonParseException("Nesting deeper than $MAX_DEPTH")
    }

    private fun exit() { depth-- }
}
