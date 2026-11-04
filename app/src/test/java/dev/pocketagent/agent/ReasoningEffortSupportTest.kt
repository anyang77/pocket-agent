package dev.pocketagent.agent

import org.junit.Assert.*
import org.junit.Test

class ReasoningEffortSupportTest {
    @Test fun unknownModelAllowsManualEfforts() {
        val declared = ReasoningEffortSupport.infer("gpt-6.1-sol")
        assertTrue(declared.isEmpty())
        assertEquals(listOf("none", "minimal", "low", "medium", "high", "xhigh", "max", "ultra"),
            ReasoningEffortSupport.selectable(declared))
    }

    @Test fun ultraSurvivesProviderMetadataAndCustomCombination() {
        assertEquals(listOf("max", "ultra"), ReasoningEffortSupport.normalize(listOf("ULTRA", "max", "ultra")))
        assertEquals("ultra", ReasoningEffortSupport.retainSelection("ultra", emptyList()))
        assertEquals("ultra", ReasoningEffortSupport.retainSelection("ultra", listOf("ultra")))
        assertEquals("ultra", ReasoningEffortSupport.retainSelection("ultra", listOf("high"), listOf("max", "ultra")))
    }

    @Test fun refreshWithoutMetadataKeepsExplicitSelection() {
        assertEquals("xhigh", ReasoningEffortSupport.retainSelection("xhigh", emptyList()))
        assertEquals("auto", ReasoningEffortSupport.retainSelection("auto", emptyList()))
    }

    @Test fun declaredCapabilitiesStillRestrictSelection() {
        assertEquals(listOf("low", "high"), ReasoningEffortSupport.selectable(listOf("HIGH", "low")))
        assertEquals("auto", ReasoningEffortSupport.retainSelection("xhigh", listOf("low", "high")))
        assertEquals("high", ReasoningEffortSupport.retainSelection("high", listOf("low", "high")))
    }

    @Test fun invalidValuesAreNotSent() {
        assertEquals("auto", ReasoningEffortSupport.retainSelection("invalid", emptyList()))
    }
    @Test fun customCombinationOverridesProviderMetadata() {
        assertEquals(listOf("low", "xhigh"),
            ReasoningEffortSupport.selectable(listOf("medium", "high"), listOf("xhigh", "low")))
        assertEquals("auto", ReasoningEffortSupport.retainSelection("high", listOf("high"), listOf("low")))
        assertEquals("xhigh", ReasoningEffortSupport.retainSelection("xhigh", emptyList(), listOf("xhigh")))
    }

    @Test fun customCombinationIsNormalizedAndDeduplicated() {
        assertEquals(listOf("low", "high", "max"),
            ReasoningEffortSupport.selectable(emptyList(), listOf(" MAX ", "high", "LOW", "low", "invalid", "auto")))
    }

    @Test fun automaticSelectionSurvivesAnyCombination() {
        assertEquals("auto", ReasoningEffortSupport.retainSelection("auto", listOf("high"), emptyList()))
        assertEquals("auto", ReasoningEffortSupport.retainSelection("auto", emptyList(), listOf("low")))
    }

    @Test fun switchingBetweenConfigurationsDoesNotShareCombinations() {
        val first = listOf("low", "high")
        val second = listOf("xhigh", "max")
        assertEquals("auto", ReasoningEffortSupport.retainSelection("high", emptyList(), second))
        assertEquals(listOf("low", "high"), ReasoningEffortSupport.selectable(emptyList(), first))
        assertEquals(listOf("xhigh", "max"), ReasoningEffortSupport.selectable(listOf("low"), second))
    }

    @Test fun emptyCustomCombinationMeansAutomaticOnly() {
        assertTrue(ReasoningEffortSupport.selectable(listOf("high"), emptyList()).isEmpty())
        assertEquals("auto", ReasoningEffortSupport.retainSelection("high", emptyList(), emptyList()))
    }
}
