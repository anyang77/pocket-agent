package dev.pocketagent.runtime

enum class RuntimeKind {
    SYSTEM_SHELL,
    ROOT_SHELL,
    TERMUX,
}

enum class RuntimePreference {
    STANDARD,
    ROOT_WHEN_AVAILABLE,
}

data class RuntimeCapabilities(
    val systemShell: Boolean = true,
    val internalTermuxReady: Boolean = false,
    val rootGranted: Boolean = false,
    val systemReadable: Boolean = false,
    val chrootAvailable: Boolean = false,
    val mountAvailable: Boolean = false,
    val error: String? = null,
) {
    val runtimeLabel: String
        get() = when {
            internalTermuxReady -> "内置 Termux"
            rootGranted -> "Root 可用"
            else -> "系统 Shell"
        }
}

object RuntimeSelector {
    fun select(
        capabilities: RuntimeCapabilities,
        preference: RuntimePreference,
    ): RuntimeKind = when {
        capabilities.internalTermuxReady -> RuntimeKind.TERMUX
        preference == RuntimePreference.ROOT_WHEN_AVAILABLE && capabilities.rootGranted ->
            RuntimeKind.ROOT_SHELL
        else -> RuntimeKind.SYSTEM_SHELL
    }
}
