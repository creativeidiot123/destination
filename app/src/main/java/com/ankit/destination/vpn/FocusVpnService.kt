package com.ankit.destination.vpn

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.ankit.destination.R
import com.ankit.destination.policy.PolicyStore
import com.ankit.destination.ui.MainActivity
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random
import kotlinx.coroutines.runBlocking

class FocusVpnService : VpnService() {
    private var vpnInterface: ParcelFileDescriptor? = null
    private var packetThread: Thread? = null
    @Volatile private var packetLoopRunning = false
    private lateinit var statusStore: VpnStatusStore
    private lateinit var domainPolicyEngine: DomainPolicyEngine
    private lateinit var policyStore: PolicyStore

    @Volatile private var domainSnapshot = DomainPolicySnapshot(
        blockedDomainsGlobal = emptySet(),
        allowedDomainsGlobal = emptySet(),
        blockedDomainsByGroup = emptyMap()
    )
    @Volatile private var domainSnapshotLoadedAtMs: Long = 0L
    @Volatile private var domainSnapshotRefreshAttemptAtMs: Long = 0L
    private val domainSnapshotRefreshInFlight = AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        statusStore = VpnStatusStore(this)
        domainPolicyEngine = DomainPolicyEngine(this)
        policyStore = PolicyStore(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return when (intent?.action) {
            ACTION_STOP -> {
                stopVpn()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                START_NOT_STICKY
            }
            else -> {
                startInForeground()
                establishIfNeeded()
                START_STICKY
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopVpn()
    }

    override fun onRevoke() {
        stopVpn()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        super.onRevoke()
    }

    private fun establishIfNeeded() {
        if (vpnInterface != null && packetThread?.isAlive == true && packetLoopRunning) return
        cleanupVpnState(closeInterface = true)
        runCatching {
            val rules = runBlocking { domainPolicyEngine.loadSnapshot() }
            domainSnapshot = rules
            domainSnapshotLoadedAtMs = System.currentTimeMillis()
            val ruleCount = rules.blockedDomainsGlobal.size +
                rules.allowedDomainsGlobal.size +
                rules.blockedDomainsByGroup.values.sumOf { it.size }
            statusStore.setDomainRuleCount(ruleCount)

            val builder = Builder()
                .setSession("Destination Focus VPN")
                .setMtu(1500)
                .addAddress("10.37.0.2", 24)
                .addRoute(VPN_DNS_ADDRESS, 32)
                .addDnsServer(VPN_DNS_ADDRESS)
            val established = builder.establish()
                ?: throw IllegalStateException("Failed to establish VPN interface")
            vpnInterface = established
            startPacketLoop()
            statusStore.setRunning(true)
            statusStore.setLastError(null)
        }.onFailure {
            cleanupVpnState(closeInterface = true)
            statusStore.setRunning(false)
            statusStore.setLastError(it.message ?: "Unknown VPN error")
        }
    }

    private fun stopVpn() {
        cleanupVpnState(closeInterface = true)
        statusStore.setRunning(false)
    }

    private fun cleanupVpnState(closeInterface: Boolean) {
        packetLoopRunning = false
        val thread = packetThread
        packetThread = null
        if (closeInterface) {
            try {
                vpnInterface?.close()
            } catch (_: IOException) {
            }
            vpnInterface = null
        }
        if (thread != null && thread !== Thread.currentThread()) {
            thread.interrupt()
        }
    }

    private fun startPacketLoop() {
        val vpn = vpnInterface ?: return
        if (packetThread?.isAlive == true) return

        packetLoopRunning = true
        val input = FileInputStream(vpn.fileDescriptor)
        val output = FileOutputStream(vpn.fileDescriptor)
        val loopThread = Thread({
            val packet = ByteArray(MAX_PACKET_SIZE)
            try {
                while (packetLoopRunning) {
                    val len = input.read(packet)
                    if (len <= 0) continue
                    val response = handlePacket(packet, len) ?: continue
                    output.write(response)
                }
            } catch (_: IOException) {
                // Expected when VPN interface is closed.
            } catch (t: Throwable) {
                packetLoopRunning = false
                statusStore.setLastError("VPN packet loop error: ${t.message}")
            } finally {
                runCatching { input.close() }
                runCatching { output.close() }
                if (packetThread === Thread.currentThread()) {
                    cleanupVpnState(closeInterface = true)
                    statusStore.setRunning(false)
                }
            }
        }, "focus-vpn-packet-loop").apply {
            isDaemon = true
        }
        packetThread = loopThread
        loopThread.start()
    }

    private fun handlePacket(packet: ByteArray, len: Int): ByteArray? {
        if (len < IPV4_HEADER_MIN_LEN + UDP_HEADER_LEN) return null
        val version = (packet[0].toInt() ushr 4) and 0x0F
        if (version != 4) return null
        val ihl = (packet[0].toInt() and 0x0F) * 4
        if (ihl < IPV4_HEADER_MIN_LEN || len < ihl + UDP_HEADER_LEN) return null
        val protocol = packet[9].toInt() and 0xFF
        if (protocol != IPPROTO_UDP) return null

        val srcIp = packet.copyOfRange(12, 16)
        val dstIp = packet.copyOfRange(16, 20)
        if (!dstIp.contentEquals(VPN_DNS_ADDRESS_BYTES)) return null

        val udpOffset = ihl
        val srcPort = readU16(packet, udpOffset)
        val dstPort = readU16(packet, udpOffset + 2)
        if (dstPort != DNS_PORT) return null
        val udpLength = readU16(packet, udpOffset + 4)
        val payloadOffset = udpOffset + UDP_HEADER_LEN
        val payloadLen = minOf(len - payloadOffset, udpLength - UDP_HEADER_LEN)
        if (payloadLen <= 0) return null
        val dnsQuery = packet.copyOfRange(payloadOffset, payloadOffset + payloadLen)
        val blockedGroups = policyStore.getBudgetBlockedGroupIds() + policyStore.getScheduleBlockedGroups()
        val domain = parseDnsQuestionDomain(dnsQuery)
        val responsePayload = when {
            domain != null && shouldBlockDomain(domain, blockedGroups) -> {
                buildDnsErrorResponse(dnsQuery, DNS_RCODE_NXDOMAIN)
            }
            else -> {
                forwardDnsQuery(dnsQuery) ?: buildDnsErrorResponse(dnsQuery, DNS_RCODE_SERVFAIL)
            }
        }
        return buildUdpIpv4Packet(
            srcIp = VPN_DNS_ADDRESS_BYTES,
            dstIp = srcIp,
            srcPort = DNS_PORT,
            dstPort = srcPort,
            payload = responsePayload
        )
    }

    private fun shouldBlockDomain(domain: String, blockedGroups: Set<String>): Boolean {
        ensureDomainSnapshotFresh()
        return domainPolicyEngine.isDomainBlocked(
            domain = domain,
            blockedGroupIds = blockedGroups,
            snapshot = domainSnapshot
        )
    }

    private fun ensureDomainSnapshotFresh() {
        val nowMs = System.currentTimeMillis()
        if (nowMs - domainSnapshotLoadedAtMs < DOMAIN_REFRESH_MS) return
        if (nowMs - domainSnapshotRefreshAttemptAtMs < DOMAIN_REFRESH_RETRY_BACKOFF_MS) return
        if (!domainSnapshotRefreshInFlight.compareAndSet(false, true)) return
        domainSnapshotRefreshAttemptAtMs = nowMs
        Thread({
            try {
                val snapshot = runBlocking { domainPolicyEngine.loadSnapshot() }
                domainSnapshot = snapshot
                domainSnapshotLoadedAtMs = System.currentTimeMillis()
                val ruleCount = snapshot.blockedDomainsGlobal.size +
                    snapshot.allowedDomainsGlobal.size +
                    snapshot.blockedDomainsByGroup.values.sumOf { it.size }
                statusStore.setDomainRuleCount(ruleCount)
            } catch (_: Throwable) {
                // Keep serving with the previous snapshot.
            } finally {
                domainSnapshotRefreshInFlight.set(false)
            }
        }, "focus-vpn-snapshot-refresh").apply {
            isDaemon = true
            start()
        }
    }

    private fun parseDnsQuestionDomain(query: ByteArray): String? {
        if (query.size < DNS_HEADER_LEN) return null
        val questionCount = readU16(query, 4)
        if (questionCount <= 0) return null
        var offset = DNS_HEADER_LEN
        val labels = mutableListOf<String>()
        while (offset < query.size) {
            val labelLen = query[offset].toInt() and 0xFF
            offset += 1
            if (labelLen == 0) break
            if ((labelLen and 0xC0) != 0) return null
            if (offset + labelLen > query.size) return null
            labels += String(query, offset, labelLen, Charsets.US_ASCII)
            offset += labelLen
        }
        return labels.joinToString(".").trim('.').lowercase().takeIf { it.isNotBlank() }
    }

    private fun forwardDnsQuery(query: ByteArray): ByteArray? {
        val upstreams = listOf("1.1.1.1", "8.8.8.8")
        for (ip in upstreams) {
            try {
                DatagramSocket().use { socket ->
                    if (!protect(socket)) {
                        return@use
                    }
                    socket.soTimeout = 2_000
                    val address = InetAddress.getByName(ip)
                    socket.connect(address, DNS_PORT)
                    socket.send(DatagramPacket(query, query.size))
                    val responseBuffer = ByteArray(MAX_DNS_PAYLOAD_SIZE)
                    val responsePacket = DatagramPacket(responseBuffer, responseBuffer.size)
                    socket.receive(responsePacket)
                    return responseBuffer.copyOf(responsePacket.length)
                }
            } catch (_: Exception) {
                // Try next upstream.
            }
        }
        return null
    }

    private fun buildDnsErrorResponse(query: ByteArray, rcode: Int): ByteArray {
        if (query.size < DNS_HEADER_LEN) return byteArrayOf()
        val rdBit = query[2].toInt() and 0x01
        val responseFlags1 = (0x80 or rdBit).toByte()
        val responseFlags2 = (rcode and 0x0F).toByte()

        val header = ByteArray(DNS_HEADER_LEN)
        header[0] = query[0]
        header[1] = query[1]
        header[2] = responseFlags1
        header[3] = responseFlags2
        header[4] = query[4]
        header[5] = query[5]
        // No answers/authority/additional on errors.
        header[6] = 0
        header[7] = 0
        header[8] = 0
        header[9] = 0
        header[10] = 0
        header[11] = 0

        val question = if (query.size > DNS_HEADER_LEN) query.copyOfRange(DNS_HEADER_LEN, query.size) else byteArrayOf()
        return header + question
    }

    private fun buildUdpIpv4Packet(
        srcIp: ByteArray,
        dstIp: ByteArray,
        srcPort: Int,
        dstPort: Int,
        payload: ByteArray
    ): ByteArray {
        val ipHeaderLen = IPV4_HEADER_MIN_LEN
        val udpLen = UDP_HEADER_LEN + payload.size
        val totalLen = ipHeaderLen + udpLen
        val packet = ByteArray(totalLen)

        packet[0] = 0x45
        packet[1] = 0x00
        writeU16(packet, 2, totalLen)
        writeU16(packet, 4, Random.nextInt(0, 0xFFFF))
        writeU16(packet, 6, 0x4000) // Don't fragment.
        packet[8] = 64
        packet[9] = IPPROTO_UDP.toByte()
        // checksum field at 10..11 left 0 before computing
        System.arraycopy(srcIp, 0, packet, 12, 4)
        System.arraycopy(dstIp, 0, packet, 16, 4)
        writeU16(packet, 10, checksum(packet, 0, ipHeaderLen))

        val udpOffset = ipHeaderLen
        writeU16(packet, udpOffset, srcPort)
        writeU16(packet, udpOffset + 2, dstPort)
        writeU16(packet, udpOffset + 4, udpLen)
        writeU16(packet, udpOffset + 6, 0)
        System.arraycopy(payload, 0, packet, udpOffset + UDP_HEADER_LEN, payload.size)
        val udpChecksum = udpChecksum(srcIp, dstIp, packet, udpOffset, udpLen)
        writeU16(packet, udpOffset + 6, udpChecksum)

        return packet
    }

    private fun readU16(data: ByteArray, offset: Int): Int {
        return ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
    }

    private fun writeU16(data: ByteArray, offset: Int, value: Int) {
        data[offset] = ((value ushr 8) and 0xFF).toByte()
        data[offset + 1] = (value and 0xFF).toByte()
    }

    private fun checksum(data: ByteArray, offset: Int, length: Int): Int {
        var sum = 0L
        var i = 0
        while (i + 1 < length) {
            val word = readU16(data, offset + i)
            sum += word.toLong()
            i += 2
        }
        if (i < length) {
            sum += (((data[offset + i].toInt() and 0xFF) shl 8)).toLong()
        }
        while ((sum ushr 16) != 0L) {
            sum = (sum and 0xFFFF) + (sum ushr 16)
        }
        return (sum.inv() and 0xFFFF).toInt()
    }

    private fun udpChecksum(srcIp: ByteArray, dstIp: ByteArray, packet: ByteArray, udpOffset: Int, udpLen: Int): Int {
        var sum = 0L
        fun add(value: Int) {
            sum += value.toLong() and 0xFFFFL
        }

        add(readU16(srcIp, 0))
        add(readU16(srcIp, 2))
        add(readU16(dstIp, 0))
        add(readU16(dstIp, 2))
        add(IPPROTO_UDP)
        add(udpLen)

        var i = 0
        while (i + 1 < udpLen) {
            add(readU16(packet, udpOffset + i))
            i += 2
        }
        if (i < udpLen) {
            add((packet[udpOffset + i].toInt() and 0xFF) shl 8)
        }
        while ((sum ushr 16) != 0L) {
            sum = (sum and 0xFFFF) + (sum ushr 16)
        }
        val computed = (sum.inv() and 0xFFFF).toInt()
        return if (computed == 0) 0xFFFF else computed
    }

    private fun startInForeground() {
        createChannelIfNeeded()
        val openIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("VPN DNS filter active")
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun createChannelIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Focus VPN",
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val ACTION_START = "com.ankit.destination.vpn.START"
        private const val ACTION_STOP = "com.ankit.destination.vpn.STOP"
        private const val CHANNEL_ID = "focus_vpn_channel"
        private const val NOTIFICATION_ID = 73
        private const val MAX_PACKET_SIZE = 32_767
        private const val MAX_DNS_PAYLOAD_SIZE = 4_096
        private const val IPPROTO_UDP = 17
        private const val IPV4_HEADER_MIN_LEN = 20
        private const val UDP_HEADER_LEN = 8
        private const val DNS_HEADER_LEN = 12
        private const val DNS_PORT = 53
        private const val DNS_RCODE_SERVFAIL = 2
        private const val DNS_RCODE_NXDOMAIN = 3
        private const val DOMAIN_REFRESH_MS = 30_000L
        private const val DOMAIN_REFRESH_RETRY_BACKOFF_MS = 5_000L
        private const val VPN_DNS_ADDRESS = "10.37.0.1"
        private val VPN_DNS_ADDRESS_BYTES: ByteArray by lazy {
            InetAddress.getByName(VPN_DNS_ADDRESS).address
        }

        fun start(context: Context) {
            val intent = Intent(context, FocusVpnService::class.java).setAction(ACTION_START)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }.onFailure {
                VpnStatusStore(context).setLastError("Failed to start VPN: ${it.message}")
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, FocusVpnService::class.java).setAction(ACTION_STOP)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }.onFailure {
                VpnStatusStore(context).setLastError("Failed to stop VPN: ${it.message}")
            }
        }

        fun isPrepared(context: Context): Boolean = prepare(context) == null

        fun isRunning(context: Context): Boolean = VpnStatusStore(context).isRunning()
    }
}
