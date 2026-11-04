package com.tvstudio.core

/** Result of one shell command on the TV. */
data class ShellResult(val stdout: String, val stderr: String = "", val exitCode: Int = 0) {
    val allOutput: String get() = stdout + stderr
}

/**
 * The only way the engine reaches a TV, so tests can replace it with a fake.
 * Implementations throw [java.io.IOException] when the connection is lost.
 */
interface TvShell {
    fun run(command: String): ShellResult
}

/** Every command that changes a TV is built here, from a name that has passed validation. */
internal object Commands {
    fun disable(pkg: String) = "pm disable-user --user 0 ${SafetyRules.requireValidPackage(pkg)}"
    fun enable(pkg: String) = "pm enable --user 0 ${SafetyRules.requireValidPackage(pkg)}"
    fun dump(pkg: String) = "dumpsys package ${SafetyRules.requireValidPackage(pkg)}"
}
