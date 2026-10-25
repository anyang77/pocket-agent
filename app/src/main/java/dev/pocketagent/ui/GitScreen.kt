package dev.pocketagent.ui

import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pocketagent.data.GitChange
import dev.pocketagent.data.GitCommit
import dev.pocketagent.data.GitCommitDetail
import dev.pocketagent.data.SshAccount

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GitScreen(
    state: AppUiState,
    onRefresh: () -> Unit,
    onInit: () -> Unit,
    onClone: (String, String, String, String, String?) -> Unit,
    onStage: (GitChange) -> Unit,
    onStageAll: () -> Unit,
    onUnstage: (GitChange) -> Unit,
    onCommit: (String) -> Unit,
    onSwitchBranch: (String, Boolean) -> Unit,
    onPull: () -> Unit,
    onPush: () -> Unit,
    onOpenDiff: (GitChange) -> Unit,
    onCloseDiff: () -> Unit,
    onOpenCommitDetail: (String) -> Unit,
    onCloseCommitDetail: () -> Unit,
    onGitAccountSelected: (String) -> Unit,
    onGitAccountRemoved: (String) -> Unit,
    onGitHubLogin: () -> Unit,
    onCancelGitHubLogin: () -> Unit,
    onSaveGitHubClientId: (String) -> Unit,
    onGenerateSshAccount: (String) -> Unit,
    onRemoveSshAccount: (String) -> Unit,
    onSwitchRepository: (String) -> Unit,
    onRemoveRepository: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var commitMessage by rememberSaveable { mutableStateOf("") }
    var cloneDialog by rememberSaveable { mutableStateOf(false) }
    var branchDialog by rememberSaveable { mutableStateOf(false) }
    var sshDialog by rememberSaveable { mutableStateOf(false) }
    var manageVisible by rememberSaveable { mutableStateOf(false) }
    var historyVisible by rememberSaveable { mutableStateOf(false) }
    var expandedCommit by rememberSaveable { mutableStateOf<String?>(null) }
    var openedLoginCode by rememberSaveable { mutableStateOf("") }
    var githubClientId by rememberSaveable { mutableStateOf("") }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val git = state.gitState
    val context = LocalContext.current

    LaunchedEffect(state.githubLogin?.userCode) {
        val login = state.githubLogin ?: return@LaunchedEffect
        if (login.userCode.isBlank() || login.userCode == openedLoginCode) {
            return@LaunchedEffect
        }
        openedLoginCode = login.userCode
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(login.verificationUri)),
            )
        }
    }

    state.githubLogin?.let { login ->
        GitHubDeviceLoginDialog(
            login = login,
            error = state.githubLoginError,
            onOpenBrowser = {
                runCatching {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(login.verificationUri)),
                    )
                }
            },
            onCopyCode = {
                context.getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText("GitHub code", login.userCode))
            },
            onCancel = onCancelGitHubLogin,
        )
    }

    if (state.gitDiffTitle != null) {
        ModalBottomSheet(
            onDismissRequest = onCloseDiff,
            sheetState = sheetState,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 240.dp, max = 680.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        state.gitDiffTitle.orEmpty(),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    TextButton(onClick = onCloseDiff) { Text("关闭") }
                }
                HorizontalDivider()
                if (state.gitLoading) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                state.gitError?.let { error ->
                    Text(
                        text = error,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentPadding = PaddingValues(12.dp),
                ) {
                    items(state.gitDiff.lines()) { line ->
                        Text(
                            line,
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                lineHeight = 18.sp,
                            ),
                            color = when {
                                line.startsWith("+") && !line.startsWith("+++") ->
                                    TermuxEditorColors.string
                                line.startsWith("-") && !line.startsWith("---") ->
                                    TermuxEditorColors.operator
                                line.startsWith("@@") -> TermuxEditorColors.type
                                else -> MaterialTheme.colorScheme.onSurface
                            },
                            softWrap = false,
                        )
                    }
                }
            }
        }
    }

    val closeHistory = {
        historyVisible = false
        expandedCommit = null
        onCloseCommitDetail()
    }
    if (historyVisible) {
        ModalBottomSheet(
            onDismissRequest = closeHistory,
            sheetState = sheetState,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 640.dp)
                    .padding(bottom = 18.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (git.hasUpstream) {
                            "提交历史 · ${git.upstream}"
                        } else {
                            "提交历史 · 仅本地"
                        },
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    TextButton(onClick = closeHistory) { Text("关闭") }
                }
                HorizontalDivider()
                LazyColumn(contentPadding = PaddingValues(16.dp)) {
                    items(git.commits, key = { "history-${it.hash}" }) { commit ->
                        val expanded = expandedCommit == commit.hash
                        CommitRow(
                            commit = commit,
                            hasUpstream = git.hasUpstream,
                            expanded = expanded,
                            loading = expanded && state.gitCommitDetailLoading,
                            detail = state.gitCommitDetail?.takeIf {
                                expanded && it.hash.startsWith(commit.hash)
                            },
                            onToggle = {
                                if (expanded) {
                                    expandedCommit = null
                                    onCloseCommitDetail()
                                } else {
                                    expandedCommit = commit.hash
                                    onOpenCommitDetail(commit.hash)
                                }
                            },
                        )
                    }
                    if (git.commits.isEmpty()) {
                        item {
                            Text(
                                "还没有提交记录",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }

    if (cloneDialog) {
        CloneDialog(
            sshAccounts = state.sshAccounts,
            onDismiss = { cloneDialog = false },
            onClone = { url, folder, username, token, sshAccountId ->
                cloneDialog = false
                onClone(url, folder, username, token, sshAccountId)
            },
        )
    }

    if (sshDialog) {
        NewSshAccountDialog(
            generating = state.sshKeyGenerating,
            onDismiss = { sshDialog = false },
            onCreate = {
                sshDialog = false
                onGenerateSshAccount(it)
            },
        )
    }

    if (branchDialog) {
        BranchDialog(
            branches = git.branches,
            current = git.branch,
            onDismiss = { branchDialog = false },
            onSelect = { branch, create ->
                branchDialog = false
                onSwitchBranch(branch, create)
            },
        )
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Git", style = MaterialTheme.typography.titleLarge)
                    Text(
                        state.workspacePath,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (git.isRepository) {
                        Text(
                            text = "已暂存 ${git.staged.size} · 更改 ${git.changes.size}",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (git.staged.isNotEmpty() || git.changes.isNotEmpty()) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                        Text(
                            text = when {
                                !git.hasUpstream -> "仅本地仓库 · 尚未设置远程分支"
                                git.ahead == 0 && git.behind == 0 ->
                                    "已与 ${git.upstream} 同步"
                                else -> "本地未推送 ↑${git.ahead} · 远程待拉取 ↓${git.behind}"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (git.ahead > 0 || !git.hasUpstream) {
                                MaterialTheme.colorScheme.tertiary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
                if (git.isRepository) {
                    AssistChip(
                        onClick = { branchDialog = true },
                        label = {
                            Text(
                                git.branch +
                                    if (git.ahead > 0 || git.behind > 0) {
                                        "  ↑${git.ahead} ↓${git.behind}"
                                    } else {
                                        ""
                                    },
                            )
                        },
                    )
                }
                IconButton(
                    onClick = { historyVisible = true },
                    enabled = git.isRepository,
                ) {
                    Icon(Icons.Filled.History, contentDescription = "历史")
                }
                IconButton(onClick = onRefresh, enabled = !state.gitLoading) {
                    Icon(Icons.Filled.Refresh, contentDescription = "刷新 Git")
                }
                TextButton(onClick = { manageVisible = !manageVisible }) {
                    Text(if (manageVisible) "收起" else "管理")
                }
            }
        }
        item {
            if (manageVisible && state.managedRepositories.isNotEmpty()) {
                Card {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Text("本地仓库", style = MaterialTheme.typography.titleSmall)
                        state.managedRepositories.forEach { repository ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    repository.path,
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable {
                                            onSwitchRepository(repository.path)
                                        }
                                        .padding(vertical = 8.dp),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (repository.path == state.workspacePath) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                TextButton(
                                    onClick = { onRemoveRepository(repository.path) },
                                ) { Text("移除") }
                            }
                        }
                    }
                }
            }
        }
        if (manageVisible && state.gitAccounts.isNotEmpty()) {
            item {
                Card {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        Text("GitHub 登录账号", style = MaterialTheme.typography.bodyMedium)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            items(state.gitAccounts, key = { it.id }) { gitAccount ->
                                AssistChip(
                                    onClick = { onGitAccountSelected(gitAccount.id) },
                                    label = {
                                        Text(
                                            gitAccount.label,
                                            color = if (
                                                gitAccount.id == state.activeGitAccountId
                                            ) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.onSurface
                                            },
                                        )
                                    },
                                )
                            }
                        }
                        state.activeGitAccountId?.let { activeId ->
                            TextButton(onClick = { onGitAccountRemoved(activeId) }) {
                                Text("移除当前凭据")
                            }
                        }
                    }
                }
            }
        }
        if (manageVisible) item {
            Card {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("GitHub 登录", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "浏览器确认一次，Token 加密保存，仓库自动复用",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (state.githubLoginRunning) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                            )
                        } else if (state.githubOAuthClientConfigured) {
                            Button(onClick = onGitHubLogin) {
                                Text("使用 GitHub 登录")
                            }
                        }
                    }
                    if (!state.githubOAuthClientConfigured) {
                        Text(
                            "发布版应在构建时设置 Client ID。本地调试也可以在这里填写一次。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedTextField(
                            value = githubClientId,
                            onValueChange = { githubClientId = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("GitHub OAuth Client ID") },
                            singleLine = true,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(
                                onClick = {
                                    runCatching {
                                        context.startActivity(
                                            Intent(
                                                Intent.ACTION_VIEW,
                                                Uri.parse(
                                                    "https://github.com/settings/applications/new",
                                                ),
                                            ),
                                        )
                                    }
                                },
                            ) { Text("创建 OAuth App") }
                            Button(
                                onClick = {
                                    onSaveGitHubClientId(githubClientId)
                                    githubClientId = ""
                                },
                                enabled = githubClientId.isNotBlank(),
                            ) { Text("保存 Client ID") }
                        }
                    }
                    state.githubLoginError?.let { error ->
                        Text(
                            error,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    if (state.gitAccounts.isNotEmpty()) {
                        Text(
                            "已登录 ${state.gitAccounts.joinToString { it.label }}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        if (manageVisible) item {
            Card {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("SSH 账号", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "每个 GitHub 账号添加一次 Public Key，所有仓库通用",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(
                            onClick = { sshDialog = true },
                            enabled = !state.sshKeyGenerating,
                        ) { Text("生成 Key") }
                    }
                    state.sshAccounts.forEach { ssh ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                ssh.label,
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            TextButton(
                                onClick = {
                                    val clipboard = context.getSystemService(
                                        ClipboardManager::class.java,
                                    )
                                    clipboard.setPrimaryClip(
                                        ClipData.newPlainText("SSH Public Key", ssh.publicKey),
                                    )
                                },
                            ) { Text("复制公钥") }
                            TextButton(
                                onClick = {
                                    runCatching {
                                        context.startActivity(
                                            Intent(
                                                Intent.ACTION_VIEW,
                                                Uri.parse("https://github.com/settings/ssh/new"),
                                            ),
                                        )
                                    }
                                },
                            ) { Text("添加") }
                            TextButton(onClick = { onRemoveSshAccount(ssh.id) }) {
                                Text("删除")
                            }
                        }
                    }
                    if (state.sshAccounts.isEmpty()) {
                        Text(
                            "尚未创建 SSH Key",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        if (state.gitLoading) {
            item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        }
        state.gitError?.let { error ->
            item {
                Card {
                    Text(
                        error,
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
        }
        if (!git.isRepository) {
            item {
                Card {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text("当前工作区不是 Git 仓库", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "可以初始化新仓库，或克隆一个远程仓库后自动切换过去。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(onClick = onInit) { Text("初始化仓库") }
                            OutlinedButton(onClick = { cloneDialog = true }) { Text("克隆仓库") }
                        }
                    }
                }
            }
            return@LazyColumn
        }
        if (git.staged.isNotEmpty()) {
            item {
                SectionHeader("已暂存 · ${git.staged.size}", null)
            }
            item(key = "staged-list") {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    git.staged.forEach { change ->
                        GitChangeRow(
                            change = change,
                            action = "取消暂存",
                            onOpen = { onOpenDiff(change) },
                            onAction = { onUnstage(change) },
                        )
                    }
                }
            }
        }
        if (git.changes.isNotEmpty()) {
            item {
                SectionHeader("更改 · ${git.changes.size}", "全部暂存", onStageAll)
            }
            item(key = "working-changes-list") {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    git.changes.forEach { change ->
                        GitChangeRow(
                            change = change,
                            action = "暂存",
                            onOpen = { onOpenDiff(change) },
                            onAction = { onStage(change) },
                        )
                    }
                }
            }
        }
        if (git.staged.isEmpty() && git.changes.isEmpty()) {
            item {
                Text(
                    "工作区没有未提交的改动",
                    modifier = Modifier.padding(vertical = 16.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            OutlinedTextField(
                value = commitMessage,
                onValueChange = { commitMessage = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("提交说明") },
                minLines = 1,
                maxLines = 4,
                shape = RoundedCornerShape(14.dp),
            )
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = {
                        onCommit(commitMessage)
                        commitMessage = ""
                    },
                    enabled = commitMessage.isNotBlank() && git.staged.isNotEmpty() &&
                        !state.gitLoading,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("提交")
                }
                OutlinedButton(
                    onClick = onPull,
                    enabled = !state.gitLoading,
                ) { Text("拉取") }
                OutlinedButton(
                    onClick = onPush,
                    enabled = !state.gitLoading,
                ) { Text("推送") }
            }
        }
        if (manageVisible) item {
            OutlinedButton(
                onClick = { cloneDialog = true },
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.gitLoading,
            ) {
                Text("克隆另一个仓库")
            }
        }
    }
}

@Composable
private fun SectionHeader(
    title: String,
    action: String?,
    onAction: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleSmall,
        )
        if (action != null && onAction != null) {
            TextButton(onClick = onAction) { Text(action) }
        }
    }
}

@Composable
private fun GitChangeRow(
    change: GitChange,
    action: String,
    onOpen: () -> Unit,
    onAction: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        tonalElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                change.displayStatus(),
                style = MaterialTheme.typography.labelMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                ),
                color = when (change.status.firstOrNull()) {
                    'D' -> MaterialTheme.colorScheme.tertiary
                    'A', 'U' -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.secondary
                },
            )
            Text(
                change.path,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 10.dp),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            TextButton(onClick = onOpen) { Text("查看") }
            TextButton(onClick = onAction) { Text(action) }
        }
    }
}

private fun GitChange.displayStatus(): String = when (status) {
    "M" -> "M 修改"
    "A" -> "A 新增"
    "D" -> "D 删除"
    "R" -> "R 重命名"
    "C" -> "C 复制"
    "U" -> "U 未跟踪"
    "!" -> "! 冲突"
    else -> status
}

@Composable
private fun CommitRow(
    commit: GitCommit,
    hasUpstream: Boolean,
    expanded: Boolean,
    loading: Boolean,
    detail: GitCommitDetail?,
    onToggle: () -> Unit,
) {
    val localOnly = !hasUpstream || commit.isAheadOfRemote
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle),
        shape = RoundedCornerShape(12.dp),
        color = if (expanded) {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
        } else {
            MaterialTheme.colorScheme.surface
        },
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    "●",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (localOnly) {
                        MaterialTheme.colorScheme.tertiary
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 9.dp),
                ) {
                    Text(
                        commit.subject,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = if (expanded) 4 else 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${commit.hash} · ${commit.author} · ${commit.date}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    when {
                        !hasUpstream -> "仅本地"
                        commit.isAheadOfRemote -> "本地未推送"
                        else -> "远程已同步"
                    },
                    modifier = Modifier.padding(start = 8.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (localOnly) {
                        MaterialTheme.colorScheme.tertiary
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
                Text(
                    if (expanded) "⌃" else "⌄",
                    modifier = Modifier.padding(start = 8.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (expanded) {
                HorizontalDivider(Modifier.padding(vertical = 10.dp))
                when {
                    loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    detail != null -> {
                        Text(
                            "作者：${detail.author} <${detail.email}>",
                            style = MaterialTheme.typography.labelSmall,
                        )
                        Text(
                            "时间：${detail.date}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "提交：${detail.hash}",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = FontFamily.Monospace,
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (detail.body.isNotBlank()) {
                            Text(
                                detail.body,
                                modifier = Modifier.padding(top = 8.dp),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Text(
                            "改动文件 · ${detail.changes.size}",
                            modifier = Modifier.padding(top = 10.dp, bottom = 5.dp),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        detail.changes.forEach { change ->
                            Text(
                                "${change.displayStatus()}  ${change.path}",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                ),
                                color = when (change.status.firstOrNull()) {
                                    'D' -> TermuxEditorColors.operator
                                    'A' -> TermuxEditorColors.string
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                        val diffLines = detail.diff.lines()
                        Text(
                            "差异",
                            modifier = Modifier.padding(top = 12.dp, bottom = 5.dp),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                        ) {
                            diffLines.take(240).forEach { line ->
                                Text(
                                    line.ifBlank { " " },
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                        lineHeight = 17.sp,
                                    ),
                                    color = when {
                                        line.startsWith("+") && !line.startsWith("+++") ->
                                            TermuxEditorColors.string
                                        line.startsWith("-") && !line.startsWith("---") ->
                                            TermuxEditorColors.operator
                                        line.startsWith("@@") -> TermuxEditorColors.type
                                        else -> MaterialTheme.colorScheme.onSurface
                                    },
                                    softWrap = false,
                                )
                            }
                            if (diffLines.size > 240) {
                                Text(
                                    "… 其余 ${diffLines.size - 240} 行未展开",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NewSshAccountDialog(
    generating: Boolean,
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit,
) {
    var label by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建 SSH 账号") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("账号名称") },
                    placeholder = { Text("例如 工作 GitHub") },
                    singleLine = true,
                )
                Text(
                    "生成后复制 Public Key，并在 GitHub 的 SSH Keys 页面添加一次。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (generating) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(label) },
                enabled = label.isNotBlank() && !generating,
            ) { Text("生成") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun GitHubDeviceLoginDialog(
    login: GitHubLoginState,
    error: String?,
    onOpenBrowser: () -> Unit,
    onCopyCode: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("登录 GitHub") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (login.userCode.isBlank()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(9.dp),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                        Text(login.status, style = MaterialTheme.typography.bodySmall)
                    }
                } else {
                    Text(
                        "浏览器已打开 GitHub 授权页，请输入一次性代码：",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        SelectionContainer {
                            Text(
                                login.userCode,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                style = MaterialTheme.typography.headlineSmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                ),
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = onCopyCode) { Text("复制代码") }
                        TextButton(onClick = onOpenBrowser) { Text("重新打开浏览器") }
                    }
                    Text(
                        login.status,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        login.verificationUri,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                        ),
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                error?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onOpenBrowser, enabled = login.userCode.isNotBlank()) {
                Text("打开浏览器")
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text("取消登录") }
        },
    )
}

@Composable
private fun CloneDialog(
    sshAccounts: List<SshAccount>,
    onDismiss: () -> Unit,
    onClone: (String, String, String, String, String?) -> Unit,
) {
    var url by rememberSaveable { mutableStateOf("") }
    var folder by rememberSaveable { mutableStateOf("repo") }
    var username by rememberSaveable { mutableStateOf("") }
    var token by rememberSaveable { mutableStateOf("") }
    var selectedSshAccountId by rememberSaveable { mutableStateOf<String?>(null) }
    var sshSelectionInitialized by rememberSaveable { mutableStateOf(false) }
    var showHttpsToken by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(sshAccounts) {
        if (!sshSelectionInitialized && sshAccounts.isNotEmpty()) {
            selectedSshAccountId = sshAccounts.first().id
            sshSelectionInitialized = true
        }
    }
    val selectedSshAccount = sshAccounts.firstOrNull { it.id == selectedSshAccountId }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("克隆 Git 仓库") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("仓库 URL") },
                    placeholder = { Text("https://github.com/user/repo.git") },
                    singleLine = true,
                )
                Text(
                    "公开仓库直接 Clone。私有 GitHub 仓库建议选择 SSH；HTTPS Token 仅作为可选项。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (sshAccounts.isNotEmpty()) {
                    Text(
                        "SSH 账号（已配置的 Key 会自动优先使用）",
                        style = MaterialTheme.typography.labelMedium,
                    )
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        item {
                            AssistChip(
                                onClick = { selectedSshAccountId = null },
                                label = {
                                    Text(
                                        if (selectedSshAccountId == null) "HTTPS ✓" else "HTTPS",
                                    )
                                },
                            )
                        }
                        items(sshAccounts, key = { it.id }) { account ->
                            AssistChip(
                                onClick = { selectedSshAccountId = account.id },
                                label = {
                                    Text(
                                        account.label +
                                            if (selectedSshAccountId == account.id) " ✓" else "",
                                        color = if (selectedSshAccountId == account.id) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurface
                                        },
                                    )
                                },
                            )
                        }
                    }
                    selectedSshAccount?.let { account ->
                        Text(
                            "将通过 SSH 克隆 · ${account.alias}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                if (selectedSshAccountId == null) {
                    TextButton(onClick = { showHttpsToken = !showHttpsToken }) {
                        Text(if (showHttpsToken) "收起 HTTPS Token" else "使用 HTTPS Token（可选）")
                    }
                    if (showHttpsToken) {
                        OutlinedTextField(
                            value = username,
                            onValueChange = { username = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("用户名（可选）") },
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = token,
                            onValueChange = { token = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Token（可选）") },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                        )
                    }
                }
                OutlinedTextField(
                    value = folder,
                    onValueChange = { folder = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("工作区内目标目录") },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onClone(url, folder, username, token, selectedSshAccountId)
                },
                enabled = url.isNotBlank() && folder.isNotBlank(),
            ) { Text("克隆") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun BranchDialog(
    branches: List<String>,
    current: String,
    onDismiss: () -> Unit,
    onSelect: (String, Boolean) -> Unit,
) {
    var creating by rememberSaveable { mutableStateOf(false) }
    var newBranch by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (creating) "新建分支" else "切换分支") },
        text = {
            if (creating) {
                OutlinedTextField(
                    value = newBranch,
                    onValueChange = { newBranch = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("分支名") },
                    singleLine = true,
                )
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 360.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(branches) { branch ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(branch, false) }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                if (branch == current) "●" else "○",
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(branch, modifier = Modifier.padding(start = 10.dp))
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (creating) {
                TextButton(
                    onClick = { onSelect(newBranch, true) },
                    enabled = newBranch.isNotBlank(),
                ) { Text("创建并切换") }
            } else {
                TextButton(onClick = { creating = true }) { Text("新建分支") }
            }
        },
        dismissButton = {
            if (creating) {
                TextButton(onClick = { creating = false }) { Text("返回") }
            } else {
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}
