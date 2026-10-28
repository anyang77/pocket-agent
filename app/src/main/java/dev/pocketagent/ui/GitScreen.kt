package dev.pocketagent.ui

import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pocketagent.data.GitChange
import dev.pocketagent.data.GitCommit
import dev.pocketagent.data.GitCommitDetail
import dev.pocketagent.data.GitRefType
import dev.pocketagent.data.GitHubRepository

private enum class GitSection {
    LOCAL,
    REMOTE,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GitScreen(
    state: AppUiState,
    onRefresh: () -> Unit,
    onInit: () -> Unit,
    onClone: (String, String, String, String, String?, String?) -> Unit,
    onStage: (GitChange) -> Unit,
    onStageAll: () -> Unit,
    onUnstage: (GitChange) -> Unit,
    onDiscard: (GitChange) -> Unit,
    onDiscardAll: (List<GitChange>) -> Unit,
    onCommit: (String) -> Unit,
    onSwitchBranch: (String, Boolean) -> Unit,
    onPull: () -> Unit,
    onPush: () -> Unit,
    onOpenDiff: (GitChange) -> Unit,
    onCloseDiff: () -> Unit,
    onOpenCommitDetail: (String) -> Unit,
    onCloseCommitDetail: () -> Unit,
    onGitAccountRemoved: (String) -> Unit,
    onGitHubLogin: () -> Unit,
    onCancelGitHubLogin: () -> Unit,
    onSaveGitHubClientId: (String) -> Unit,
    onRefreshGitHubRepositories: () -> Unit,
    onLoadCloneBranches: suspend (String) -> dev.pocketagent.data.GitRemoteBranches,
    onSwitchRepository: (String) -> Unit,
    onRemoveRepository: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var commitMessage by rememberSaveable { mutableStateOf("") }
    var cloneDialog by rememberSaveable { mutableStateOf(false) }
    var cloneUrl by rememberSaveable { mutableStateOf("") }
    var cloneFolder by rememberSaveable { mutableStateOf("") }
    var branchDialog by rememberSaveable { mutableStateOf(false) }
    var localRepositoriesVisible by rememberSaveable { mutableStateOf(false) }
    var repositoriesVisible by rememberSaveable { mutableStateOf(false) }
    var section by rememberSaveable { mutableStateOf(GitSection.LOCAL) }
    var historyVisible by rememberSaveable { mutableStateOf(false) }
    var expandedCommit by rememberSaveable { mutableStateOf<String?>(null) }
    var actionCommit by remember { mutableStateOf<GitCommit?>(null) }
    var discardTarget by remember { mutableStateOf<GitChange?>(null) }
    var discardAllTarget by remember { mutableStateOf<List<GitChange>>(emptyList()) }
    var openedLoginCode by rememberSaveable { mutableStateOf("") }
    var githubClientId by rememberSaveable { mutableStateOf("") }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val git = state.gitState
    val activeGitAccount = state.gitAccounts.firstOrNull {
        it.id == state.activeGitAccountId
    }
    val context = LocalContext.current

    actionCommit?.let { commit ->
        CommitActionsDialog(
            commit = commit,
            onCopy = { label, value ->
                context.getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText(label, value))
                Toast.makeText(context, "已复制$label", Toast.LENGTH_SHORT).show()
                actionCommit = null
            },
            onDismiss = { actionCommit = null },
        )
    }

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

    discardTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { discardTarget = null },
            title = { Text("撤销改动") },
            text = {
                Text(
                    if (target.untracked) {
                        "将删除未跟踪的文件 ${target.path}，删除后无法恢复。"
                    } else {
                        "将把 ${target.path} 恢复到上次提交的状态，本地改动会丢失且无法恢复。"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        discardTarget = null
                        onDiscard(target)
                    },
                ) {
                    Text("撤销", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { discardTarget = null }) { Text("取消") }
            },
        )
    }

    if (discardAllTarget.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { discardAllTarget = emptyList() },
            title = { Text("撤销全部改动") },
            text = {
                Text(
                    "将撤销 ${discardAllTarget.size} 个文件的改动，" +
                        "其中未跟踪文件会被直接删除，操作无法恢复。",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val targets = discardAllTarget
                        discardAllTarget = emptyList()
                        onDiscardAll(targets)
                    },
                ) {
                    Text("全部撤销", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { discardAllTarget = emptyList() }) { Text("取消") }
            },
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
        actionCommit = null
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
                    Column(Modifier.weight(1f)) {
                        Text(
                            "提交图谱",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            "本地与远程统一显示 · 长按提交可复制 ID",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = closeHistory) { Text("关闭") }
                }
                HorizontalDivider()
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    itemsIndexed(
                        git.history,
                        key = { index, line ->
                            line.commit?.let { "commit-${it.fullHash}" } ?: "graph-$index"
                        },
                    ) { _, line ->
                        val commit = line.commit
                        if (commit == null) {
                            GraphOnlyRow(line.graph)
                        } else {
                            val expanded = expandedCommit == commit.hash
                            CommitRow(
                                commit = commit,
                                graph = line.graph,
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
                                onLongPress = { actionCommit = commit },
                            )
                        }
                    }
                    if (git.history.isEmpty() && git.commits.isEmpty()) {
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
            initialUrl = cloneUrl,
            initialFolder = cloneFolder,
            onLoadBranches = onLoadCloneBranches,
            onDismiss = { cloneDialog = false; cloneUrl = ""; cloneFolder = "" },
            onClone = onClone,
        )
    }

    if (repositoriesVisible) {
        ModalBottomSheet(
            onDismissRequest = { repositoriesVisible = false },
            sheetState = sheetState,
        ) {
            GitHubRepositorySheet(
                state = state,
                onRefresh = onRefreshGitHubRepositories,
                onClone = { repository ->
                    repositoriesVisible = false
                    cloneUrl = repository.cloneUrl
                    cloneFolder = repository.name
                    cloneDialog = true
                },
            )
        }
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

    if (localRepositoriesVisible) {
        ModalBottomSheet(
            onDismissRequest = { localRepositoriesVisible = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            Text(
                "切换仓库",
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                style = MaterialTheme.typography.titleMedium,
            )
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            ) {
                items(state.managedRepositories, key = { it.path }) { repository ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(
                            Modifier.weight(1f).clickable {
                                localRepositoriesVisible = false
                                onSwitchRepository(repository.path)
                            }.padding(vertical = 12.dp),
                        ) {
                            Text(
                                repository.path.trimEnd('/').substringAfterLast('/'),
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (repository.path == state.workspacePath) {
                                    MaterialTheme.colorScheme.primary
                                } else MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                repository.path,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        TextButton(onClick = { onRemoveRepository(repository.path) }) { Text("移除") }
                    }
                }
                if (state.managedRepositories.isEmpty()) item {
                    Text("暂无已保存的仓库", modifier = Modifier.padding(16.dp))
                }
            }
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f).clickable { localRepositoriesVisible = true }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            state.workspacePath.trimEnd('/').substringAfterLast('/'),
                            modifier = Modifier.weight(1f, fill = false),
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        Icon(Icons.Filled.ArrowDropDown, "切换仓库", Modifier.size(18.dp))
                    }
                    Text(
                        state.workspacePath,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = onRefresh, enabled = !state.gitLoading) {
                    Icon(Icons.Filled.Refresh, contentDescription = "刷新 Git", Modifier.size(22.dp))
                }
            }
        }
        item {
            SecondaryTabRow(
                selectedTabIndex = section.ordinal,
                containerColor = MaterialTheme.colorScheme.background,
                divider = { HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant) },
            ) {
                Tab(selected = section == GitSection.LOCAL,
                    onClick = { section = GitSection.LOCAL }, text = { Text("本地") })
                Tab(selected = section == GitSection.REMOTE,
                    onClick = { section = GitSection.REMOTE }, text = { Text("远程") })
            }
        }
        if (section == GitSection.REMOTE) item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (activeGitAccount == null) "GitHub 登录" else "GitHub 账号",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                if (activeGitAccount == null) {
                                    "登录后可浏览并克隆 GitHub 仓库"
                                } else {
                                    "已登录 ${activeGitAccount.label}"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (activeGitAccount != null) {
                            TextButton(onClick = { onGitAccountRemoved(activeGitAccount.id) }) {
                                Text("退出登录")
                            }
                        } else if (state.githubLoginRunning) {
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
                    if (activeGitAccount == null && !state.githubOAuthClientConfigured) {
                        Text(
                            "连接 GitHub 需要 OAuth Client ID，可在 GitHub 开发者设置中创建。",
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
                }
            }
        }
        if (section == GitSection.REMOTE) item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("GitHub 仓库", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "显示当前账号可访问的仓库，点选即可克隆",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (state.activeGitAccountId != null) {
                            IconButton(
                                onClick = onRefreshGitHubRepositories,
                                enabled = !state.githubRepositoriesLoading,
                            ) {
                                Icon(Icons.Filled.Refresh, contentDescription = "刷新仓库")
                            }
                        }
                    }
                    if (state.activeGitAccountId == null) {
                        Text(
                            "登录 GitHub 后显示仓库",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        if (state.githubRepositoriesLoading) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                        Text(
                            "可访问 ${state.githubRepositories.size} 个仓库",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        state.githubRepositoriesError?.let { error ->
                            Text(
                                error,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        Button(
                            onClick = {
                                repositoriesVisible = true
                                onRefreshGitHubRepositories()
                            },
                            enabled = !state.githubRepositoriesLoading,
                        ) {
                            Text("浏览仓库")
                        }
                    }
                }
            }
        }
        if (state.gitLoading) {
            item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        }
        state.gitError?.let { error ->
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
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
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (section == GitSection.LOCAL) {
                            Text("当前工作区不是 Git 仓库", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "可以在这里初始化本地仓库，或去远程页面克隆已有仓库。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Button(onClick = onInit) { Text("初始化本地仓库") }
                                OutlinedButton(
                                    onClick = { section = GitSection.REMOTE },
                                ) { Text("前往远程") }
                            }
                        } else {
                            Text("克隆远程仓库", style = MaterialTheme.typography.titleSmall)
                            Text(
                                if (state.activeGitAccountId == null) {
                                    "先登录 GitHub，随后可浏览账号中的仓库并直接克隆。"
                                } else {
                                    "当前账号已连接，可以浏览仓库，也可以输入公开仓库 URL。"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            OutlinedButton(
                                onClick = { cloneDialog = true },
                                enabled = !state.gitLoading,
                            ) { Text("按 URL 克隆") }
                        }
                    }
                }
            }
        }
        if (section == GitSection.LOCAL && git.isRepository) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(git.branch.ifBlank { "未创建分支" }, style = MaterialTheme.typography.titleSmall)
                            Text(
                                "已暂存 ${git.staged.size} · 更改 ${git.changes.size}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        AssistChip(
                            onClick = { branchDialog = true },
                            label = { Text("分支") },
                        )
                        IconButton(onClick = { historyVisible = true }) {
                            Icon(Icons.Filled.History, contentDescription = "提交历史")
                        }
                    }
                }
            }
        }
        if (section == GitSection.LOCAL && git.isRepository && git.staged.isNotEmpty()) {
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
                            onDiscard = { discardTarget = change },
                        )
                    }
                }
            }
        }
        if (section == GitSection.LOCAL && git.isRepository && git.changes.isNotEmpty()) {
            item {
                ChangesHeader(
                    count = git.changes.size,
                    enabled = !state.gitLoading,
                    onStageAll = onStageAll,
                    onDiscardAll = { discardAllTarget = git.changes + git.staged },
                )
            }
            item(key = "working-changes-list") {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    git.changes.forEach { change ->
                        GitChangeRow(
                            change = change,
                            action = "暂存",
                            onOpen = { onOpenDiff(change) },
                            onAction = { onStage(change) },
                            onDiscard = { discardTarget = change },
                        )
                    }
                }
            }
        }
        if (
            section == GitSection.LOCAL &&
            git.isRepository &&
            git.staged.isEmpty() &&
            git.changes.isEmpty()
        ) {
            item {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(Icons.Outlined.CheckCircle, null, Modifier.size(28.dp),
                        tint = MaterialTheme.colorScheme.primary)
                    Text("没有待提交的改动", style = MaterialTheme.typography.bodyMedium)
                    Text("文件修改后会显示在这里", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (section == GitSection.LOCAL && git.isRepository &&
            (git.changes.isNotEmpty() || git.staged.isNotEmpty() || commitMessage.isNotBlank())) item {
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
        if (section == GitSection.LOCAL && git.isRepository &&
            (git.changes.isNotEmpty() || git.staged.isNotEmpty() || commitMessage.isNotBlank())) item {
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
            }
        }
        if (section == GitSection.REMOTE && git.isRepository) item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("远程同步", style = MaterialTheme.typography.titleSmall)
                    Text(
                        when {
                            !git.hasUpstream -> "当前分支尚未设置远程分支"
                            git.ahead == 0 && git.behind == 0 ->
                                "已与 ${git.upstream} 同步"
                            else -> "本地待推送 ↑${git.ahead} · 远程待拉取 ↓${git.behind}"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
            }
        }
        if (section == GitSection.REMOTE && git.isRepository) item {
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
    onDiscard: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
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
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "更多操作", Modifier.size(18.dp))
                }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(
                        text = {
                            Text("撤销改动", color = MaterialTheme.colorScheme.error)
                        },
                        onClick = {
                            menu = false
                            onDiscard()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ChangesHeader(
    count: Int,
    enabled: Boolean,
    onStageAll: () -> Unit,
    onDiscardAll: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "更改 · $count",
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleSmall,
        )
        TextButton(onClick = onDiscardAll, enabled = enabled) { Text("全部撤销") }
        TextButton(onClick = onStageAll, enabled = enabled) { Text("全部暂存") }
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
private fun GraphText(
    graph: String,
    modifier: Modifier = Modifier,
) {
    val palette = listOf(
        MaterialTheme.colorScheme.primary,
        TermuxEditorColors.string,
        TermuxEditorColors.type,
        TermuxEditorColors.number,
    )
    Text(
        text = buildAnnotatedString {
            graph.forEachIndexed { index, char ->
                if (char == ' ') {
                    append(char)
                } else {
                    withStyle(SpanStyle(color = palette[index % palette.size])) {
                        append(char)
                    }
                }
            }
        },
        modifier = modifier,
        style = MaterialTheme.typography.bodySmall.copy(
            fontFamily = FontFamily.Monospace,
            lineHeight = 17.sp,
        ),
        softWrap = false,
        maxLines = 1,
    )
}

@Composable
private fun GraphOnlyRow(graph: String) {
    GraphText(
        graph = graph,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp),
    )
}

@Composable
private fun CommitActionsDialog(
    commit: GitCommit,
    onCopy: (String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("提交操作") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    commit.subject,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = { onCopy("完整提交 ID", commit.fullHash) }) {
                    Text("复制完整 ID")
                }
                TextButton(onClick = { onCopy("短提交 ID", commit.hash) }) {
                    Text("复制短 ID")
                }
                TextButton(onClick = { onCopy("提交说明", commit.subject) }) {
                    Text("复制提交说明")
                }
                TextButton(
                    onClick = {
                        onCopy(
                            "完整提交信息",
                            "${commit.fullHash}\n${commit.subject}\n" +
                                "${commit.author} · ${commit.date}",
                        )
                    },
                ) {
                    Text("复制完整信息")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CommitRow(
    commit: GitCommit,
    graph: String,
    expanded: Boolean,
    loading: Boolean,
    detail: GitCommitDetail?,
    onToggle: () -> Unit,
    onLongPress: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onToggle,
                onLongClick = onLongPress,
            ),
        shape = RoundedCornerShape(12.dp),
        color = if (expanded) {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
        } else {
            MaterialTheme.colorScheme.surface
        },
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GraphText(graph.ifBlank { "* " })
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
                    if (commit.refs.isNotEmpty()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(top = 5.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            commit.refs.forEach { ref ->
                                Text(
                                    ref.name,
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.SemiBold,
                                    ),
                                    color = when (ref.type) {
                                        GitRefType.HEAD -> MaterialTheme.colorScheme.primary
                                        GitRefType.LOCAL -> TermuxEditorColors.string
                                        GitRefType.REMOTE -> TermuxEditorColors.type
                                        GitRefType.TAG -> TermuxEditorColors.number
                                    },
                                )
                            }
                        }
                    }
                }
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
private fun GitHubRepositorySheet(
    state: AppUiState,
    onRefresh: () -> Unit,
    onClone: (GitHubRepository) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 700.dp)
            .padding(bottom = 18.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("克隆 GitHub 仓库", style = MaterialTheme.typography.titleMedium)
                Text(
                    state.gitAccounts.firstOrNull { it.id == state.activeGitAccountId }?.label
                        ?: "GitHub",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(
                onClick = onRefresh,
                enabled = !state.githubRepositoriesLoading,
            ) {
                Icon(Icons.Filled.Refresh, contentDescription = "刷新仓库")
            }
        }
        if (state.githubRepositoriesLoading) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        state.githubRepositoriesError?.let { error ->
            Text(
                error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        HorizontalDivider()
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(state.githubRepositories, key = { it.fullName }) { repository ->
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onClone(repository) },
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                repository.fullName,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontWeight = FontWeight.SemiBold,
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            repository.description.takeIf { it.isNotBlank() }?.let {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Text(
                                buildString {
                                    append(if (repository.private) "私有" else "公开")
                                    append(" · ")
                                    append(repository.defaultBranch)
                                    if (repository.updatedAt.isNotBlank()) {
                                        append(" · ")
                                        append(repository.updatedAt.take(10))
                                    }
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = { onClone(repository) }) {
                            Text("克隆")
                        }
                    }
                }
            }
            if (
                !state.githubRepositoriesLoading &&
                state.githubRepositories.isEmpty() &&
                state.githubRepositoriesError == null
            ) {
                item {
                    Text(
                        "没有可克隆的仓库",
                        modifier = Modifier.padding(vertical = 24.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
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
    initialUrl: String,
    initialFolder: String,
    onLoadBranches: suspend (String) -> dev.pocketagent.data.GitRemoteBranches,
    onDismiss: () -> Unit,
    onClone: (String, String, String, String, String?, String?) -> Unit,
) {
    var url by rememberSaveable { mutableStateOf(initialUrl) }
    var folder by rememberSaveable { mutableStateOf(initialFolder) }
    var branches by remember { mutableStateOf<List<String>>(emptyList()) }
    var selectedBranch by rememberSaveable { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var expanded by remember { mutableStateOf(false) }
    var retry by remember { mutableStateOf(0) }
    androidx.compose.runtime.LaunchedEffect(url, retry) {
        branches = emptyList()
        selectedBranch = ""
        error = null
        loading = url.isNotBlank()
        if (url.isBlank()) return@LaunchedEffect
        kotlinx.coroutines.delay(500)
        try {
            val result = onLoadBranches(url.trim())
            branches = result.names
            selectedBranch = result.defaultBranch?.takeIf { it in branches }
                ?: branches.firstOrNull().orEmpty()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "读取分支失败"
        } finally {
            loading = false
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("克隆 Git 仓库") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("自动读取远程分支，私有 GitHub 仓库复用当前登录账号。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(value = url, onValueChange = { url = it; branches = emptyList(); selectedBranch = "" },
                    label = { Text("仓库 URL") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(value = folder, onValueChange = { folder = it },
                    label = { Text("目录名") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                if (loading) {
                    Text("正在读取远程分支…")
                }
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { retry++ }) { Text("重试读取分支") }
                }
                Box {
                    OutlinedButton(onClick = { expanded = true }, enabled = branches.isNotEmpty()) {
                        Text(if (selectedBranch.isBlank()) "选择分支" else "分支：$selectedBranch")
                        Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                    }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false },
                        modifier = Modifier.heightIn(max = 300.dp)) {
                        branches.forEach { branch ->
                            DropdownMenuItem(text = { Text(branch) }, onClick = {
                                selectedBranch = branch
                                expanded = false
                            })
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(enabled = url.isNotBlank() && folder.isNotBlank() &&
                !loading && selectedBranch in branches,
                onClick = {
                    onClone(url.trim(), folder.trim(), "", "", null, selectedBranch)
                    onDismiss()
                }) { Text("克隆") }
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
