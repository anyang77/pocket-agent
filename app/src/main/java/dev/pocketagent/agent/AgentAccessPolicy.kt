package dev.pocketagent.agent

/** Keep the model's instructions aligned with the permissions enforced by the app. */
object AgentAccessPolicy {
    fun hasFullAccess(permissions: AgentPermissions): Boolean =
        permissions.mode == PermissionMode.AUTO &&
            permissions.allowFileWrite && permissions.allowCommands &&
            permissions.allowNetwork && permissions.allowSystemRead

    fun instructions(permissions: AgentPermissions): String = if (hasFullAccess(permissions)) {
        """
            Access mode: FULL ACCESS (explicitly enabled by the user).
            - The user has authorized device-wide file reads, writes, and shell execution,
              including paths outside the workspace and other apps' configuration files.
            - Use workspace file tools for workspace edits when convenient. They are workspace-scoped,
              not a restriction on your access: use run_command for reads or writes elsewhere,
              binary files, and operations not supported by the dedicated file tools.
            - run_command accepts an absolute cwd in this mode. Full shell syntax is supported.
            - Do not refuse an authorized operation merely because its path is outside the workspace
              or is a system path. Do not request redundant per-action approval in this mode.
            - Root is used automatically only when already granted. Android permissions, SELinux,
              read-only mounts, and actual OS capabilities still apply; report concrete failures.
            - Inspect before editing, back up important configuration, preserve ownership and modes,
              and verify results. Actions remain audited. Stay within the user's task.
        """.trimIndent()
    } else {
        """
            Access mode: LIMITED (full access is not enabled).
            - Workspace writes must use write_file or edit_file. Do not write to system paths.
            - Follow the app's permission decisions and approval requests. Do not bypass disabled
              permissions through shell commands. Network access follows the network permission.
            - System-read tools are read-only and have their own path restrictions. Do not use the
              shell to bypass those restrictions in limited mode.
        """.trimIndent()
    }
}
