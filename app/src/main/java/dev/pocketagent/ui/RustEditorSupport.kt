package dev.pocketagent.ui

import java.io.File

/** Resolves the Cargo edition for a buffer without running Cargo or modifying its project. */
object RustEditorSupport {
    private val editionValue = Regex("""^\s*edition\s*=\s*["'](2015|2018|2021|2024)["']""")
    private val inheritedEdition = Regex("""^\s*edition\.workspace\s*=\s*true\b""")

    fun edition(source: File, workspace: File): String {
        val root = workspace.canonicalFile
        var directory = source.canonicalFile.parentFile
        var inherit = false
        while (directory != null && (directory == root || directory.path.startsWith(root.path + File.separator))) {
            val manifest = File(directory, "Cargo.toml")
            if (manifest.isFile) {
                var section = ""
                var declared: String? = null
                var workspaceEdition: String? = null
                var declaresPackage = false
                manifest.readLines().forEach { line ->
                    val trimmed = line.trim()
                    if (trimmed.startsWith("[")) section = trimmed.substringBefore('#').trim()
                    if (section == "[package]") {
                        declaresPackage = true
                        if (!inherit) {
                            editionValue.find(line)?.let { declared = it.groupValues[1] }
                            if (inheritedEdition.containsMatchIn(line)) inherit = true
                        }
                    }
                    if (section == "[workspace.package]") {
                        editionValue.find(line)?.let { workspaceEdition = it.groupValues[1] }
                    }
                }
                declared?.let { return it }
                if (inherit) workspaceEdition?.let { return it }
                if (declaresPackage && !inherit) return "2015"
            }
            directory = directory.parentFile
        }
        return "2021"
    }
}
