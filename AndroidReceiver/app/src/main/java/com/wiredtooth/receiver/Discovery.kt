package com.wiredtooth.receiver

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException

/**
 * Finds senders on the local network by unicast sweep.
 *
 * Why not mDNS/NSD or a broadcast probe, which would be the obvious answers:
 *
 *   - The Windows sender does not announce itself. Adding an mDNS responder
 *     to it is a change to a component that is working and shipped.
 *   - CLAUDE.md rules the transport unicast-only, and the iOS receiver that
 *     this design eventually has to serve cannot use multicast or Bonjour at
 *     all without an entitlement the project deliberately does not request.
 *
 * A sweep sidesteps both. It sends an ordinary HELLO to every host address on
 * the local subnet and collects whoever HELLO_ACKs. That is exactly the
 * handshake a real client performs, so any sender that would accept a
 * connection will answer, with no sender-side change and no multicast.
 *
 * Cost is bounded: a phone hotspot is a /28, which is 14 addresses. A home
 * /24 is 254 probes of 20 bytes each -- about 5 KB, sent once.
 */
object Discovery {

    /** Subnets larger than this are not swept; the user types the IP instead. */
    private const val MAX_HOSTS = 254

    data class Found(val ip: String)

    /**
     * Sweeps and returns whoever answered, in the order they replied.
     * Blocking: call from a background thread.
     */
    fun sweep(timeoutMs: Long = 2500): List<Found> {
        val local = localIPv4() ?: return emptyList()
        val targets = hostsFor(local.address, local.prefixLength)
        if (targets.isEmpty()) return emptyList()

        val found = LinkedHashSet<String>()

        DatagramSocket().use { sock ->
            sock.soTimeout = 200

            val hello = Wtp.writeControl(Wtp.TYPE_HELLO, 1, Wtp.timestampMicros())
            for (t in targets) {
                try {
                    sock.send(
                        DatagramPacket(hello, hello.size, t, AudioReceiver.CONTROL_PORT),
                    )
                } catch (_: Exception) {
                    // An unreachable host on the sweep is the normal case, not
                    // an error worth surfacing.
                }
            }

            // Collect replies until the budget runs out. Senders answer almost
            // immediately on a LAN; the budget exists for the ones that do not.
            val deadline = System.currentTimeMillis() + timeoutMs
            val buf = ByteArray(512)
            while (System.currentTimeMillis() < deadline) {
                try {
                    val p = DatagramPacket(buf, buf.size)
                    sock.receive(p)
                    val h = Wtp.parse(buf, p.length) ?: continue
                    if (h.type == Wtp.TYPE_HELLO_ACK) {
                        p.address?.hostAddress?.let { found.add(it) }
                    }
                } catch (_: SocketTimeoutException) {
                    // keep waiting until the deadline
                } catch (_: Exception) {
                    break
                }
            }
        }

        return found.map { Found(it) }
    }

    private data class Local(val address: Inet4Address, val prefixLength: Short)

    /** The IPv4 address on an interface that is up and not loopback. */
    private fun localIPv4(): Local? {
        for (nic in NetworkInterface.getNetworkInterfaces()) {
            if (!nic.isUp || nic.isLoopback) continue
            for (ia in nic.interfaceAddresses) {
                val addr = ia.address
                if (addr is Inet4Address && !addr.isLoopbackAddress) {
                    return Local(addr, ia.networkPrefixLength)
                }
            }
        }
        return null
    }

    /** Every host address in the subnet, excluding our own, network and broadcast. */
    private fun hostsFor(addr: Inet4Address, prefix: Short): List<InetAddress> {
        if (prefix < 20) return emptyList()   // too large to sweep politely

        val bytes = addr.address
        var value = 0
        for (b in bytes) value = (value shl 8) or (b.toInt() and 0xFF)

        val hostBits = 32 - prefix
        val mask = if (hostBits >= 32) 0 else (-1 shl hostBits)
        val network = value and mask
        val broadcast = network or (mask.inv())

        val count = broadcast - network - 1
        if (count <= 0 || count > MAX_HOSTS) return emptyList()

        val out = ArrayList<InetAddress>(count)
        for (h in (network + 1) until broadcast) {
            if (h == value) continue          // skip ourselves
            out.add(
                InetAddress.getByAddress(
                    byteArrayOf(
                        ((h ushr 24) and 0xFF).toByte(),
                        ((h ushr 16) and 0xFF).toByte(),
                        ((h ushr 8) and 0xFF).toByte(),
                        (h and 0xFF).toByte(),
                    ),
                ),
            )
        }
        return out
    }
}
