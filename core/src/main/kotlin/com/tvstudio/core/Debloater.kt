package com.tvstudio.core

import java.io.IOException

/** The TV can't be debloated safely right now. Nothing was changed. */
class DebloatRefused(message: String) : Exception(message)

/** The checked plan for one run: what will change, and why anything else won't. */
data class Preflight(
    val toDisable: List<String>,
    val alreadyOff: List<String>,
    val notInstalled: List<String>,
    val blocked: Map<String, BlockReason>,
    val homeOwner: String?,
    val warnings: List<String>,
)

data class DebloatReport(
    val disabled: List<String>,
    /** Package name to the TV's answer. The TV still lists these as enabled. */
    val failed: Map<String, String>,
    /** Sent, then the connection dropped before the result could be read. */
    val unverified: List<String>,
    val notAttempted: List<String>,
    /** Why the run ended early, or null if it finished. */
    val stoppedBecause: String?,
)

data class RestoreReport(val restored: List<String>, val failed: List<String>, val leftAlone: List<String>)

class Debloater(
    private val shell: TvShell,
    private val store: RestoreStore,
    private val sleep: (Long) -> Unit = Thread::sleep,
) {
    private val inspector = TvInspector(shell)

    /** Read-only. Decides what may be disabled, keeping anything that fails a check. */
    fun preflight(selected: Collection<String>, sdk: Int?): Preflight {
        val states = inspector.readStates()
        val facts = inspector.readFacts()
        if (!facts.homeKnown) {
            throw DebloatRefused("The TV won't say which app provides its home screen, so it isn't safe to continue. Nothing was changed.")
        }
        val toDisable = ArrayList<String>()
        val alreadyOff = ArrayList<String>()
        val notInstalled = ArrayList<String>()
        val blocked = LinkedHashMap<String, BlockReason>()
        for (pkg in selected.distinct()) {
            val reason = SafetyRules.staticBlock(pkg) ?: facts.block(pkg)
            when {
                reason != null -> blocked[pkg] = reason
                pkg in states.disabled -> alreadyOff += pkg
                pkg !in states.enabled -> notInstalled += pkg
                else -> {
                    val driver = inspector.inputDriverCheck(pkg, sdk)
                    if (driver != null) blocked[pkg] = driver else toDisable += pkg
                }
            }
        }
        return Preflight(toDisable, alreadyOff, notInstalled, blocked, facts.homeOwner, facts.warnings)
    }

    /**
     * Saves the restore record, then disables in small batches and reads the TV back after each.
     * A failed save throws before anything reaches the TV. An exit code proves nothing here, because
     * firmware can report success and change nothing.
     */
    fun apply(plan: Preflight, tvKey: String, tvLabel: String, onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): DebloatReport {
        plan.toDisable.forEach { check(SafetyRules.staticBlock(it) == null) { "Blocked package in plan: $it" } }
        if (plan.toDisable.isNotEmpty()) {
            store.saveFirst(
                tvKey,
                tvLabel,
                plan.toDisable.associateWith { PriorState.ENABLED } + plan.alreadyOff.associateWith { PriorState.DISABLED },
            )
        }

        val disabled = ArrayList<String>()
        val failed = LinkedHashMap<String, String>()
        var unverified = emptyList<String>()
        var stoppedBecause: String? = null
        var sent = 0

        for (batch in plan.toDisable.chunked(BATCH_SIZE)) {
            val answers = LinkedHashMap<String, String>()
            try {
                // One package per shell call: chained commands can silently stop after the first.
                for (pkg in batch) {
                    answers[pkg] = shell.run(Commands.disable(pkg)).allOutput.trim()
                    sent++
                    onProgress(sent, plan.toDisable.size)
                }
                val nowOff = inspector.disabledPackages()
                for (pkg in batch) {
                    if (pkg in nowOff) disabled += pkg else failed[pkg] = answers[pkg].orEmpty().ifBlank { "No answer from the TV." }
                }
                if (disabled.isEmpty()) {
                    stoppedBecause = "This TV refuses to disable apps, so TVStudio stopped instead of retrying."
                    break
                }
                val homeNow = inspector.currentHome()
                if (plan.homeOwner != null && homeNow != plan.homeOwner) {
                    stoppedBecause = "The home screen changed during the run, so TVStudio stopped. Use Undo if Home looks wrong."
                    break
                }
            } catch (e: IOException) {
                unverified = batch - disabled.toSet() - failed.keys
                stoppedBecause = "The connection to the TV dropped. Reconnect to check what changed, or use Undo."
                break
            }
        }

        val finished = disabled.toSet() + failed.keys + unverified
        return DebloatReport(disabled, failed, unverified, plan.toDisable.filter { it !in finished }, stoppedBecause)
    }

    /** Android writes package state to disk about ten seconds after a change, so wait before a reboot. */
    fun flushToDisk() {
        shell.run("sync")
        sleep(FLUSH_WAIT_MS)
    }

    fun reboot() {
        try {
            shell.run("reboot")
        } catch (_: IOException) {
            // The connection going away is what a reboot looks like.
        }
    }

    /** Packages TVStudio disabled that are enabled again, usually after a reboot or firmware update. */
    fun cameBack(tvKey: String): List<String> {
        val record = store.load(tvKey) ?: return emptyList()
        val enabled = inspector.enabledPackages()
        return record.states.filter { (pkg, prior) -> prior == PriorState.ENABLED && pkg in enabled }.keys.toList()
    }

    /** Puts back recorded states. The record is removed only once every package is confirmed back. */
    fun restore(tvKey: String): RestoreReport {
        val record = store.load(tvKey) ?: return RestoreReport(emptyList(), emptyList(), emptyList())
        val toEnable = record.states.filterValues { it == PriorState.ENABLED }.keys.toList()
        val leftAlone = record.states.filterValues { it == PriorState.DISABLED }.keys.toList()
        val stillOff = inspector.disabledPackages()
        toEnable.filter { it in stillOff }.forEach { shell.run(Commands.enable(it)) }
        val nowOff = inspector.disabledPackages()
        val failed = toEnable.filter { it in nowOff }
        if (failed.isEmpty()) store.delete(tvKey)
        return RestoreReport(toEnable - failed.toSet(), failed, leftAlone)
    }

    private companion object {
        const val BATCH_SIZE = 5
        const val FLUSH_WAIT_MS = 15_000L
    }
}
