package com.tvstudio.core

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** What a package's state was before TVStudio first touched it. */
enum class PriorState { ENABLED, DISABLED }

data class RestoreRecord(val tvLabel: String, val states: Map<String, PriorState>)

class CorruptRecordException(message: String) : IOException(message)

/**
 * One record per TV, written before the first command reaches it.
 *
 * The first state seen for a package always wins. A retry after a half-finished run must not record
 * the half-debloated TV as the original.
 */
class RestoreStore(private val directory: File) {

    fun load(tvKey: String): RestoreRecord? {
        val file = fileFor(tvKey)
        if (!file.exists()) return null
        val lines = file.readLines()
        if (lines.firstOrNull() != HEADER) throw CorruptRecordException("The restore record for this TV can't be read.")
        val label = lines.getOrNull(1)?.removePrefix("tv=").orEmpty()
        val states = LinkedHashMap<String, PriorState>()
        for (line in lines.drop(2)) {
            val parts = line.split('\t')
            val state = parts.getOrNull(1)?.let { value -> PriorState.entries.firstOrNull { it.name == value } }
            if (parts.size != 2 || state == null || !SafetyRules.isValidPackage(parts[0])) {
                throw CorruptRecordException("The restore record for this TV can't be read.")
            }
            states[parts[0]] = state
        }
        return RestoreRecord(label, states)
    }

    /** Adds only packages the record doesn't already hold, and returns the merged record. */
    fun saveFirst(tvKey: String, tvLabel: String, states: Map<String, PriorState>): RestoreRecord {
        val existing = load(tvKey)
        val merged = LinkedHashMap(existing?.states.orEmpty())
        for ((pkg, state) in states) {
            SafetyRules.requireValidPackage(pkg)
            merged.putIfAbsent(pkg, state)
        }
        val record = RestoreRecord(existing?.tvLabel ?: tvLabel.replace(Regex("""[\r\n\t]"""), " "), merged)
        write(fileFor(tvKey), record)
        return record
    }

    fun delete(tvKey: String) {
        fileFor(tvKey).delete()
    }

    private fun write(file: File, record: RestoreRecord) {
        directory.mkdirs()
        val temp = File(directory, "${file.name}.tmp")
        temp.writeText(
            buildString {
                appendLine(HEADER)
                appendLine("tv=${record.tvLabel}")
                record.states.forEach { (pkg, state) -> appendLine("$pkg\t${state.name}") }
            },
        )
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    private fun fileFor(tvKey: String): File {
        val hash = MessageDigest.getInstance("SHA-256").digest(tvKey.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(directory, "$hash.record")
    }

    private companion object {
        const val HEADER = "tvstudio-restore 1"
    }
}
