package dev.pocketagent.data

import android.content.Context
import android.util.AtomicFile
import dev.pocketagent.agent.AgentMessage
import dev.pocketagent.agent.AgentPlanStep
import dev.pocketagent.agent.TokenUsage
import dev.pocketagent.agent.ToolActivity
import java.io.File
import java.io.IOException
import java.io.OutputStreamWriter

data class StoredMessage(
    val id: Long,
    val role: String,
    val content: String,
    val timestamp: Long,
    val activities: List<ToolActivity> = emptyList(),
    val usage: TokenUsage? = null,
    val lastRequestUsage: TokenUsage? = null,
    val lastRequestAt: Long? = null,
    val plan: List<AgentPlanStep> = emptyList(),
    val error: String? = null,
    val retryCount: Int = 0,
    val goal: String? = null,
    val phase: String = "",
    val reasoning: String = "",
    val attachments: List<ChatAttachment> = emptyList(),
)

data class StoredConversation(
    val id: Long,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val messages: List<StoredMessage>,
    val agentMessages: List<AgentMessage> = emptyList(),
)

data class ConversationSummary(
    val id: Long,
    val title: String,
    val updatedAt: Long,
    val messageCount: Int,
    val preview: String,
)

class ConversationStore(context: Context) {
    private val atomicFile = AtomicFile(File(context.applicationContext.filesDir, "conversations.json"))

    private var historyLoaded = false

    @Volatile
    var loadFailure: Throwable? = null
        private set

    @Synchronized
    fun load(): List<StoredConversation> {
        loadFailure = null
        historyLoaded = false
        return try {
            // openRead also restores a legacy AtomicFile .bak after an interrupted write.
            atomicFile.openRead().bufferedReader(Charsets.UTF_8).use(ConversationJson::read)
                .also { historyLoaded = true }
        } catch (error: java.io.FileNotFoundException) {
            if (!atomicFile.baseFile.exists() && !File(atomicFile.baseFile.path + ".bak").exists()) {
                historyLoaded = true
                emptyList()
            } else {
                loadFailure = error
                emptyList()
            }
        } catch (error: Throwable) {
            // Never let an unreadable history be replaced by an empty/new conversation cache.
            loadFailure = error
            emptyList()
        }
    }

    @Synchronized
    fun save(conversations: List<StoredConversation>) {
        if (!historyLoaded) throw IOException("历史尚未成功读取，已阻止覆盖原文件", loadFailure)
        val output = atomicFile.startWrite()
        try {
            // Do not close the wrapper before finishWrite: AtomicFile must fsync the open FD.
            val writer = OutputStreamWriter(output, Charsets.UTF_8).buffered()
            ConversationJson.write(writer, conversations)
            writer.flush()
            atomicFile.finishWrite(output)
        } catch (error: Throwable) {
            // Roll back even for allocation errors; retain the last complete history.
            try {
                atomicFile.failWrite(output)
            } catch (rollback: Throwable) {
                error.addSuppressed(rollback)
            }
            throw error
        }
    }
}
