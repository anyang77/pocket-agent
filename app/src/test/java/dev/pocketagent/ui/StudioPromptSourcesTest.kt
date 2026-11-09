package dev.pocketagent.ui

import org.junit.Assert.*
import org.junit.Test

class StudioPromptSourcesTest {
    private val document = """
        # Community prompts
        ```
        not a prompt
        ```
        ### No. 1: 测试商品摄影
        #### 📖 描述
        柔光棚拍。
        #### 📝 提示词
        ```text
        制作一张商品照片。
        商品：{subject}
        ```
        #### 🖼️ 生成图片
        <img src="https://example.org/image.png">
        #### 📌 详情
        - **作者:** [Alice](https://example.org/alice)
        - **来源:** [Post](https://example.org/post)
        ### No. 2: Needs reference image
        #### Description
        Use uploaded photo.
        #### Prompt
        ```
        Keep the person in the reference image.
        ```
        ### No. 3: Missing prompt
        #### Description
        Invalid entry
    """.trimIndent()

    @Test fun parsesChineseAndEnglishEntriesAndPreservesAttribution() {
        val entries = parseStudioCommunityPrompts(document)
        assertEquals(2, entries.size)
        assertEquals("测试商品摄影", entries[0].title)
        assertEquals("柔光棚拍。", entries[0].description)
        assertEquals("Alice", entries[0].author)
        assertEquals("https://example.org/post", entries[0].originalUrl)
        assertEquals("制作一张商品照片。\n商品：{subject}", entries[0].prompt)
        assertFalse(entries[0].prompt.contains("<img"))
        assertEquals("", entries[1].author)
        assertNull(entries[1].originalUrl)
    }

    @Test fun searchesAcrossTitleDescriptionAuthorAndPrompt() {
        val entry = parseStudioCommunityPrompts(document).first()
        assertTrue(entry.matches(" alice "))
        assertTrue(entry.matches("subject"))
        assertTrue(entry.matches("柔光"))
        assertFalse(entry.matches("unknown"))
    }

    @Test fun rejectsIncompleteOversizedAndDuplicateEntries() {
        assertTrue(parseStudioCommunityPrompts("# no entries").isEmpty())
        assertTrue(parseStudioCommunityPrompts("### No. 1: title\n#### Prompt\n```\nincomplete").isEmpty())
        assertTrue(parseStudioCommunityPrompts("### No. 1: title\n#### Prompt\n```\n${"x".repeat(20_001)}\n```").isEmpty())
        assertEquals(2, parseStudioCommunityPrompts(document + "\n" + document).size)
    }

    @Test fun doesNotTreatWorkflowCodeAsPromptAndAllowsHeadingsInsidePrompt() {
        val workflow = "### No. 1: title\n#### Prompt\nSee original post.\n#### Workflow\n```json\n{\"workflow\":1}\n```"
        assertTrue(parseStudioCommunityPrompts(workflow).isEmpty())
        val prompt = "### No. 1: title\n#### Prompt\n```\n# Subject\nA cat\n```"
        assertEquals("# Subject\nA cat", parseStudioCommunityPrompts(prompt).single().prompt)
    }

    @Test fun applyingPromptsRequiresGenerationLengthWithoutTruncatingSourceText() {
        assertFalse(canApplyStudioPrompt("   "))
        assertTrue(canApplyStudioPrompt("x".repeat(16_000)))
        assertFalse(canApplyStudioPrompt("x".repeat(16_001)))
        val raw = "x".repeat(16_001)
        val entry = parseStudioCommunityPrompts("### No. 1: title\n#### Prompt\n```\n$raw\n```").single()
        assertEquals(raw, entry.prompt)
        assertFalse(canApplyStudioPrompt(entry.prompt))
    }

    @Test fun sourcesAreFixedPublicRepositoriesWithLicenseLinks() {
        assertEquals(2, studioPromptSources.size)
        studioPromptSources.forEach {
            assertTrue(it.url.startsWith("https://github.com/YouMind-OpenLab/"))
            assertTrue(it.documentUrl.endsWith("README_zh.md"))
            assertTrue(it.licenseUrl.endsWith("LICENSE"))
        }
    }
}
