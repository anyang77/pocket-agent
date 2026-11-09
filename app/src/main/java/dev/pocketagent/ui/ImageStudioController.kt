package dev.pocketagent.ui

import dev.pocketagent.agent.AgentPermissions
import dev.pocketagent.agent.ImageGenerationClient
import dev.pocketagent.agent.PermissionMode
import dev.pocketagent.data.AiSettings
import dev.pocketagent.data.ImageStudioStore
import dev.pocketagent.data.StudioArtwork
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.util.UUID

internal data class ImageStudioState(
    val prompt: String = "",
    val conversationId: String = dev.pocketagent.data.LEGACY_STUDIO_CONVERSATION,
    val managing: Boolean = false,
    val profileId: String = "",
    val model: String = "",
    val size: String = "auto",
    val count: Int = 1,
    val running: Boolean = false,
    val loading: Boolean = true,
    val startedAt: Long? = null,
    val activeRequestId: String? = null,
    val submittedPrompt: String? = null,
    val progress: dev.pocketagent.agent.ImageGenerationProgress? = null,
    val stageStartedAt: Long? = null,
    val lastByteAt: Long? = null,
    val completed: Int = 0,
    val status: String? = null,
    val error: String? = null,
    val history: List<StudioArtwork> = emptyList(),
)

