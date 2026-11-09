package dev.pocketagent.ui

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Fixed public sources only. No service credentials, user prompts or generated images are sent. */
internal data class StudioPromptSource(val id: String, val title: String, val repo: String) {
    val url get() = "https://github.com/$repo"
    val documentUrl get() = "$url/blob/main/README_zh.md"
    val licenseUrl get() = "$url/blob/main/LICENSE"
}

internal val studioPromptSources = listOf(
    StudioPromptSource("gpt-image", "GPT Image", "YouMind-OpenLab/awesome-gpt-image-2"),
    StudioPromptSource("nano-banana", "Nano Banana", "YouMind-OpenLab/awesome-nano-banana-pro-prompts"),
)

internal data class StudioCommunityPrompt(
    val id: String,
    val title: String,
    val description: String,
    val prompt: String,
    val author: String,
    val originalUrl: String?,
) {
    fun matches(query: String): Boolean = listOf(title, description, prompt, author)
        .any { it.contains(query.trim(), ignoreCase = true) }
}

/** Parse README content as inert text, never execute HTML, instructions or linked workflows. */
internal fun parseStudioCommunityPrompts(markdown: String): List<StudioCommunityPrompt> {
    val heading = Regex("(?m)^### (?:No\\.\\s*)?(\\d+)[：:]\\s*(.+)\\r?$")
    val matches = heading.findAll(markdown).toList()
    return matches.mapIndexedNotNull { index, match ->
        val block = markdown.substring(match.range.last + 1, matches.getOrNull(index + 1)?.range?.first ?: markdown.length)
        // Ignore code blocks before the explicit prompt section (e.g. descriptions or examples).
        val section = Regex("(?m)^#### [^\\n]*(?:提示词|Prompt)[^\\n]*$", RegexOption.IGNORE_CASE).find(block)
            ?: return@mapIndexedNotNull null
        val remaining = block.substring(section.range.last + 1)
        val fence = Regex("(?ms)^```[^\\n]*\\n(.*?)^```[ \\t]*\\r?$").find(remaining)
            ?: return@mapIndexedNotNull null
        // A heading inside the fenced prompt is content; a heading before it ends the section.
        if (Regex("(?m)^#{1,4} ").containsMatchIn(remaining.substring(0, fence.range.first)))
            return@mapIndexedNotNull null
        val prompt = fence.groupValues[1].trim()
        if (prompt.isBlank() || prompt.length > 20_000) return@mapIndexedNotNull null
        val description = Regex("(?ms)^#### [^\\n]*(?:描述|Description)[^\\n]*\\n(.*?)(?=^#### |\\z)", RegexOption.IGNORE_CASE)
            .find(block)?.groupValues?.get(1)?.trim().orEmpty().take(1200)
        val author = Regex("\\*\\*(?:作者|Author):?\\*\\*[:：]?\\s*\\[([^]]+)]", RegexOption.IGNORE_CASE)
            .find(block)?.groupValues?.get(1).orEmpty()
        val original = Regex("\\*\\*(?:来源|Source):?\\*\\*[:：]?\\s*\\[[^]]*]\\((https://[^\\s)]+)\\)", RegexOption.IGNORE_CASE)
            .find(block)?.groupValues?.get(1)
        StudioCommunityPrompt(match.groupValues[1], match.groupValues[2].trim(), description, prompt, author, original)
    }.distinctBy { it.id }.take(500)
}

/** Keep source text intact; require the user to shorten overlong prompts before applying. */
internal fun canApplyStudioPrompt(prompt: String): Boolean = prompt.isNotBlank() && prompt.length <= 16_000

internal class StudioPromptRepository {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS)
        .callTimeout(40, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()
    private val cache = mutableMapOf<String, List<StudioCommunityPrompt>>()

    suspend fun load(source: StudioPromptSource, refresh: Boolean = false): List<StudioCommunityPrompt> = withContext(Dispatchers.IO) {
        if (!refresh) cache[source.id]?.let { return@withContext it }
        val request = Request.Builder()
            .url("https://api.github.com/repos/${source.repo}/contents/README_zh.md")
            .header("Accept", "application/vnd.github.raw+json")
            .header("User-Agent", "CodeMate-PromptCatalog").build()
        val text = readDocument(request)
        val entries = parseStudioCommunityPrompts(text)
        check(entries.isNotEmpty()) { "目录格式已变化" }
        cache[source.id] = entries
        entries
    }

    private suspend fun readDocument(request: Request): String = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { continuation.resumeWith(Result.failure(e)) }
            override fun onResponse(call: Call, response: Response) {
                val result = runCatching { response.use { readBody(it) } }
                continuation.resumeWith(result)
            }
        })
    }

    private fun readBody(response: Response): String {
            check(response.isSuccessful) { "HTTP ${response.code}" }
            val body = checkNotNull(response.body)
            check(body.contentLength() <= MAX_BYTES) { "提示词目录过大" }
            val bytes = java.io.ByteArrayOutputStream().use { output ->
                val input = body.byteStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    check(output.size() + count <= MAX_BYTES) { "提示词目录过大" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            val raw = bytes.toString(Charsets.UTF_8)
            // Also accept GitHub's JSON envelope when an intermediary strips the Accept header.
            return if (raw.trimStart().startsWith("{")) {
                val json = JSONObject(raw)
                check(json.optString("encoding") == "base64") { "目录格式不受支持" }
                java.util.Base64.getMimeDecoder().decode(json.getString("content")).toString(Charsets.UTF_8)
            } else raw
    }

    private companion object { const val MAX_BYTES = 4 * 1024 * 1024 }
}
