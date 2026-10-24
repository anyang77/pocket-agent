package dev.pocketagent.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

data class AuditEntry(
    val id: Long,
    val timestamp: Long,
    val category: String,
    val action: String,
    val detail: String,
    val result: String? = null,
)

class AuditLog(context: Context) {
    private val file = File(context.applicationContext.filesDir, "audit/audit.jsonl")
    private val nextId = AtomicLong(1)
    private val lock = Any()
    private val _entries = MutableStateFlow(load())
    val entries: StateFlow<List<AuditEntry>> = _entries.asStateFlow()

    init {
        nextId.set((_entries.value.maxOfOrNull { it.id } ?: 0) + 1)
    }

    fun append(
        category: String,
        action: String,
        detail: String,
        result: String? = null,
    ) {
        synchronized(lock) {
            val entry = AuditEntry(
                id = nextId.getAndIncrement(),
                timestamp = Instant.now().toEpochMilli(),
                category = category,
                action = action,
                detail = detail.take(MAX_DETAIL_CHARS),
                result = result?.take(MAX_RESULT_CHARS),
            )
            file.parentFile?.mkdirs()
            file.appendText(
                JSONObject().apply {
                    put("id", entry.id)
                    put("timestamp", entry.timestamp)
                    put("category", entry.category)
                    put("action", entry.action)
                    put("detail", entry.detail)
                    result?.let { put("result", entry.result) }
                }.toString() + "\n",
            )
            _entries.update { (it + entry).takeLast(MAX_ENTRIES) }
        }
    }

    private fun load(): List<AuditEntry> {
        if (!file.isFile) return emptyList()
        return runCatching {
            file.readLines()
                .takeLast(MAX_ENTRIES)
                .mapNotNull { line ->
                    runCatching {
                        val json = JSONObject(line)
                        AuditEntry(
                            id = json.getLong("id"),
                            timestamp = json.getLong("timestamp"),
                            category = json.getString("category"),
                            action = json.getString("action"),
                            detail = json.optString("detail"),
                            result = json.optString("result").takeIf { it.isNotBlank() },
                        )
                    }.getOrNull()
                }
        }.getOrDefault(emptyList())
    }

    companion object {
        private const val MAX_ENTRIES = 2_000
        private const val MAX_DETAIL_CHARS = 8_000
        private const val MAX_RESULT_CHARS = 1_000
    }
}
