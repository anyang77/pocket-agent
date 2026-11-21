package dev.pocketagent.data

import java.util.UUID

/** Stable local ID: legacy sessions migrate deterministically; new sessions use random UUIDs. */
fun legacyConversationDeepId(id: Long, createdAt: Long): String =
    UUID.nameUUIDFromBytes("codemate-session:$id:$createdAt".toByteArray(Charsets.UTF_8)).toString()

fun newConversationDeepId(): String = UUID.randomUUID().toString()

/** No credentials in conversation history; resolve the provider from encrypted settings. */
data class ConversationModelSelection(
    val profileId: String,
    val model: String,
    val reasoningEffort: String = "auto",
) {
    fun resolve(profiles: List<ModelProfile>, workspacePath: String): AiSettings? {
        val profile = profiles.firstOrNull { it.id == profileId } ?: return null
        return AiSettings(profile.baseUrl, model, profile.apiKey, profile.contextWindow,
            workspacePath, reasoningEffort, profile.customReasoningEfforts)
    }
}

/** Copy a completed snapshot into a target session; keep the target identity and model. */
fun StoredConversation.forkInto(target: StoredConversation, nextMessageId: () -> Long): StoredConversation =
    target.copy(title = "接续 · ${title.removePrefix("接续 · ")}".take(50),
        messages = messages.map { message -> message.copy(id = nextMessageId(),
            activities = message.activities.map { activity ->
                if (activity.success == null) activity.copy(success = false, result = activity.result ?: "源会话执行未完成") else activity
            }) },
        agentMessages = agentMessages,
        inheritedFromDeepId = deepId)
