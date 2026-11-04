package com.tvstudio.core

import java.io.IOException

/** A pretend TV that answers the shell commands the engine sends and keeps real package state. */
class FakeTv(
    enabled: Set<String>,
    disabled: Set<String> = emptySet(),
    var home: String? = "com.google.android.tvlauncher",
    var homeClaimers: List<String> = listOfNotNull(home),
    var inputApps: List<String> = emptyList(),
    var settingsApps: List<String> = listOf("com.android.tv.settings"),
    var keyboard: String = "com.google.android.leanback.ime/.Ime",
) : TvShell {
    val enabled = enabled.toMutableSet()
    val disabled = disabled.toMutableSet()
    val commands = ArrayList<String>()
    var dumps = HashMap<String, String>()

    /** The firmware answers disable commands with success but changes nothing. */
    var ignoresDisable = false

    /** The firmware answers success for these too, but they stay enabled. */
    var refuses: Set<String> = emptySet()

    /** Throw on the Nth disable command, as if the connection dropped. */
    var dropOnDisableNumber: Int? = null
    private var disableCount = 0

    /** Called when a disable succeeds, for firmware that changes something else as a result. */
    var onDisable: (String) -> Unit = {}

    override fun run(command: String): ShellResult {
        commands += command
        val words = command.split(' ')
        return when {
            command.startsWith("pm disable-user --user 0 ") -> disable(words.last())
            command.startsWith("pm enable --user 0 ") -> {
                if (disabled.remove(words.last())) enabled += words.last()
                ShellResult("Package ${words.last()} new state: enabled\n")
            }
            command == "pm list packages -e --user 0" -> ShellResult(enabled.joinToString("") { "package:$it\r\n" })
            command == "pm list packages -d --user 0" -> ShellResult(disabled.joinToString("") { "package:$it\r\n" })
            command.startsWith("cmd package resolve-activity") ->
                ShellResult(home?.let { "priority=0 preferredOrder=0\n$it/.Main\n" } ?: "No activity found\n")
            command == "dumpsys activity" -> ShellResult("")
            "category.HOME" in command -> ShellResult(components(homeClaimers))
            "cursor.item/channel" in command -> ShellResult(components(inputApps))
            "android.settings.SETTINGS" in command -> ShellResult(components(settingsApps))
            command == "settings get secure default_input_method" -> ShellResult("$keyboard\n")
            command == "settings get secure enabled_input_methods" -> ShellResult("$keyboard\n")
            command.startsWith("dumpsys package ") ->
                ShellResult(dumps[words.last()] ?: "Package [${words.last()}] (abc):\n  requested permissions:\n    android.permission.INTERNET\n")
            command == "sync" || command == "reboot" -> ShellResult("")
            else -> error("FakeTv got an unexpected command: $command")
        }
    }

    private fun disable(pkg: String): ShellResult {
        disableCount++
        if (dropOnDisableNumber == disableCount) throw IOException("connection lost")
        if (!ignoresDisable && pkg !in refuses && enabled.remove(pkg)) {
            disabled += pkg
            onDisable(pkg)
        }
        return ShellResult("Package $pkg new state: disabled-user\n")
    }

    private fun components(packages: List<String>) = packages.joinToString("") { "$it/.Main\n" }
}
