package dev.pocketagent.data

import android.content.Context
import android.util.AtomicFile
import dev.pocketagent.ui.QueuedPrompt
import dev.pocketagent.agent.AgentTaskMode
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class StoredComposer(
    val text: String = "",
    val attachments: List<ChatAttachment> = emptyList(),
    val queue: List<QueuedPrompt> = emptyList(),
)

class AgentComposerStore(context: Context) {
    private val directory = File(context.filesDir, "agent-composers").apply { mkdirs() }
    private val revisions = mutableMapOf<Long, Long>()
    @Synchronized fun reserveRevision(id: Long): Long = (revisions.getOrDefault(id, 0) + 1).also { revisions[id] = it }
    @Synchronized fun load(id: Long): StoredComposer = runCatching {
        val root = JSONObject(AtomicFile(File(directory, "$id.json")).readFully().toString(Charsets.UTF_8))
        val queue = root.optJSONArray("queue") ?: JSONArray()
        StoredComposer(
            text = root.optString("text"),
            attachments = root.optJSONArray("attachments").toChatAttachments(),
            queue = List(queue.length()) { index ->
                val item = queue.getJSONObject(index)
                QueuedPrompt(item.getLong("id"), item.optString("text"),
                    item.optJSONArray("attachments").toChatAttachments(),
                    taskMode = AgentTaskMode.restored(item.optString("taskMode")))
            },
        )
    }.getOrDefault(StoredComposer())

    @Synchronized fun save(id: Long, value: StoredComposer, revision: Long) {
        if (revisions[id] != revision) return
        val root = JSONObject().put("text", value.text)
            .put("attachments", JSONArray().apply { value.attachments.forEach { put(it.toJson()) } })
            .put("queue", JSONArray().apply {
                value.queue.forEach { prompt ->
                    put(JSONObject().put("id", prompt.id).put("text", prompt.text).put("taskMode", prompt.taskMode.name)
                        .put("attachments", JSONArray().apply { prompt.attachments.forEach { put(it.toJson()) } }))
                }
            })
        val atomic = AtomicFile(File(directory, "$id.json"))
        val output = atomic.startWrite()
        try {
            output.write(root.toString().toByteArray())
            atomic.finishWrite(output)
        } catch (error: Exception) { atomic.failWrite(output); throw error }
    }
    @Synchronized fun referencedPaths(): Set<String> = directory.listFiles().orEmpty()
        .mapNotNull { it.name.removeSuffix(".json").toLongOrNull() }
        .flatMap { id -> load(id).let { composer -> composer.attachments + composer.queue.flatMap { it.attachments } } }
        .map { it.path }.toSet()

    @Synchronized fun delete(id: Long) {
        reserveRevision(id)
        AtomicFile(File(directory, "$id.json")).delete()
    }
}
