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
    val workspaceScoped: Boolean = false,
    val requiresConfirmation: Boolean = false,
    // Independent capability dimensions: ROOT/DESTRUCTIVE must not mask network access.
    val usesNetwork: Boolean = kind == PermissionKind.NETWORK,
    val usesRoot: Boolean = kind == PermissionKind.ROOT,
)

/** Shared by UI authorization and policy tests; capability checks always run first. */
fun PermissionRequest.needsApproval(mode: PermissionMode): Boolean =
    requiresConfirmation || (mode == PermissionMode.ASK && !workspaceScoped)

enum class AuthorizationAction { ALLOW, DENY, CONFIRM }

data class AuthorizationAssessment(val action: AuthorizationAction, val reason: String)

/** Pure decision layer; confirmation never overrides read-only, disabled commands or missing Root. */
object AgentAuthorizationPolicy {
    fun assess(request: PermissionRequest, permissions: AgentPermissions, rootGranted: Boolean): AuthorizationAssessment {
        val disabled = when {
            request.kind != PermissionKind.SYSTEM_READ && permissions.mode == PermissionMode.READ_ONLY -> "当前是只读模式"
            request.kind == PermissionKind.WRITE_FILES && !permissions.allowFileWrite -> "文件写入权限已关闭"
            request.kind == PermissionKind.SYSTEM_READ && !permissions.allowSystemRead -> "系统信息读取权限已关闭"
            request.kind !in setOf(PermissionKind.WRITE_FILES, PermissionKind.SYSTEM_READ) && !permissions.allowCommands -> "命令执行权限已关闭"
            (request.usesRoot || request.kind == PermissionKind.ROOT) && !permissions.allowCommands -> "命令执行权限已关闭"
            (request.usesRoot || request.kind == PermissionKind.ROOT) && !rootGranted -> "尚未授予 Root 权限"
            else -> null
        }
        if (disabled != null) return AuthorizationAssessment(AuthorizationAction.DENY, disabled)
        if (request.needsApproval(permissions.mode) ||
            ((request.usesNetwork || request.kind == PermissionKind.NETWORK) && !permissions.allowNetwork)) {
            return AuthorizationAssessment(AuthorizationAction.CONFIRM,
                if (request.requiresConfirmation) "高危操作必须确认" else "需要单次授权")
        }
        return AuthorizationAssessment(AuthorizationAction.ALLOW,
            if (permissions.mode == PermissionMode.ASK) "工作区内操作，无需逐项审批" else "已授权 · 保留审计记录")
    }
}

data class PermissionDecision(
    val allowed: Boolean,
    val reason: String,
)
