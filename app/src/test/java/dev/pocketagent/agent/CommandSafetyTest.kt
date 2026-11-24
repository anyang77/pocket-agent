package dev.pocketagent.agent

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class CommandSafetyTest {
    private val root = File(System.getProperty("java.io.tmpdir"), "codemate-policy-workspace").canonicalFile

    @Test fun criticalCommandsRequireConfirmationEvenInAutoAndRoot() {
        listOf("rm -rf /*", "rm -rf /", "rm -rf /data/*", "su -c 'rm -rf /'",
            "busybox rm -rf /system", "/system/bin/rm -rf /sdcard/*",
            "mkfs.ext4 /dev/block/sda", "parted /dev/block/sda mklabel gpt",
            "sgdisk --zap-all /dev/block/sda", "dd if=image of=/dev/block/by-name/boot",
            "cat image > /dev/block/by-name/boot", "sm format private", "recovery --wipe_data",
            "am broadcast -a android.intent.action.MASTER_CLEAR", "cd /; rm -rf *",
            "find / -delete").forEach { command ->
            assertNotNull(command, CommandSafety.criticalReason(command, root))
            PermissionKind.values().forEach { kind ->
                val request = PermissionRequest(kind, "danger", command, requiresConfirmation = true, workspaceScoped = true)
                assertTrue(command, request.needsApproval(PermissionMode.AUTO))
                assertTrue(command, request.needsApproval(PermissionMode.ASK))
            }
        }
    }

    @Test fun normalCommandsAreNotCritical() {
        listOf("rm -rf build", "rm *.tmp", "git reset --hard", "git push", "npm install",
            "dd if=input.bin of=copy.bin", "pm list packages", "./gradlew test",
            "cat /dev/block/sda", "ls /dev/block").forEach {
            assertNull(it, CommandSafety.criticalReason(it, root))
        }
    }

    @Test fun sourceSearchOutputCommentsAndHeredocsAreNotExecutedCommands() {
        listOf(
            "rg 'mkfs|fdisk|fastboot|factory_reset' app/src",
            "echo '\$(rm -rf /)'", "echo '`rm -rf /`'",
            "grep -n 'rm -rf /' README.md",
            "echo 'mkfs.ext4 /dev/block/sda'",
            "printf '%s\\n' 'rm -rf /data/*'",
            "python3 -c 'print(\"fdisk /dev/block/sda\")'",
            "git diff -- app/src/main/java/CommandSafety.kt",
            "# rm -rf /\npwd # mkfs.ext4 /dev/block/sda",
            "cat <<'EOF' > example.txt\nrm -rf /\nmkfs.ext4 /dev/block/sda\nEOF\npwd",
            "rm \"\$TARGET\"", "rm -f build/{a,b}.tmp", "rm ~/project/build.tmp",
            "cat /dev/block/sda > backup.img", "cp /dev/block/sda backup.img",
        ).forEach { assertNull(it, CommandSafety.criticalReason(it, root)) }
    }

    @Test fun readOnlyDeviceQueriesAndNormalSystemCommandsAreNotCritical() {
        listOf("fdisk -l /dev/block/sda", "sfdisk --dump /dev/block/sda",
            "sgdisk --print /dev/block/sda", "parted /dev/block/sda print",
            "wipefs /dev/block/sda", "wipefs -n /dev/block/sda", "mkfs.ext4 --help",
            "command -v fdisk", "find / -name rm -exec echo {} +",
            "fastboot devices", "fastboot getvar all", "fastboot reboot", "adb devices",
            "reboot", "settings put system screen_brightness 100", "pm clear com.example",
            "rm -rf build; ./gradlew assembleDebug").forEach {
            assertNull(it, CommandSafety.criticalReason(it, root))
            val request = PermissionRequest(PermissionKind.DESTRUCTIVE, "command", it,
                requiresConfirmation = CommandSafety.criticalReason(it, root) != null)
            assertEquals(it, AuthorizationAction.ALLOW, AgentAuthorizationPolicy.assess(request,
                AgentPermissions(mode = PermissionMode.AUTO, allowNetwork = true), true).action)
        }
    }

    @Test fun actualDestructiveCommandsStillMatchAfterQuotingAndWrappers() {
        listOf("r'm' -rf /", "env LC_ALL=C busybox rm -rf /data/*",
            "sh -c 'rm -rf /'", "su -c 'sh -c \"rm -rf /\"'",
            "echo \"\$(rm -rf /)\"", "echo `rm -rf /`",
            "echo hello; mkfs.ext4 /dev/block/sda", "pwd # comment\nrm -rf /",
            "cat <<EOF\nsample text\nEOF\nrm -rf /",
            "cd /data && rm -rf *", "rm -rf /data/../system/*",
            "tee /dev/block/by-name/boot < image", "cp image /dev/block/by-name/boot",
            "sgdisk --print --zap-all /dev/block/sda", "sgdisk --print -G /dev/block/sda", "wipefs -a /dev/block/sda",
            "fastboot flash boot boot.img", "fastboot -w", "fastboot flashing unlock",
            "parted /dev/block/sda mklabel gpt print", "fdisk /dev/block/sda",
            "cmd activity broadcast -a android.intent.action.FACTORY_RESET").forEach {
            assertNotNull(it, CommandSafety.criticalReason(it, root))
        }
    }

    @Test fun workspaceOperationsSkipAskApproval() {
        listOf("pwd", "ls -la", "rm -rf build", "git diff", "./gradlew test", "npm run build",
            "mkdir -p output; cp src/a output/a", "cat src/a | grep hello > output/a").forEach {
            assertTrue(it, CommandSafety.isWithinWorkspace(it, root, root, false))
        }
        val request = PermissionRequest(PermissionKind.WRITE_FILES, "edit", "file", workspaceScoped = true)
        assertFalse(request.needsApproval(PermissionMode.ASK))
        assertTrue(request.copy(workspaceScoped = false).needsApproval(PermissionMode.ASK))
        assertFalse(request.copy(workspaceScoped = false).needsApproval(PermissionMode.AUTO))
    }

    @Test fun externalPathsAndOpaqueExpansionAreNotWorkspaceScoped() {
        listOf("cat ../secret", "touch /data/secret", "cp src /sdcard/other", "echo text > /tmp/out",
            "dd if=local of=/dev/block/sda", "cat --file=/outside", "cd ..; rm a",
            "rm \$TARGET", "python -c 'open(\"/outside\", \"w\")'", "sh -c 'rm /outside'",
            "pm clear com.example").forEach {
            assertFalse(it, CommandSafety.isWithinWorkspace(it, root, root, false))
        }
        assertFalse(CommandSafety.isWithinWorkspace("ls", root.parentFile!!, root, false))
        assertFalse(CommandSafety.isWithinWorkspace("ls", root, root, true))
    }

    @Test fun canonicalPathsRejectSiblingPrefixAndSymlinkEscapes() {
        val dir = Files.createTempDirectory("scope").toFile()
        try {
            val workspace = File(dir, "project").apply { mkdir() }
            val outside = File(dir, "project-other").apply { mkdir() }
            Files.createSymbolicLink(File(workspace, "link").toPath(), outside.toPath())
            assertFalse(CommandSafety.isWithinWorkspace("cat link/secret", workspace, workspace, false))
            assertFalse(CommandSafety.isWithinWorkspace("rm link", workspace, workspace, false))
            assertFalse(CommandSafety.isWithinWorkspace("ls", outside, workspace, false))
        } finally { dir.deleteRecursively() }
    }
}
