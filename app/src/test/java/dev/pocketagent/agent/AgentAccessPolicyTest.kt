package dev.pocketagent.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class AgentAccessPolicyTest {
    private val full = AgentPermissions(
        mode = PermissionMode.AUTO,
        allowNetwork = true,
    )

    @Test fun defaultsToAuditMode() {
        assertEquals(PermissionMode.ASK, AgentPermissions().mode)
        assertFalse(AgentAccessPolicy.hasFullAccess(AgentPermissions()))
    }

    @Test fun requiresEveryPermissionAndAutoMode() {
        assertTrue(AgentAccessPolicy.hasFullAccess(full))
        listOf(
            full.copy(mode = PermissionMode.ASK),
            full.copy(mode = PermissionMode.READ_ONLY),
            full.copy(allowFileWrite = false),
            full.copy(allowCommands = false),
            full.copy(allowNetwork = false),
            full.copy(allowSystemRead = false),
        ).forEach { assertFalse(AgentAccessPolicy.hasFullAccess(it)) }
    }

    @Test fun fullAccessDoesNotInstructModelToRefuseSystemWrites() {
        val prompt = AgentAccessPolicy.instructions(full)
        assertTrue(prompt.contains("FULL ACCESS"))
        assertTrue(prompt.contains("outside the workspace"))
        assertTrue(prompt.contains("already granted"))
        assertFalse(prompt.contains("Do not write to system paths"))
    }

    @Test fun revokingPermissionRestoresLimitedInstructions() {
        val prompt = AgentAccessPolicy.instructions(full.copy(allowCommands = false))
        assertTrue(prompt.contains("LIMITED"))
        assertTrue(prompt.contains("Do not write to system paths"))
        assertTrue(prompt.contains("Do not bypass disabled"))
    }
}
