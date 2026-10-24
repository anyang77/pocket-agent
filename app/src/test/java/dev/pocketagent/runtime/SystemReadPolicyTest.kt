package dev.pocketagent.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemReadPolicyTest {
    @Test
    fun requiresAbsolutePaths() {
        assertFalse(
            SystemReadPolicy.check("proc/version", false, SystemPathType.FILE).allowed,
        )
    }

    @Test
    fun allowsPublicSystemPathsWithoutRoot() {
        assertTrue(
            SystemReadPolicy.check("/proc/version", false, SystemPathType.FILE).allowed,
        )
        assertTrue(
            SystemReadPolicy.check("/system/app", false, SystemPathType.DIRECTORY).allowed,
        )
    }

    @Test
    fun blocksPrivateDataWithoutRoot() {
        val decision = SystemReadPolicy.check(
            "/data/data/com.example.app",
            false,
            SystemPathType.DIRECTORY,
        )
        assertFalse(decision.allowed)
        assertTrue(decision.reason.orEmpty().contains("Root"))
    }

    @Test
    fun allowsPrivateDataWithRoot() {
        assertTrue(
            SystemReadPolicy.check(
                "/data/data/com.example.app",
                true,
                SystemPathType.DIRECTORY,
            ).allowed,
        )
    }

    @Test
    fun normalizesTraversalBeforeApplyingPolicy() {
        val decision = SystemReadPolicy.check(
            "/system/app/../../data/data/com.example.app",
            false,
            SystemPathType.DIRECTORY,
        )
        assertFalse(decision.allowed)
        assertTrue(decision.normalizedPath.startsWith("/data/data/"))
    }

    @Test
    fun blocksDangerousSpecialFilesEvenWithRoot() {
        assertFalse(
            SystemReadPolicy.check("/dev/urandom", true, SystemPathType.FILE).allowed,
        )
        assertFalse(
            SystemReadPolicy.check("/proc/kcore", true, SystemPathType.FILE).allowed,
        )
        assertFalse(
            SystemReadPolicy.check("/proc/1234/mem", true, SystemPathType.FILE).allowed,
        )
    }
}
