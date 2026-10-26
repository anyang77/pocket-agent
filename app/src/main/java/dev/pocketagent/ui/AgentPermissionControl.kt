package dev.pocketagent.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.pocketagent.agent.AgentAccessPolicy
import dev.pocketagent.agent.AgentPermissions
import dev.pocketagent.agent.PermissionMode

/** Permission selector rendered as a compact pill so it matches the header control strip. */
@Composable
fun AgentPermissionControl(
    permissions: AgentPermissions,
    onChange: (AgentPermissions) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var confirmingFullAccess by rememberSaveable { mutableStateOf(false) }
    val fullAccess = AgentAccessPolicy.hasFullAccess(permissions)
    val label = when {
        fullAccess -> "完整权限"
        permissions.mode == PermissionMode.READ_ONLY -> "只读"
        else -> "审计"
    }
    fun select(mode: PermissionMode) {
        onChange(
            AgentPermissions(
                mode = mode,
                allowFileWrite = mode != PermissionMode.READ_ONLY,
                allowCommands = mode != PermissionMode.READ_ONLY,
                allowNetwork = mode != PermissionMode.READ_ONLY,
                allowSystemRead = true,
            ),
        )
        expanded = false
    }

    Box {
        AgentPill(
            label = label,
            trailingIcon = Icons.Filled.ArrowDropDown,
            dotColor = when {
                fullAccess -> MaterialTheme.colorScheme.error
                permissions.mode == PermissionMode.READ_ONLY ->
                    MaterialTheme.colorScheme.onSurfaceVariant
                else -> MaterialTheme.colorScheme.primary
            },
            active = fullAccess,
            onClick = { expanded = true },
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.widthIn(max = 300.dp),
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(16.dp),
            tonalElevation = 3.dp,
            shadowElevation = 10.dp,
        ) {
            Column(Modifier.padding(vertical = 4.dp)) {
                AgentMenuSection(text = "执行权限", trailing = label)
                AgentMenuItem(
                    title = "只读模式",
                    subtitle = "不写入、不执行命令",
                    selected = permissions.mode == PermissionMode.READ_ONLY,
                    onClick = { select(PermissionMode.READ_ONLY) },
                )
                AgentMenuItem(
                    title = "审计模式",
                    subtitle = "写入、执行、联网逐项确认",
                    selected = permissions.mode == PermissionMode.ASK,
                    onClick = { select(PermissionMode.ASK) },
                )
                AgentMenuItem(
                    title = "完整权限",
                    subtitle = "设备范围内的读写与命令自动执行",
                    selected = fullAccess,
                    onClick = {
                        expanded = false
                        if (fullAccess) {
                            select(PermissionMode.ASK)
                        } else {
                            confirmingFullAccess = true
                        }
                    },
                )
                Text(
                    "新权限用于后续操作，已开始的命令不会撤销；所有模式都保留审计记录。",
                    modifier = Modifier.padding(horizontal = 15.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (confirmingFullAccess) {
        AlertDialog(
            onDismissRequest = { confirmingFullAccess = false },
            title = { Text("确认授予完整权限？") },
            text = {
                Text("模型可不再逐项询问，自动读取、修改、删除工作区以外的文件及其他应用配置，执行系统命令并联网。已授予 Root 时会自动使用 Root。\n\n错误指令或不可信内容可能导致隐私、密钥泄露，数据永久丢失，应用损坏、设备无法启动等严重后果。审计记录不是备份，也不能保证恢复。系统实际权限和只读挂载仍然有效。\n\n仅在理解风险并信任任务内容时启用；可随时切回审计模式。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmingFullAccess = false
                        select(PermissionMode.AUTO)
                    },
                ) { Text("我了解风险，启用完整权限") }
            },
            dismissButton = {
                TextButton(onClick = { confirmingFullAccess = false }) { Text("取消") }
            },
        )
    }
}
