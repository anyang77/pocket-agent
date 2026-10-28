package dev.pocketagent.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pocketagent.data.FileEntry
import java.io.File

@Composable
fun FilesScreen(
    state: AppUiState,
    onRefresh: () -> Unit,
    onOpenFile: (String) -> Unit,
    onOpenDirectory: (String) -> Unit,
    onGoParent: () -> Unit,
    onCloseFile: () -> Unit,
    onEditorChange: (String) -> Unit,
    onSave: (String) -> Unit,
    onRunScript: (String) -> Unit,
    onCloseScriptOutput: () -> Unit,
    onCreateEntry: (String, Boolean) -> Unit,
    onRenameEntry: (String, String) -> Unit,
    onDeleteEntry: (String) -> Unit,
    onOpenWorkspacePicker: () -> Unit,
    onBrowseWorkspaceDirectory: (String) -> Unit,
    onChooseWorkspaceDirectory: (String) -> Unit,
    onCloseWorkspacePicker: () -> Unit,
    modifier: Modifier = Modifier,
) {
    state.scriptOutput?.let { output ->
        ScriptOutputDialog(
            title = state.scriptTitle ?: "脚本运行",
            output = output,
            running = state.scriptRunning,
            onDismiss = onCloseScriptOutput,
        )
    }
    if (state.workspacePickerVisible) {
        WorkspaceDirectoryPicker(
            state = state,
            onBrowse = onBrowseWorkspaceDirectory,
            onChoose = onChooseWorkspaceDirectory,
            onClose = onCloseWorkspacePicker,
        )
    }
    if (state.activeFile == null) {
        FileList(
            state = state,
            onRefresh = onRefresh,
            onOpenFile = onOpenFile,
            onOpenDirectory = onOpenDirectory,
            onGoParent = onGoParent,
            onOpenWorkspacePicker = onOpenWorkspacePicker,
            onCreateEntry = onCreateEntry,
            onRenameEntry = onRenameEntry,
            onDeleteEntry = onDeleteEntry,
            modifier = modifier,
        )
    } else {
        Editor(
            path = state.activeFile,
            content = state.editorContent,
            dirty = state.editorDirty,
            diagnostics = state.editorDiagnostics,
            onClose = onCloseFile,
            onChange = onEditorChange,
            onSave = { onSave(it) },
            onRun = onRunScript,
            modifier = modifier,
        )
    }
}

