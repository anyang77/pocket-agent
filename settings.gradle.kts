pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "PocketAgent"
// xposed-oaid（LSPosed 模块）已独立到 /storage/emulated/0/xposed-oaid，
// 有自己的 settings.gradle.kts 与 gradlew，不再作为本仓库的子工程参与构建。
include(":app", ":terminal-emulator", ":terminal-view")
