package dev.pocketagent.data

data class GitRemoteBranches(
    val names: List<String>,
    val defaultBranch: String?,
)

object GitRemoteBranchParser {
    fun parse(output: String): GitRemoteBranches {
        val lines = output.lineSequence().map { it.trim().split('\t', limit = 2) }
            .filter { it.size == 2 }.toList()
        val defaultBranch = lines.firstOrNull {
            it[0].startsWith("ref: refs/heads/") && it[1] == "HEAD"
        }?.get(0)?.removePrefix("ref: refs/heads/")
        val names = lines.mapNotNull { fields ->
            fields[1].takeIf { it.startsWith("refs/heads/") }
                ?.removePrefix("refs/heads/")
        }.distinct().sortedWith(compareBy<String> { it != defaultBranch }.thenBy { it })
        return GitRemoteBranches(names, defaultBranch)
    }
}
