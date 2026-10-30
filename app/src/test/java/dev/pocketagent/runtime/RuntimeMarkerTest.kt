package dev.pocketagent.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RuntimeMarkerTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun missingOrInvalidMarkersDoNotThrow() {
        assertNull(readRuntimeMarker(java.io.File(temporary.root, "missing")))
        assertNull(readRuntimeMarker(temporary.newFolder()))
        assertNull(readRuntimeMarker(temporary.newFile()))
    }

    @Test fun readsOnlyTheVersionLine() {
        val file = temporary.newFile().apply { writeText(" runtime-version \nextra data") }
        assertEquals("runtime-version", readRuntimeMarker(file))
    }
}
