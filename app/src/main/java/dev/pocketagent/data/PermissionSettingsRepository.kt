package dev.pocketagent.data

import android.content.Context
import dev.pocketagent.agent.AgentPermissions
import dev.pocketagent.agent.PermissionMode

class PermissionSettingsRepository(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences("pocket_agent_permissions", Context.MODE_PRIVATE)

    fun load(): AgentPermissions {
        val mode = runCatching {
            PermissionMode.valueOf(
                preferences.getString("mode", PermissionMode.ASK.name)!!,
            )
        }.getOrDefault(PermissionMode.ASK)
        return AgentPermissions(
            // Legacy AUTO selections did not show the full-access risk confirmation.
            mode = if (mode == PermissionMode.AUTO &&
                !preferences.getBoolean("full_access_risk_confirmed_v1", false)
            ) PermissionMode.ASK else mode,
            allowFileWrite = preferences.getBoolean("allow_file_write", true),
            allowCommands = preferences.getBoolean("allow_commands", true),
            allowNetwork = preferences.getBoolean("allow_network", false),
            allowSystemRead = preferences.getBoolean("allow_system_read", true),
        )
    }

    fun save(permissions: AgentPermissions) {
        preferences.edit()
            .putString("mode", permissions.mode.name)
            .putBoolean("full_access_risk_confirmed_v1", permissions.mode == PermissionMode.AUTO)
            .putBoolean("allow_file_write", permissions.allowFileWrite)
            .putBoolean("allow_commands", permissions.allowCommands)
            .putBoolean("allow_network", permissions.allowNetwork)
            .putBoolean("allow_system_read", permissions.allowSystemRead)
            .apply()
    }
}
