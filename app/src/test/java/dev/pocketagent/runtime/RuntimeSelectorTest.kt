package dev.pocketagent.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

class RuntimeSelectorTest {
    @Test
    fun prefersInternalTermuxWhenReady() {
        val selected = RuntimeSelector.select(
            RuntimeCapabilities(internalTermuxReady = true, rootGranted = true),
            RuntimePreference.ROOT_WHEN_AVAILABLE,
        )
        assertEquals(RuntimeKind.TERMUX, selected)
    }

    @Test
    fun usesRootOnlyWhenRequestedAndGranted() {
        assertEquals(
            RuntimeKind.SYSTEM_SHELL,
            RuntimeSelector.select(
                RuntimeCapabilities(rootGranted = true),
                RuntimePreference.STANDARD,
            ),
        )
        assertEquals(
            RuntimeKind.ROOT_TERMUX,
            RuntimeSelector.select(
                RuntimeCapabilities(rootGranted = true),
                RuntimePreference.ROOT_WHEN_AVAILABLE,
            ),
        )
    }
}