@Composable
private fun FileList(
    state: AppUiState,
    onRefresh: () -> Unit,
    onOpenFile: (String) -> Unit,
    onOpenDirectory: (String) -> Unit,
    onGoParent: () -> Unit,
    onOpenWorkspacePicker: () -> Unit,
    onCreateEntry: (String, Boolean) -> Unit,
    onRenameEntry: (String, String) -> Unit,
    onDeleteEntry: (String) -> Unit,
    modifier: Modifier,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(state.currentDirectory) {
        listState.scrollToItem(0)
    }
    val currentPath = if (state.currentDirectory == ".") {
        state.workspacePath
    } else {
        state.workspacePath.trimEnd('/') + "/" + state.currentDirectory
    }
    var query by rememberSaveable { mutableStateOf("") }
    var searchVisible by rememberSaveable { mutableStateOf(false) }
    var createDialog by rememberSaveable { mutableStateOf(false) }
    var actionEntry by remember { mutableStateOf<FileEntry?>(null) }
    var renameEntry by remember { mutableStateOf<FileEntry?>(null) }
    var deleteEntry by remember { mutableStateOf<FileEntry?>(null) }
    val filteredFiles = remember(state.files, query) {
        if (query.isBlank()) state.files
        else state.files.filter {
            it.relativePath.substringAfterLast('/').contains(query, ignoreCase = true)
        }
    }

    if (createDialog) {
        CreateEntryDialog(
            onDismiss = { createDialog = false },
            onCreate = { name, directory ->
                createDialog = false
                onCreateEntry(name, directory)
            },
        )
    }
    actionEntry?.let { entry ->
        AlertDialog(
            onDismissRequest = { actionEntry = null },
            title = { Text(entry.relativePath.substringAfterLast('/')) },
            text = { Text(if (entry.isDirectory) "目录操作" else "文件操作") },
            confirmButton = {
                TextButton(
                    onClick = {
                        actionEntry = null
                        renameEntry = entry
                    },
                ) { Text("重命名") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        actionEntry = null
                        deleteEntry = entry
                    },
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.tertiary)
                }
            },
        )
    }
    renameEntry?.let { entry ->
        RenameEntryDialog(
            currentName = entry.relativePath.substringAfterLast('/'),
            onDismiss = { renameEntry = null },
            onRename = { newName ->
                renameEntry = null
                onRenameEntry(entry.relativePath, newName)
            },
        )
    }
    deleteEntry?.let { entry ->
        AlertDialog(
            onDismissRequest = { deleteEntry = null },
            title = { Text("确认删除") },
            text = {
                Text(
                    "将永久删除 ${entry.relativePath}，此操作不可撤销。",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteEntry = null
                        onDeleteEntry(entry.relativePath)
                    },
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.tertiary)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteEntry = null }) { Text("取消") }
            },
        )
    }

    Column(modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (state.currentDirectory != ".") {
                IconButton(onClick = onGoParent) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回上一级",
                    )
                }
            }
            Column(Modifier.weight(1f).clickable(onClick = onOpenWorkspacePicker)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        currentPath.trimEnd('/').substringAfterLast('/').ifBlank { "文件" },
                        modifier = Modifier.weight(1f, fill = false),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Icon(Icons.Filled.ArrowDropDown, "切换工作区", Modifier.size(18.dp))
                }
                Text(
                    currentPath,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = { createDialog = true }) {
                Icon(Icons.Filled.Add, contentDescription = "新建")
            }
            IconButton(onClick = {
                searchVisible = !searchVisible
                if (!searchVisible) query = ""
            }) {
                Icon(Icons.Outlined.Search, contentDescription = "搜索当前目录")
            }
            IconButton(onClick = onRefresh) {
                Icon(Icons.Filled.Refresh, contentDescription = "刷新")
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        if (searchVisible) OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 6.dp),
            placeholder = { Text("搜索当前目录") },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium,
            leadingIcon = { Icon(Icons.Outlined.Search, null, Modifier.size(20.dp)) },
            trailingIcon = {
                IconButton(onClick = { query = ""; searchVisible = false }) {
                    Icon(Icons.Filled.Close, "关闭搜索", Modifier.size(20.dp))
                }
            },
            shape = MaterialTheme.shapes.medium,
        )
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
        ) {
            items(filteredFiles, key = { it.relativePath }) { entry ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            if (entry.isDirectory) {
                                onOpenDirectory(entry.relativePath)
                            } else {
                                onOpenFile(entry.relativePath)
                            }
                        }
                        .heightIn(min = 52.dp)
                        .padding(horizontal = 16.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(11.dp),
                ) {
                    Icon(
                        imageVector = if (entry.isDirectory) {
                            Icons.Outlined.Folder
                        } else {
                            Icons.Outlined.InsertDriveFile
                        },
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = if (entry.isDirectory) {
                            MaterialTheme.colorScheme.tertiary
                        } else {
                            MaterialTheme.colorScheme.secondary
                        },
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = entry.relativePath.substringAfterLast('/'),
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (!entry.isDirectory) {
                            Text(
                                text = formatBytes(entry.sizeBytes),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    IconButton(onClick = { actionEntry = entry }, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "更多操作")
                    }
                }
                HorizontalDivider(
                    modifier = Modifier.padding(start = 50.dp),
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.18f),
                )
            }
        }
    }
}

