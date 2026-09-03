package com.example.dnsspeed

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import kotlin.random.Random

sealed class DnsCheckResult {
    data class Success(val latencyMs: Long) : DnsCheckResult()
    data object Timeout : DnsCheckResult()
    data class Failure(val reason: String) : DnsCheckResult()
}

/**
 * Measures DNS server availability/latency by sending a real DNS query
 * (A record for probeHost) over UDP port 53 and timing the reply.
 */
object DnsChecker {

    private const val TIMEOUT_MS = 3000
    private const val PROBE_HOST = "example.com"

    suspend fun check(ip: String): DnsCheckResult = withContext(Dispatchers.IO) {
        var socket: DatagramSocket? = null
        try {
            val address = InetAddress.getByName(ip)
            val queryId = Random.nextInt(0, 0xFFFF)
            val query = buildQuery(queryId, PROBE_HOST)

            socket = DatagramSocket()
            socket.soTimeout = TIMEOUT_MS

            val sendPacket = DatagramPacket(query, query.size, address, 53)
            val receiveBuffer = ByteArray(512)
            val receivePacket = DatagramPacket(receiveBuffer, receiveBuffer.size)

            val start = System.nanoTime()
            socket.send(sendPacket)
            socket.receive(receivePacket)
            val elapsedMs = (System.nanoTime() - start) / 1_000_000

            if (isValidResponse(receiveBuffer, receivePacket.length, queryId)) {
                DnsCheckResult.Success(elapsedMs)
            } else {
                DnsCheckResult.Failure("Некорректный ответ")
            }
        } catch (e: SocketTimeoutException) {
            DnsCheckResult.Timeout
        } catch (e: IOException) {
            DnsCheckResult.Failure(e.message ?: "Ошибка сети")
        } finally {
            socket?.close()
        }
    }

    private fun buildQuery(id: Int, host: String): ByteArray {
        val header = byteArrayOf(
            (id shr 8).toByte(), id.toByte(),   // ID
            0x01, 0x00,                          // flags: standard recursive query
            0x00, 0x01,                          // QDCOUNT = 1
            0x00, 0x00,                          // ANCOUNT
            0x00, 0x00,                          // NSCOUNT
            0x00, 0x00                           // ARCOUNT
        )

        val question = mutableListOf<Byte>()
        for (label in host.split(".")) {
            question.add(label.length.toByte())
            question.addAll(label.toByteArray(Charsets.US_ASCII).toList())
        }
        question.add(0x00) // root label
        question.add(0x00); question.add(0x01) // QTYPE = A
        question.add(0x00); question.add(0x01) // QCLASS = IN

        return header + question.toByteArray()
    }

    private fun isValidResponse(buffer: ByteArray, length: Int, expectedId: Int): Boolean {
        if (length < 12) return false
        val respId = ((buffer[0].toInt() and 0xFF) shl 8) or (buffer[1].toInt() and 0xFF)
        val flags = ((buffer[2].toInt() and 0xFF) shl 8) or (buffer[3].toInt() and 0xFF)
        val isResponse = (flags and 0x8000) != 0
        return respId == expectedId && isResponse
    }
}
