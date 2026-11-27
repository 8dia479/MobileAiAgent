package com.mobileaiagent

import com.mobileaiagent.testkit.ShellProbe
import com.mobileaiagent.tools.builtin.ShellHistory
import com.mobileaiagent.tools.builtin.ShellRunner
import com.mobileaiagent.tools.builtin.TerminalPolicy
import org.junit.Assert
import org.junit.Test

/**
 * The terminal tool runs user commands on the user's own device. These tests are
 * the contract for what it will and will not execute; a regression here is a
 * security regression, not a UX bug.
 */
class TerminalPolicyTest {

    private val allow = setOf("ls", "cat", "grep", "echo", "python3", "sh")

    private fun allowed(cmd: String, chaining: Boolean = false, list: Set<String> = allow) =
        TerminalPolicy.validate(cmd, list, chaining) is TerminalPolicy.Verdict.Allowed

    private fun rejection(cmd: String, chaining: Boolean = false, list: Set<String> = allow): String {
        val v = TerminalPolicy.validate(cmd, list, chaining)
        Assert.assertTrue("expected rejection of '$cmd'", v is TerminalPolicy.Verdict.Rejected)
        return (v as TerminalPolicy.Verdict.Rejected).reason
    }

    // ------------------------------------------------------------- allow-list

    @Test fun allowsWhitelistedBinaries() {
        Assert.assertTrue(allowed("ls -la"))
        Assert.assertTrue(allowed("grep foo notes.txt"))
        Assert.assertTrue(allowed("python3 script.py"))
    }

    @Test fun rejectsBinariesOutsideTheList() {
        val reason = rejection("curl https://example.com")
        Assert.assertTrue(reason, reason.contains("curl") && reason.contains("not in the allowed list"))
    }

    @Test fun rejectsAbsolutePathsEvenForAllowedBinaries() {
        // /data/local/tmp/ls could be the user's own shell script masquerading as ls.
        Assert.assertTrue(rejection("/data/local/tmp/ls").contains("Absolute paths"))
        Assert.assertTrue(rejection("/system/bin/ls").contains("Absolute paths"))
    }

    // ------------------------------------------------------------ chaining gate

    @Test fun chainingIsOffByDefault() {
        Assert.assertFalse(allowed("ls && cat x"))
        Assert.assertFalse(allowed("ls | grep x"))
        Assert.assertTrue(rejection("ls; cat x").contains("chaining is disabled"))
    }

    @Test fun chainingWorksWhenEnabled() {
        Assert.assertTrue(allowed("ls && cat x", chaining = true))
        Assert.assertTrue(allowed("cat x | grep y", chaining = true))
    }

    @Test fun everyChainSegmentIsValidated() {
        // The classic bypass: an allowed command followed by a forbidden one.
        Assert.assertTrue(rejection("ls && curl http://x", chaining = true).contains("curl"))
        Assert.assertTrue(rejection("cat f | rm f", chaining = true).contains("rm"))
        Assert.assertTrue(rejection("ls; python3 evil.py; sudo rm x", chaining = true).isNotEmpty())
    }

    @Test fun quotesAreRespectedWhenSplitting() {
        // '&&' inside quotes is data, not a separator.
        val segments = TerminalPolicy.splitSegments("""echo "a && b" """)
        Assert.assertEquals(1, segments.size)
        Assert.assertEquals("echo", segments.first().binary)
    }

    @Test fun tooManySegmentsAreRejected() {
        val many = (1..14).joinToString(" && ") { "ls" }
        Assert.assertTrue(rejection(many, chaining = true).contains("too many"))
    }

    // ------------------------------------------------------------- hard blocks

    @Test fun commandSubstitutionIsAlwaysBlocked() {
        Assert.assertTrue(rejection("echo \$(whoami)").contains("substitution"))
        Assert.assertTrue(rejection("echo `whoami`").contains("Backticks"))
        // Even with chaining enabled: these bypass segment validation entirely.
        Assert.assertFalse(allowed("ls \$(curl http://evil)", chaining = true))
    }

    @Test fun dangerousPatternsAreBlockedEvenIfTheBinaryIsAllowed() {
        listOf("rm -rf /", "mkfs.ext4 /dev/block/x", "dd if=/dev/zero of=/dev/block/x")
            .forEach { cmd ->
                val v = TerminalPolicy.validate(cmd, setOf("rm", "mkfs.ext4", "dd"), true)
                Assert.assertTrue("'$cmd' should be rejected", v is TerminalPolicy.Verdict.Rejected)
            }
    }

    @Test fun multilineAndNulAreRejected() {
        Assert.assertTrue(TerminalPolicy.validate("ls\ncurl http://x", allow, true) is TerminalPolicy.Verdict.Rejected)
        Assert.assertTrue(TerminalPolicy.validate("ls\u0000", allow, true) is TerminalPolicy.Verdict.Rejected)
    }

    @Test fun emptyCommandIsRejected() {
        Assert.assertTrue(TerminalPolicy.validate("   ", allow, true) is TerminalPolicy.Verdict.Rejected)
    }

    // -------------------------------------------------------- tokenising helpers

    @Test fun envAssignmentsAreSkippedWhenFindingTheBinary() {
        Assert.assertEquals("python3", TerminalPolicy.firstToken("FOO=1 python3 a.py"))
        Assert.assertEquals("echo", TerminalPolicy.firstToken("""echo "hi there" """))
    }

