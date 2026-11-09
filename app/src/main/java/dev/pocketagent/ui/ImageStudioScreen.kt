package dev.pocketagent.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.pocketagent.agent.ImageGenerationStage
import dev.pocketagent.data.AiSettings
import dev.pocketagent.data.StudioArtwork
import kotlinx.coroutines.delay
import java.io.File

/** One prompt turn per submitted batch, including identical prompts submitted later. */
internal fun studioConversationGroups(history: List<StudioArtwork>): List<List<StudioArtwork>> =
    history.asReversed().groupBy { it.requestId.ifBlank { it.id } }.values.toList()

internal fun studioHistoryIndex(groups: List<List<StudioArtwork>>, artworkId: String, loading: Boolean, profileMissing: Boolean): Int? =
    groups.indexOfFirst { group -> group.any { it.id == artworkId } }.takeIf { it >= 0 }
        ?.plus((if (loading) 1 else 0) + (if (profileMissing) 1 else 0))

private data class StudioDeletion(val title: String, val conversationId: String? = null, val requestId: String? = null)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ImageStudioScreen(
    app: AppUiState,
    controller: ImageStudioController,
    modifier: Modifier = Modifier,
    onExit: () -> Unit = {},
) {
    val state by controller.state.collectAsState()
    var confirm by remember { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showHistory by rememberSaveable { mutableStateOf(false) }
    var showTemplates by rememberSaveable { mutableStateOf(false) }
    val catalogScope = rememberCoroutineScope()
    val catalog = remember { StudioModelCatalog(catalogScope) }
    val catalogState by catalog.state.collectAsState()
    val feedback = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val promptFocus = remember { FocusRequester() }
    val listState = rememberLazyListState()
    val conversations = remember(state.history) { state.history.groupBy { it.conversationId }.values.toList() }
    val groups = remember(state.history, state.conversationId) {
        studioConversationGroups(state.history.filter { it.conversationId == state.conversationId })
    }
    var deletion by remember { mutableStateOf<StudioDeletion?>(null) }
    var switchConversation by remember { mutableStateOf<String?>(null) }
    fun navigateConversation(id: String) {
        if (state.prompt.isNotBlank()) switchConversation = id
        else if (id.isEmpty()) controller.newConversation() else controller.openConversation(id)
    }
    val wide = LocalConfiguration.current.screenWidthDp >= 840
    var historyTarget by remember { mutableStateOf<String?>(null) }
    val followingLatest by remember { derivedStateOf { !listState.canScrollForward } }
    val profile = app.modelProfiles.firstOrNull { it.id == state.profileId }
        ?: app.modelProfiles.firstOrNull { it.id == app.activeProfileId }
    val settings = profile?.let {
        AiSettings(
            baseUrl = it.baseUrl,
            model = state.model.ifBlank { it.model },
            apiKey = it.apiKey,
            workspacePath = app.workspacePath,
        )
    } ?: app.settings.copy(model = state.model.ifBlank { app.settings.model })
    val profileMissing = state.profileId.isNotBlank() && app.modelProfiles.none { it.id == state.profileId }
    val enabled = !state.running && !state.loading && !state.managing

    LaunchedEffect(state.loading, state.profileId, app.activeProfileId, app.modelProfiles) {
        if (!state.loading && state.profileId.isBlank() && profile != null) {
            controller.edit { it.copy(profileId = profile.id, model = it.model.ifBlank { profile.model }) }
        }
    }
    LaunchedEffect(state.history.size, state.running, state.error, state.loading) {
        // Do not pull users away from an older work while they inspect it.
        if (!state.loading && (followingLatest || state.running)) {
            withFrameNanos { }
            val count = listState.layoutInfo.totalItemsCount
            if (count > 0) listState.animateScrollToItem(count - 1)
        }
    }
    LaunchedEffect(state.conversationId) {
        if (listState.layoutInfo.totalItemsCount > 0) listState.scrollToItem(0)
    }
    LaunchedEffect(historyTarget) {
        historyTarget?.let { id ->
            val index = studioHistoryIndex(groups, id, state.loading, profileMissing)
            if (index != null) listState.animateScrollToItem(index)
        }
        historyTarget = null
    }

    // Model discovery uses the studio service, never the Agent's refresh/auto-selection path.
    val canReadCatalog = app.permissions.allowNetwork && !profileMissing
    LaunchedEffect(profile?.id, settings.baseUrl, settings.apiKey, canReadCatalog) {
        if (canReadCatalog) catalog.load(settings)
        else catalog.clear(if (profileMissing) "原服务已删除，请重新选择服务。" else "请在设置中允许网络访问后读取模型列表。")
    }
    if (showTemplates) StudioTemplateSheet(
        enabled = enabled,
        hasDraft = state.prompt.isNotBlank(),
        model = settings.model,
        allowNetwork = app.permissions.allowNetwork,
        onApplyPrompt = { prompt ->
            controller.edit { it.copy(prompt = prompt) }
            showTemplates = false
            promptFocus.requestFocus()
        },
        onDismiss = { showTemplates = false },
        onApply = { prompt, size ->
            controller.edit { it.copy(prompt = prompt, size = size) }
            showTemplates = false
            promptFocus.requestFocus()
        },
    )

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("生成 ${state.count} 个变体？") },
            text = {
                Text(
                    "${settings.model} · ${sizeLabel(state.size)}\n\n每张都是独立请求，可能产生费用。失败不自动重试。",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    controller.generate(settings, File(app.workspacePath), app.permissions)
                }) { Text("开始生成") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("取消") } },
        )
    }

    if (showSettings) {
        ImageStudioSettingsSheet(
            app = app,
            state = state,
            profile = profile,
            enabled = enabled,
            catalog = catalogState,
            onRefreshModels = { if (canReadCatalog) catalog.load(settings) },
            onDismiss = { showSettings = false },
            onEdit = controller::edit,
        )
    }

    switchConversation?.let { id ->
        AlertDialog(onDismissRequest = { switchConversation = null },
            title = { Text("离开当前草稿？") }, text = { Text("当前未发送的描述将被清空，已生成的作品会保留在历史对话。") },
            confirmButton = { TextButton(enabled = enabled, onClick = {
                if (id.isEmpty()) controller.newConversation() else controller.openConversation(id)
                switchConversation = null
            }) { Text("继续") } },
            dismissButton = { TextButton(onClick = { switchConversation = null }) { Text("保留草稿") } })
    }
    deletion?.let { target ->
        AlertDialog(onDismissRequest = { deletion = null },
            title = { Text(target.title) },
            text = { Text("将移除对话中的相关描述和作品记录，无法撤销。工作区原图与已导出的文件会保留，不会删除。") },
            confirmButton = { TextButton(enabled = enabled, onClick = {
                controller.deleteRecords(target.conversationId, target.requestId)
                deletion = null
            }) { Text("删除记录", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { deletion = null }) { Text("取消") } })
    }
    if (showHistory) ModalBottomSheet(onDismissRequest = { showHistory = false }) {
        LazyColumn(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp)) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("历史对话", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    TextButton(enabled = enabled, onClick = { navigateConversation(""); showHistory = false }) { Text("新建对话") }
                }
            }
            if (conversations.isEmpty()) item { Text("生成作品后，对话会保存在这里", Modifier.padding(vertical = 32.dp)) }
            items(conversations, key = { it.first().conversationId }) { artworks ->
                val id = artworks.first().conversationId
                ListItem(
                    headlineContent = { Text(artworks.last().prompt, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text("${if (id == state.conversationId) "当前对话 · " else ""}${artworks.size} 张 · ${java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(artworks.first().createdAt))}") },
                    trailingContent = {
                        Row {
                            IconButton(enabled = enabled, onClick = { if (id != state.conversationId) navigateConversation(id); showHistory = false }) {
                                Icon(Icons.Outlined.OpenInNew, "打开对话")
                            }
                            IconButton(enabled = enabled, onClick = { deletion = StudioDeletion("删除这个对话？", id) }) {
                                Icon(Icons.Outlined.Delete, "删除历史对话")
                            }
                        }
                    },
                )
                HorizontalDivider()
            }
            if (conversations.isNotEmpty()) item {
                TextButton(enabled = enabled, onClick = { deletion = StudioDeletion("清空全部历史对话？") }) {
                    Text("清空历史记录", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
    Row(modifier.fillMaxSize().imePadding()) {
      Column(Modifier.weight(1f)) {
        ImageStudioHeader(
            model = settings.model,
            enabled = enabled,
            onExit = onExit,
            onHistory = { showHistory = true },
            onNew = { navigateConversation("") },
            onDelete = { deletion = StudioDeletion("删除当前对话？", state.conversationId) },
            canDelete = groups.isNotEmpty() || state.submittedPrompt != null || state.prompt.isNotBlank(),
            onSettings = { showSettings = true },
        )

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth().widthIn(max = 920.dp).align(Alignment.CenterHorizontally),
            state = listState,
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(26.dp),
        ) {
            if (state.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }

            if (!state.loading && groups.isEmpty() && !state.running && state.submittedPrompt == null) {
                item { StudioEmptyState(enabled = enabled, onTemplates = { showTemplates = true }) }
            }
            if (profileMissing) item {
                StudioNotice(
                    title = "图像服务不可用",
                    message = "原服务配置已删除，请在右上角重新选择服务或图像模型。",
                    error = true,
                )
            }
            items(groups, key = { it.first().requestId.ifBlank { it.first().id } }) { artworks ->
                StudioConversationTurn(artworks, enabled, feedback,
                    reveal = artworks.first().requestId == state.activeRequestId,
                    onDelete = { deletion = StudioDeletion("删除这次生成记录？", state.conversationId, artworks.first().requestId.ifBlank { artworks.first().id }) }) { artwork ->
                    controller.reuse(artwork)
                    promptFocus.requestFocus()
                    keyboard?.show()
                }
            }
            if (state.submittedPrompt != null && state.history.none { it.requestId == state.activeRequestId }) {
                item { StudioPromptBubble(state.submittedPrompt.orEmpty()) }
            }
            if (state.running) item { StudioProgress(state) }
            state.error?.let { error ->
                item {
                    StudioNotice(
                        title = "生成未完成",
                        message = error,
                        footer = "提示词已保留，可以修改后再次提交。",
                        error = true,
                    )
                }
            }
            if (!state.running && state.error == null) state.status?.let { status ->
                item { Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }

        SnackbarHost(feedback)
        ImageStudioComposer(
            state = state,
            enabled = enabled,
            profileMissing = profileMissing,
            promptFocus = promptFocus,
            onSettings = { showSettings = true },
            onTemplates = { keyboard?.hide(); showTemplates = true },
            onSize = { size -> controller.edit { it.copy(size = size) } },
            onCount = { count -> controller.edit { it.copy(count = count) } },
            onPromptChange = { value -> controller.edit { it.copy(prompt = value) } },
            onGenerate = {
                keyboard?.hide()
                if (state.running) controller.stop()
                else if (state.count > 1) confirm = true
                else controller.generate(settings, File(app.workspacePath), app.permissions)
            },
        )
      }
      if (wide) {
          VerticalDivider()
          Surface(Modifier.width(336.dp).fillMaxHeight(), color = MaterialTheme.colorScheme.surfaceContainerLow) {
              ImageStudioSettingsContent(app, state, profile, enabled, catalogState, { if (canReadCatalog) catalog.load(settings) }, controller::edit)
          }
      }
    }
}

@Composable
private fun ImageStudioHeader(
    model: String,
    enabled: Boolean,
    onExit: () -> Unit,
    onHistory: () -> Unit,
    onNew: () -> Unit,
    onDelete: () -> Unit,
    canDelete: Boolean,
    onSettings: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.background) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onExit) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回编码工作区") }
            Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                Text("图片创作", style = MaterialTheme.typography.titleLarge)
                Text(model.ifBlank { "选择图像模型" }, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = onNew, enabled = enabled) { Icon(Icons.Outlined.Add, "新建图片对话") }
            IconButton(onClick = onHistory) { Icon(Icons.Outlined.History, "打开创作记录") }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "对话菜单") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("创作设置") }, enabled = enabled,
                        onClick = { menu = false; onSettings() }, leadingIcon = { Icon(Icons.Outlined.Tune, null) })
                    DropdownMenuItem(text = { Text("删除当前对话") }, enabled = enabled && canDelete,
                        onClick = { menu = false; onDelete() }, leadingIcon = { Icon(Icons.Outlined.Delete, null) })
                }
            }
        }
    }
}

