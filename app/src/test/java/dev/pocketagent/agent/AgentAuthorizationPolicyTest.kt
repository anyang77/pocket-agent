package dev.pocketagent.agent

import org.junit.Assert.*
import org.junit.Test

class AgentAuthorizationPolicyTest {
    private val ask = AgentPermissions(mode = PermissionMode.ASK, allowNetwork = true)
    private val auto = ask.copy(mode = PermissionMode.AUTO)
    private fun request(kind: PermissionKind = PermissionKind.RUN_COMMAND) =
        PermissionRequest(kind, "test", "test", workspaceScoped = true)
    private fun action(request: PermissionRequest, permissions: AgentPermissions = auto, root: Boolean = true) =
        AgentAuthorizationPolicy.assess(request, permissions, root).action

    @Test fun workspaceIsAllowedButExternalAndMcpDefaultToConfirmationInAsk() {
        assertEquals(AuthorizationAction.ALLOW, action(request(), ask))
        assertEquals(AuthorizationAction.ALLOW, action(request(PermissionKind.WRITE_FILES), ask))
        assertEquals(AuthorizationAction.CONFIRM, action(request().copy(workspaceScoped = false), ask))
        assertEquals(AuthorizationAction.CONFIRM, action(PermissionRequest(PermissionKind.RUN_COMMAND, "MCP", "external"), ask))
        assertEquals(AuthorizationAction.ALLOW, action(request().copy(workspaceScoped = false)))
    }

    @Test fun criticalConfirmationSurvivesAllKindsIncludingRoot() {
        PermissionKind.values().forEach { kind ->
            val critical = request(kind).copy(requiresConfirmation = true)
            assertEquals(kind.name, AuthorizationAction.CONFIRM, action(critical, auto))
            assertEquals(kind.name, AuthorizationAction.CONFIRM, action(critical, ask))
        }
    }

    @Test fun hardDenialsRunBeforeConfirmation() {
        val critical = request().copy(requiresConfirmation = true)
        assertEquals(AuthorizationAction.DENY, action(critical, auto.copy(mode = PermissionMode.READ_ONLY)))
        assertEquals(AuthorizationAction.DENY, action(critical, auto.copy(allowCommands = false)))
        assertEquals(AuthorizationAction.DENY, action(request(PermissionKind.ROOT), auto.copy(allowCommands = false)))
        assertEquals(AuthorizationAction.DENY, action(request(PermissionKind.ROOT).copy(requiresConfirmation = true), root = false))
        assertEquals(AuthorizationAction.DENY, action(request(PermissionKind.WRITE_FILES), auto.copy(allowFileWrite = false)))
        assertEquals(AuthorizationAction.DENY, action(request(PermissionKind.SYSTEM_READ), auto.copy(allowSystemRead = false)))
        assertEquals(AuthorizationAction.ALLOW, action(request(PermissionKind.SYSTEM_READ), auto.copy(mode = PermissionMode.READ_ONLY)))
    }

    @Test fun networkCannotBeMaskedByDestructiveOrRootClassification() {
        listOf("rm local.tmp; curl https://example.com", "git push", "su -c 'wget https://example.com'").forEach { command ->
            assertTrue(command, CommandPolicy.usesNetwork(command))
            listOf(PermissionKind.DESTRUCTIVE, PermissionKind.ROOT).forEach { kind ->
                val req = request(kind).copy(usesNetwork = CommandPolicy.usesNetwork(command))
                assertEquals(command, AuthorizationAction.CONFIRM, action(req, auto.copy(allowNetwork = false)))
                assertEquals(command, AuthorizationAction.ALLOW, action(req, auto))
            }
        }
        assertFalse(CommandPolicy.usesNetwork("rm build/output"))
    }

    @Test fun automaticRootAlsoChecksRootAvailability() {
        val req = request(PermissionKind.DESTRUCTIVE).copy(usesRoot = true)
        assertEquals(AuthorizationAction.DENY, action(req, root = false))
        assertEquals(AuthorizationAction.ALLOW, action(req, root = true))
    }
}
