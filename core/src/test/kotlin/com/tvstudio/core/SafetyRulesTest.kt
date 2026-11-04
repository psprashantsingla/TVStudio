package com.tvstudio.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class SafetyRulesTest {
    @Test
    fun `core Android packages are refused`() {
        // The bare "android" package has no dot, so it never passes as a package name at all.
        assertEquals(BlockReason.INVALID_NAME, SafetyRules.staticBlock("android"))
        listOf(
            "android.ext.services",
            "android.ext.shared",
            "com.android.systemui",
            "com.android.shell",
            "com.android.settings",
            "com.android.tv.settings",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
            "com.google.android.packageinstaller",
            "com.google.android.modulemetadata",
            "com.google.android.overlay.modules.ext.services",
            "com.android.providers.settings",
            "com.android.location.fused",
            "com.android.sdksandbox",
            "com.cltv.mal",
            "com.mediatek.tis",
            "com.mediatek.tv.service",
        ).forEach { assertEquals(BlockReason.CORE_ANDROID, SafetyRules.staticBlock(it), it) }
    }

    @Test
    fun `providers no TV feature uses are allowed`() {
        listOf("calendar", "contacts", "userdictionary", "blockednumber").forEach {
            assertNull(SafetyRules.staticBlock("com.android.providers.$it"), it)
        }
    }

    @Test
    fun `what the TV needs to work is refused`() {
        listOf(
            "com.android.vending",
            "com.google.android.gms",
            "com.google.android.gsf",
            "com.android.bluetooth",
            "com.google.android.leanback.ime",
            "com.google.android.tv.remote.service",
            "com.vendor.tv.btremotecontrolservice",
            "com.vendor.bluetoothpairing",
        ).forEach { assertEquals(BlockReason.ESSENTIAL, SafetyRules.staticBlock(it), it) }
    }

    @Test
    fun `stock launchers and the setup wizard are refused`() {
        SafetyRules.STOCK_LAUNCHERS.forEach { assertEquals(BlockReason.HOME, SafetyRules.staticBlock(it), it) }
        assertEquals(BlockReason.SETUP_WIZARD, SafetyRules.staticBlock(SafetyRules.SETUP_WIZARD))
    }

    @Test
    fun `ordinary bloat passes`() {
        listOf(
            "com.miui.tv.analytics",
            "com.xiaomi.mimusic2",
            "fusion.android.tv.demo",
            // "rcu" appears inside these words, and they aren't remote services.
            "com.tcl.partnercustomizer",
            "com.fireos.arcus.proxy",
        ).forEach { assertNull(SafetyRules.staticBlock(it), it) }
        assertEquals(BlockReason.ESSENTIAL, SafetyRules.staticBlock("com.vendor.rcu.service"))
    }

    @Test
    fun `shell syntax never passes as a package name`() {
        listOf(
            "com.foo; reboot",
            "com.foo && rm -rf /",
            "com.foo\nreboot",
            "com.foo\$(reboot)",
            "com.foo`reboot`",
            "com.foo|sh",
            "com.foo bar",
            "-rf",
            "nodots",
            ".com.foo",
            "com.foo.",
            "",
        ).forEach {
            assertEquals(BlockReason.INVALID_NAME, SafetyRules.staticBlock(it), it)
            assertFailsWith<IllegalArgumentException>(it) { Commands.disable(it) }
            assertFailsWith<IllegalArgumentException>(it) { Commands.enable(it) }
            assertFailsWith<IllegalArgumentException>(it) { Commands.dump(it) }
        }
    }
}