@Composable
private fun CreateEntryDialog(
    onDismiss: () -> Unit,
    onCreate: (String, Boolean) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var directory by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("名称") },
                    placeholder = {
                        Text(if (directory) "新建文件夹" else "example.kt")
                    },
                    singleLine = true,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { directory = false }) {
                        Text(
                            "文件",
                            color = if (!directory) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                    }
                    TextButton(onClick = { directory = true }) {
                        Text(
                            "文件夹",
                            color = if (directory) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(name.trim(), directory) },
                enabled = name.isNotBlank(),
            ) { Text("创建") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun RenameEntryDialog(
    currentName: String,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit,
) {
    var name by rememberSaveable(currentName) { mutableStateOf(currentName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重命名") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("新名称") },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onRename(name.trim()) },
                enabled = name.isNotBlank() && name.trim() != currentName,
            ) { Text("重命名") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun WorkspaceDirectoryPicker(
    state: AppUiState,
    onBrowse: (String) -> Unit,
    onChoose: (String) -> Unit,
    onClose: () -> Unit,
) {
    val parent = File(state.workspacePickerPath).parentFile?.absolutePath
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("选择工作区") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        state.workspacePickerPath,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontFamily = FontFamily.Monospace,
                        ),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    TextButton(
                        onClick = { parent?.let(onBrowse) },
                        enabled = parent != null && parent != state.workspacePickerPath,
                    ) {
                        Text("上一级")
                    }
                }
                HorizontalDivider()
                when {
                    state.workspacePickerLoading -> Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.padding(4.dp))
                    }
                    state.workspacePickerError != null -> Text(
                        state.workspacePickerError,
                        color = MaterialTheme.colorScheme.tertiary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    state.workspacePickerEntries.isEmpty() -> Text(
                        "当前目录没有子目录，仍然可以选择这里作为工作区。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    else -> LazyColumn(
                        modifier = Modifier.heightIn(max = 380.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        items(state.workspacePickerEntries, key = { it.path }) { entry ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onBrowse(entry.path) }
                                    .padding(horizontal = 6.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Icon(
                                    Icons.Outlined.Folder,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.tertiary,
                                )
                                Text(
                                    entry.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onChoose(state.workspacePickerPath) },
                enabled = !state.workspacePickerLoading,
            ) {
                Text("使用此目录")
            }
        },
        dismissButton = {
            TextButton(onClick = onClose) { Text("取消") }
        },
    )
}

@Composable
private fun Editor(
    path: String,
    content: String,
    dirty: Boolean,
    diagnostics: List<EditorDiagnostic>,
    onClose: () -> Unit,
    onChange: (String) -> Unit,
    onSave: (String) -> Unit,
    onRun: (String) -> Unit,
    modifier: Modifier,
) {
    val language = remember(path) { syntaxLanguageFor(path) }
    val canRun = remember(path) {
        path.substringAfterLast('.', "").lowercase() in
            setOf("sh", "shell", "bash", "py", "python", "js", "mjs", "cjs", "java")
    }
    var localDirty by remember(path) { mutableStateOf(dirty) }
    var cursorLine by remember(path) { mutableStateOf(1) }
    var cursorColumn by remember(path) { mutableStateOf(1) }
    var canUndo by remember(path) { mutableStateOf(false) }
    var canRedo by remember(path) { mutableStateOf(false) }
    var controller by remember(path) { mutableStateOf<CodeEditorController?>(null) }
    var showProblems by rememberSaveable(path) { mutableStateOf(false) }
    var symbolsExpanded by rememberSaveable(path) { mutableStateOf(false) }
    var editorMenuVisible by remember { mutableStateOf(false) }
    LaunchedEffect(dirty) {
        if (!dirty) localDirty = false
    }
    if (showProblems && diagnostics.isNotEmpty()) {
        SyntaxDiagnosticsDialog(
            diagnostics = diagnostics,
            onGoTo = { diagnostic ->
                controller?.goTo(diagnostic.line, diagnostic.column)
                showProblems = false
            },
            onFix = { diagnostic ->
                diagnostic.replacement?.let { replacement ->
                    controller?.applyFix(
                        diagnostic.line,
                        diagnostic.column,
                        replacement,
                    )
                    showProblems = false
                }
            },
            onDismiss = { showProblems = false },
        )
    }
    Column(modifier.imePadding()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = {
                    val editorController = controller
                    if (editorController == null) {
                        onClose()
                    } else {
                        editorController.getContent { latest ->
                            onChange(latest)
                            onClose()
                        }
                    }
                },
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Column(
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = path.substringAfterLast('/') + if (localDirty) " •" else "",
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${language.label} · $cursorLine:$cursorColumn",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(
                onClick = { controller?.openSearch() },
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    Icons.Outlined.Search,
                    contentDescription = "搜索和替换",
                    modifier = Modifier.size(20.dp),
                )
            }
            IconButton(
                onClick = { controller?.undo() },
                modifier = Modifier.size(36.dp),
                enabled = canUndo && controller != null,
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Undo,
                    contentDescription = "撤回",
                    modifier = Modifier.size(20.dp),
                )
            }
            IconButton(
                onClick = {
                    controller?.getContent { latest -> onSave(latest) }
                },
                modifier = Modifier.size(36.dp),
                enabled = localDirty && controller != null,
            ) {
                Icon(
                    Icons.Outlined.Save,
                    contentDescription = "保存",
                    modifier = Modifier.size(20.dp),
                )
            }
            Box {
                IconButton(onClick = { editorMenuVisible = true }, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Filled.MoreVert, "更多编辑操作", Modifier.size(20.dp))
                }
                DropdownMenu(
                    expanded = editorMenuVisible,
                    onDismissRequest = { editorMenuVisible = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("重做") },
                        enabled = canRedo && controller != null,
                        onClick = { editorMenuVisible = false; controller?.redo() },
                    )
                    DropdownMenuItem(
                        text = { Text("代码补全") },
                        enabled = controller != null,
                        onClick = { editorMenuVisible = false; controller?.showCompletions() },
                    )
                    DropdownMenuItem(
                        text = { Text("运行脚本") },
                        enabled = canRun && controller != null,
                        onClick = {
                            editorMenuVisible = false
                            controller?.getContent { latest -> onRun(latest) }
                        },
                    )
                }
            }

        }
        diagnostics.firstOrNull()?.let { diagnostic ->
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 2.dp),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 10.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = buildString {
                            append(
                                if (diagnostic.projectLevel) {
                                    "项目依赖未解析："
                                } else {
                                    "第 ${diagnostic.line} 行："
                                },
                            )
                            append(diagnostic.message)
                            if (diagnostics.size > 1) {
                                append("（另有 ${diagnostics.size - 1} 个问题）")
                            }
                        },
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (diagnostic.replacement != null) {
                        TextButton(
                            onClick = {
                                controller?.applyFix(
                                    diagnostic.line,
                                    diagnostic.column,
                                    diagnostic.replacement,
                                )
                            },
                        ) {
                            Text("修复")
                        }
                    }
                    TextButton(onClick = { showProblems = true }) {
                        Text("全部")
                    }
                }
            }
        }
        CodeEditorWebView(
            path = path,
            initialContent = content,
            language = language,
            diagnostics = diagnostics,
            onContentChanged = onChange,
            onDirtyChanged = { localDirty = it },
            onCursorChanged = { line, column ->
                cursorLine = line
                cursorColumn = column
            },
            onHistoryStateChanged = { undo, redo ->
                canUndo = undo
                canRedo = redo
            },
            onControllerReady = { controller = it },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        )
        SymbolPad(
            expanded = symbolsExpanded,
            onExpandedChange = { symbolsExpanded = it },
            onInsert = { text, cursorOffset -> controller?.insertText(text, cursorOffset) },
        )
    }
}

