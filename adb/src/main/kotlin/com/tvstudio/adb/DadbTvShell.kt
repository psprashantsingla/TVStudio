package com.tvstudio.adb

import com.tvstudio.core.ShellResult
import com.tvstudio.core.TvShell
import dadb.AdbAuthException
import dadb.AdbKeyPair
import dadb.Dadb
import java.io.File
import java.net.SocketTimeoutException

/** Connects to a TV's ADB port directly, with no adb binary or computer. */
class DadbTvShell private constructor(private val dadb: Dadb) : TvShell, AutoCloseable {

    override fun run(command: String): ShellResult {
        val response = dadb.shell(command)
        return ShellResult(response.output, response.errorOutput, response.exitCode)
    }

    override fun close() = dadb.close()

    companion object {
        private const val CONNECT_TIMEOUT_MS = 5_000

        /** Long enough for someone to find the remote and accept the "Allow?" prompt on the TV. */
        private const val SOCKET_TIMEOUT_MS = 60_000

        /**
         * Uses this phone's ADB key, creating one the first time. The TV remembers the key when the
         * person ticks "Always allow", so it should only ask once.
         */
        fun connect(host: String, port: Int, keyDirectory: File): DadbTvShell {
            val privateKey = File(keyDirectory, "adbkey")
            val publicKey = File(keyDirectory, "adbkey.pub")
            if (!privateKey.exists() || !publicKey.exists()) AdbKeyPair.generate(privateKey, publicKey)
            val keys = AdbKeyPair.read(privateKey, publicKey)
            return DadbTvShell(Dadb.create(host, port, keys, CONNECT_TIMEOUT_MS, SOCKET_TIMEOUT_MS))
        }

        /** Connects with a key the caller already has. The emulator test uses this. */
        fun connectWithDefaultKey(host: String, port: Int): DadbTvShell =
            DadbTvShell(Dadb.create(host, port, AdbKeyPair.readDefault(), CONNECT_TIMEOUT_MS, SOCKET_TIMEOUT_MS))

        /** Turns a connection failure into what the person should do about it. */
        fun describeFailure(error: Throwable): String {
            val chain = generateSequence(error) { it.cause }.toList()
            val text = chain.mapNotNull { it.message }.joinToString(" ").lowercase()
            return when {
                chain.any { it is AdbAuthException } ->
                    "The TV didn't allow this phone. Try again, and choose Allow on the TV when it asks."
                "refused" in text ->
                    "The TV refused the connection. Check that USB debugging is on and any Network or ADB debugging " +
                        "option too. Some Google TVs only offer Wireless debugging with a pairing code, which this " +
                        "version doesn't support yet."
                chain.any { it is SocketTimeoutException } || "timed out" in text || "timeout" in text ->
                    "The TV didn't answer in time. If it's showing an \"Allow USB debugging?\" prompt, choose Allow " +
                        "and try again. Otherwise check that the phone and TV are on the same Wi-Fi."
                "unreachable" in text || "no route" in text ->
                    "The phone can't reach that address. Check that the phone and TV are on the same Wi-Fi."
                else -> "The connection to the TV failed: ${error.message ?: error::class.simpleName}"
            }
        }
    }
}
