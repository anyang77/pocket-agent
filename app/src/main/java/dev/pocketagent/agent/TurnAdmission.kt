package dev.pocketagent.agent

/** Accept and publish history before any suspending extension setup can fail. */
internal suspend fun admitUserTurn(
    history: MutableList<AgentMessage>,
    message: AgentMessage,
    onAccepted: () -> Unit,
    prepare: suspend () -> Unit,
) {
    require(message.role == "user")
    history += message
    onAccepted()
    prepare()
}
