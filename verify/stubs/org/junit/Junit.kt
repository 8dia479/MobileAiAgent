// Minimal JUnit-4-compatible stubs used ONLY by the offline verification harness.
// Not part of the Gradle source set: the real project uses org.junit:junit:4.13.2.
package org.junit

@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
annotation class Test

@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
annotation class Before

object Assert {
    @JvmStatic fun assertTrue(condition: Boolean) { if (!condition) throw AssertionError("expected true") }
    @JvmStatic fun assertTrue(message: String, condition: Boolean) { if (!condition) throw AssertionError(message) }
    @JvmStatic fun assertFalse(condition: Boolean) { if (condition) throw AssertionError("expected false") }
    @JvmStatic fun assertFalse(message: String, condition: Boolean) { if (condition) throw AssertionError(message) }
    @JvmStatic fun assertEquals(expected: Any?, actual: Any?) {
        if (expected != actual) throw AssertionError("expected <$expected> but was <$actual>")
    }
    @JvmStatic fun assertEquals(message: String, expected: Any?, actual: Any?) {
        if (expected != actual) throw AssertionError("$message: expected <$expected> but was <$actual>")
    }
    @JvmStatic fun assertNotEquals(unexpected: Any?, actual: Any?) {
        if (unexpected == actual) throw AssertionError("expected != <$unexpected>")
    }
    @JvmStatic fun assertEquals(expected: Double, actual: Double, delta: Double) {
        if (kotlin.math.abs(expected - actual) > delta) throw AssertionError("expected <$expected> but was <$actual> (±$delta)")
    }
    @JvmStatic fun assertEquals(expected: Float, actual: Float, delta: Float) {
        if (kotlin.math.abs(expected - actual) > delta) throw AssertionError("expected <$expected> but was <$actual> (±$delta)")
    }
    @JvmStatic fun assertNull(actual: Any?) { if (actual != null) throw AssertionError("expected null but was <$actual>") }
    @JvmStatic fun assertNotNull(actual: Any?) { if (actual == null) throw AssertionError("expected non-null") }
    @JvmStatic fun assertNotNull(message: String, actual: Any?) { if (actual == null) throw AssertionError(message) }
    @JvmStatic fun fail(message: String): Nothing = throw AssertionError(message)
    @JvmStatic fun <T : Throwable> assertThrows(expected: Class<T>, runnable: () -> Unit): T {
        try { runnable() } catch (t: Throwable) {
            if (expected.isInstance(t)) @Suppress("UNCHECKED_CAST") return t as T
            throw AssertionError("expected ${expected.name} but got ${t::class.java.name}: ${t.message}")
        }
        throw AssertionError("expected ${expected.name} but nothing was thrown")
    }
}

/**
 * JUnit's Assume: a failed assumption *skips* a test rather than failing it.
 * The Gradle build uses the real org.junit.Assume; this stub keeps the offline
 * harness behaviourally identical.
 */
object Assume {
    class AssumptionViolatedException(message: String) : RuntimeException(message)

    @JvmStatic fun assumeTrue(condition: Boolean) {
        if (!condition) throw AssumptionViolatedException("assumption failed")
    }

    @JvmStatic fun assumeTrue(message: String, condition: Boolean) {
        if (!condition) throw AssumptionViolatedException(message)
    }
}
