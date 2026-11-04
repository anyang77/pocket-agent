package dev.pocketagent.ui

import dev.pocketagent.agent.AgentChoice
import dev.pocketagent.data.AiSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

class SessionStateRouterTest {
    private fun session(id: Long, text: String = "session-$id") = AppUiState(
        currentConversationId = id,
        chat = listOf(ChatEntry(id, ChatRole.ASSISTANT, text)),
    )

    @Test fun hiddenOutputDoesNotReplaceVisibleConversation() = runTest {
        val router = SessionStateRouter(AppUiState())
        router.show(session(1))
        router.show(session(2))
        withContext(router.context(1) + Dispatchers.Default) {
            router.update { it.copy(chat = it.chat.map { entry -> entry.copy(content = "background result") }, agentRunning = true) }
        }
        assertEquals(2L, router.asStateFlow().value.currentConversationId)
        assertEquals("session-2", router.value.chat.single().content)
        router.show(session(1, "stale persisted text"))
        assertEquals("background result", router.value.chat.single().content)
        assertTrue(router.value.agentRunning)
    }

    @Test fun queueDraftApprovalAndChoiceAreRestoredForEachSession() {
        val router = SessionStateRouter(AppUiState())
        val approval = PendingApproval(7, "edit", "a.kt", "write")
        val choice = PendingUserChoice("which?", listOf(AgentChoice("a", "A"), AgentChoice("b", "B")))
        router.show(session(1).copy(agentInputDraft = "draft A",
            queuedPrompts = listOf(QueuedPrompt(9, "next A")), pendingApproval = approval, pendingUserChoice = choice))
        router.show(session(2))
        assertNull(router.value.pendingApproval)
        assertNull(router.value.pendingUserChoice)
        assertTrue(router.value.queuedPrompts.isEmpty())
        router.show(session(1))
        assertEquals("draft A", router.value.agentInputDraft)
        assertEquals("next A", router.value.queuedPrompts.single().text)
        assertEquals(approval, router.value.pendingApproval)
        assertEquals(choice, router.value.pendingUserChoice)
    }

    @Test fun finishingOneSessionDoesNotStopAnother() = runTest {
        val router = SessionStateRouter(AppUiState())
        router.show(session(1).copy(agentRunning = true, agentStatus = "A running"))
        router.show(session(2).copy(agentRunning = true, agentStatus = "B running"))
        withContext(router.context(1)) { router.update { it.copy(agentRunning = false, agentStatus = null) } }
        assertTrue(router.value.agentRunning)
        assertEquals("B running", router.value.agentStatus)
        assertFalse(requireNotNull(router.snapshot(1)).agentRunning)
    }

    @Test fun switchingDoesNotRevertGlobalSettingsOrRunningMarkers() = runTest {
        val router = SessionStateRouter(AppUiState())
        router.show(session(1))
        router.show(session(2))
        router.publish { it.copy(settings = AiSettings(model = "new-model"), runningConversationIds = setOf(1, 2)) }
        withContext(router.context(1)) {
            assertEquals("new-model", router.value.settings.model)
            router.update { it.copy(agentStatus = "A") }
        }
        router.show(session(1))
        assertEquals("new-model", router.value.settings.model)
        assertEquals(setOf(1L, 2L), router.value.runningConversationIds)
    }

    @Test fun switchingPreservesAllGlobalStateIncludingCompletedOnboarding() = runTest {
        val router = SessionStateRouter(AppUiState())
        router.show(session(1))
        router.show(session(2))
        router.publish { it.copy(
            onboardingCompleted = true, welcomeAccepted = true,
            tab = AppTab.AGENT, availableModels = listOf("current-model"),
            activeFile = "unsaved.kt", editorContent = "unsaved changes", editorDirty = true,
            currentDirectory = "src", terminalOutput = "latest output",
        ) }
        val global = router.value
        // Includes both a stale existing snapshot and a newly created conversation.
        for (id in listOf(1L, 3L, 2L, 1L)) {
            router.show(session(id))
            assertTrue(router.value.onboardingCompleted)
            assertEquals(global.withConversation(router.value), router.value)
        }
        withContext(router.context(2)) {
            router.update { it.copy(agentStatus = "background progress") }
            assertTrue(router.value.onboardingCompleted)
        }
        router.show(session(2))
        assertTrue(router.value.onboardingCompleted)
        assertEquals(global.withConversation(router.value), router.value)
    }

    @Test fun twoConcurrentStreamsRetainAllTheirOwnDeltas() = runTest {
        val router = SessionStateRouter(AppUiState())
        router.show(session(1, ""))
        router.show(session(2, ""))
        val start = CompletableDeferred<Unit>()
        val jobs = (1L..2L).map { id ->
            launch(Dispatchers.Default + router.context(id)) {
                start.await()
                repeat(100) {
                    router.update { state -> state.copy(chat = state.chat.map { it.copy(content = it.content + id) }) }
                }
            }
        }
        start.complete(Unit)
        jobs.forEach { it.join() }
        assertEquals("1".repeat(100), router.snapshot(1)?.chat?.single()?.content)
        assertEquals("2".repeat(100), router.snapshot(2)?.chat?.single()?.content)
    }

    @Test fun targetedStopClearsOnlyItsSessionAndRestoresCallerContext() = runTest {
        val router = SessionStateRouter(AppUiState())
        router.show(session(1).copy(agentRunning = true))
        router.show(session(2).copy(agentRunning = true))
        withContext(router.context(2)) {
            router.forSession(1) { router.update { it.copy(agentRunning = false, queuePaused = true) } }
            assertEquals(2L, router.value.currentConversationId)
            assertTrue(router.value.agentRunning)
        }
        assertFalse(requireNotNull(router.snapshot(1)).agentRunning)
        assertTrue(requireNotNull(router.snapshot(1)).queuePaused)
    }
}
