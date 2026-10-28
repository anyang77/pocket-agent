package dev.pocketagent.extensions

import org.junit.Assert.*
import org.junit.Test

class ExtensionConfigTest {
    @Test fun importsStandardRemoteAndStdioConfigsWithoutExposingSecrets() {
        val values = parseMcpConfig("""{"mcpServers":{
          "web":{"type":"http","url":"https://example.com/mcp","headers":{"Authorization":"Bearer secret"}},
          "local":{"command":"npx","args":["-y","example-server","dir with space"],"env":{"TOKEN":"private"},"disabled":true}
        }}""")
        assertEquals(2, values.size)
        val web = values.first { it.name == "web" }
        assertEquals("Bearer secret", web.headers["Authorization"])
        assertFalse(web.publicJson().toString().contains("secret"))
        val local = values.first { it.name == "local" }
        assertFalse(local.enabled)
        assertEquals(listOf("-y", "example-server", "dir with space"), local.args)
        assertEquals("private", local.env["TOKEN"])
        assertFalse(local.publicJson().toString().contains("private"))
    }
    @Test fun rejectsUnsupportedTransportAndMalformedConfigs() {
        listOf(
            """{"mcpServers":{"x":{"type":"sse","url":"https://example.com/sse"}}}""",
            """{"mcpServers":{"x":{"url":"file:///etc/passwd"}}}""",
            """{"mcpServers":{"x":{"url":"https://secret@example.com/mcp"}}}""",
            """{"mcpServers":{"x":{"command":"node\nrm","args":[]}}}""",
            """{"mcpServers":{"x":{"command":"node","env":{"INVALID KEY":"v"}}}}""",
            """{"mcpServers":{"x":{"url":"https://example.com","headers":{"X-Test":"a\r\nb"}}}}""",
        ).forEach { text -> assertThrows(Exception::class.java) { parseMcpConfig(text) } }
    }
    @Test fun parsesSkillFrontMatterAndMultilineDescriptions() {
        val value = parseSkill("test", "/project/.agents/skills/build/SKILL.md", """
            ---
            name: build-project
            description: >
              Build Android projects
              and verify changes.
            ---

            Read README first.
            Run the project build.
        """.trimIndent())
        assertEquals("build-project", value.name)
        assertEquals("Build Android projects and verify changes.", value.description)
        assertTrue(value.content.startsWith("Read README first."))
        assertFalse(value.content.contains("description:"))
    }
    @Test fun toolAliasesAreDeterministicUniqueAndValidForModelApis() {
        val a = mcpToolAlias("one", "a b")
        val b = mcpToolAlias("one", "a_b")
        assertNotEquals(a, b)
        assertNotEquals(a, mcpToolAlias("two", "a b"))
        assertEquals(a, mcpToolAlias("one", "a b"))
        assertTrue(a.length <= 64)
        assertTrue(Regex("[A-Za-z0-9_-]+").matches(a))
    }
}
