package com.tvstudio.core

import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelListsTest {
    private val sample = """
        # Xiaomi Mi TV 4A, firmware 123
        # Undo: use restore

        # Xiaomi telemetry
        com.miui.tv.analytics
        com.xiaomi.statistic

        # Background services
        com.xiaomi.mitv.updateservice          # re-enable to check for updates
        # com.commented.out
    """.trimIndent()

    @Test
    fun `reads headers, sections and inline notes`() {
        val list = ListParser.parse(sample)
        assertEquals(listOf("Xiaomi Mi TV 4A, firmware 123", "Undo: use restore"), list.headerNotes)
        assertEquals(listOf("com.miui.tv.analytics", "com.xiaomi.statistic", "com.xiaomi.mitv.updateservice"), list.entries.map { it.pkg })
        assertEquals("Xiaomi telemetry", list.entries[0].section)
        assertEquals("re-enable to check for updates", list.entries[2].note)
    }

    @Test
    fun `a Notepad file with a BOM and CRLF reads the same`() {
        val windows = "﻿" + sample.replace("\n", "\r\n")
        assertEquals(ListParser.parse(sample), ListParser.parse(windows))
    }

    @Test
    fun `one bad entry refuses the whole list`() {
        assertFailsWith<BadListException> { ListParser.parse("com.good.one\ncom.bad;reboot\n") }
        assertFailsWith<BadListException> { ListParser.parse("\$(reboot)\n") }
    }

    private val entries = listOf(
        ModelEntry("xiaomi-mi-tv-4a", "Xiaomi Mi TV 4A (MiTV-MOOQ1)", "Xiaomi", "Android TV 10", ""),
        ModelEntry("xiaomi-other", "Xiaomi Mi TV P1", "Xiaomi", "Android TV 11", ""),
        ModelEntry("sony-x90", "Sony KD-55X90J", "Sony", "Google TV", ""),
    )

    private fun identity(brand: String, model: String, device: String = "") =
        TvIdentity(brand, brand, model, device, "11", 30, "fw", "s", isTv = true, adbIsOpenToAnyone = false)

    @Test
    fun `the TV's model finds its list, with the same brand offered after it`() {
        val matches = ModelMatcher.match(identity("Xiaomi", "MiTV-MOOQ1"), entries)
        assertEquals("xiaomi-mi-tv-4a", matches[0].entry.slug)
        assertEquals(MatchStrength.MODEL, matches[0].strength)
        assertEquals(MatchStrength.BRAND_ONLY, matches[1].strength)
        assertEquals(2, matches.size)
    }

    @Test
    fun `a short generic model doesn't count as a match`() {
        assertTrue(ModelMatcher.match(identity("Acme", "TV"), entries).isEmpty())
    }

    @Test
    fun `search needs every word`() {
        assertEquals(listOf("sony-x90"), ModelMatcher.search("sony 55x90", entries).map { it.slug })
        assertTrue(ModelMatcher.search("sony p1", entries).isEmpty())
        assertTrue(ModelMatcher.search("   ", entries).isEmpty())
    }

    @Test
    fun `the index parses and skips short rows`() {
        val text = "slug\ttv\tbrand\tos\tnotes\na-b\tA B\tA\tAndroid 11\tnote\nbroken\trow\n"
        assertEquals(listOf(ModelEntry("a-b", "A B", "A", "Android 11", "note")), ModelMatcher.parseIndex(text))
    }

    private val facts = TvFacts("com.vendor.home", setOf("com.vendor.home"), emptySet(), setOf("com.android.tv.settings"), emptySet())

    @Test
    fun `apps people may use are never preselected`() {
        listOf(
            "com.netflix.ninja",
            "com.google.android.youtube.tv",
            "com.google.android.katniss",
            "com.google.android.apps.mediashell",
            "com.xiaomi.mitv.updateservice",
            "com.amazon.amazonvideo.livingroom.avod",
        ).forEach { pkg ->
            val list = ParsedList(emptyList(), listOf(ListEntry(pkg, null, null)))
            val item = ReviewBuilder.fromList(list, PackageStates(setOf(pkg), emptySet()), facts).single()
            assertFalse(item.preselected, pkg)
            assertEquals(Category.APPS_YOU_MAY_USE, item.category, pkg)
        }
    }

    @Test
    fun `a list drops what isn't installed, and locks what must stay`() {
        val list = ParsedList(
            emptyList(),
            listOf(
                ListEntry("com.vendor.junk", "Junk", null),
                ListEntry("com.vendor.absent", null, null),
                ListEntry("com.vendor.home", null, null),
                ListEntry("com.vendor.junk", null, null),
            ),
        )
        val items = ReviewBuilder.fromList(list, PackageStates(setOf("com.vendor.junk", "com.vendor.home"), emptySet()), facts)
        assertEquals(2, items.size)
        val junk = items.first { it.pkg == "com.vendor.junk" }
        assertTrue(junk.preselected)
        assertNull(junk.lockedBecause)
        assertEquals("Junk", junk.note)
        val home = items.first { it.pkg == "com.vendor.home" }
        assertFalse(home.preselected)
        assertEquals(BlockReason.HOME, home.lockedBecause)
    }

    @Test
    fun `a scan hides Android's own packages and ticks only clear names`() {
        val states = PackageStates(
            setOf("com.android.printspooler", "com.google.android.feedback", "com.vendor.analytics", "com.vendor.adservice", "com.vendor.mystery"),
            emptySet(),
        )
        val items = ReviewBuilder.fromScan(states, facts).associateBy { it.pkg }
        assertEquals(setOf("com.vendor.analytics", "com.vendor.adservice", "com.vendor.mystery"), items.keys)
        assertTrue(items.getValue("com.vendor.analytics").preselected)
        assertTrue(items.getValue("com.vendor.adservice").preselected)
        assertFalse(items.getValue("com.vendor.mystery").preselected)
    }

    @Test
    fun `addresses are validated`() {
        assertEquals(TvAddress("192.168.1.50", 5555), TvAddress.parse(" 192.168.1.50 "))
        assertEquals(TvAddress("10.0.0.2", 5877), TvAddress.parse("10.0.0.2:5877"))
        listOf("", "tv", "300.1.1.1", "1.2.3", "1.2.3.4:0", "1.2.3.4:99999", "1.2.3.4:abc", "1.2.3.4; reboot").forEach {
            assertNull(TvAddress.parse(it), it)
        }
    }

    @Test
    fun `neighbours are the rest of the network`() {
        val hosts = Network.neighbours("192.168.1.50")
        assertEquals(253, hosts.size)
        assertFalse("192.168.1.50" in hosts)
        assertTrue("192.168.1.1" in hosts)
    }

    @Test
    fun `only a refused connection counts as closed`() {
        val server = ServerSocket(0)
        val port = server.localPort
        assertEquals(PortStatus.OPEN, Network.probe("127.0.0.1", port, 1000))
        server.close()
        assertEquals(PortStatus.CLOSED, Network.probe("127.0.0.1", port, 1000))
        // TEST-NET-1 is reserved and never answers, so a timeout or unreachable must not read as closed.
        assertNotEquals(PortStatus.CLOSED, Network.probe("192.0.2.1", 5555, 200))
    }
}
