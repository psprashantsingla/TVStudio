package com.tvstudio.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Checks the real lists that ship in the app, rather than samples. */
class BundledListsTest {
    private val lists = File("../app/src/main/assets/lists")
    private val index = ModelMatcher.parseIndex(File(lists, "index.tsv").readText())

    @Test
    fun `every model in the index has a list`() {
        assertTrue(index.size > 400, "Expected the full index, found ${index.size}")
        val missing = index.filter { !File(lists, "${it.slug}/debloat.txt").exists() }
        assertEquals(emptyList(), missing)
    }

    @Test
    fun `every bundled list parses`() {
        val bad = index.mapNotNull { entry ->
            try {
                ListParser.parse(File(lists, "${entry.slug}/debloat.txt").readText())
                null
            } catch (e: BadListException) {
                "${entry.slug}: ${e.message}"
            }
        }
        assertEquals(emptyList(), bad)
    }

    @Test
    fun `the safety rules catch what lists can't be trusted to leave out`() {
        val all = index.flatMap { ListParser.parse(File(lists, "${it.slug}/debloat.txt").readText()).entries }.map { it.pkg }.toSet()
        val blocked = all.groupBy { SafetyRules.staticBlock(it) }
        println("Distinct packages across all lists: ${all.size}")
        blocked.forEach { (reason, packages) ->
            println("  ${reason ?: "allowed"}: ${packages.size}" + if (reason == null) "" else " $packages")
        }
        assertTrue(blocked[null].orEmpty().size > all.size / 2, "The rules shouldn't block most of what lists disable")
        assertTrue(BlockReason.INVALID_NAME !in blocked)
    }
}