internal class ImageStudioController(
    private val scope: CoroutineScope,
    directory: File,
    private val requestTimeoutMs: Long = 7 * 60_000L,
    private val requestImage: (suspend (AiSettings, String, String) -> dev.pocketagent.agent.GeneratedImage)? = null,
) {
    private val preferences = File(directory, "draft.json")
    private val store = ImageStudioStore(File(directory, "history.json"))
    private val mutable = MutableStateFlow(ImageStudioState())
    val state = mutable.asStateFlow()
    private var generation: Job? = null
    private var saveDraft: Job? = null
    private var historyHealthy = true

    init {
        scope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) {
                    val history = store.load()
                    var warning: String? = null
                    val draft = try {
                        if (preferences.exists()) org.json.JSONObject(preferences.readText()) else org.json.JSONObject()
                    } catch (error: Exception) {
                        val backup = File(preferences.parentFile, "draft-corrupt-${UUID.randomUUID()}.json")
                        check(preferences.renameTo(backup)) { "草稿损坏且无法备份，已保留原文件" }
                        warning = "草稿读取失败，已备份原草稿；已有作品未受影响。"
                        org.json.JSONObject()
                    }
                    ImageStudioState(prompt = draft.optString("prompt"),
                        conversationId = draft.optString("conversation_id", dev.pocketagent.data.LEGACY_STUDIO_CONVERSATION), profileId = draft.optString("profile"),
                        model = draft.optString("model"), size = draft.optString("size", "auto").takeIf { it in ImageGenerationClient.SIZES } ?: "auto",
                        count = draft.optInt("count", 1).coerceIn(1, 4), loading = false, history = history, status = warning)
                }
                mutable.value = loaded
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                historyHealthy = false
                mutable.update { it.copy(loading = false, error = "创作记录读取失败，已保护原文件：${error.message}") }
            }
        }
    }

    fun edit(transform: (ImageStudioState) -> ImageStudioState) {
        if (mutable.value.running || mutable.value.loading || mutable.value.managing) return
        // A draft edit must not erase the result of a failed request. Clear it on the next submission.
        mutable.update { transform(it).copy(error = it.error) }
        persistDraft(mutable.value)
    }

    private fun persistDraft(snapshot: ImageStudioState) {
        val previous = saveDraft
        previous?.cancel()
        saveDraft = scope.launch {
            previous?.join() // Let an in-flight atomic write finish before reusing draft.tmp.
            delay(350)
            withContext(Dispatchers.IO) {
                ensureActive()
                val stage = File(preferences.parentFile, "draft.tmp")
                var failed = false
                try {
                    preferences.parentFile?.mkdirs()
                    stage.writeText(org.json.JSONObject().put("prompt", snapshot.prompt).put("profile", snapshot.profileId)
                        .put("model", snapshot.model).put("size", snapshot.size).put("count", snapshot.count)
                        .put("conversation_id", snapshot.conversationId).toString())
                    check(stage.renameTo(preferences)) { "无法替换草稿文件" }
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    failed = true
                } finally { stage.delete() }
                // Publish the terminal state only after the temporary write has been cleaned up.
                if (failed) mutable.update {
                    it.copy(status = "草稿保存失败，重新打开后可能恢复旧描述；作品原图不受影响。")
                }
            }
        }
    }

    fun reuse(artwork: StudioArtwork) = edit {
        // Reusing one artwork starts one new variation, independent of the current batch size.
        it.copy(prompt = artwork.prompt, model = artwork.model, size = artwork.size, profileId = artwork.profileId, count = 1)
    }

    private fun resetConversation(state: ImageStudioState, id: String) = state.copy(
        conversationId = id, prompt = "", submittedPrompt = null, activeRequestId = null,
        startedAt = null, progress = null, stageStartedAt = null, lastByteAt = null,
        completed = 0, error = null, status = null,
    )

    fun newConversation() {
        val current = mutable.value
        if (current.running || current.loading || current.managing) return
        mutable.value = resetConversation(current, UUID.randomUUID().toString())
        persistDraft(mutable.value)
    }

    fun openConversation(id: String) {
        val current = mutable.value
        if (current.running || current.loading || current.managing || current.conversationId == id) return
        if (current.history.none { it.conversationId == id }) return
        mutable.value = resetConversation(current, id)
        persistDraft(mutable.value)
    }

    /** Remove metadata only. Never delete workspace originals or exported copies. */
    fun deleteRecords(conversationId: String? = null, requestId: String? = null) {
        val current = mutable.value
        if (current.running || current.loading || current.managing || !historyHealthy) return
        if (conversationId == null && requestId != null) return
        val remaining = current.history.filterNot {
            (conversationId == null || it.conversationId == conversationId) &&
                (requestId == null || it.requestId.ifBlank { it.id } == requestId)
        }
        mutable.update { it.copy(managing = true) }
        scope.launch {
            try {
                withContext(Dispatchers.IO) { store.save(remaining) }
                mutable.update {
                    val removedActive = conversationId == null ||
                        (conversationId == it.conversationId && requestId == null)
                    val next = if (removedActive) resetConversation(it, UUID.randomUUID().toString())
                        else if (requestId != null && it.activeRequestId == requestId)
                            it.copy(submittedPrompt = null, activeRequestId = null, error = null, status = null)
                        else it
                    next.copy(history = remaining, status = "记录已删除，原图文件已保留")
                }
                persistDraft(mutable.value)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutable.update { it.copy(error = "删除失败，原记录未变更：${error.message}") }
            } finally { mutable.update { it.copy(managing = false) } }
        }
    }

    fun generate(settings: AiSettings, workspace: File, permissions: AgentPermissions) {
        val draft = mutable.value
        if (draft.running || draft.loading || draft.managing) return
        val invalid = when {
            !historyHealthy -> "请先处理创作记录读取错误，避免覆盖已有作品记录"
            !permissions.allowNetwork -> "请在设置中允许网络访问"
            !permissions.allowFileWrite || permissions.mode == PermissionMode.READ_ONLY -> "请在设置中允许写入文件并退出只读模式"
            draft.prompt.isBlank() || draft.prompt.length > 16000 -> "提示词需要 1–16000 字符"
            settings.model.isBlank() || settings.apiKey.isBlank() || settings.baseUrl.isBlank() -> "请选择服务和图像模型，并在设置中填写服务密钥"
            draft.size !in ImageGenerationClient.SIZES || draft.count !in 1..4 -> "生成参数不受支持"
            else -> null
        }
        if (invalid != null) { mutable.update { it.copy(error = invalid) }; return }
        val requestId = UUID.randomUUID().toString()
        mutable.update { it.copy(running = true, startedAt = System.currentTimeMillis(), activeRequestId = requestId,
            submittedPrompt = draft.prompt, completed = 0, progress = null, error = null, status = "正在提交图片请求") }
        generation = scope.launch {
            try {
                val directory = withContext(Dispatchers.IO) {
                    val root = workspace.canonicalFile
                    File(root, "generated-images").canonicalFile.also {
                        require(it.parentFile == root) { "生成图片目录不能指向工作区外" }
                        require(it.mkdirs() || it.isDirectory) { "无法创建作品目录" }
                    }
                }
                repeat(draft.count) { index ->
                    mutable.update { it.copy(status = "等待服务返回第 ${index + 1}/${draft.count} 张", progress = null,
                        stageStartedAt = System.currentTimeMillis(), lastByteAt = null) }
                    val startedNanos = System.nanoTime()
                    var receiveWait = 0L
                    var receiveTransfer = 0L
                    var downloadWait = 0L
                    var downloadTransfer = 0L
                    var responseBytes = 0L
                    var downloadBytes = 0L
                    var receiveReadWait = 0L
                    var downloadReadWait = 0L
                    var protocol = ""
                    // Bound each image, including URL download. Keep parsing off the UI thread.
                    withTimeout(requestTimeoutMs) {
                        withContext(Dispatchers.IO) {
                            val image = requestImage?.invoke(settings, draft.prompt, draft.size)
                                ?: ImageGenerationClient().generate(settings, draft.prompt, draft.size) { progress ->
                                    when (progress.stage) {
                                        dev.pocketagent.agent.ImageGenerationStage.RECEIVING -> {
                                            receiveWait = progress.waitMs; receiveTransfer = progress.transferMs
                                            responseBytes = progress.receivedBytes
                                            receiveReadWait = progress.readWaitMs
                                            protocol = progress.protocol
                                        }
                                        dev.pocketagent.agent.ImageGenerationStage.DOWNLOADING -> {
                                            downloadWait = progress.waitMs; downloadTransfer = progress.transferMs
                                            downloadBytes = progress.receivedBytes
                                            downloadReadWait = progress.readWaitMs
                                        }
                                        else -> Unit
                                    }
                                    val now = System.currentTimeMillis()
                                    mutable.update { it.copy(progress = progress,
                                        stageStartedAt = if (it.progress?.stage != progress.stage) now else it.stageStartedAt,
                                        lastByteAt = if (it.progress?.stage != progress.stage) null
                                            else if (progress.receivedBytes > (it.progress?.receivedBytes ?: 0L)) now else it.lastByteAt,
                                        status = when (progress.stage) {
                                        dev.pocketagent.agent.ImageGenerationStage.WAITING -> "等待服务返回第 ${index + 1}/${draft.count} 张"
                                        dev.pocketagent.agent.ImageGenerationStage.RECEIVING -> "正在接收图片响应"
                                        dev.pocketagent.agent.ImageGenerationStage.DOWNLOADING -> "正在下载原图"
                                        dev.pocketagent.agent.ImageGenerationStage.DECODING -> "正在解析图片"
                                    }) }
                                }
                    mutable.update { it.copy(status = "正在保存原图", progress = null) }
                    // Save before returning across the cancellable IO dispatcher boundary.
                    withContext(NonCancellable) {
                        val file = File.createTempFile("studio-", ".${image.extension}", directory)
                        try { file.writeBytes(image.bytes) } catch (error: Exception) { file.delete(); throw error }
                        val elapsed = (System.nanoTime() - startedNanos) / 1_000_000
                        fun seconds(ms: Long) = java.lang.String.format(java.util.Locale.ROOT, "%.2f", ms / 1000.0)
                        val networkMs = receiveWait + receiveTransfer + downloadWait + downloadTransfer
                        val diagnostics = if (responseBytes > 0) buildString {
                            append("等待首字节 ${seconds(receiveWait)}s · 接收 ${seconds(receiveTransfer)}s (${responseBytes / 1024} KiB)")
                            if (downloadBytes > 0) append(" · 下载等待 ${seconds(downloadWait)}s / 传输 ${seconds(downloadTransfer)}s (${downloadBytes / 1024} KiB)")
                            append(" · 正文读取等待 ${seconds(receiveReadWait + downloadReadWait)}s")
                            if (protocol.isNotBlank()) append(" · $protocol")
                            append(" · 本地处理及保存 ${seconds((elapsed - networkMs).coerceAtLeast(0))}s")
                        } else ""
                        val artwork = StudioArtwork(UUID.randomUUID().toString(), file.path, draft.prompt, settings.model,
                            draft.size, System.currentTimeMillis(), elapsed, draft.profileId, diagnostics, requestId, draft.conversationId)
                        val history = listOf(artwork) + mutable.value.history
                        mutable.update { it.copy(history = history, completed = index + 1) }
                        try { store.save(history) } catch (error: Exception) {
                            throw IllegalStateException("原图已保留在 ${file.path}，但作品索引保存失败：${error.message}", error)
                        }
                    }
                        }
                    }
                }
                // Clear only after success: failed/stopped prompts remain available for an explicit retry.
                mutable.update { it.copy(prompt = "", status = "已生成 ${draft.count} 张，可查看或保存原图") }
                persistDraft(mutable.value)
            } catch (error: TimeoutCancellationException) {
                mutable.update { it.copy(status = null, error = "单张等待超过 ${requestTimeoutMs / 60_000} 分钟，已结束本地等待。服务端可能仍在生成或已计费，请先检查服务端记录；不会自动重试。已完成 ${it.completed} 张${transferFailureDetails(it.progress)}") }
            } catch (error: CancellationException) {
                mutable.update { it.copy(status = "已停止等待；服务端可能仍在生成或已计费，已收到的作品会保留") }
                throw error
            } catch (error: Exception) {
                mutable.update { it.copy(error = studioFailureMessage(error, it.completed, it.progress), status = null) }
            } finally {
                mutable.update { it.copy(running = false) }
            }
        }
    }

    fun stop() { generation?.cancel() }
}

internal fun studioFailureMessage(error: Exception, completed: Int,
    progress: dev.pocketagent.agent.ImageGenerationProgress?): String {
    val message = error.message ?: "生成失败"
    val retryNotice = if (message.contains("不会自动重试")) "" else "；不会自动重试"
    return "$message$retryNotice；已完成 $completed 张${transferFailureDetails(progress)}"
}

internal fun transferFailureDetails(progress: dev.pocketagent.agent.ImageGenerationProgress?): String {
    if (progress == null || progress.stage !in setOf(
            dev.pocketagent.agent.ImageGenerationStage.RECEIVING,
            dev.pocketagent.agent.ImageGenerationStage.DOWNLOADING)) return ""
    return buildString {
        append("\n传输诊断：${progress.receivedBytes / 1024} KiB · 等待首字节 ${progress.waitMs / 1000}s")
        append(" · 传输 ${progress.transferMs / 1000}s · 正文读取等待 ${progress.readWaitMs / 1000}s")
        if (progress.protocol.isNotBlank()) append(" · ${progress.protocol}")
    }
}