    @Test fun redirectsWithinASingleCommandArePermitted() {
        // A redirect does not spawn a second binary, so it is allowed.
        Assert.assertTrue(allowed("ls > out.txt"))
    }

    // -------------------------------------------------------------------- env

    @Test fun extraEnvParsesKeyValuePairs() {
        val env = TerminalPolicy.parseEnv("A=1,B=two")
        Assert.assertEquals("1", env?.get("A"))
        Assert.assertEquals("two", env?.get("B"))
    }

    @Test fun extraEnvRejectsMalformedInput() {
        Assert.assertNull(TerminalPolicy.parseEnv("novalue"))
        Assert.assertNull(TerminalPolicy.parseEnv("1BAD=x"))
        Assert.assertEquals(emptyMap<String, String>(), TerminalPolicy.parseEnv(null))
        Assert.assertEquals(emptyMap<String, String>(), TerminalPolicy.parseEnv(""))
    }

    // ---------------------------------------------------------------- history

    @Test fun shellHistoryIsBoundedAndOrdered() {
        ShellHistory.clear()
        (1..50).forEach { ShellHistory.record("echo $it", ".", 0, 1) }
        val recent = ShellHistory.recent(limit = 10)
        Assert.assertEquals(10, recent.size)
        // Newest last, and the last one is the 50th command we ran.
        Assert.assertEquals("echo 50", recent.last().command)
        Assert.assertEquals("echo 41", recent.first().command)
        // Capacity is 40, so the oldest entries are gone.
        Assert.assertFalse(ShellHistory.recent(limit = 100).any { it.command == "echo 1" })
        ShellHistory.clear()
    }

    @Test fun historyRecordsFailuresToo() {
        ShellHistory.clear()
        ShellHistory.record("false", "sub", 1, 5)
        val entry = ShellHistory.recent().single()
        Assert.assertEquals(1, entry.exitCode)
        Assert.assertEquals("sub", entry.cwd)
        ShellHistory.clear()
    }

    // ------------------------------------------------------------- execution

    @Test
    fun runsARealCommandAndCapturesBothStreams() {
        ShellProbe.assumeShellAvailable()
        val result = ShellRunner.run(
            "echo out; echo err 1>&2; exit 3",
            cwd = java.io.File(System.getProperty("java.io.tmpdir")),
            timeoutSeconds = 20,
        )
        Assert.assertEquals(3, result.exitCode)
        Assert.assertTrue(result.stdout, result.stdout.contains("out"))
        Assert.assertTrue(result.stderr, result.stderr.contains("err"))
        Assert.assertFalse(result.timedOut)
    }

    @Test
    fun feedsStdinToTheProcess() {
        ShellProbe.assumeShellAvailable()
        val result = ShellRunner.run(
            "cat",
            cwd = java.io.File(System.getProperty("java.io.tmpdir")),
            timeoutSeconds = 20,
            stdin = "hello from stdin\n",
        )
        Assert.assertEquals(0, result.exitCode)
        Assert.assertTrue(result.stdout, result.stdout.contains("hello from stdin"))
    }

    @Test
    fun killsCommandsThatExceedTheTimeout() {
        ShellProbe.assumeShellAvailable()
        val result = ShellRunner.run(
            "sleep 30",
            cwd = java.io.File(System.getProperty("java.io.tmpdir")),
            timeoutSeconds = 1,
        )
        Assert.assertTrue(result.timedOut)
        Assert.assertEquals(-9, result.exitCode)
        Assert.assertTrue("should finish quickly", result.durationMs < 15_000)
    }

    @Test
    fun largeOutputIsTruncatedNotOom() {
        ShellProbe.assumeShellAvailable()
        // 200k lines would be ~1.2 MB; the bound is 4000 bytes here.
        val result = ShellRunner.run(
            "seq 1 200000",
            cwd = java.io.File(System.getProperty("java.io.tmpdir")),
            timeoutSeconds = 30,
            maxStdout = 4000,
        )
        Assert.assertTrue(result.truncatedStdout)
        Assert.assertTrue(result.stdout.length < 20_000)
    }

    @Test
    fun cancellationKillsTheProcess() {
        ShellProbe.assumeShellAvailable()
        val token = com.mobileaiagent.core.util.CancellationToken()
        Thread {
            Thread.sleep(500)
            token.cancel()
        }.apply { isDaemon = true; start() }
        val result = ShellRunner.run(
            "sleep 30",
            cwd = java.io.File(System.getProperty("java.io.tmpdir")),
            timeoutSeconds = 20,
            cancellation = token,
        )
        Assert.assertTrue("expected the process to be killed", result.timedOut || result.exitCode != 0)
        Assert.assertTrue("should return promptly", result.durationMs < 10_000)
    }

    @Test
    fun missingBinaryReportsExit127InsteadOfCrashing() {
        ShellProbe.assumeShellAvailable()
        val result = ShellRunner.run(
            "definitely_not_a_real_binary_xyz",
            cwd = java.io.File(System.getProperty("java.io.tmpdir")),
            timeoutSeconds = 10,
        )
        Assert.assertEquals(127, result.exitCode)
        Assert.assertTrue(result.stderr.isNotBlank())
    }
}
