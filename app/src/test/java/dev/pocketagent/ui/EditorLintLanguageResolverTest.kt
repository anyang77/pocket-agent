package dev.pocketagent.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EditorLintLanguageResolverTest {
    @Test
    fun detectsRustSource() {
        assertEquals(EditorLintLanguage.RUST,
            EditorLintLanguageResolver.resolve("src/lib.rs", "pub fn run() {}"))
    }

    @Test
    fun detectsShellByExtension() {
        assertEquals(
            EditorLintLanguage.SHELL,
            EditorLintLanguageResolver.resolve("script.sh", "#!/bin/sh\n"),
        )
    }

    @Test
    fun detectsBashShebangInShellFile() {
        assertEquals(
            EditorLintLanguage.BASH,
            EditorLintLanguageResolver.resolve("script.sh", "#!/usr/bin/env bash\n"),
        )
    }

    @Test
    fun detectsJavaSource() {
        assertEquals(
            EditorLintLanguage.JAVA,
            EditorLintLanguageResolver.resolve("Main.java", "class Main {}\n"),
        )
    }

    @Test
    fun usesPublicJavaTypeAsCompilerFilename() {
        assertEquals(
            "Main.java",
            EditorLintLanguageResolver.javaSourceFilename(
                "Download/demo.java",
                "public final class Main {}\n",
            ),
        )
    }

    @Test
    fun detectsShellWithoutExtensionFromShebang() {
        assertEquals(
            EditorLintLanguage.SHELL,
            EditorLintLanguageResolver.resolve("deploy", "#!/system/bin/sh\n"),
        )
    }

    @Test
    fun detectsCommonShellConfigFile() {
        assertEquals(
            EditorLintLanguage.SHELL,
            EditorLintLanguageResolver.resolve(".bashrc", "export PATH=/bin\n"),
        )
    }

    @Test
    fun neverTreatsWindowsBatchAsPosixShell() {
        assertNull(EditorLintLanguageResolver.resolve("build.bat", "@echo off\n"))
        assertNull(EditorLintLanguageResolver.resolve("BUILD.CMD", "REM comment\n"))
    }

    @Test
    fun ignoresUnsupportedTextFile() {
        assertNull(
            EditorLintLanguageResolver.resolve("notes.txt", "just text\n"),
        )
    }
}
