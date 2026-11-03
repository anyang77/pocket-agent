package dev.pocketagent.runtime

import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner

/** Shared by UI and service for the lifetime of the application process. */
internal class AgentRuntimeOwner : ViewModelStoreOwner {
    override val viewModelStore = ViewModelStore()
}
