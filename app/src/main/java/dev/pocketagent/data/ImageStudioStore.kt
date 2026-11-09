package dev.pocketagent.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

const val LEGACY_STUDIO_CONVERSATION = "legacy-studio"

/** Metadata never contains credentials or encoded image bytes. Original files remain in the workspace. */
data class StudioArtwork(
    val id: String,
    val path: String,
    val prompt: String,
    val model: String,
    val size: String,
    val createdAt: Long,
    val elapsedMs: Long,
    val profileId: String = "",
    val diagnostics: String = "",
    val requestId: String = "",
    val conversationId: String = LEGACY_STUDIO_CONVERSATION,
) {
    fun attachment() = ChatAttachment(id, File(path).name, path, "image", mimeType = when (File(path).extension) {
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        else -> "image/png"
    })
}

class ImageStudioStore(private val file: File) {
    @Synchronized fun load(): List<StudioArtwork> {
        if (!file.exists()) return emptyList()
        val data = JSONArray(file.readText())
        return (0 until data.length()).map { index ->
            val item = data.getJSONObject(index)
            StudioArtwork(item.getString("id"), item.getString("path"), item.getString("prompt"),
                item.getString("model"), item.getString("size"), item.getLong("created_at"), item.getLong("elapsed_ms"), item.optString("profile"), item.optString("diagnostics"), item.optString("request_id"), item.optString("conversation_id", LEGACY_STUDIO_CONVERSATION))
        }
    }

    @Synchronized fun save(artworks: List<StudioArtwork>) {
        require(file.parentFile?.let { it.mkdirs() || it.isDirectory } == true) { "无法创建作品目录" }
        val data = JSONArray().apply {
            artworks.forEach { artwork -> put(JSONObject().apply {
                put("id", artwork.id); put("path", artwork.path); put("prompt", artwork.prompt)
                put("model", artwork.model); put("size", artwork.size)
                put("created_at", artwork.createdAt); put("elapsed_ms", artwork.elapsedMs); put("profile", artwork.profileId)
                put("diagnostics", artwork.diagnostics); put("request_id", artwork.requestId)
                put("conversation_id", artwork.conversationId)
            }) }
        }
        val staging = File(file.parentFile, "${file.name}.tmp")
        try {
            staging.outputStream().use { output ->
                output.write(data.toString().toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            check(staging.renameTo(file)) { "作品记录保存失败" }
        } finally { staging.delete() }
    }
}
