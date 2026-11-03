package dev.pocketagent

import android.app.Application
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner
import dev.pocketagent.ui.AppViewModel
import dev.pocketagent.runtime.AgentRuntimeOwner

/** Process-scoped runtime: finishing or recreating the UI must not cancel work.
 * Process death still requires explicit recovery; never replay tool calls here.
 */
class CodeMateApplication : Application(), ViewModelStoreOwner {
    private val runtimeOwner = AgentRuntimeOwner()
    override val viewModelStore get() = runtimeOwner.viewModelStore

    val appViewModel: AppViewModel
        get() = ViewModelProvider(
            this,
            ViewModelProvider.AndroidViewModelFactory.getInstance(this),
        )[AppViewModel::class.java]
}
