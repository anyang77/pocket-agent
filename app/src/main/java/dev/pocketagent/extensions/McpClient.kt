package dev.pocketagent.extensions

import dev.pocketagent.agent.ToolDefinition
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.buffer
import okio.source
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

interface McpTransport : Closeable {
    suspend fun send(message: JSONObject, expectReply: Boolean = true): JSONObject?
}

/** Streamable HTTP: JSON and SSE responses, negotiated protocol, and per-client session headers. */
class HttpMcpTransport(private val config: McpServerConfig,
    private val client: OkHttpClient = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS).callTimeout(65, TimeUnit.SECONDS).followRedirects(false).build(),
) : McpTransport {
    private var session: String? = null
    var protocol: String? = null
    private val active = ConcurrentHashMap.newKeySet<Call>()
    override suspend fun send(message: JSONObject, expectReply: Boolean): JSONObject? = suspendCancellableCoroutine { continuation ->
        val request = Request.Builder().url(config.url)
            .post(message.toString().toRequestBody("application/json".toMediaType()))
            .header("Accept", "application/json, text/event-stream")
        config.headers.forEach { (name, value) -> request.header(name, value) }
        session?.let { request.header("Mcp-Session-Id", it) }
        protocol?.let { request.header("MCP-Protocol-Version", it) }
        val call = client.newCall(request.build())
        active.add(call)
        continuation.invokeOnCancellation { call.cancel(); active.remove(call) }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                active.remove(call)
                if (continuation.isActive) continuation.resumeWithException(IOException("MCP 网络连接失败", error))
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use {
                        require(response.isSuccessful) { "MCP 服务返回 HTTP ${response.code}" }
                        response.header("Mcp-Session-Id")?.let { session = it }
                        val value = if (!expectReply || response.code == 202 || response.code == 204) null else {
                            val body = response.body ?: error("MCP 响应为空")
                            if (response.header("Content-Type").orEmpty().contains("text/event-stream", true)) {
                                readSseReply(body.source(), message.opt("id")?.toString().orEmpty())
                            } else {
                                val source = body.source()
                                require(!source.request(MAX_RPC_BYTES + 1)) { "MCP 响应超过 4 MB" }
                                JSONObject(source.readUtf8())
                            }
                        }
                        if (continuation.isActive) continuation.resume(value)
                    }
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                } finally { active.remove(call) }
            }
        })
    }
    override fun close() { active.forEach { it.cancel() }; active.clear() }
}

private const val MAX_RPC_BYTES = 4L * 1024 * 1024
internal fun readSseReply(source: okio.BufferedSource, expectedId: String): JSONObject {
    val data = StringBuilder()
    var total = 0L
    fun parse(): JSONObject? {
        if (data.isEmpty()) return null
        val event = runCatching { JSONObject(data.toString()) }.getOrNull()
        data.clear()
        return event?.takeIf { it.has("id") && it.opt("id")?.toString().orEmpty() == expectedId && (it.has("result") || it.has("error")) }
    }
    while (true) {
        val line = if (source.exhausted()) break else source.readUtf8LineStrict(MAX_RPC_BYTES)
        total += line.length
        require(total <= MAX_RPC_BYTES) { "MCP 事件响应超过 4 MB" }
        if (line.isBlank()) parse()?.let { return it }
        else if (line.startsWith("data:")) {
            if (data.isNotEmpty()) data.append('\n')
            data.append(line.substringAfter(':').removePrefix(" "))
        }
    }
    return parse() ?: error("MCP 事件流未返回请求结果")
}

/** Stdio is newline-delimited JSON-RPC. stderr is kept separate so logs cannot corrupt the wire. */
class StdioMcpTransport(private val process: Process, private val root: File) : McpTransport {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()
    private val output = process.outputStream.bufferedWriter()
    private val writeLock = Any()
    init {
        scope.launch {
            try {
                process.inputStream.source().buffer().use { source ->
                    while (!source.exhausted()) {
                        val line = source.readUtf8LineStrict(MAX_RPC_BYTES)
                        if (line.isBlank()) continue
                        val message = JSONObject(line)
                        if (message.has("method")) {
                            if (message.has("id")) replyToServer(message)
                        } else pending.remove(message.opt("id")?.toString().orEmpty())?.complete(message)
                    }
                }
                throw IOException("MCP 进程已结束")
            } catch (error: Exception) {
                pending.values.forEach { it.completeExceptionally(IOException("MCP stdio 连接已断开", error)) }; pending.clear()
            }
        }
        scope.launch {
            runCatching { process.errorStream.use { input -> val bytes = ByteArray(4096); while (input.read(bytes) >= 0) ensureActive() } }
        }
    }
    private fun write(message: JSONObject) = synchronized(writeLock) { output.write(message.toString()); output.newLine(); output.flush() }
    private fun replyToServer(message: JSONObject) {
        val result = JSONObject().put("jsonrpc", "2.0").put("id", message.get("id"))
        when (message.optString("method")) {
            "ping" -> result.put("result", JSONObject())
            "roots/list" -> result.put("result", JSONObject().put("roots", JSONArray().put(JSONObject().put("uri", root.toURI().toString()).put("name", root.name))))
            else -> result.put("error", JSONObject().put("code", -32601).put("message", "Client capability is unavailable"))
        }
        write(result)
    }
    override suspend fun send(message: JSONObject, expectReply: Boolean): JSONObject? = withContext(Dispatchers.IO) {
        if (!expectReply) { write(message); return@withContext null }
        val id = message.get("id").toString()
        val deferred = CompletableDeferred<JSONObject>()
        pending[id] = deferred
        try { write(message); withTimeout(60_000) { deferred.await() } }
        finally { pending.remove(id) }
    }
    override fun close() {
        pending.values.forEach { it.cancel() }; pending.clear()
        process.destroy()
        if (process.isAlive) process.destroyForcibly()
        scope.cancel()
    }
}

