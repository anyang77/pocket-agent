package dev.pocketagent.agent

import dev.pocketagent.data.AiSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.EventListener
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class AiClientConnectionRetryTest {
    private val loopback = InetAddress.getByName("127.0.0.1")
    private fun unusedPort() = ServerSocket(0, 1, loopback).use { it.localPort }
    private fun settings(port: Int) = AiSettings("http://127.0.0.1:$port/v1", "test-model", "test-key")
    private fun http(counter: AtomicInteger = AtomicInteger()) = OkHttpClient.Builder()
        .retryOnConnectionFailure(false)
        .connectTimeout(1, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .callTimeout(3, TimeUnit.SECONDS)
        .eventListener(object : EventListener() {
            override fun connectStart(call: okhttp3.Call, address: java.net.InetSocketAddress, proxy: java.net.Proxy) {
                counter.incrementAndGet()
            }
        }).build()

    @Test fun reconnectsAfterRealConnectionRefusalAndReceivesResponse() = runBlocking {
        val port = unusedPort()
        val attempts = AtomicInteger()
        val retries = mutableListOf<RetryNotice>()
        var server: ServerSocket? = null
        var responder: Thread? = null
        try {
            val result = AiClient(http(attempts)).complete(settings(port), listOf(AgentMessage("user", "hello")), emptyList(), onRetry = { notice ->
                retries += notice
                if (server == null) {
                    server = ServerSocket(port, 1, loopback)
                    responder = thread(isDaemon = true, name = "recovered-connection-server") {
                        server!!.accept().use { socket ->
                            socket.getInputStream().read(ByteArray(8192))
                            val body = """{"choices":[{"message":{"content":"recovered"},"finish_reason":"stop"}]}"""
                            socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n$body").toByteArray())
                            socket.getOutputStream().flush()
                        }
                    }
                }
            })
            assertEquals("recovered", result.content)
            assertEquals(2, attempts.get())
            assertEquals(listOf(1), retries.map { it.attempt })
            assertEquals(5, retries.single().maxAttempts)
            assertTrue(retries.single().reason.contains("重新连接"))
        } finally { server?.close(); responder?.join(1000) }
    }

    @Test fun exhaustsExactlyFiveReconnectsForPersistentConnectionRefusal() = runBlocking {
        val attempts = AtomicInteger()
        val retries = mutableListOf<RetryNotice>()
        try {
            AiClient(http(attempts)).complete(settings(unusedPort()), listOf(AgentMessage("user", "hello")), emptyList(), onRetry = retries::add)
            fail("A closed port must fail after all reconnects")
        } catch (error: AgentException) {
            assertEquals(5, error.retryAttempts)
            assertTrue(error.message.orEmpty().contains("Failed to connect", ignoreCase = true))
        }
        assertEquals(6, attempts.get())
        assertEquals(listOf(1, 2, 3, 4, 5), retries.map { it.attempt })
        assertEquals(listOf(1, 2, 4, 8, 16), retries.map { it.delaySeconds })
        assertTrue(retries.all { it.maxAttempts == 5 })
    }

    @Test fun cancellationDuringReconnectWaitDoesNotStartAnotherConnection() = runBlocking {
        val attempts = AtomicInteger()
        val waiting = CompletableDeferred<Unit>()
        val retries = mutableListOf<RetryNotice>()
        val job = launch {
            AiClient(http(attempts)).complete(settings(unusedPort()), listOf(AgentMessage("user", "hello")), emptyList(), onRetry = {
                retries += it
                waiting.complete(Unit)
            })
        }
        try {
            kotlinx.coroutines.withTimeout(5000) { waiting.await() }
        } finally { job.cancelAndJoin() }
        assertTrue(job.isCancelled)
        assertEquals(1, attempts.get())
        assertEquals(listOf(1), retries.map { it.attempt })
    }
}
