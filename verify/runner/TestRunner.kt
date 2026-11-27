package verify

import java.lang.reflect.Modifier

/** Offline reflection runner for the JUnit-style tests in src/test/kotlin. */
object TestRunner {
    /**
     * When true, a skipped test fails the run. CI sets this so that "the sandbox
     * cannot spawn processes" never becomes a way to lose coverage silently.
     */
    private val failOnSkip = System.getenv("MAA_FAIL_ON_SKIP") == "1"

    @JvmStatic
    fun main(args: Array<String>) {
        val classNames = args.toList()
        var passed = 0
        var failed = 0
        var skipped = 0
        val failures = mutableListOf<String>()
        // Tests that spawn processes are skipped when the sandbox blocks
        // posix_spawn (e.g. this development environment). They still run in CI.
        val requiresShell = runCatching {
            Class.forName("com.mobileaiagent.testkit.RequiresShell")
        }.getOrNull()
        val shellProbe = runCatching {
            Class.forName("com.mobileaiagent.testkit.ShellProbe").getMethod("available")
        }.getOrNull()
        val canSpawn = runCatching { shellProbe?.invoke(null) as? Boolean ?: true }.getOrDefault(true)
        for (name in classNames) {
            val clazz = Class.forName(name)
            val instance = clazz.getDeclaredConstructor().newInstance()
            val befores = clazz.methods.filter { it.isAnnotationPresent(org.junit.Before::class.java) }
            val tests = clazz.methods.filter { it.isAnnotationPresent(org.junit.Test::class.java) }
                .sortedBy { it.name }
            if (tests.isEmpty()) println("?? $name has no @Test methods")
            for (test in tests) {
                val label = "${clazz.simpleName}.${test.name}"
                val needsShell = requiresShell != null && test.isAnnotationPresent(requiresShell as Class<out Annotation>)
                if (needsShell && !canSpawn) {
                    skipped++
                    println("SKIP  $label (this environment cannot spawn processes)")
                    continue
                }
                try {
                    befores.forEach { it.invoke(instance) }
                    test.invoke(instance)
                    passed++
                    println("PASS  $label")
                } catch (invocation: java.lang.reflect.InvocationTargetException) {
                    val cause = invocation.cause ?: invocation
                    if (cause is org.junit.Assume.AssumptionViolatedException) {
                        skipped++
                        println("SKIP  $label (${cause.message})")
                        continue
                    }
                    failed++
                    println("FAIL  $label\n      ${cause::class.java.simpleName}: ${cause.message}")
                    cause.stackTrace.take(6).forEach { println("        at $it") }
                    failures.add(label)
                } catch (t: Throwable) {
                    failed++
                    println("FAIL  $label -> $t")
                    failures.add(label)
                }
            }
        }
        println()
        val skipNote = if (skipped > 0) ", $skipped skipped" else ""
        println("==== $passed passed, $failed failed$skipNote ====")
        if (skipped > 0 && failOnSkip) {
            println("ERROR: $skipped test(s) were skipped but MAA_FAIL_ON_SKIP=1.")
            println("These tests spawn real processes and must run in a normal environment.")
            kotlin.system.exitProcess(1)
        }
        if (failures.isNotEmpty()) {
            failures.forEach { println("FAILED: $it") }
            kotlin.system.exitProcess(1)
        }
    }
}
