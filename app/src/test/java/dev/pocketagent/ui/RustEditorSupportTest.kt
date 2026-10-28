package dev.pocketagent.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RustEditorSupportTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun usesNearestPackageEdition() {
        val root = temporary.newFolder()
        File(root, "Cargo.toml").writeText("[package]\nedition = '2024'\n")
        assertEquals("2024", RustEditorSupport.edition(File(root, "src/lib.rs"), root))
    }

    @Test fun resolvesInheritedWorkspaceEdition() {
        val root = temporary.newFolder()
        File(root, "Cargo.toml").writeText("[workspace.package]\nedition = '2021'\n")
        val member = File(root, "crates/app").apply { mkdirs() }
        File(member, "Cargo.toml").writeText("[package]\nedition.workspace = true\n")
        assertEquals("2021", RustEditorSupport.edition(File(member, "src/main.rs"), root))
    }

    @Test fun cargoPackageWithoutEditionUses2015() {
        val root = temporary.newFolder()
        File(root, "Cargo.toml").writeText("[package]\nname = 'legacy'\n")
        assertEquals("2015", RustEditorSupport.edition(File(root, "src/lib.rs"), root))
    }

    @Test fun neverReadsManifestOutsideWorkspace() {
        val parent = temporary.newFolder()
        File(parent, "Cargo.toml").writeText("[package]\nedition = '2015'\n")
        val root = File(parent, "scratch").apply { mkdir() }
        assertEquals("2021", RustEditorSupport.edition(File(root, "demo.rs"), root))
    }
}