private data class SymbolKey(val label: String, val text: String, val cursorOffset: Int)

private val compactSymbolKeys = listOf(
    SymbolKey("→", "\t", 1),
    SymbolKey("/", "/", 1),
    SymbolKey("+", "+", 1),
    SymbolKey("-", "-", 1),
    SymbolKey("*", "*", 1),
    SymbolKey("=", "=", 1),
    SymbolKey("<", "<", 1),
)

private val expandedSymbolRows = listOf(
    compactSymbolKeys,
    listOf(
        SymbolKey(">", ">", 1),
        SymbolKey("\"", "\"\"", 1),
        SymbolKey("'", "''", 1),
        SymbolKey(";", ";", 1),
        SymbolKey("|", "|", 1),
        SymbolKey("\\", "\\", 1),
        SymbolKey("_", "_", 1),
    ),
    listOf(
        SymbolKey("()", "()", 1),
        SymbolKey("[]", "[]", 1),
        SymbolKey("{}", "{}", 1),
        SymbolKey("…", "…", 1),
    ),
)

@Composable
private fun SymbolPad(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onInsert: (String, Int) -> Unit,
) {
    BackHandler(enabled = expanded) { onExpandedChange(false) }
    val rows = if (expanded) expandedSymbolRows else listOf(compactSymbolKeys)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(expanded) {
                var dragDistance = 0f
                val threshold = 20.dp.toPx()
                detectVerticalDragGestures(
                    onDragStart = { dragDistance = 0f },
                    onVerticalDrag = { change, amount ->
                        change.consume()
                        dragDistance += amount
                    },
                    onDragEnd = {
                        if (dragDistance < -threshold) onExpandedChange(true)
                        else if (dragDistance > threshold) onExpandedChange(false)
                    },
                )
            },
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            HorizontalDivider(thickness = 0.5.dp)
            if (expanded) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(28.dp)
                        .clickable { onExpandedChange(false) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "默认",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(
                        Modifier.align(Alignment.BottomCenter)
                            .width(48.dp).height(2.dp)
                            .background(MaterialTheme.colorScheme.primary),
                    )
                }
            }
            rows.forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    row.forEach { key ->
                        SymbolKeyButton(
                            key = key,
                            modifier = Modifier.weight(1f),
                            onClick = { onInsert(key.text, key.cursorOffset) },
                        )
                    }
                    repeat(7 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun SymbolKeyButton(
    key: SymbolKey,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .height(38.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = key.label,
            fontFamily = FontFamily.Monospace,
            fontSize = 16.sp,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun SyntaxDiagnosticsDialog(
    diagnostics: List<EditorDiagnostic>,
    onGoTo: (EditorDiagnostic) -> Unit,
    onFix: (EditorDiagnostic) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("代码检查 · ${diagnostics.size}")
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                diagnostics.forEach { diagnostic ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onGoTo(diagnostic) },
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        ) {
                            Text(
                                if (diagnostic.projectLevel) {
                                    "项目依赖未解析 · 不是当前文件的语法错误"
                                } else {
                                    "第 ${diagnostic.line} 行，第 ${diagnostic.column} 列"
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = if (diagnostic.projectLevel) {
                                    MaterialTheme.colorScheme.tertiary
                                } else {
                                    MaterialTheme.colorScheme.error
                                },
                            )
                            Text(
                                diagnostic.message,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            if (diagnostic.replacement != null) {
                                TextButton(onClick = { onFix(diagnostic) }) {
                                    Text("一键修复为 ${diagnostic.replacement}")
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        },
    )
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 1_024 -> "$bytes B"
    bytes < 1_048_576 -> "%.1f KB".format(bytes / 1_024.0)
    else -> "%.1f MB".format(bytes / 1_048_576.0)
}

@Composable
private fun ScriptOutputDialog(
    title: String,
    output: String,
    running: Boolean,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("运行输出", style = MaterialTheme.typography.titleMedium)
                Text(
                    title,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (running) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                SelectionContainer {
                    Text(
                        text = output.ifBlank { "(暂无输出)" },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 480.dp)
                            .verticalScroll(rememberScrollState()),
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                        ),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(if (running) "后台运行" else "关闭")
            }
        },
    )
}
