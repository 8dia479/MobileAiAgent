package com.mobileaiagent.core.json

object JsonWriter {

    fun write(value: JsonValue, pretty: Boolean = false): String {
        val sb = StringBuilder(256)
        writeValue(value, sb, pretty, 0)
        return sb.toString()
    }

    private fun writeValue(value: JsonValue, sb: StringBuilder, pretty: Boolean, indent: Int) {
        when (value) {
            is JsonValue.Null -> sb.append("null")
            is JsonValue.Bool -> sb.append(if (value.value) "true" else "false")
            is JsonValue.Num -> sb.append(formatNumber(value.value))
            is JsonValue.Str -> writeString(value.value, sb)
            is JsonValue.Arr -> {
                if (value.items.isEmpty()) { sb.append("[]"); return }
                sb.append('[')
                value.items.forEachIndexed { i, item ->
                    if (i > 0) sb.append(',')
                    if (pretty) { sb.append('\n'); indent(sb, indent + 1) }
                    writeValue(item, sb, pretty, indent + 1)
                }
                if (pretty) { sb.append('\n'); indent(sb, indent) }
                sb.append(']')
            }
            is JsonValue.Obj -> {
                if (value.entries.isEmpty()) { sb.append("{}"); return }
                sb.append('{')
                var first = true
                for ((k, v) in value.entries) {
                    if (!first) sb.append(',')
                    first = false
                    if (pretty) { sb.append('\n'); indent(sb, indent + 1) }
                    writeString(k, sb)
                    sb.append(':')
                    if (pretty) sb.append(' ')
                    writeValue(v, sb, pretty, indent + 1)
                }
                if (pretty) { sb.append('\n'); indent(sb, indent) }
                sb.append('}')
            }
        }
    }

    private fun indent(sb: StringBuilder, level: Int) {
        repeat(level) { sb.append("  ") }
    }

    fun formatNumber(d: Double): String {
        if (d.isNaN() || d.isInfinite()) return "null"
        if (d == kotlin.math.floor(d) && kotlin.math.abs(d) < 1e15) {
            return d.toLong().toString()
        }
        val asString = d.toString()
        // Avoid scientific notation for ordinary values, e.g. 1.0E-5 -> 0.00001
        return if (asString.contains('E') || asString.contains('e')) {
            java.math.BigDecimal(d).stripTrailingZeros().toPlainString()
        } else asString
    }

    fun writeString(s: String, sb: StringBuilder) {
        sb.append('"')
        var i = 0
        while (i < s.length) {
            when (val c = s[i]) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (c < ' ') sb.append("\\u").append(String.format("%04x", c.code))
                else sb.append(c)
            }
            i++
        }
        sb.append('"')
    }
}
