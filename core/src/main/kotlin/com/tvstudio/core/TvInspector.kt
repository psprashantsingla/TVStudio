package com.tvstudio.core

/** What the TV says about itself. */
data class TvIdentity(
    val brand: String,
    val manufacturer: String,
    val model: String,
    val device: String,
    val androidVersion: String,
    val sdk: Int?,
    val firmware: String,
    val serial: String,
    val isTv: Boolean,
    val adbIsOpenToAnyone: Boolean,
) {
    val label: String
        get() = listOf(brand, model).filter { it.isNotBlank() }.joinToString(" ").ifBlank { "Android TV" }

    /** Names this TV's restore record. It stays on the phone. */
    val stableKey: String
        get() = listOf(brand, model, device, serial.ifBlank { firmware }).joinToString("|")
}

data class PackageStates(val enabled: Set<String>, val disabled: Set<String>) {
    val installed: Set<String> get() = enabled + disabled
}

/** What must stay enabled on this particular TV, read from the TV rather than guessed from names. */
data class TvFacts(
    val homeOwner: String?,
    val homeClaimers: Set<String>,
    val inputApps: Set<String>,
    val settingsApps: Set<String>,
    val keyboards: Set<String>,
) {
    /** If nothing can say who owns Home, the Home guard can't work. */
    val homeKnown: Boolean get() = homeOwner != null || homeClaimers.isNotEmpty()

    val warnings: List<String>
        get() = if (settingsApps.isEmpty()) {
            listOf(
                "The TV didn't say which apps open its inputs and Settings. " +
                    "Afterwards, test an HDMI input and Settings before you put the remote down.",
            )
        } else {
            emptyList()
        }

    fun block(pkg: String): BlockReason? = when (pkg) {
        homeOwner, in homeClaimers -> BlockReason.HOME
        in inputApps -> BlockReason.INPUTS
        in settingsApps -> BlockReason.SETTINGS
        in keyboards -> BlockReason.KEYBOARD
        else -> null
    }
}

/** Read-only questions for the TV. Nothing here changes anything. */
class TvInspector(private val shell: TvShell) {

    fun identify(): TvIdentity {
        val secure = prop("ro.adb.secure")
        val features = shell.run("pm list features").stdout
        return TvIdentity(
            brand = prop("ro.product.brand"),
            manufacturer = prop("ro.product.manufacturer"),
            model = prop("ro.product.model"),
            device = prop("ro.product.device"),
            androidVersion = prop("ro.build.version.release"),
            sdk = prop("ro.build.version.sdk").toIntOrNull(),
            firmware = prop("ro.build.display.id"),
            serial = prop("ro.serialno"),
            isTv = "android.software.leanback" in features || "android.hardware.type.television" in features,
            adbIsOpenToAnyone = secure == "0" || secure.isEmpty(),
        )
    }

    fun readStates(): PackageStates {
        val enabled = listPackages("-e")
        if (enabled.isEmpty()) throw DebloatRefused("The TV didn't return a list of its apps, so nothing was changed.")
        return PackageStates(enabled, listPackages("-d"))
    }

    fun disabledPackages(): Set<String> = listPackages("-d")

    fun enabledPackages(): Set<String> = listPackages("-e")

    fun readFacts(): TvFacts = TvFacts(
        homeOwner = currentHome(),
        homeClaimers = components("$QUERY -a android.intent.action.MAIN -c android.intent.category.HOME"),
        inputApps = components("$QUERY -a android.intent.action.VIEW -t vnd.android.cursor.item/channel"),
        settingsApps = components("$QUERY -a android.settings.SETTINGS"),
        keyboards = keyboards(),
    )

    /** The package that answers Home, or null if the TV can't say or shows the chooser. */
    fun currentHome(): String? {
        val answer = shell.run("cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME")
        val line = answer.stdout.lineSequence().map { it.trim() }.lastOrNull { it.isNotEmpty() }
        val owner = line?.substringBefore('/')?.takeIf { '/' in line && SafetyRules.isValidPackage(it) }
        if (owner != null) return owner
        // "android" alone is the chooser: the TV answered, and Home just has no default.
        if (line?.startsWith("android/") == true) return null
        val dump = shell.run("dumpsys activity").stdout
        return HOME_PROCESS.find(dump)?.groupValues?.get(1)?.takeIf(SafetyRules::isValidPackage)
    }

    /** Null means the package isn't ruled out. See [inputDriverBlock] for how the dump is read. */
    fun inputDriverCheck(pkg: String, sdk: Int?): BlockReason? = inputDriverBlock(shell.run(Commands.dump(pkg)).allOutput, pkg, sdk)

    private fun prop(name: String): String = shell.run("getprop $name").stdout.trim()

    private fun listPackages(flag: String): Set<String> {
        val result = shell.run("pm list packages $flag --user 0")
        if (result.exitCode != 0) throw DebloatRefused("The TV refused to list its apps: ${result.allOutput.trim().take(200)}")
        return result.stdout.lineSequence()
            .map { it.trim().removePrefix("package:") }
            .filter(SafetyRules::isValidPackage)
            .toSet()
    }

    private fun components(command: String): Set<String> =
        shell.run("$command 2>/dev/null").stdout.lineSequence()
            .mapNotNull { COMPONENT.matchEntire(it.trim())?.groupValues?.get(1) }
            .toSet()

    private fun keyboards(): Set<String> {
        val active = shell.run("settings get secure default_input_method").stdout.trim()
        val enabled = shell.run("settings get secure enabled_input_methods").stdout.trim()
        return (enabled.split(':') + active)
            .map { it.substringBefore('/').substringBefore(';').trim() }
            .filter(SafetyRules::isValidPackage)
            .toSet()
    }

    companion object {
        private const val QUERY = "cmd package query-activities --brief"
        private val COMPONENT = Regex("""([A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z0-9_]+)+)/\S+""")
        private val HOME_PROCESS = Regex("""mHomeProcess:.*[: ]([A-Za-z0-9_.]+)/[A-Za-z0-9_.]+""")

        /**
         * Packages asking for TV_INPUT_HARDWARE are usually the layer under HDMI, AV and the tuner.
         * Asking is not proof, so this can keep an app that was safe to disable. That trade is on
         * purpose: a false match keeps one app, a miss can cost someone their inputs.
         * An unreadable answer also keeps the package, because a check that didn't run hasn't passed.
         */
        internal fun inputDriverBlock(dump: String, pkg: String, sdk: Int?): BlockReason? = when {
            "Service dumps disabled due to hung" in dump -> BlockReason.UNCHECKED
            "android.permission.TV_INPUT_HARDWARE" in dump -> BlockReason.INPUT_DRIVER
            "requested permissions:" in dump -> null
            // From Android 6.0 a readable dump with no such block is a package that asks for nothing.
            "Package [$pkg]" in dump && sdk != null && sdk >= 23 -> null
            else -> BlockReason.UNCHECKED
        }
    }
}