class McpClient(private val config: McpServerConfig, private val transport: McpTransport) : Closeable {
    private val serial = Mutex()
    private val ids = AtomicLong()
    private var connected = false
    private suspend fun request(method: String, params: JSONObject = JSONObject()): JSONObject {
        val id = ids.incrementAndGet()
        val reply = transport.send(JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", method).put("params", params))
            ?: error("MCP 未返回结果")
        require(reply.opt("id")?.toString().orEmpty() == id.toString()) { "MCP 响应编号不匹配" }
        reply.optJSONObject("error")?.let { error("MCP 错误 ${it.optInt("code")}: ${it.optString("message").take(400)}") }
        return reply.optJSONObject("result") ?: error("MCP 结果格式无效")
    }
    suspend fun tools(): List<McpTool> = serial.withLock {
        if (!connected) {
            val result = request("initialize", JSONObject().put("protocolVersion", "2025-03-26")
                .put("capabilities", JSONObject().apply {
                    if (transport is StdioMcpTransport) put("roots", JSONObject().put("listChanged", false))
                })
                .put("clientInfo", JSONObject().put("name", "CodeMate").put("version", "0.1.0")))
            val version = result.getString("protocolVersion")
            require(version in listOf("2024-11-05", "2025-03-26", "2025-06-18", "2025-11-25")) { "不支持 MCP 协议版本 $version" }
            (transport as? HttpMcpTransport)?.protocol = version
            transport.send(JSONObject().put("jsonrpc", "2.0").put("method", "notifications/initialized"), false)
            connected = true
        }
        val all = mutableListOf<McpTool>()
        var cursor: String? = null
        val seen = mutableSetOf<String>()
        do {
            val result = request("tools/list", JSONObject().apply { cursor?.let { put("cursor", it) } })
            val tools = result.optJSONArray("tools") ?: JSONArray()
            for (i in 0 until tools.length()) {
                val item = tools.getJSONObject(i)
                val name = item.getString("name")
                val alias = mcpToolAlias(config.id, name)
                all += McpTool(config.id, name, ToolDefinition(alias, "[${config.name}] ${item.optString("description", name)}".take(1600),
                    item.optJSONObject("inputSchema") ?: toolSchema()), item.optJSONObject("annotations")?.optBoolean("readOnlyHint", false) ?: false)
            }
            require(all.size <= 256) { "MCP 服务工具过多（最多 256）" }
            cursor = result.optString("nextCursor").takeIf { it.isNotBlank() }
            require(cursor == null || seen.add(cursor!!)) { "MCP 工具分页重复" }
        } while (cursor != null)
        all.distinctBy { it.definition.name }
    }
    suspend fun call(name: String, arguments: JSONObject): ExtensionToolResult = serial.withLock {
        val result = try { request("tools/call", JSONObject().put("name", name).put("arguments", arguments)) }
        catch (error: CancellationException) { close(); throw error }
        val content = result.optJSONArray("content") ?: JSONArray()
        val text = buildString {
            for (i in 0 until content.length()) {
                val item = content.getJSONObject(i)
                appendLine(when (item.optString("type")) {
                    "text" -> item.optString("text")
                    "resource" -> item.optJSONObject("resource")?.let { it.optString("text", it.optString("uri")) }.orEmpty()
                    "image", "audio" -> "[${item.optString("type")} ${item.optString("mimeType")}; binary data omitted]"
                    "resource_link" -> "${item.optString("name")}: ${item.optString("uri")}"
                    else -> "[${item.optString("type")} content]"
                })
            }
            result.optJSONObject("structuredContent")?.let { appendLine(it.toString()) }
        }.take(64_000).ifBlank { "MCP 工具已完成" }
        val success = !result.optBoolean("isError", false)
        ExtensionToolResult(text, success, "${config.name} · $name${if (success) " 已完成" else " 失败"}")
    }
    override fun close() { connected = false; transport.close() }
}

internal fun mcpToolAlias(serverId: String, name: String): String {
    fun hash(text: String) = java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).take(5).joinToString("") { "%02x".format(it) }
    return "mcp_${hash(serverId)}_${name.replace(Regex("[^A-Za-z0-9_-]"), "_").take(30)}_${hash(name)}"
}
