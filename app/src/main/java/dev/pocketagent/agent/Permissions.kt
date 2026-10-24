package dev.pocketagent.agent

enum class PermissionMode {
    READ_ONLY,
    ASK,
    AUTO,
}

enum class PermissionKind {
    WRITE_FILES,
    RUN_COMMAND,
    NETWORK,
    DESTRUCTIVE,
    SYSTEM_READ,
    ROOT,
}

data class AgentPermissions(
    val mode: PermissionMode = PermissionMode.ASK,
    val allowFileWrite: Boolean = true,
    val allowCommands: Boolean = true,
    val allowNetwork: Boolean = false,
    val allowSystemRead: Boolean = true,
)

data class PermissionRequest(
    val kind: PermissionKind,
    val title: String,
    val detail: String,
    val risk: String = "normal",
)

data class PermissionDecision(
    val allowed: Boolean,
    val reason: String,
)
