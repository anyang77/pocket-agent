package dev.pocketagent.ui

enum class EditorLintLanguage(
    val sourceExtension: String,
    val shellDialect: String? = null,
) {
    PYTHON("py"),
    JAVASCRIPT("js"),
    JAVA("java"),
    SHELL("sh", "sh"),
    BASH("sh", "bash"),
    JSON("json"),
    C("c"),
    CPP("cpp"),
}

object EditorLintLanguageResolver {
    private val publicJavaType = Regex(
        """\bpublic\s+(?:(?:abstract|final|sealed|non-sealed|strictfp)\s+)*""" +
            """(?:class|interface|enum|record)\s+([A-Za-z_$][A-Za-z0-9_$]*)""",
    )
    private val extensionMap = mapOf(
        "py" to EditorLintLanguage.PYTHON,
        "python" to EditorLintLanguage.PYTHON,
        "js" to EditorLintLanguage.JAVASCRIPT,
        "mjs" to EditorLintLanguage.JAVASCRIPT,
        "cjs" to EditorLintLanguage.JAVASCRIPT,
        "java" to EditorLintLanguage.JAVA,
        "json" to EditorLintLanguage.JSON,
        "c" to EditorLintLanguage.C,
        "h" to EditorLintLanguage.C,
        "cc" to EditorLintLanguage.CPP,
        "cpp" to EditorLintLanguage.CPP,
        "cxx" to EditorLintLanguage.CPP,
        "hpp" to EditorLintLanguage.CPP,
    )

    private val shellFilenames = setOf(
        ".bash_profile",
        ".bash_login",
        ".bashrc",
        ".profile",
        "bashrc",
        "profile",
    )

    fun resolve(path: String, content: String): EditorLintLanguage? {
        val name = path.substringAfterLast('/').lowercase()
        val extension = name.substringAfterLast('.', "")
        val shebang = content.lineSequence().firstOrNull()
            ?.trim()
            ?.takeIf { it.startsWith("#!") }
            ?.lowercase()
            .orEmpty()

        if (extension == "sh" || extension == "shell") {
            return if (shebang.contains("bash")) {
                EditorLintLanguage.BASH
            } else {
                EditorLintLanguage.SHELL
            }
        }
        if (extension == "bash") return EditorLintLanguage.BASH
        extensionMap[extension]?.let { return it }

        if (extension.isBlank() || name in shellFilenames) {
            if (shebang.contains("bash")) return EditorLintLanguage.BASH
            if (
                shebang.contains("/sh") ||
                shebang.contains("/dash") ||
                name in shellFilenames
            ) {
                return EditorLintLanguage.SHELL
            }
        }
        return null
    }

    fun javaSourceFilename(path: String, content: String): String {
        val publicType = publicJavaType.find(content)?.groupValues?.getOrNull(1)
        if (!publicType.isNullOrBlank()) return "$publicType.java"
        val original = path.substringAfterLast('/').substringBeforeLast('.')
        return if (original.matches(Regex("""[A-Za-z_$][A-Za-z0-9_$]*"""))) {
            "$original.java"
        } else {
            "Source.java"
        }
    }
}
