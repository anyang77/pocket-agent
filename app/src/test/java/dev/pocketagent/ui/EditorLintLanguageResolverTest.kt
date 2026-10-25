package dev.pocketagent.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EditorLintLanguageResolverTest {
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
    fun ignoresUnsupportedTextFile() {
        assertNull(
            EditorLintLanguageResolver.resolve("notes.txt", "just text\n"),
        )
    }
}