@Composable
private fun StudioEmptyState(enabled: Boolean, onTemplates: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(top = 42.dp, bottom = 30.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(Icons.Outlined.Lightbulb, null, Modifier.size(30.dp), tint = MaterialTheme.colorScheme.primary)
        Text("从一个创作意图开始", style = MaterialTheme.typography.headlineSmall)
        Text(
            "写下主体、风格与构图，再选择画幅。每次独立生成，不会自动引用上一张图片。",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = onTemplates, enabled = enabled) {
            Icon(Icons.Outlined.DashboardCustomize, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("浏览创作模板")
        }
        Text("商品 · 海报 · 人像 · 插画 · 空间", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImageStudioSettingsSheet(
    app: AppUiState,
    state: ImageStudioState,
    profile: dev.pocketagent.data.ModelProfile?,
    enabled: Boolean,
    catalog: StudioModelCatalogState,
    onRefreshModels: () -> Unit,
    onDismiss: () -> Unit,
    onEdit: (((ImageStudioState) -> ImageStudioState)) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        ImageStudioSettingsContent(app, state, profile, enabled, catalog, onRefreshModels, onEdit,
            footer = { Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("完成") } })
    }
}

@Composable
private fun ImageStudioSettingsContent(
    app: AppUiState,
    state: ImageStudioState,
    profile: dev.pocketagent.data.ModelProfile?,
    enabled: Boolean,
    catalog: StudioModelCatalogState,
    onRefreshModels: () -> Unit,
    onEdit: (((ImageStudioState) -> ImageStudioState)) -> Unit,
    modifier: Modifier = Modifier,
    footer: (@Composable () -> Unit)? = null,
) {
    var profileMenu by remember { mutableStateOf(false) }
    var modelMenu by remember { mutableStateOf(false) }
    if (modelMenu) StudioModelPicker(catalog, state.model, enabled, onRefreshModels,
        onDismiss = { modelMenu = false },
        onSelect = { model -> modelMenu = false; onEdit { it.copy(model = model) } })
        LazyColumn(
            modifier = modifier,
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 34.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            item {
                Text("创作设置", style = MaterialTheme.typography.headlineSmall)
                Text("独立于编码 Agent。服务与模型决定可用尺寸，不会自动换模型或重试。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item {
                Text("服务与模型", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Box {
                    OutlinedButton(onClick = { profileMenu = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Outlined.Cloud, null, Modifier.size(19.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(profile?.name ?: "选择图像服务", Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Icon(Icons.Outlined.ExpandMore, null)
                    }
                    DropdownMenu(expanded = profileMenu, onDismissRequest = { profileMenu = false }) {
                        app.modelProfiles.forEach { item ->
                            DropdownMenuItem(text = { Text(item.name) }, onClick = {
                                profileMenu = false
                                onEdit { it.copy(profileId = item.id, model = item.model) }
                            })
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = state.model,
                    onValueChange = { value -> onEdit { it.copy(model = value) } },
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("图像模型 ID") },
                    placeholder = { Text("例如 gpt-image-2.6") },
                    singleLine = true,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { modelMenu = true }, enabled = enabled) { Text("选择生图模型") }
                    TextButton(onClick = onRefreshModels, enabled = enabled && !catalog.loading) {
                        Text(if (catalog.loading) "读取中…" else "刷新列表")
                    }
                }
                if (state.model.isNotBlank() && !isStudioImageModel(state.model)) {
                    Text("当前 ID 未被识别为生图模型，请确认支持图片生成接口，或从生图列表选择。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                catalog.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
            item {
                Text("输出画幅", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("auto" to "自动", "1024x1024" to "方形", "1024x1536" to "竖版", "1536x1024" to "横版").forEach { (size, label) ->
                        FilterChip(selected = state.size == size, enabled = enabled, onClick = { onEdit { it.copy(size = size) } }, label = { Text(label) })
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("精确尺寸", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("1792x1024", "1024x1792", "512x512", "256x256").forEach { size ->
                        FilterChip(selected = state.size == size, enabled = enabled, onClick = { onEdit { it.copy(size = size) } }, label = { Text(size.replace("x", "×")) })
                    }
                }
                Text("画幅名称对应服务支持的实际尺寸；并不承诺精确 9:16。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("生成变体", style = MaterialTheme.typography.titleMedium)
                        Text("每张独立请求，最多 4 张", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(enabled = enabled && state.count > 1, onClick = { onEdit { it.copy(count = it.count - 1) } }) { Icon(Icons.Outlined.Remove, "减少变体") }
                    Text("${state.count}", style = MaterialTheme.typography.titleMedium)
                    IconButton(enabled = enabled && state.count < 4, onClick = { onEdit { it.copy(count = it.count + 1) } }) { Icon(Icons.Outlined.Add, "增加变体") }
                }
            }
            footer?.let { item { it() } }
        }
}

@Composable
private fun ImageStudioComposer(
    state: ImageStudioState,
    enabled: Boolean,
    profileMissing: Boolean,
    promptFocus: FocusRequester,
    onSettings: () -> Unit,
    onTemplates: () -> Unit,
    onSize: (String) -> Unit,
    onCount: (Int) -> Unit,
    onPromptChange: (String) -> Unit,
    onGenerate: () -> Unit,
) {
    var sizeMenu by remember { mutableStateOf(false) }
    var countMenu by remember { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)) {
        Column(
            Modifier.fillMaxWidth().widthIn(max = 920.dp).navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box {
                    TextButton(onClick = { sizeMenu = true }, enabled = enabled) {
                        Icon(Icons.Outlined.AspectRatio, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                        Text(sizeLabel(state.size)); Icon(Icons.Outlined.ExpandMore, null, Modifier.size(18.dp))
                    }
                    DropdownMenu(expanded = sizeMenu, onDismissRequest = { sizeMenu = false }) {
                        listOf("auto", "1024x1024", "1024x1536", "1536x1024").forEach { size ->
                            DropdownMenuItem(text = { Text(sizeLabel(size)) }, onClick = { sizeMenu = false; onSize(size) })
                        }
                        DropdownMenuItem(text = { Text("更多尺寸…") }, onClick = { sizeMenu = false; onSettings() })
                    }
                }
                Box {
                    TextButton(onClick = { countMenu = true }, enabled = enabled) { Text("${state.count} 张"); Icon(Icons.Outlined.ExpandMore, null, Modifier.size(18.dp)) }
                    DropdownMenu(expanded = countMenu, onDismissRequest = { countMenu = false }) {
                        (1..4).forEach { count -> DropdownMenuItem(text = { Text("$count 张") }, onClick = { countMenu = false; onCount(count) }) }
                    }
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onTemplates, enabled = enabled) { Icon(Icons.Outlined.DashboardCustomize, "打开创作模板") }
                IconButton(onClick = onSettings, enabled = enabled) { Icon(Icons.Outlined.Tune, "输出参数") }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Bottom) {
                OutlinedTextField(
                    value = state.prompt,
                    onValueChange = onPromptChange,
                    enabled = enabled,
                    modifier = Modifier.weight(1f).focusRequester(promptFocus),
                    minLines = 2,
                    maxLines = 5,
                    placeholder = { Text("描述画面，开始创作…") },
                    isError = state.prompt.length > 16000,
                    supportingText = if (state.prompt.length > 16000) ({ Text("提示词超过 16000 字符") }) else null,
                    shape = MaterialTheme.shapes.large,
                )
                FilledIconButton(
                    onClick = onGenerate,
                    enabled = state.running || (enabled && !profileMissing && state.prompt.isNotBlank() && state.prompt.length <= 16000),
                    modifier = Modifier.size(56.dp),
                ) {
                    Icon(if (state.running) Icons.Outlined.Stop else Icons.AutoMirrored.Outlined.Send, if (state.running) "停止等待" else "生成图片")
                }
            }
            Text(
                when {
                    state.running -> "${state.status.orEmpty()} · 停止只会结束本地等待，服务端可能仍在处理"
                    state.prompt.length > 16000 -> "请精简提示词后再生成"
                    else -> "每条描述独立生成 · 发送可能计费 · 不会自动重试"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (state.prompt.length > 16000) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StudioConversationTurn(artworks: List<StudioArtwork>, enabled: Boolean, feedback: SnackbarHostState,
    reveal: Boolean,
    onDelete: () -> Unit, onReuse: (StudioArtwork) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        StudioPromptBubble(artworks.first().prompt)
        artworks.forEach { artwork -> StudioArtworkView(artwork, enabled, feedback, reveal = reveal, onReuse = onReuse) }
        Box(Modifier.align(Alignment.End)) {
            IconButton(enabled = enabled, onClick = { menu = true }) { Icon(Icons.Outlined.MoreHoriz, "生成记录操作") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("删除这次生成记录") },
                    leadingIcon = { Icon(Icons.Outlined.Delete, null) }, onClick = { menu = false; onDelete() })
            }
        }
    }
}

@Composable
private fun StudioPromptBubble(prompt: String) {
    var expanded by rememberSaveable(prompt) { mutableStateOf(false) }
    var hasOverflow by remember(prompt) { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Surface(Modifier.fillMaxWidth(.9f), color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                SelectionContainer {
                    Text(prompt, Modifier.padding(vertical = 5.dp), style = MaterialTheme.typography.bodyLarge,
                        maxLines = if (expanded) Int.MAX_VALUE else 4, overflow = TextOverflow.Ellipsis,
                        onTextLayout = { if (!expanded) hasOverflow = it.hasVisualOverflow })
                }
                if (hasOverflow || expanded) TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "收起描述" else "展开完整描述")
                }
            }
        }
    }
}

@Composable
private fun StudioNotice(title: String, message: String, footer: String? = null, error: Boolean = false) {
    val color = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerHigh
    val onColor = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface
    Surface(color = color, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = onColor)
            Text(message, style = MaterialTheme.typography.bodyMedium, color = onColor)
            footer?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = onColor.copy(alpha = .78f)) }
        }
    }
}

@Composable
private fun StudioProgress(state: ImageStudioState) {
    var seconds by remember(state.startedAt) { mutableStateOf(0L) }
    var expanded by rememberSaveable(state.startedAt) { mutableStateOf(false) }
    LaunchedEffect(state.startedAt) {
        while (true) {
            seconds = ((System.currentTimeMillis() - (state.startedAt ?: System.currentTimeMillis())) / 1000).coerceAtLeast(0)
            delay(1000)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        StudioLoadingPreview(state.size)
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.HourglassEmpty, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(state.status.orEmpty(), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                Text("${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}", style = MaterialTheme.typography.labelLarge)
            }
            val progress = state.progress
            val total = progress?.totalBytes
            if (progress != null && total != null && total > 0) {
                LinearProgressIndicator(progress = { (progress.receivedBytes.toFloat() / total).coerceIn(0f, 1f) }, Modifier.fillMaxWidth())
            } else LinearProgressIndicator(Modifier.fillMaxWidth())
            val receiving = progress?.stage == ImageGenerationStage.RECEIVING || progress?.stage == ImageGenerationStage.DOWNLOADING
            if (receiving) {
                val stale = System.nanoTime() - progress.measuredAtNanos >= 3_000_000_000L
                val speed = if (stale) null else progress.recentBytesPerSecond
                Text(
                    "已接收 ${progress.receivedBytes / 1024} KiB" +
                        (total?.let { " / ${it / 1024} KiB" } ?: " · 总大小未知") +
                        (speed?.let { " · ${it / 1024} KiB/s" } ?: " · 正在测量速度"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起传输详情" else "传输详情") }
                if (expanded) Text("等待首字节 ${progress.waitMs / 1000}s · 传输 ${progress.transferMs / 1000}s", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else if (seconds >= 90 && (progress == null || progress.stage == ImageGenerationStage.WAITING)) {
                Text("服务尚未返回图片，仍在等待。停止不代表服务端取消或退款。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

internal fun sizeLabel(size: String): String = when (size) {
    "auto" -> "自动画幅"
    "1024x1024" -> "方形 · 1:1"
    "1024x1536" -> "竖版 · 2:3"
    "1536x1024" -> "横版 · 3:2"
    else -> size.replace("x", "×")
}
