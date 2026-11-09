package dev.pocketagent.ui

import dev.pocketagent.agent.AiClient
import dev.pocketagent.data.AiSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class StudioModelCatalogState(
    val models: List<String> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
)

/** Isolated from Agent model selection; stale service responses must never replace a newer list. */
internal class StudioModelCatalog(
    private val scope: CoroutineScope,
    private val fetch: suspend (AiSettings) -> List<String> = { settings -> AiClient().listModels(settings).map { it.id } },
) {
    private val mutable = MutableStateFlow(StudioModelCatalogState())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    private var revision = 0L

    fun clear(message: String) {
        ++revision
        job?.cancel()
        mutable.value = StudioModelCatalogState(error = message)
    }

    fun load(settings: AiSettings) {
        val request = ++revision
        job?.cancel()
        mutable.value = StudioModelCatalogState(loading = true)
        job = scope.launch {
            try {
                val models = fetch(settings)
                if (request == revision) mutable.value = StudioModelCatalogState(models = models)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // Do not surface provider response bodies that might contain sensitive data.
                if (request == revision) mutable.value = StudioModelCatalogState(
                    error = "模型列表读取失败。请检查所选服务的地址、密钥和网络后刷新；也可以手动填写模型 ID。")
            }
        }
    }
}
