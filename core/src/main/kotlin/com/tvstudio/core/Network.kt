package com.tvstudio.core

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

enum class PortStatus { OPEN, CLOSED, UNKNOWN }

data class TvAddress(val host: String, val port: Int) {
    companion object {
        const val ADB_PORT = 5555
        private val IPV4 = Regex("""(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})""")

        /** Accepts `192.168.1.50` or `192.168.1.50:5555`, or returns null. */
        fun parse(text: String): TvAddress? {
            val trimmed = text.trim()
            val host = trimmed.substringBefore(':')
            val port = if (':' in trimmed) trimmed.substringAfter(':').toIntOrNull() else ADB_PORT
            val octets = IPV4.matchEntire(host)?.groupValues?.drop(1)?.map { it.toInt() } ?: return null
            if (octets.any { it > 255 } || port == null || port !in 1..65535) return null
            return TvAddress(host, port)
        }
    }
}

object Network {
    /** Every other address in the phone's /24 network. */
    fun neighbours(phoneIp: String): List<String> {
        val prefix = phoneIp.substringBeforeLast('.')
        return (1..254).map { "$prefix.$it" }.filter { it != phoneIp }
    }

    /**
     * Only a refused connection counts as closed. A timeout or an unreachable network says nothing
     * about the port, so it reads as unknown, never as safe.
     */
    fun probe(host: String, port: Int, timeoutMs: Int): PortStatus = try {
        Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs) }
        PortStatus.OPEN
    } catch (e: IOException) {
        val message = generateSequence<Throwable>(e) { it.cause }.mapNotNull { it.message }.joinToString(" ")
        if (message.contains("refused", ignoreCase = true)) PortStatus.CLOSED else PortStatus.UNKNOWN
    }
}
