package com.mobileaiagent.testkit

/**
 * Marks a test that spawns a real process.
 *
 * Some sandboxes (including the one this project was developed in) block
 * `posix_spawn` at the syscall level, so `ProcessBuilder.start()` throws
 * "Permission denied" even though `/bin/sh` exists. Those tests are meaningful
 * and must run in CI, so instead of deleting them they are skipped with an
 * explicit message when the environment cannot spawn processes.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
annotation class RequiresShell

/**
 * Probes process spawning once per JVM. Reflection-friendly so the offline test
 * runner (which is compiled without the test sources) can call it by name.
 */
object ShellProbe {
    @JvmStatic
    fun available(): Boolean = available

    private val available: Boolean by lazy {
        try {
            val p = ProcessBuilder("/bin/sh", "-c", "exit 0").start()
            p.waitFor()
            true
        } catch (e: Exception) {
            false
        }
    }
}
