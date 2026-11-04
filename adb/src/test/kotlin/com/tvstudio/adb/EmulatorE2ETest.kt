package com.tvstudio.adb

import com.tvstudio.core.BlockReason
import com.tvstudio.core.Debloater
import com.tvstudio.core.RestoreStore
import com.tvstudio.core.TvInspector
import java.net.ConnectException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * The whole engine over a real ADB connection to an Android TV emulator. CI sets TVSTUDIO_E2E_HOST;
 * elsewhere this test is skipped.
 */
class EmulatorE2ETest {
    private val host = System.getenv("TVSTUDIO_E2E_HOST").orEmpty()

    /** Harmless packages an emulator image may have. A test run only needs one of them. */
    private val candidates = listOf(
        "com.android.dreams.basic",
        "com.android.printspooler",
        "com.android.wallpaperbackup",
        "com.android.sharedstoragebackup",
    )

    @Test
    fun `identify, disable, verify and restore on an Android TV`() {
        assumeTrue(host.isNotEmpty(), "No emulator configured")
        DadbTvShell.connectWithDefaultKey(host, 5555).use { shell ->
            val inspector = TvInspector(shell)
            val tv = inspector.identify()
            println("Connected to ${tv.label}, Android ${tv.androidVersion}, SDK ${tv.sdk}")
            assertTrue(tv.isTv, "The emulator should identify as a TV")

            val before = inspector.readStates()
            println("Enabled ${before.enabled.size}, disabled ${before.disabled.size}")
            val facts = inspector.readFacts()
            println("Home owner ${facts.homeOwner}, claimers ${facts.homeClaimers}")
            assertTrue(facts.homeKnown, "The TV should say who owns Home")

            val target = candidates.first { it in before.enabled }
            println("Target package: $target")
            val debloater = Debloater(shell, RestoreStore(Files.createTempDirectory("e2e").toFile()))
            val key = tv.stableKey

            // A protected package must be refused on a real TV too.
            val plan = debloater.preflight(listOf(target, "com.android.systemui", facts.homeOwner!!), tv.sdk)
            assertEquals(listOf(target), plan.toDisable)
            assertEquals(BlockReason.CORE_ANDROID, plan.blocked["com.android.systemui"])
            assertEquals(BlockReason.HOME, plan.blocked[facts.homeOwner!!])

            val report = debloater.apply(plan, key, tv.label)
            assertEquals(listOf(target), report.disabled, "Failures: ${report.failed}")
            assertNull(report.stoppedBecause)
            assertTrue(target in inspector.disabledPackages())
            assertEquals(facts.homeOwner, inspector.currentHome(), "Home should not change")

            val restored = debloater.restore(key)
            assertTrue(restored.failed.isEmpty(), "Restore failures: ${restored.failed}")
            assertEquals(before, inspector.readStates(), "Package state should match the start exactly")
            assertFalse(target in inspector.disabledPackages())
            println("Disable and restore verified")
        }
    }

    @Test
    fun `a closed port is described helpfully`() {
        val message = DadbTvShell.describeFailure(ConnectException("Connection refused"))
        assertTrue("USB debugging" in message, message)
    }
}
