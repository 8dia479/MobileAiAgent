package com.mobileaiagent.core.json

/**
 * Immutable, dependency-free JSON tree.
 *
 * Why not use kotlinx.serialization for everything? Plans, tool arguments and
 * provider payloads are dynamically shaped at runtime (tool schemas, model
 * discovery responses, provider-specific bodies). A small tree type keeps the
 * agent core free of reflection/serializer boilerplate, lets us splice provider
 * payloads safely, and makes the whole core testable on a plain JVM.
 */
sealed class JsonValue {

    data object Null : JsonValue()
    data class Bool(val value: Boolean) : JsonValue()
    data class Num(val value: Double) : JsonValue() {
        constructor(value: Int) : this(value.toDouble())
        constructor(value: Long) : this(value.toDouble())
    }

    data class Str(val value: String) : JsonValue()
    data class Arr(val items: List<JsonValue>) : JsonValue()
    data class Obj(val entries: Map<String, JsonValue>) : JsonValue()

    val isNull: Boolean get() = this is Null

    fun asStringOrNull(): String? = when (this) {
        is Str -> value
        is Num -> if (value % 1.0 == 0.0 && kotlin.math.abs(value) < 1e15) value.toLong().toString() else value.toString()
        is Bool -> value.toString()
        else -> null
    }

    fun asString(): String = asStringOrNull() ?: error("Value is not a string: ${typeName()}")

    fun asBooleanOrNull(): Boolean? = when (this) {
        is Bool -> value
        is Str -> when (value.lowercase()) { "true" -> true; "false" -> false; else -> null }
        else -> null
    }

    fun asBoolean(): Boolean = asBooleanOrNull() ?: error("Value is not a boolean: ${typeName()}")

    fun asDoubleOrNull(): Double? = when (this) {
        is Num -> value
        is Str -> value.toDoubleOrNull()
        else -> null
    }

    fun asDouble(): Double = asDoubleOrNull() ?: error("Value is not a number: ${typeName()}")

    fun asIntOrNull(): Int? = asDoubleOrNull()?.let {
        if (it.isNaN() || it.isInfinite()) null else it.toInt()
    }

    fun asInt(): Int = asIntOrNull() ?: error("Value is not an int: ${typeName()}")

    fun asLongOrNull(): Long? = asDoubleOrNull()?.let {
        if (it.isNaN() || it.isInfinite()) null else it.toLong()
    }

    fun asArrayOrNull(): List<JsonValue>? = (this as? Arr)?.items

    fun asArray(): List<JsonValue> = asArrayOrNull() ?: error("Value is not an array: ${typeName()}")

    fun asObjectOrNull(): Map<String, JsonValue>? = (this as? Obj)?.entries

    fun asObject(): Map<String, JsonValue> = asObjectOrNull() ?: error("Value is not an object: ${typeName()}")

    /** Safe nested access: obj["a"]["b"] returns Null instead of throwing. */
    operator fun get(key: String): JsonValue = (this as? Obj)?.entries?.get(key) ?: Null

    operator fun get(index: Int): JsonValue = (this as? Arr)?.items?.getOrNull(index) ?: Null

    fun path(vararg keys: String): JsonValue {
        var cur: JsonValue = this
        for (k in keys) cur = cur[k]
        return cur
    }

    fun stringOr(key: String, fallback: String): String = this[key].asStringOrNull() ?: fallback

    fun intOr(key: String, fallback: Int): Int = this[key].asIntOrNull() ?: fallback

    fun boolOr(key: String, fallback: Boolean): Boolean = this[key].asBooleanOrNull() ?: fallback

    fun stringList(key: String): List<String> =
        this[key].asArrayOrNull()?.mapNotNull { it.asStringOrNull() } ?: emptyList()

    fun typeName(): String = when (this) {
        Null -> "null"
        is Bool -> "boolean"
        is Num -> "number"
        is Str -> "string"
        is Arr -> "array"
        is Obj -> "object"
    }

    fun toJson(pretty: Boolean = false): String = JsonWriter.write(this, pretty)

    override fun toString(): String = toJson()

    fun deepCopy(): JsonValue = JsonParser.parse(toJson())

    // ----- builders -------------------------------------------------------

    companion object {
        fun of(value: String?): JsonValue = if (value == null) Null else Str(value)
        fun of(value: Boolean): JsonValue = Bool(value)
        fun of(value: Int): JsonValue = Num(value)
        fun of(value: Long): JsonValue = Num(value)
        fun of(value: Double): JsonValue = Num(value)
        fun arr(items: Iterable<JsonValue>): Arr = Arr(items.toList())
        fun arr(vararg items: JsonValue): Arr = Arr(items.toList())
        fun obj(entries: Map<String, JsonValue>): Obj = Obj(entries)
        fun obj(vararg pairs: Pair<String, JsonValue>): Obj = Obj(linkedMapOf(*pairs))

        /** Recursively converts Kotlin primitives/collections/maps into a tree. */
        fun from(any: Any?): JsonValue = when (any) {
            null -> Null
            is JsonValue -> any
            is String -> Str(any)
            is Boolean -> Bool(any)
            is Int -> Num(any)
            is Long -> Num(any)
            is Short -> Num(any.toInt())
            is Byte -> Num(any.toInt())
            is Float -> Num(any.toDouble())
            is Double -> Num(any)
            is Number -> Num(any.toDouble())
            is Map<*, *> -> Obj(any.entries.associate { (k, v) -> k.toString() to from(v) })
            is Iterable<*> -> Arr(any.map { from(it) })
            is Array<*> -> Arr(any.map { from(it) })
            is BooleanArray -> Arr(any.map { Bool(it) })
            is IntArray -> Arr(any.map { Num(it) })
            is LongArray -> Arr(any.map { Num(it) })
            is DoubleArray -> Arr(any.map { Num(it) })
            else -> Str(any.toString())
        }
    }
}

fun String?.toJsonValue(): JsonValue = JsonValue.of(this)
