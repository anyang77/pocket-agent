package dev.pocketagent.extensions

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class OnlineCatalogTest {
    @Test fun registryFiltersInactiveAndUnsupportedTransportsAndKeepsCursor() {
        val root = JSONObject("""{"servers":[
          {"server":{"name":"io.test/remote","version":"1","remotes":[{"type":"sse","url":"https://a"},{"type":"streamable-http","url":"https://a/mcp"}]}},
          {"server":{"name":"io.test/local","packages":[{"registryType":"npm","identifier":"@test/server","version":"1.2.3","transport":{"type":"stdio"}},{"registryType":"oci","identifier":"docker-image","transport":{"type":"stdio"}}]}},
          {"server":{"name":"io.test/deleted"},"_meta":{"io.modelcontextprotocol.registry/official":{"status":"deleted"}}}
        ],"metadata":{"nextCursor":"io.test/local:1.2.3"}}""")
        val page = parseMcpCatalog(root)
        assertEquals(listOf("io.test/remote", "io.test/local"), page.items.map { it.name })
        assertEquals(listOf(1, 1), page.items.map { it.options.size })
        assertEquals("io.test/local:1.2.3", page.cursor)
    }
    @Test fun resolvesHeaderTemplatesRequiredFieldsAndSecretsWithoutShellExpansion() {
        val item = JSONObject("""{"type":"streamable-http","url":"https://{tenant}.example.com/mcp","variables":{"tenant":{"default":"team"}},"headers":[
          {"name":"Authorization","value":"Bearer {key}","variables":{"key":{"isSecret":true}}},
          {"name":"X-Workspace","isRequired":true}, {"name":"Optional"}
        ]}""")
        val fields = catalogInputs(item)
        assertTrue(fields.first { it.label == "key" }.secret)
        assertThrows(IllegalArgumentException::class.java) { compileCatalogMcp("io.test/x", item, emptyMap()) }
        val config = compileCatalogMcp("io.test/x", item, mapOf("headers:0:key" to "secret", "headers:1" to "project"))
        assertEquals("https://team.example.com/mcp", config.url)
        assertEquals(mapOf("Authorization" to "Bearer secret", "X-Workspace" to "project"), config.headers)
        assertFalse(config.publicJson().toString().contains("secret"))
    }
    @Test fun createsPinnedPackageCommandsAndKeepsUserArgumentsAsSingleValues() {
        val item = JSONObject("""{"registryType":"npm","identifier":"@test/mcp","version":"1.2.3","transport":{"type":"stdio"},
          "packageArguments":[{"type":"named","name":"--root","isRequired":true,"valueHint":"directory"}],
          "environmentVariables":[{"name":"TOKEN","isRequired":true,"isSecret":true}] }""")
        val config = compileCatalogMcp("io.test/local", item, mapOf("packageArguments:0" to "/path with spaces/\$(whoami)", "environmentVariables:0" to "s"))
        assertEquals("npx", config.command)
        assertEquals(listOf("-y", "@test/mcp@1.2.3", "--root=/path with spaces/\$(whoami)"), config.args)
        assertEquals(mapOf("TOKEN" to "s"), config.env)
        item.put("registryBaseUrl", "https://unrelated.example.com")
        assertThrows(IllegalArgumentException::class.java) { compileCatalogMcp("io.test/local", item, mapOf("packageArguments:0" to "x", "environmentVariables:0" to "s")) }
    }
    @Test fun chineseCatalogUsesPublishedDescriptionsCategoriesAndRepositoryLinks() {
        val result = parseChineseMcpCatalog("""
            # MCP 中文资源
            ## MCP 服务器精选列表
            ### 浏览器自动化
            | 名称 | 中文介绍 | 备注 |
            | :--- | :--- | :--- |
            | [microsoft/playwright-mcp](https://github.com/microsoft/playwright-mcp) | 微软官方出品，使用 Playwright 让 AI 精确控制网页。 | 官方实现，浏览器自动化。 |
            | [Read](https://github.com/owner/repo/tree/main/src/read) | 读取文件与文档 | 支持本地 \| 远程 |
            | [not a repo](https://example.com) | 不应收录 | |
            ## MCP 更多玩法
            | [other](https://github.com/other/repo) | 其他链接 | |
        """.trimIndent())
        assertEquals(2, result.size)
        assertEquals("浏览器自动化", result.first().category)
        assertEquals("微软官方出品，使用 Playwright 让 AI 精确控制网页。", result.first().description)
        assertEquals("https://github.com/owner/repo/tree/main/src/read", result[1].repository)
        assertEquals("支持本地 | 远程", result[1].notes)
    }
    @Test fun rejectsInvalidRepositoryNames() {
        assertEquals("owner/repo", normalizeSkillRepository("https://github.com/owner/repo.git"))
        listOf("../repo", "owner/repo/../../", "https://evil.example/owner/repo").forEach { assertThrows(IllegalArgumentException::class.java) { normalizeSkillRepository(it) } }
    }
    @Test fun installsCompleteBundleWithLicensesAndOmitsOtherSkills() {
        val target = Files.createTempDirectory("skill-install").toFile()
        try {
            extractSkillBundle(zip(mapOf("repo/SKILL.md" to "wrong", "repo/skills/build/SKILL.md" to "Build", "repo/skills/build/scripts/build.py" to "print('ok')", "repo/skills/build/references/guide.md" to "guide", "repo/skills/other/private.txt" to "other", "repo/LICENSE" to "license")), "skills/build", target)
            assertEquals("Build", target.resolve("SKILL.md").readText())
            assertEquals("print('ok')", target.resolve("scripts/build.py").readText())
            assertEquals("guide", target.resolve("references/guide.md").readText())
            assertEquals("license", target.resolve("SOURCE-LICENSE").readText())
            assertFalse(target.resolve("private.txt").exists())
        } finally { target.deleteRecursively() }
    }
    @Test fun rejectsEscapingFilesAndBundlesWithoutManifest() {
        val target = Files.createTempDirectory("skill-invalid").toFile()
        try {
            assertThrows(IllegalArgumentException::class.java) { extractSkillBundle(zip(mapOf("repo/skills/a/../../../escape.txt" to "bad")), "skills/a", target) }
            assertFalse(target.parentFile!!.resolve("escape.txt").exists())
            assertThrows(IllegalArgumentException::class.java) { extractSkillBundle(zip(mapOf("repo/skills/a/data.txt" to "no manifest")), "skills/a", target) }
            assertThrows(IllegalArgumentException::class.java) { extractSkillBundle(zip(mapOf("repo/skills/a/.codemate-source.json" to "{}")), "skills/a", target) }
        } finally { target.deleteRecursively() }
    }
    private fun zip(files: Map<String, String>): ByteArray = ByteArrayOutputStream().apply {
        ZipOutputStream(this).use { zip -> files.forEach { (path, content) -> zip.putNextEntry(ZipEntry(path)); zip.write(content.toByteArray()); zip.closeEntry() } }
    }.toByteArray()
}
