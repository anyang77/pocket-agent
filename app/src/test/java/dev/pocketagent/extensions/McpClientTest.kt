package dev.pocketagent.extensions

import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class McpClientTest {
    @Test fun negotiatesHttpSessionPaginatesToolsAndCallsToolThroughSse() = runBlocking {
        val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val requests = CopyOnWriteArrayList<Pair<Map<String, String>, JSONObject>>()
        val worker = thread(isDaemon = true) {
            repeat(5) {
                socket.accept().use { connection ->
                    connection.soTimeout = 5000
                    val input = connection.getInputStream()
                    fun line(): String {
                        val out = StringBuilder()
                        while (true) {
                            val b = input.read()
                            check(b >= 0)
                            if (b == 10) return out.toString().trimEnd('\r')
                            out.append(b.toChar())
                        }
                    }
                    line()
                    val headers = mutableMapOf<String, String>()
                    while (true) {
                        val line = line()
                        if (line.isEmpty()) break
                        headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
                    }
                    val data = ByteArray(headers.getValue("content-length").toInt())
                    var count = 0
                    while (count < data.size) { val read = input.read(data, count, data.size - count); check(read > 0); count += read }
                    val request = JSONObject(data.toString(Charsets.UTF_8))
                    requests += headers to request
                    val method = request.getString("method")
                    val result = when (method) {
                        "initialize" -> JSONObject().put("protocolVersion", "2025-03-26").put("capabilities", JSONObject().put("tools", JSONObject()))
                        "tools/list" -> JSONObject().put("tools", JSONArray().put(JSONObject().put("name", if (request.getJSONObject("params").has("cursor")) "second" else "first")
                            .put("inputSchema", JSONObject().put("type", "object")))).apply {
                                if (!request.getJSONObject("params").has("cursor")) put("nextCursor", "page2")
                            }
                        else -> JSONObject().put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "tool result")))
                    }
                    val reply = JSONObject().put("jsonrpc", "2.0").put("id", request.opt("id")).put("result", result).toString()
                    val body = if (method == "tools/call") "event: message\ndata: $reply\n\n" else reply
                    val response = if (method == "notifications/initialized") "HTTP/1.1 202 Accepted\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                    else "HTTP/1.1 200 OK\r\nContent-Type: ${if (method == "tools/call") "text/event-stream" else "application/json"}\r\nMcp-Session-Id: session-1\r\nContent-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n$body"
                    connection.getOutputStream().write(response.toByteArray())
                }
            }
        }
        val config = McpServerConfig("server", "fixture", url = "http://127.0.0.1:${socket.localPort}/mcp", headers = mapOf("Authorization" to "Bearer test"))
        val client = McpClient(config, HttpMcpTransport(config, OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build()))
        try {
            val tools = client.tools()
            assertEquals(listOf("first", "second"), tools.map { it.remoteName })
            assertEquals("tool result\n", client.call("first", JSONObject().put("a", 1)).content)
            assertEquals("initialize", requests[0].second.getString("method"))
            assertEquals("notifications/initialized", requests[1].second.getString("method"))
            assertEquals("session-1", requests[2].first["mcp-session-id"])
            assertEquals("2025-03-26", requests[2].first["mcp-protocol-version"])
            assertEquals("Bearer test", requests[4].first["authorization"])
            assertEquals(1, requests[4].second.getJSONObject("params").getJSONObject("arguments").getInt("a"))
        } finally { client.close(); socket.close(); worker.join(1000) }
    }
    @Test fun sseIgnoresNotificationsAndUnrelatedIds() {
        val input = Buffer().writeUtf8(": ping\n\ndata: {\"method\":\"notifications/progress\"}\n\ndata: {\"id\":99,\"result\":{}}\n\ndata: {\"id\":2,\"result\":{\"ok\":true}}\n\n")
        assertTrue(readSseReply(input, "2").getJSONObject("result").getBoolean("ok"))
    }
    @Test fun toolErrorsStayToolErrors() = runBlocking {
        val fake = object : McpTransport {
            override suspend fun send(message: JSONObject, expectReply: Boolean): JSONObject? {
                if (!expectReply) return null
                return JSONObject().put("id", message.get("id")).put("result", JSONObject().put("isError", true)
                    .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "operation denied"))))
            }
            override fun close() {}
        }
        val result = McpClient(McpServerConfig("id", "fake", url = "https://example.com"), fake).call("test", JSONObject())
        assertFalse(result.success)
        assertTrue(result.content.contains("operation denied"))
    }
    @Test fun stdioKeepsServerLogsOutOfJsonRpcAndHandlesTools() = runBlocking {
        val script = """
            import json,sys
            for line in sys.stdin:
                request=json.loads(line)
                if 'id' not in request: continue
                method=request['method']
                if method=='initialize': result={'protocolVersion':'2025-03-26','capabilities':{'tools':{}}}
                elif method=='tools/list': result={'tools':[{'name':'echo','inputSchema':{'type':'object'}}]}
                else: result={'content':[{'type':'text','text':request['params']['arguments']['text']}]}
                print('server log',file=sys.stderr,flush=True)
                print(json.dumps({'jsonrpc':'2.0','id':request['id'],'result':result}),flush=True)
        """.trimIndent()
        val process = ProcessBuilder("python3", "-u", "-c", script).start()
        val client = McpClient(McpServerConfig("stdio", "local", transport = "stdio", command = "python3"),
            StdioMcpTransport(process, java.io.File(System.getProperty("java.io.tmpdir") ?: ".")))
        try {
            assertEquals("echo", client.tools().single().remoteName)
            assertEquals("stdio works\n", client.call("echo", JSONObject().put("text", "stdio works")).content)
        } finally { client.close() }
        assertTrue(process.waitFor(2, TimeUnit.SECONDS))
        assertFalse(process.isAlive)
    }

}
