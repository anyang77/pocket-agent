package dev.pocketagent.data

import dev.pocketagent.agent.AgentPermissions
import dev.pocketagent.agent.agentSystemPrompt
import org.junit.Assert.*
import org.junit.Test

class PromptPresetsTest {
    @Test fun oldPresetsLoadAsAgentsWithDefaultAvatar() {
        val raw = """{"items":[{"id":"a","name":"导师","content":"角色"}],"active_id":"a"}"""
        val agents = PromptPresets.decode(raw)
        assertEquals("", agents.active?.avatarPath)
        assertEquals("导师", agents.active?.name)
        assertTrue(agents.effectivePrompt.contains("导师"))
    }

    @Test fun avatarPersistsCanBeRemovedAndDoesNotEnterPrompt() {
        val agents = PromptPresets().save("a", "可莉", "用中文", "/private/avatar.png").select("a")
        assertEquals(agents, PromptPresets.decode(agents.encode()))
        assertFalse(agents.effectivePrompt.contains("/private/avatar.png"))
        assertTrue(agents.effectivePrompt.contains("可莉"))
        assertEquals("/private/avatar.png", agents.save("a", "新名字", "新角色").active?.avatarPath)
        assertEquals("", agents.save("a", "新名字", "新角色", "").active?.avatarPath)
    }

    @Test fun agentSelectionSwitchesIdentityAndRoleTogether() {
        val agents = PromptPresets().save("a", "导师", "role A", "a.png")
            .save("b", "作家", "role B", "b.png").select("b")
        assertEquals("b.png", agents.active?.avatarPath)
        assertTrue(agents.effectivePrompt.contains("作家"))
        assertFalse(agents.effectivePrompt.contains("导师"))
        assertEquals("", agents.select(null).effectivePrompt)
    }

    @Test fun migratesExistingPromptWithoutLosingSelection() {
        val migrated = PromptPresets.decode(null, "  你是导师\n用中文  ")
        assertEquals("我的提示词", migrated.active?.name)
        assertEquals("你是导师\n用中文", migrated.active?.content)
        assertTrue(migrated.effectivePrompt.endsWith("你是导师\n用中文"))
        assertEquals(migrated, PromptPresets.decode(migrated.encode(), "ignored"))
    }

    @Test fun freshInstallAndBlankLegacyUseBuiltIn() {
        assertEquals(PromptPresets(), PromptPresets.decode(null))
        assertEquals(PromptPresets(), PromptPresets.decode(null, " \n"))
    }

    @Test fun explicitEmptyCollectionNeverResurrectsLegacyPrompt() {
        val migrated = PromptPresets.decode(null, "legacy")
        val deleted = migrated.delete(migrated.activeId!!)
        assertEquals(PromptPresets(), PromptPresets.decode(deleted.encode(), "legacy"))
    }

    @Test fun savingDoesNotActivateAndEditingPreservesStableId() {
        val a = PromptPresets().save("a", " A ", " role A ").select("a")
        val b = a.save("b", "B", "role B")
        assertEquals("role A", b.active?.content)
        val edited = b.save("a", "renamed", "updated")
        assertEquals(2, edited.items.size)
        assertEquals("a", edited.activeId)
        assertEquals("renamed", edited.active?.name)
        assertTrue(edited.effectivePrompt.endsWith("updated"))
        assertTrue(edited.effectivePrompt.contains("renamed"))
    }

    @Test fun selectionIsExclusiveAndDefaultDoesNotDeletePresets() {
        val presets = PromptPresets().save("a", "A", "role A").save("b", "B", "role B")
        val selected = presets.select("a").select("b")
        val prompt = agentSystemPrompt(AgentPermissions(), selected.effectivePrompt)
        assertTrue(prompt.endsWith("role B"))
        assertFalse(prompt.contains("role A"))
        assertEquals(agentSystemPrompt(AgentPermissions()), agentSystemPrompt(AgentPermissions(), selected.select(null).effectivePrompt))
        assertEquals(2, selected.select(null).items.size)
    }

    @Test fun deletionOnlyResetsSelectionWhenActiveIsDeleted() {
        val presets = PromptPresets().save("a", "A", "role A").save("b", "B", "role B").select("a")
        assertEquals("a", presets.delete("b").activeId)
        assertNull(presets.delete("a").activeId)
        assertEquals("", presets.delete("a").effectivePrompt)
        assertEquals(presets, presets.delete("missing"))
    }

    @Test fun roundTripKeepsNamesNewlinesUnicodeAndDefaultSelection() {
        val presets = PromptPresets().save("a", "中文 😀", "# 角色\n\"引号\" 与 ${'$'}变量")
        assertEquals(presets, PromptPresets.decode(presets.encode()))
        assertEquals(presets.select("a"), PromptPresets.decode(presets.select("a").encode()))
    }

    @Test fun invalidSelectionDoesNotChangeState() {
        val presets = PromptPresets().save("a", "A", "content").select("a")
        assertThrows(IllegalArgumentException::class.java) { presets.select("missing") }
        assertEquals("a", presets.activeId)
    }

    @Test fun validatesNamesAndContentAtBoundary() {
        val empty = PromptPresets()
        assertThrows(IllegalArgumentException::class.java) { empty.save("a", " ", "x") }
        assertThrows(IllegalArgumentException::class.java) { empty.save("a", "x".repeat(61), "x") }
        assertThrows(IllegalArgumentException::class.java) { empty.save("a", "A", " \n") }
        assertThrows(IllegalArgumentException::class.java) { empty.save("a", "A", "x".repeat(16001)) }
        assertEquals(16000, empty.save("a", "x".repeat(60), "x".repeat(16000)).items.single().content.length)
    }

    @Test fun stableDraftIdMakesRepeatedCreateIdempotent() {
        val first = PromptPresets().save("draft-id", "Name", "Content")
        assertEquals(first, first.save("draft-id", "Name", "Content"))
    }

    @Test fun corruptDataFallsBackWithoutPretendingTheCollectionIsEmpty() {
        val loaded = PromptPresets.loadSafely("{broken", "legacy")
        assertNotNull(loaded.loadError)
        assertEquals("", loaded.effectivePrompt)
        assertNotNull(loaded.save("a", "A", "content").loadError)
        assertNotNull(loaded.select(null).loadError)
        assertNotNull(loaded.delete("missing").loadError)
    }

    @Test fun duplicateIdsAreRejected() {
        val raw = """{"items":[{"id":"a","name":"A","content":"a"},{"id":"a","name":"B","content":"b"}],"active_id":"a"}"""
        assertNotNull(PromptPresets.loadSafely(raw).loadError)
    }

    @Test fun staleActiveIdUsesDefault() {
        val raw = """{"items":[],"active_id":"missing"}"""
        assertEquals(PromptPresets(), PromptPresets.decode(raw))
    }
}
