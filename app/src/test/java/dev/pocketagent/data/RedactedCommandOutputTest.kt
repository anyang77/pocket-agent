package dev.pocketagent.data

import org.junit.Assert.*
import org.junit.Test

class RedactedCommandOutputTest {
    @Test fun credentialsAreHiddenAtEveryPossibleChunkBoundary() {
        val text = "before my-secret-token after\n"
        for (split in 0..text.length) {
            val output = StringBuilder()
            val stream = GitCommandAuth(secrets = listOf("my-secret-token")).outputStream { output.append(it) }
            stream.append(text.take(split))
            stream.append(text.drop(split))
            stream.finish()
            assertEquals("before [已隐藏 Git 凭据] after\n", output.toString())
        }
    }
    @Test fun withoutSecretsOutputIsImmediate() {
        val output = StringBuilder()
        val stream = GitCommandAuth().outputStream { output.append(it) }
        stream.append("partial progress")
        assertEquals("partial progress", output.toString())
    }
}
