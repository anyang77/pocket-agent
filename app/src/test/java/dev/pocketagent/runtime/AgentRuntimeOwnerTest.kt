package dev.pocketagent.runtime

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import org.junit.Assert.*
import org.junit.Test

class AgentRuntimeOwnerTest {
    private class RuntimeModel : ViewModel() {
        var cancelled = false
        override fun onCleared() { cancelled = true }
    }

    private val factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = RuntimeModel() as T
    }

    @Test fun reopeningUiKeepsTheSameRuntime() {
        val process = AgentRuntimeOwner()
        val first = ViewModelProvider(process, factory)[RuntimeModel::class.java]
        val reopened = ViewModelProvider(process, factory)[RuntimeModel::class.java]
        assertSame(first, reopened)
        assertFalse(first.cancelled)
        process.viewModelStore.clear()
    }

    @Test fun destroyingActivityStoreDoesNotCancelProcessRuntime() {
        val process = AgentRuntimeOwner()
        val runtime = ViewModelProvider(process, factory)[RuntimeModel::class.java]
        val activity = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
        val local = ViewModelProvider(activity, factory)[RuntimeModel::class.java]
        activity.viewModelStore.clear()
        assertTrue(local.cancelled)
        assertFalse(runtime.cancelled)
        assertSame(runtime, ViewModelProvider(process, factory)[RuntimeModel::class.java])
        process.viewModelStore.clear()
        assertTrue(runtime.cancelled)
    }
}
