package dev.pocketagent.ui

import dev.pocketagent.data.AiSettings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelCatalogRequestTest {
    private val requested = AiSettings(baseUrl = "https://old.example/v1", apiKey = "old-key")

    @Test fun acceptsOnlyTheCurrentRequestAndProfile() {
        assertTrue(modelCatalogRequestMatches(2, 2, "a", "a", requested, requested))
        assertFalse(modelCatalogRequestMatches(1, 2, "a", "a", requested, requested))
        assertFalse(modelCatalogRequestMatches(2, 2, "a", "b", requested, requested))
    }

    @Test fun lateResponsesCannotRestoreOldCredentialsOrUserSelections() {
        listOf(
            requested.copy(baseUrl = "https://new.example/v1"),
            requested.copy(apiKey = "new-key"),
            requested.copy(model = "another-model"),
            requested.copy(contextWindow = 256_000),
            requested.copy(reasoningEffort = "high"),
        ).forEach { current ->
            assertFalse(modelCatalogRequestMatches(2, 2, "a", "a", requested, current))
        }
    }
}
