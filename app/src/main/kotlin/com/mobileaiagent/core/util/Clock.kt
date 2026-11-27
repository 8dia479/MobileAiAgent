package com.mobileaiagent.core.util

/**
 * Injectable time source. The agent core never calls System.currentTimeMillis
 * directly so persistence/recovery logic can be tested deterministically.
 */
interface Clock {
    fun nowMillis(): Long
    fun nowIso(): String = IsoTime.fromMillis(nowMillis())
}

object SystemClock : Clock {
    override fun nowMillis(): Long = System.currentTimeMillis()
}

fun interface IdGenerator {
    fun newId(prefix: String): String

    companion object {
        fun random(): IdGenerator = IdGenerator { prefix ->
            val rand = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 10)
            "${prefix}_${System.currentTimeMillis().toString(36)}_$rand"
        }
    }
}

object IsoTime {
    fun fromMillis(millis: Long): String =
        java.time.Instant.ofEpochMilli(millis).toString()

    fun toMillis(iso: String): Long? = try {
        java.time.Instant.parse(iso).toEpochMilli()
    } catch (e: Exception) {
        try {
            java.time.OffsetDateTime.parse(iso).toInstant().toEpochMilli()
        } catch (e2: Exception) {
            null
        }
    }

    fun formatDuration(ms: Long): String {
        val seconds = ms / 1000
        return when {
            seconds < 60 -> "${seconds}s"
            seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s"
            else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
        }
    }
}
