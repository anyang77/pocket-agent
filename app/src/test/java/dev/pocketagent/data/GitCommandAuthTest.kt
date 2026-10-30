package dev.pocketagent.data

import java.util.Base64
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GitCommandAuthTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun auth(
        accounts: List<GitCommandAccount>,
        extraEnvironment: Map<String, String> = emptyMap(),
    ): GitCommandAuth {
        val script = temporary.newFile().apply {
            writeText(gitAskpassScript(if (java.io.File("/system/bin/sh").exists()) "/system/bin/sh" else "/bin/sh"))
            setExecutable(true)
        }
        return gitCommandAuth(accounts, script.absolutePath, extraEnvironment)
    }

    private fun fill(auth: GitCommandAuth, url: String): Pair<Int, String> {
        val process = ProcessBuilder("git", "credential", "fill")
            .directory(temporary.root)
            .redirectErrorStream(true)
            .apply {
                environment().apply {
                    put("GIT_CONFIG_GLOBAL", "/dev/null")
                    put("GIT_CONFIG_NOSYSTEM", "1")
                    remove("GIT_ASKPASS")
                    remove("SSH_ASKPASS")
                    putAll(auth.environment)
                }
            }.start()
        process.outputStream.bufferedWriter().use { it.write("url=$url\n\n") }
        assertTrue("credential helper hung", process.waitFor(10, TimeUnit.SECONDS))
        return process.exitValue() to process.inputStream.bufferedReader().readText()
    }

    @Test fun gitUsesTheSignedInAccountForItsHost() {
        val token = "test-token'with\$shell;characters"
        val auth = auth(listOf(GitCommandAccount("github.com", "alice", token)))
        val (exit, output) = fill(auth, "https://github.com/alice/private.git")
        assertEquals(output, 0, exit)
        assertTrue(output.contains("username=alice\n"))
        assertTrue(output.contains("password=$token\n"))
        assertEquals(token, auth.environment["GH_TOKEN"])
        assertEquals(token, auth.environment["GITHUB_TOKEN"])
    }

    @Test fun credentialsDoNotGoToOtherHostsOrPlainHttp() {
        val auth = auth(listOf(GitCommandAccount("github.com", "alice", "private-token")))
        listOf(
            "https://github.com.evil.example/repo.git",
            "https://example.com/repo.git",
            "http://github.com/alice/repo.git",
            "https://github.com:444/alice/repo.git",
        ).forEach { url ->
            val (exit, output) = fill(auth, url)
            assertTrue("unexpected credentials for $url", exit != 0)
            assertFalse(output.contains("private-token"))
        }
    }

    @Test fun multipleHostsUseTheirOwnActiveCredentials() {
        val auth = auth(listOf(
            GitCommandAccount("github.com", "alice", "github-token"),
            GitCommandAccount("gitlab.com", "oauth2", "gitlab-token"),
        ))
        val (exit, output) = fill(auth, "https://gitlab.com/team/private.git")
        assertEquals(output, 0, exit)
        assertTrue(output.contains("username=oauth2\n"))
        assertTrue(output.contains("password=gitlab-token\n"))
        assertFalse(output.contains("github-token"))
    }

    @Test fun passwordPromptWithUsernameAndPathStillMatchesTheExactHost() {
        val auth = auth(listOf(GitCommandAccount("github.com", "alice", "test-token")))
        val (exit, output) = fill(auth, "https://alice@github.com/team/private.git")
        assertEquals(output, 0, exit)
        assertTrue(output.contains("password=test-token\n"))
    }

    @Test fun switchingAndRemovingAccountsTakesEffectOnTheNextCommand() {
        val first = auth(listOf(GitCommandAccount("github.com", "alice", "alice-token")))
        val second = auth(listOf(GitCommandAccount("github.com", "bob", "bob-token")))
        assertTrue(fill(first, "https://github.com/repo.git").second.contains("password=alice-token"))
        val output = fill(second, "https://github.com/repo.git").second
        assertTrue(output.contains("password=bob-token"))
        assertFalse(output.contains("alice-token"))
        val removed = auth(emptyList())
        assertFalse(removed.environment.containsKey("GH_TOKEN"))
        assertFalse(removed.environment.keys.any { it.startsWith("CODEMATE_GIT_TOKEN_") })
    }

    @Test fun modelInstructionsAndGitConfigContainNoTokensAndOutputIsRedacted() {
        val token = "private+/token=123"
        val auth = auth(listOf(GitCommandAccount("github.com", "alice", token)))
        val basic = Base64.getEncoder().encodeToString("alice:$token".toByteArray())
        val output = auth.redact("$token private%2B%2Ftoken%3D123 $basic")
        assertFalse(output.contains(token))
        assertFalse(output.contains(basic))
        assertFalse(output.contains("private%2B%2Ftoken%3D123"))
        assertTrue(auth.description.contains("alice@github.com"))
        assertFalse(auth.description.contains(token))
        assertFalse(auth.environment.filterKeys { it.startsWith("GIT_CONFIG_") }.values.any { token in it })
    }

    @Test fun sshConfigurationIsPreservedAlongsideHttpsCredentials() {
        val auth = auth(
            listOf(GitCommandAccount("github.com", "alice", "test-token")),
            mapOf("GIT_SSH" to "/app/libpocket_ssh.so", "POCKET_SSH_CONFIG" to "/app/home/.ssh/config"),
        )
        assertEquals("/app/libpocket_ssh.so", auth.environment["GIT_SSH"])
        assertEquals("/app/home/.ssh/config", auth.environment["POCKET_SSH_CONFIG"])
        assertEquals(0, fill(auth, "https://github.com/alice/private.git").first)
    }
}
