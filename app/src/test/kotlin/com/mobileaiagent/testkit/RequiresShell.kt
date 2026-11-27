package com.mobileaiagent.testkit

/**
 * Support for tests that spawn real processes.
 *
 * Some sandboxes block `posix_spawn` at the syscall level, so
 * `ProcessBuilder.start()` throws "Permission denied" even though `/bin/sh`
 * exists. Those tests are genuine and must run in CI, so rather than deleting
 * them this provides a standard skip.
 *
 * It uses JUnit's `Assume`, which means the same rule applies everywhere:
 *  - Gradle/JUnit: the test is reported as *skipped*, and the build stays green.
 *  - The offline harness (verify/run.sh): its runner understands Assume too.
 *
 * The previous version used a private annotation that only the offline runner
 * understood, so under Gradle the tests ran and failed in a sandbox that cannot
 * spawn processes. One mechanism for both is the point.
 */
object ShellProbe {
    val available: Boolean by lazy {
        try {
            val p = ProcessBuilder("/bin/sh", "-c", "exit 0").start()
            p.waitFor()
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Skips the calling test when the environment cannot spawn processes.
     * Call this as the first statement of the test body.
     */
    fun assumeShellAvailable() {
        org.junit.Assume.assumeTrue(
            "This environment cannot spawn processes (posix_spawn denied); " +
                "the test is skipped rather than failing.",
            available,
        )
    }
}
