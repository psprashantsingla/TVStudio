package com.tvstudio.core

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DebloaterTest {
    private val key = "brand|model|device|serial"
    private val directory: File = Files.createTempDirectory("tvstudio-test").toFile()
    private val store = RestoreStore(directory)
    private val sleeps = ArrayList<Long>()

    private fun debloater(tv: FakeTv) = Debloater(tv, store) { sleeps += it }

    private fun bloat(count: Int) = (1..count).map { "com.vendor.bloat$it" }.toSet()

    private fun tvWith(vararg extra: String) = FakeTv(
        enabled = bloat(3) + extra + setOf("com.google.android.tvlauncher", "com.android.settings"),
    )

    private fun run(tv: FakeTv, selected: Collection<String>): DebloatReport {
        val d = debloater(tv)
        return d.apply(d.preflight(selected, 30), key, "Test TV")
    }

    @Test
    fun `disables the selection and reads it back`() {
        val tv = tvWith()
        val report = run(tv, bloat(3))
        assertEquals(bloat(3).toList(), report.disabled)
        assertTrue(tv.disabled.containsAll(bloat(3)))
        assertNull(report.stoppedBecause)
    }

    @Test
    fun `sends one package per shell call`() {
        val tv = tvWith()
        run(tv, bloat(3))
        val disables = tv.commands.filter { it.startsWith("pm disable-user") }
        assertEquals(3, disables.size)
        disables.forEach { assertFalse(";" in it || "&&" in it, it) }
    }

    @Test
    fun `the restore record exists before the first change reaches the TV`() {
        var recordWhenFirstDisabled: RestoreRecord? = null
        val tv = tvWith()
        tv.onDisable = { if (recordWhenFirstDisabled == null) recordWhenFirstDisabled = store.load(key) }
        run(tv, bloat(3))
        assertEquals(bloat(3), assertNotNull(recordWhenFirstDisabled).states.keys)
    }

    @Test
    fun `a failed save stops the run with the TV untouched`() {
        val blocker = File(directory, "not-a-directory").apply { writeText("x") }
        val tv = tvWith()
        val d = Debloater(tv, RestoreStore(blocker))
        val plan = d.preflight(bloat(3), 30)
        assertFailsWith<Exception> { d.apply(plan, key, "Test TV") }
        assertTrue(tv.commands.none { it.startsWith("pm disable-user") })
    }

    @Test
    fun `a success answer with no change is reported as failed`() {
        val tv = tvWith()
        tv.refuses = setOf("com.vendor.bloat2")
        val report = run(tv, bloat(3))
        assertEquals(listOf("com.vendor.bloat1", "com.vendor.bloat3"), report.disabled)
        assertEquals(setOf("com.vendor.bloat2"), report.failed.keys)
        assertTrue("disabled-user" in report.failed.getValue("com.vendor.bloat2"))
    }

    @Test
    fun `a TV that refuses everything stops after the first batch`() {
        val many = bloat(12)
        val tv = FakeTv(enabled = many + "com.google.android.tvlauncher")
        tv.ignoresDisable = true
        val report = run(tv, many)
        assertEquals(5, tv.commands.count { it.startsWith("pm disable-user") })
        assertEquals(emptyList(), report.disabled)
        assertEquals(5, report.failed.size)
        assertEquals(7, report.notAttempted.size)
        assertNotNull(report.stoppedBecause)
    }

    @Test
    fun `protected packages are refused even when selected`() {
        val tv = tvWith("com.android.systemui", "com.android.vending")
        val selected = listOf("com.android.systemui", "com.android.vending", "com.google.android.tvlauncher", "com.vendor.bloat1")
        val plan = debloater(tv).preflight(selected, 30)
        assertEquals(listOf("com.vendor.bloat1"), plan.toDisable)
        assertEquals(BlockReason.CORE_ANDROID, plan.blocked["com.android.systemui"])
        assertEquals(BlockReason.ESSENTIAL, plan.blocked["com.android.vending"])
        assertEquals(BlockReason.HOME, plan.blocked["com.google.android.tvlauncher"])
    }

    @Test
    fun `a blocked package smuggled into a plan is refused before anything changes`() {
        val tv = tvWith("com.android.systemui")
        val plan = Preflight(listOf("com.vendor.bloat1", "com.android.systemui"), emptyList(), emptyList(), emptyMap(), null, emptyList())
        assertFailsWith<IllegalStateException> { debloater(tv).apply(plan, key, "Test TV") }
        assertTrue(tv.commands.none { it.startsWith("pm disable-user") })
    }

    @Test
    fun `whatever owns Home on this TV is kept, whatever its name`() {
        val tv = FakeTv(enabled = setOf("com.vendor.home", "com.vendor.otherhome", "com.vendor.bloat1"), home = "com.vendor.home")
        tv.homeClaimers = listOf("com.vendor.home", "com.vendor.otherhome")
        val plan = debloater(tv).preflight(tv.enabled.toList(), 30)
        assertEquals(listOf("com.vendor.bloat1"), plan.toDisable)
        assertEquals(BlockReason.HOME, plan.blocked["com.vendor.home"])
        assertEquals(BlockReason.HOME, plan.blocked["com.vendor.otherhome"])
    }

    @Test
    fun `refuses to run when the TV can't say who owns Home`() {
        val tv = tvWith()
        tv.home = null
        tv.homeClaimers = emptyList()
        assertFailsWith<DebloatRefused> { debloater(tv).preflight(bloat(3), 30) }
        assertTrue(tv.commands.none { it.startsWith("pm disable-user") })
    }

    @Test
    fun `the home screen chooser counts as known when apps claim Home`() {
        val tv = tvWith()
        tv.home = null
        tv.homeClaimers = listOf("com.google.android.tvlauncher")
        assertEquals(bloat(3).toList(), debloater(tv).preflight(bloat(3), 30).toDisable)
    }

    @Test
    fun `stops if Home changes during the run`() {
        val many = bloat(12)
        val tv = FakeTv(enabled = many + "com.vendor.home", home = "com.vendor.home")
        tv.onDisable = { tv.home = "com.google.android.tungsten.setupwraith" }
        val report = run(tv, many)
        assertEquals(5, report.disabled.size)
        assertEquals(7, report.notAttempted.size)
        assertNotNull(report.stoppedBecause)
    }

    @Test
    fun `the apps behind inputs, Settings and the keyboard are kept`() {
        val tv = FakeTv(
            enabled = setOf("com.vendor.inputs", "com.vendor.settings", "com.vendor.kbd", "com.vendor.bloat1", "com.vendor.home"),
            home = "com.vendor.home",
            inputApps = listOf("com.vendor.inputs"),
            settingsApps = listOf("com.vendor.settings"),
            keyboard = "com.vendor.kbd/.Ime",
        )
        val plan = debloater(tv).preflight(tv.enabled.toList(), 30)
        assertEquals(BlockReason.INPUTS, plan.blocked["com.vendor.inputs"])
        assertEquals(BlockReason.SETTINGS, plan.blocked["com.vendor.settings"])
        assertEquals(BlockReason.KEYBOARD, plan.blocked["com.vendor.kbd"])
        assertEquals(listOf("com.vendor.bloat1"), plan.toDisable)
    }

    @Test
    fun `input drivers and unreadable dumps are kept`() {
        val tv = tvWith("com.vendor.driver", "com.vendor.silent", "com.vendor.hung")
        tv.dumps["com.vendor.driver"] = "Package [com.vendor.driver]\n requested permissions:\n android.permission.TV_INPUT_HARDWARE\n"
        tv.dumps["com.vendor.silent"] = "nothing useful"
        tv.dumps["com.vendor.hung"] = "Service dumps disabled due to hung system process."
        val plan = debloater(tv).preflight(listOf("com.vendor.driver", "com.vendor.silent", "com.vendor.hung", "com.vendor.bloat1"), 30)
        assertEquals(BlockReason.INPUT_DRIVER, plan.blocked["com.vendor.driver"])
        assertEquals(BlockReason.UNCHECKED, plan.blocked["com.vendor.silent"])
        assertEquals(BlockReason.UNCHECKED, plan.blocked["com.vendor.hung"])
        assertEquals(listOf("com.vendor.bloat1"), plan.toDisable)
    }

    @Test
    fun `a readable dump with no permissions passes only from Android 6`() {
        val dump = "Package [com.vendor.bloat1] (abc):\n  versionName=1\n"
        assertNull(TvInspector.inputDriverBlock(dump, "com.vendor.bloat1", 23))
        assertEquals(BlockReason.UNCHECKED, TvInspector.inputDriverBlock(dump, "com.vendor.bloat1", 22))
        assertEquals(BlockReason.UNCHECKED, TvInspector.inputDriverBlock(dump, "com.vendor.bloat1", null))
    }

    @Test
    fun `absent and already disabled packages aren't touched`() {
        val tv = FakeTv(enabled = setOf("com.vendor.bloat1", "com.google.android.tvlauncher"), disabled = setOf("com.vendor.off"))
        val plan = debloater(tv).preflight(listOf("com.vendor.bloat1", "com.vendor.off", "com.vendor.absent"), 30)
        assertEquals(listOf("com.vendor.bloat1"), plan.toDisable)
        assertEquals(listOf("com.vendor.off"), plan.alreadyOff)
        assertEquals(listOf("com.vendor.absent"), plan.notInstalled)
    }

    @Test
    fun `a lost connection is reported honestly and restore still works`() {
        val many = bloat(7)
        val tv = FakeTv(enabled = many + "com.google.android.tvlauncher")
        tv.dropOnDisableNumber = 7
        val report = run(tv, many)
        assertEquals(5, report.disabled.size)
        assertEquals(listOf("com.vendor.bloat6", "com.vendor.bloat7"), report.unverified)
        assertNotNull(report.stoppedBecause)

        tv.dropOnDisableNumber = null
        val restored = debloater(tv).restore(key)
        assertTrue(restored.failed.isEmpty())
        assertTrue(tv.enabled.containsAll(many))
    }

    @Test
    fun `restore returns the exact recorded state`() {
        val tv = FakeTv(
            enabled = setOf("com.vendor.bloat1", "com.vendor.bloat2", "com.google.android.tvlauncher"),
            disabled = setOf("com.vendor.owneroff"),
        )
        val before = tv.enabled.toSet() to tv.disabled.toSet()
        run(tv, listOf("com.vendor.bloat1", "com.vendor.bloat2", "com.vendor.owneroff"))
        val report = debloater(tv).restore(key)
        assertEquals(before, tv.enabled.toSet() to tv.disabled.toSet())
        assertEquals(listOf("com.vendor.owneroff"), report.leftAlone)
        assertNull(store.load(key), "A finished restore removes its record")
    }

    @Test
    fun `the first record is kept on a retry`() {
        val tv = tvWith()
        run(tv, listOf("com.vendor.bloat1"))
        // The second run sees bloat1 as already off. The record must still say it started enabled.
        run(tv, listOf("com.vendor.bloat1", "com.vendor.bloat2"))
        val record = assertNotNull(store.load(key))
        assertEquals(PriorState.ENABLED, record.states["com.vendor.bloat1"])
        assertEquals(PriorState.ENABLED, record.states["com.vendor.bloat2"])
        debloater(tv).restore(key)
        assertTrue(tv.disabled.isEmpty())
    }

    @Test
    fun `a failed restore keeps its record so it can be retried`() {
        val tv = tvWith()
        run(tv, bloat(2))
        val stuck = object : TvShell by tv {
            override fun run(command: String): ShellResult =
                if (command.startsWith("pm enable")) ShellResult("Error: refused\n", exitCode = 1) else tv.run(command)
        }
        val report = Debloater(stuck, store).restore(key)
        assertEquals(bloat(2).toList(), report.failed)
        assertNotNull(store.load(key))
    }

    @Test
    fun `a damaged record stops the run rather than being replaced`() {
        val tv = tvWith()
        run(tv, listOf("com.vendor.bloat1"))
        directory.listFiles()!!.single().writeText("garbage")
        val d = debloater(tv)
        val plan = d.preflight(listOf("com.vendor.bloat2"), 30)
        assertFailsWith<CorruptRecordException> { d.apply(plan, key, "Test TV") }
        assertFalse(tv.commands.contains("pm disable-user --user 0 com.vendor.bloat2"))
    }

    @Test
    fun `cameBack finds what returned after a reboot`() {
        val tv = tvWith()
        run(tv, bloat(2))
        tv.disabled.remove("com.vendor.bloat1")
        tv.enabled.add("com.vendor.bloat1")
        assertEquals(listOf("com.vendor.bloat1"), debloater(tv).cameBack(key))
    }

    @Test
    fun `waits for Android to write state before a reboot`() {
        val tv = tvWith()
        debloater(tv).flushToDisk()
        assertTrue("sync" in tv.commands)
        assertEquals(listOf(15_000L), sleeps)
    }

    @Test
    fun `an empty package list is treated as a failed read`() {
        val tv = FakeTv(enabled = emptySet())
        assertFailsWith<DebloatRefused> { debloater(tv).preflight(listOf("com.vendor.bloat1"), 30) }
    }
}
