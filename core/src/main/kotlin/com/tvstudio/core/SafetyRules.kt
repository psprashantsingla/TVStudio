package com.tvstudio.core

/** Why a package must stay enabled. */
enum class BlockReason(val message: String) {
    INVALID_NAME("This isn't a valid package name."),
    CORE_ANDROID("Core Android or TV system software. Without it the TV may not boot."),
    ESSENTIAL("The TV needs this for the Play Store, keyboard, remote, network or inputs."),
    HOME("It provides the home screen. Without a replacement, Home could stop working."),
    SETUP_WIZARD("On Google TV, disabling it can delete the saved Wi-Fi network and break Home."),
    INPUTS("The TV opens its HDMI or AV inputs with it."),
    SETTINGS("The TV opens Settings with it, which is the way back to Developer options."),
    KEYBOARD("It's the TV's active on-screen keyboard."),
    INPUT_DRIVER("It may drive the HDMI, AV or tuner inputs. Disabling it can leave them with no signal."),
    UNCHECKED("The TV didn't give enough information to check that this package is safe to disable."),
}

/**
 * Rules that hold on every TV, with no override.
 *
 * The core-package patterns and the reasons behind them come from CleanYourTV's tools/disable.sh
 * (MIT, see THIRD_PARTY_NOTICES.md), which records real TVs that stopped booting without them.
 */
object SafetyRules {
    /** Package names end up inside shell commands, so nothing that could carry shell syntax passes. */
    private val PACKAGE = Regex("""[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+""")

    private val CORE = Regex(
        """android|android\.ext\.(services|shared)|com\.android\.sdksandbox|com\.android\.location\.fused""" +
            """|com\.android\.systemui|com\.android\.shell|com\.android\.phone|com\.android\.settings""" +
            """|com\.android\.tv\.settings""" +
            """|com\.(google\.)?android\.(permissioncontroller|packageinstaller|ext\.services|ext\.shared|modulemetadata)""" +
            """|com\.google\.android\.overlay\.modules.*|com\.android\.providers\..*|com\.cltv\.mal""" +
            """|com\.mediatek\.(tis|tv\.service|tv\.agent)""",
    )

    /** Content providers no TV feature uses, the exception to the providers rule above. */
    private val CORE_EXCEPTIONS = Regex("""com\.android\.providers\.(calendar|contacts|userdictionary|blockednumber)""")

    private val ESSENTIAL = Regex(
        """com\.android\.vending|com\.google\.android\.gms(\..*)?|com\.google\.android\.gsf(\..*)?""" +
            """|com\.android\.tv|com\.google\.android\.tv""" +
            """|com\.android\.networkstack.*|com\.google\.android\.networkstack.*|com\.android\.wifi.*""" +
            """|com\.google\.android\.wifi.*|com\.android\.bluetooth.*|com\.google\.android\.bluetooth.*""" +
            """|com\.android\.inputmethod.*|com\.google\.android\.inputmethod.*|com\.google\.android\.leanback\.ime""" +
            """|com\.google\.android\.tv\.remote.*""",
    )

    /** Vendors name these services freely, so match on the words. It's better to keep one too many. */
    private val ESSENTIAL_WORDS = Regex("""(bluetooth|inputmethod|tvinput|remotecontrol)|(^|\.)rcu(\.|$)""")

    val STOCK_LAUNCHERS = setOf(
        "com.google.android.tvlauncher",
        "com.google.android.apps.tv.launcherx",
        "com.google.android.leanbacklauncher",
        "com.mitv.tvhome",
    )

    const val SETUP_WIZARD = "com.google.android.tungsten.setupwraith"

    fun isValidPackage(name: String): Boolean = PACKAGE.matches(name)

    fun requireValidPackage(name: String): String {
        require(isValidPackage(name)) { "Not a package name: $name" }
        return name
    }

    /** The block that applies on any TV, or null if the package isn't ruled out by name. */
    fun staticBlock(name: String): BlockReason? = when {
        !isValidPackage(name) -> BlockReason.INVALID_NAME
        name == SETUP_WIZARD -> BlockReason.SETUP_WIZARD
        name in STOCK_LAUNCHERS -> BlockReason.HOME
        CORE.matches(name) && !CORE_EXCEPTIONS.matches(name) -> BlockReason.CORE_ANDROID
        ESSENTIAL.matches(name) -> BlockReason.ESSENTIAL
        ESSENTIAL_WORDS.containsMatchIn(name.lowercase()) -> BlockReason.ESSENTIAL
        else -> null
    }
}
