package com.tvstudio.core

/** One package in a model list, with the comments around it. */
data class ListEntry(val pkg: String, val section: String?, val note: String?)

data class ParsedList(val headerNotes: List<String>, val entries: List<ListEntry>)

class BadListException(message: String) : Exception(message)

object ListParser {
    /**
     * Reads a debloat list: one package per line, `#` comments, and a Windows BOM or CRLF allowed.
     * The whole list is refused if any entry isn't a bare package name.
     */
    fun parse(text: String): ParsedList {
        val header = ArrayList<String>()
        val entries = ArrayList<ListEntry>()
        var section: String? = null
        var inHeader = true
        for (raw in text.removePrefix("﻿").lineSequence()) {
            val line = raw.trim()
            when {
                line.isEmpty() -> {
                    if (header.isNotEmpty()) inHeader = false
                }
                line.startsWith("#") -> {
                    val comment = line.removePrefix("#").trim()
                    if (inHeader) {
                        if (comment.isNotEmpty()) header += comment
                    } else if (comment.isNotEmpty() && !SafetyRules.isValidPackage(comment.substringBefore(' '))) {
                        // A commented-out package isn't a section heading.
                        section = comment
                    }
                }
                else -> {
                    inHeader = false
                    val pkg = line.substringBefore('#').trim().substringBefore(' ').substringBefore('\t')
                    if (!SafetyRules.isValidPackage(pkg)) throw BadListException("This list has an entry that isn't a package name: ${pkg.take(80)}")
                    val note = line.substringAfter('#', "").trim().ifEmpty { null }
                    entries += ListEntry(pkg, section, note)
                }
            }
        }
        return ParsedList(header, entries)
    }
}

/** One row of the bundled model index. */
data class ModelEntry(val slug: String, val tv: String, val brand: String, val os: String, val notes: String)

enum class MatchStrength { MODEL, BRAND_ONLY }

data class ModelMatch(val entry: ModelEntry, val strength: MatchStrength)

object ModelMatcher {
    /** Reads the tab-separated index written by tools/sync_lists.py. */
    fun parseIndex(text: String): List<ModelEntry> = text.lineSequence()
        .drop(1)
        .filter { it.isNotBlank() }
        .mapNotNull { line ->
            val parts = line.split('\t')
            if (parts.size < 5) null else ModelEntry(parts[0], parts[1], parts[2], parts[3], parts[4])
        }
        .toList()

    /**
     * Candidates for this TV, best first. A model match is still only a suggestion, because lists
     * are tied to firmware. The person always confirms it.
     */
    fun match(tv: TvIdentity, entries: List<ModelEntry>): List<ModelMatch> {
        val model = normalize(tv.model)
        val device = normalize(tv.device)
        val brands = setOf(normalize(tv.brand), normalize(tv.manufacturer)).filter { it.isNotEmpty() }
        val byModel = entries.filter { entry ->
            val haystack = normalize(entry.tv) + " " + normalize(entry.slug)
            (model.length >= MIN_ID && model in haystack) || (device.length >= MIN_ID && device in haystack)
        }
        val byBrand = entries.filter { it !in byModel && normalize(it.brand) in brands }
        return byModel.map { ModelMatch(it, MatchStrength.MODEL) } + byBrand.map { ModelMatch(it, MatchStrength.BRAND_ONLY) }
    }

    /** Every word in the query must appear in the model's name or brand. */
    fun search(query: String, entries: List<ModelEntry>): List<ModelEntry> {
        val words = query.split(Regex("""\s+""")).map(::normalize).filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        return entries.filter { entry ->
            val haystack = normalize(entry.tv) + normalize(entry.brand) + normalize(entry.slug)
            words.all { it in haystack }
        }
    }

    private fun normalize(text: String) = text.lowercase().filter { it.isLetterOrDigit() }

    private const val MIN_ID = 4
}
