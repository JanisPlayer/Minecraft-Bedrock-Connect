package de.heldendesbildschirms.mcbedrockconnector

import android.annotation.SuppressLint
import android.util.Log
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.min

/**
 * Bridges Minecraft: Bedrock Edition NetherNet LAN Discovery & Signaling (UDP 7551)
 * to a remote NetherNet server's HTTP Signaling endpoint (GET/POST /v1/join on TCP 19132).
 */
class NetherNetLanBridge(
    private val sendDiscoveryUdp: (ByteBuffer, InetSocketAddress) -> Unit,
) {
    companion object {
        private const val TAG = "NetherNetLanBridge"

        private const val ID_REQUEST_PACKET: Int = 0
        private const val ID_RESPONSE_PACKET: Int = 1
        private const val ID_MESSAGE_PACKET: Int = 2

        private const val MESSAGE_HEADER_LENGTH = 32
        private const val MAX_DECLARED_MESSAGE_DATA = 1140
        private const val SERVER_DATA_VERSION: Byte = 7
        private const val LAN_CONNECTION_TYPE: Int = 4
        private const val OFFER_CANDIDATE_WAIT_MS = 220L

        private const val SIGNAL_OFFER = "CONNECTREQUEST"
        private const val SIGNAL_ANSWER = "CONNECTRESPONSE"
        private const val SIGNAL_CANDIDATE = "CANDIDATEADD"
        private const val SIGNAL_ERROR = "CONNECTERROR"

        // SHA-256 of 0xdeadbeef as 64-bit little-endian integer
        private val DISCOVERY_KEY: ByteArray = run {
            val leDeadBeef = ByteBuffer.allocate(8)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putLong(0xdeadbeefL)
                .array()
            MessageDigest.getInstance("SHA-256").digest(leDeadBeef)
        }

        private val AES_KEY_SPEC = SecretKeySpec(DISCOVERY_KEY, "AES")
        private val HMAC_KEY_SPEC = SecretKeySpec(DISCOVERY_KEY, "HmacSHA256")
        private val HEX_CHARS = "0123456789abcdef".toCharArray()
    }

    // Unique 64-bit NetworkID representing our local LAN proxy server
    val localServerNetworkId: Long = run {
        var id = SecureRandom().nextLong()
        if (id == 0L) id = 1L
        id
    }

    private class CachedServerStatus(
        val binaryPayload: ByteArray,
        val fetchedAtMs: Long,
    )

    @Volatile
    private var defaultCachedPayload: ByteArray? = null

    @Volatile
    private var httpEndpointReachable: Boolean = true

    private val clientStatusCache = ConcurrentHashMap<Long, CachedServerStatus>()
    private val activeFetches = ConcurrentHashMap.newKeySet<Long>()
    private val isFetchingDefault = AtomicBoolean(false)

    private var scheduler: ScheduledExecutorService? = null

    private class PendingOffer(
        val clientSenderId: Long,
        val connectionId: String,
        @Volatile var clientAddress: InetSocketAddress,
        @Volatile var sdpOffer: String,
        val candidates: MutableList<String> = mutableListOf(),
        var scheduledTask: ScheduledFuture<*>? = null,
    )

    private val pendingOffers = ConcurrentHashMap<String, PendingOffer>()
    private val clientAddresses = ConcurrentHashMap<Long, InetSocketAddress>()

    fun start(host: String, port: Int) {
        stop()
        val pool = Executors.newScheduledThreadPool(3)
        scheduler = pool
        refreshDefaultStatusAsync(host, port)
    }

    fun stop() {
        pendingOffers.values.forEach { it.scheduledTask?.cancel(true) }
        pendingOffers.clear()
        clientAddresses.clear()
        clientStatusCache.clear()
        activeFetches.clear()
        scheduler?.shutdownNow()
        scheduler = null
    }

    fun onDestinationChanged(host: String, port: Int) {
        defaultCachedPayload = null
        clientStatusCache.clear()
        activeFetches.clear()
        refreshDefaultStatusAsync(host, port)
    }

    /**
     * Handles an incoming UDP packet on port 7551.
     * Returns true if the packet was a valid NetherNet discovery packet.
     */
    fun handleIncomingPacket(
        rawBytes: ByteArray,
        senderAddress: InetSocketAddress,
        targetHost: String,
        targetPort: Int,
    ): Boolean {
        if (rawBytes.size < 32 + 16) return false

        val receivedHmac = rawBytes.copyOfRange(0, 32)
        val ciphertext = rawBytes.copyOfRange(32, rawBytes.size)
        if ((ciphertext.size % 16) != 0) return false

        val plaintext = try {
            decryptAesEcb(ciphertext)
        } catch (_: Exception) {
            return false
        }

        val expectedHmac = computeHmacSha256(plaintext)
        if (!MessageDigest.isEqual(receivedHmac, expectedHmac)) {
            return false
        }

        if (plaintext.size < 20) return false
        val buf = ByteBuffer.wrap(plaintext).order(ByteOrder.LITTLE_ENDIAN)
        val declaredLen = buf.short.toInt() and 0xFFFF
        if (plaintext.size < declaredLen) return false

        val packetId = buf.short.toInt() and 0xFFFF
        val senderId = buf.long
        // Skip 8 bytes padding
        buf.position(buf.position() + 8)

        // Ignore our own packets if received via broadcast
        if (senderId == localServerNetworkId) {
            return true
        }

        when (packetId) {
            ID_REQUEST_PACKET -> {
                if (senderId != 0L) {
                    clientAddresses[senderId] = senderAddress
                }
                handleDiscoveryRequest(senderId, senderAddress, targetHost, targetPort)
            }
            ID_RESPONSE_PACKET -> {
                // Ignore responses from other LAN servers
            }
            ID_MESSAGE_PACKET -> {
                if (senderId != 0L) {
                    clientAddresses[senderId] = senderAddress
                }
                if (buf.remaining() >= 12) {
                    val recipientId = buf.long
                    buf.int // Skip 4-byte declared data length (vanilla reads remaining bytes)
                    val dataBytes = ByteArray(buf.remaining())
                    buf.get(dataBytes)
                    val messageData = String(dataBytes, StandardCharsets.UTF_8)
                    handleDiscoveryMessage(
                        senderId = senderId,
                        recipientId = recipientId,
                        messageData = messageData,
                        senderAddress = senderAddress,
                        targetHost = targetHost,
                        targetPort = targetPort,
                    )
                }
            }
        }
        return true
    }

    private fun handleDiscoveryRequest(
        clientSenderId: Long,
        senderAddress: InetSocketAddress,
        targetHost: String,
        targetPort: Int,
    ) {
        val now = System.currentTimeMillis()
        val clientCached = clientStatusCache[clientSenderId]

        if ((clientCached == null) || ((now - clientCached.fetchedAtMs) > 6_000L)) {
            refreshClientStatusAsync(
                host = targetHost,
                port = targetPort,
                clientSenderId = clientSenderId,
                replyImmediatelyTo = if (clientCached == null) senderAddress else null,
            )
        }

        val appData = clientCached?.binaryPayload
            ?: if (!httpEndpointReachable) (defaultCachedPayload ?: buildFallbackServerData(targetHost, targetPort)) else return

        val responsePacket = encodeResponsePacket(localServerNetworkId, appData)
        sendDiscoveryUdp(ByteBuffer.wrap(responsePacket), senderAddress)
    }

    private fun handleDiscoveryMessage(
        senderId: Long,
        recipientId: Long,
        messageData: String,
        senderAddress: InetSocketAddress,
        targetHost: String,
        targetPort: Int,
    ) {
        if (recipientId != localServerNetworkId && recipientId != 0L) return
        if (messageData.isEmpty() || (messageData == "Ping")) return

        val parts = messageData.split(" ", limit = 3)
        if (parts.size < 3) {
            Log.w(TAG, "Malformed signaling message from ${java.lang.Long.toUnsignedString(senderId)}: $messageData")
            return
        }

        val signalType = parts[0]
        val connectionId = parts[1]
        val signalPayload = parts[2]
        val offerKey = "${java.lang.Long.toUnsignedString(senderId)}:$connectionId"

        when (signalType) {
            SIGNAL_OFFER -> {
                val hasIdentity = signalPayload.contains("a=identity:")
                val hasFingerprint = signalPayload.contains("a=fingerprint:")
                Log.i(
                    TAG,
                    "Received LAN CONNECTREQUEST from ${java.lang.Long.toUnsignedString(senderId)} (conn=$connectionId) at $senderAddress [hasIdentity=$hasIdentity, hasFingerprint=$hasFingerprint, len=${signalPayload.length}]",
                )
                Log.d(TAG, "LAN SDP Offer payload:\n$signalPayload")
                val pending = PendingOffer(
                    clientSenderId = senderId,
                    connectionId = connectionId,
                    clientAddress = senderAddress,
                    sdpOffer = signalPayload,
                )
                pendingOffers.put(offerKey, pending)?.scheduledTask?.cancel(false)
                scheduleOfferForward(offerKey, pending, targetHost, targetPort)
            }
            SIGNAL_CANDIDATE -> {
                Log.i(TAG, "Received LAN CANDIDATEADD (conn=$connectionId): $signalPayload")
                val pending = pendingOffers[offerKey]
                if (pending != null) {
                    synchronized(pending) {
                        pending.clientAddress = senderAddress
                        pending.candidates.add(signalPayload.trim())
                    }
                }
            }
            SIGNAL_ERROR -> {
                Log.w(TAG, "Received LAN CONNECTERROR (conn=$connectionId): $signalPayload")
                pendingOffers.remove(offerKey)?.scheduledTask?.cancel(false)
            }
        }
    }

    private fun scheduleOfferForward(
        offerKey: String,
        pending: PendingOffer,
        targetHost: String,
        targetPort: Int,
    ) {
        val pool = scheduler ?: return
        pending.scheduledTask = pool.schedule({
            pendingOffers.remove(offerKey)
            forwardOfferToHttpEndpoint(pending, targetHost, targetPort)
        }, OFFER_CANDIDATE_WAIT_MS, TimeUnit.MILLISECONDS)
    }

    private fun forwardOfferToHttpEndpoint(
        pending: PendingOffer,
        targetHost: String,
        targetPort: Int,
    ) {
        val unsignedSenderId = java.lang.Long.toUnsignedString(pending.clientSenderId)
        val candidatesSnapshot: List<String>
        synchronized(pending) {
            candidatesSnapshot = pending.candidates.toList()
        }

        val fullSdpOffer = buildFullIceSdpOffer(pending.sdpOffer, candidatesSnapshot)
        val joinUrl = "http://$targetHost:$targetPort/v1/join/$unsignedSenderId"

        Log.i(
            TAG,
            "Forwarding LAN SDP offer (${candidatesSnapshot.size} candidates) -> POST $joinUrl",
        )

        var conn: HttpURLConnection? = null
        try {
            conn = (URL(joinUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 8_000
                readTimeout = 10_000
                doOutput = true
                useCaches = false
                setRequestProperty("Content-Type", "application/sdp")
                setRequestProperty("User-Agent", "libhttpclient/1.0.0.0")
                setRequestProperty("Connection", "close")
            }

            val offerBytes = fullSdpOffer.toByteArray(StandardCharsets.UTF_8)
            conn.setFixedLengthStreamingMode(offerBytes.size)
            conn.outputStream.use { out ->
                out.write(offerBytes)
                out.flush()
            }

            val status = conn.responseCode
            val responseBody = try {
                val stream = if (status in 200..299) conn.inputStream else conn.errorStream
                stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() } ?: ""
            } catch (_: Exception) {
                ""
            }

            if ((status !in 200..299) || responseBody.isBlank()) {
                Log.w(TAG, "HTTP POST $joinUrl failed with status $status: $responseBody")
                sendSignalToClient(
                    clientSenderId = pending.clientSenderId,
                    clientAddress = pending.clientAddress,
                    signalType = SIGNAL_ERROR,
                    connectionId = pending.connectionId,
                    data = "21",
                )
                return
            }

            Log.i(TAG, "Received HTTP SDP answer (${responseBody.length} bytes) from $joinUrl")
            deliverHttpAnswerToLanClient(
                pending = pending,
                fullSdpAnswer = responseBody,
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to POST SDP offer to $joinUrl: ${e.message}", e)
            sendSignalToClient(
                clientSenderId = pending.clientSenderId,
                clientAddress = pending.clientAddress,
                signalType = SIGNAL_ERROR,
                connectionId = pending.connectionId,
                data = "21",
            )
        } finally {
            conn?.disconnect()
        }
    }

    private fun deliverHttpAnswerToLanClient(
        pending: PendingOffer,
        fullSdpAnswer: String,
    ) {
        val rawLines = fullSdpAnswer.replace("\r\n", "\n").split("\n")
        val extractedCandidates = mutableListOf<String>()
        val answerLinesWithoutCandidates = mutableListOf<String>()
        var serverIceUfrag: String? = null

        for (line in rawLines) {
            val trimmed = line.trimEnd('\r')
            if (trimmed.isEmpty()) continue

            if (trimmed.startsWith("a=ice-ufrag:")) {
                serverIceUfrag = trimmed.removePrefix("a=ice-ufrag:").trim()
                answerLinesWithoutCandidates.add(trimmed)
            } else if (trimmed.startsWith("a=candidate:")) {
                extractedCandidates.add(trimmed.removePrefix("a=").trim())
            } else if (trimmed == "a=end-of-candidates") {
                // Omit in trickle ICE answer
            } else if (trimmed.startsWith("m=application ")) {
                // Vanilla LAN CONNECTRESPONSE uses port 9 in m=application
                val parts = trimmed.split(" ")
                if (parts.size >= 4) {
                    answerLinesWithoutCandidates.add(
                        "m=application 9 ${parts.subList(2, parts.size).joinToString(" ")}",
                    )
                } else {
                    answerLinesWithoutCandidates.add(trimmed)
                }
            } else if (trimmed.startsWith("c=IN IP4 ") || trimmed.startsWith("c=IN IP6 ")) {
                answerLinesWithoutCandidates.add("c=IN IP4 0.0.0.0")
            } else {
                answerLinesWithoutCandidates.add(trimmed)
            }
        }

        val cleanSdpAnswer = answerLinesWithoutCandidates.joinToString("\r\n") + "\r\n"

        // 1. Send CONNECTRESPONSE
        Log.i(
            TAG,
            "Sending LAN CONNECTRESPONSE (conn=${pending.connectionId}) + ${extractedCandidates.size} candidates to ${pending.clientAddress}",
        )
        sendSignalToClient(
            clientSenderId = pending.clientSenderId,
            clientAddress = pending.clientAddress,
            signalType = SIGNAL_ANSWER,
            connectionId = pending.connectionId,
            data = cleanSdpAnswer,
        )

        // 2. Send each candidate via CANDIDATEADD
        for ((index, rawCandidate) in extractedCandidates.withIndex()) {
            val formattedCandidate = ensureCandidateMetadata(rawCandidate, serverIceUfrag, index + 1)
            Log.d(TAG, "Sending LAN CANDIDATEADD #${index + 1}: $formattedCandidate")
            sendSignalToClient(
                clientSenderId = pending.clientSenderId,
                clientAddress = pending.clientAddress,
                signalType = SIGNAL_CANDIDATE,
                connectionId = pending.connectionId,
                data = formattedCandidate,
            )
        }
    }

    private fun ensureCandidateMetadata(
        candidateLine: String,
        iceUfrag: String?,
        networkId: Int,
    ): String {
        var result = if (candidateLine.startsWith("candidate:")) {
            candidateLine
        } else {
            "candidate:$candidateLine"
        }
        if (!result.contains(" generation ")) {
            result += " generation 0"
        }
        if (!result.contains(" ufrag ") && !iceUfrag.isNullOrEmpty()) {
            result += " ufrag $iceUfrag"
        }
        if (!result.contains(" network-id ")) {
            result += " network-id $networkId"
        }
        if (!result.contains(" network-cost ")) {
            result += " network-cost 0"
        }
        return result
    }

    private fun buildFullIceSdpOffer(
        rawOffer: String,
        trickleCandidates: List<String>,
    ): String {
        val lines = rawOffer.replace("\r\n", "\n").split("\n").map { it.trimEnd('\r') }.filter { it.isNotEmpty() }
        val hasInlineCandidates = lines.any { it.startsWith("a=candidate:") }
        if (hasInlineCandidates || trickleCandidates.isEmpty()) {
            return lines.joinToString("\r\n") + "\r\n"
        }

        // Find the first UDP IPv4 candidate to populate m=application <port> and c=IN IP4 <ip>
        var defaultIp: String? = null
        var defaultPort: String? = null
        for (cand in trickleCandidates) {
            val clean = cand.removePrefix("a=").removePrefix("candidate:").trim()
            val tokens = clean.split(Regex("\\s+"))
            if (tokens.size >= 8 && tokens[2].equals("udp", ignoreCase = true)) {
                val ip = tokens[4]
                val port = tokens[5]
                if (!ip.contains(":")) {
                    defaultIp = ip
                    defaultPort = port
                    break
                }
            }
        }

        val outLines = mutableListOf<String>()
        var candidatesInserted = false

        for (line in lines) {
            when {
                line.startsWith("m=application ") && (defaultPort != null) -> {
                    val parts = line.split(" ")
                    if (parts.size >= 4) {
                        outLines.add("m=application $defaultPort ${parts.subList(2, parts.size).joinToString(" ")}")
                    } else {
                        outLines.add(line)
                    }
                }
                line.startsWith("c=IN IP4 ") && (defaultIp != null) -> {
                    outLines.add("c=IN IP4 $defaultIp")
                    if (!candidatesInserted) {
                        for (cand in trickleCandidates) {
                            val clean = cand.removePrefix("a=").trim()
                            val withPrefix = if (clean.startsWith("candidate:")) "a=$clean" else "a=candidate:$clean"
                            outLines.add(withPrefix)
                        }
                        candidatesInserted = true
                    }
                }
                else -> {
                    outLines.add(line)
                }
            }
        }

        if (!candidatesInserted) {
            for (cand in trickleCandidates) {
                val clean = cand.removePrefix("a=").trim()
                val withPrefix = if (clean.startsWith("candidate:")) "a=$clean" else "a=candidate:$clean"
                outLines.add(withPrefix)
            }
        }

        return outLines.joinToString("\r\n") + "\r\n"
    }

    private fun sendSignalToClient(
        clientSenderId: Long,
        clientAddress: InetSocketAddress,
        signalType: String,
        connectionId: String,
        data: String,
    ) {
        val targetAddr = clientAddresses[clientSenderId] ?: clientAddress
        val signalString = "$signalType $connectionId $data"
        val packetBytes = encodeMessagePacket(
            senderId = localServerNetworkId,
            recipientId = clientSenderId,
            data = signalString,
        )
        sendDiscoveryUdp(ByteBuffer.wrap(packetBytes), targetAddr)
    }

    private fun refreshDefaultStatusAsync(host: String, port: Int) {
        val pool = scheduler ?: return
        if (!isFetchingDefault.compareAndSet(false, true)) return

        pool.execute {
            try {
                val payload = fetchServerDataBlocking(host, port, clientSenderId = 0L)
                if (payload != null) {
                    defaultCachedPayload = payload
                    httpEndpointReachable = true
                } else {
                    httpEndpointReachable = false
                }
            } finally {
                isFetchingDefault.set(false)
            }
        }
    }

    private fun refreshClientStatusAsync(
        host: String,
        port: Int,
        clientSenderId: Long,
        replyImmediatelyTo: InetSocketAddress?,
    ) {
        val pool = scheduler ?: return
        if (!activeFetches.add(clientSenderId)) return

        pool.execute {
            try {
                val payload = fetchServerDataBlocking(host, port, clientSenderId)
                if (payload != null) {
                    val now = System.currentTimeMillis()
                    clientStatusCache[clientSenderId] = CachedServerStatus(payload, now)
                    defaultCachedPayload = payload
                    httpEndpointReachable = true
                    if (replyImmediatelyTo != null) {
                        val responsePacket = encodeResponsePacket(localServerNetworkId, payload)
                        sendDiscoveryUdp(ByteBuffer.wrap(responsePacket), replyImmediatelyTo)
                    }
                } else {
                    httpEndpointReachable = false
                }
            } finally {
                activeFetches.remove(clientSenderId)
            }
        }
    }

    private fun fetchServerDataBlocking(host: String, port: Int, clientSenderId: Long): ByteArray? {
        var conn: HttpURLConnection? = null
        return try {
            val query = if (clientSenderId != 0L) {
                "?version=1.26.60&protocol=2225&platform=7&id=${java.lang.Long.toUnsignedString(clientSenderId)}"
            } else {
                ""
            }
            val url = URL("http://$host:$port/v1/join$query")
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 3_500
                readTimeout = 3_500
                useCaches = false
                setRequestProperty("User-Agent", "libhttpclient/1.0.0.0")
                setRequestProperty("Connection", "close")
            }

            val code = conn.responseCode
            if (code == HttpURLConnection.HTTP_OK) {
                val body = conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
                val json = JSONObject(body)
                val binaryServerData = marshalServerDataFromJson(json, host, port)
                Log.d(
                    TAG,
                    "Fetched /v1/join from $host:$port (client=${java.lang.Long.toUnsignedString(clientSenderId)}) -> name='${json.optString("name")}', level='${json.optString("level")}'",
                )
                binaryServerData
            } else {
                null
            }
        } catch (e: Exception) {
            Log.d(TAG, "Could not fetch /v1/join from $host:$port (${e.message})")
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun marshalServerDataFromJson(json: JSONObject, host: String, port: Int): ByteArray {
        val serverName = json.optString("name", "MC Bedrock Proxy ($host)")
        val protocol = json.optInt("protocol", 2225)
        val version = json.optString("version", "1.26.60")
        val levelName = json.optString("level", "$host:$port")
        val players = json.optInt("players", 1)
        val maxPlayers = json.optInt("maxPlayers", 20)
        val gameType = json.optInt("gameType", 0)
        val editor = json.optBoolean("editor", false)
        val hardcore = json.optBoolean("hardcore", false)
        val onlineAuth = json.optBoolean("onlineAuth", true)
        val selfSignedAuth = json.optBoolean("selfSignedAuth", false)
        val nonce = json.optString("nonce", "0000000000000000")

        return serializeServerData(
            serverName = serverName,
            protocol = protocol,
            version = version,
            levelName = levelName,
            playerCount = players,
            maxPlayerCount = maxPlayers,
            gameType = gameType,
            editorWorld = editor,
            hardcore = hardcore,
            acceptsOnlineAuth = onlineAuth,
            acceptsSelfSignedAuth = selfSignedAuth,
            nonce = nonce,
        )
    }

    private fun buildFallbackServerData(host: String, port: Int): ByteArray {
        return serializeServerData(
            serverName = "MC Bedrock Connector",
            protocol = 2225,
            version = "1.26.60",
            levelName = "$host:$port",
            playerCount = 1,
            maxPlayerCount = 20,
            gameType = 0,
            editorWorld = false,
            hardcore = false,
            acceptsOnlineAuth = true,
            acceptsSelfSignedAuth = true,
            nonce = "0000000000000000",
        )
    }

    private fun serializeServerData(
        serverName: String,
        protocol: Int,
        version: String,
        levelName: String,
        playerCount: Int,
        maxPlayerCount: Int,
        gameType: Int,
        editorWorld: Boolean,
        hardcore: Boolean,
        acceptsOnlineAuth: Boolean,
        acceptsSelfSignedAuth: Boolean,
        nonce: String,
    ): ByteArray {
        val out = ByteArrayOutputStream(128)
        out.write(SERVER_DATA_VERSION.toInt())
        writeString(out, serverName)
        writeVarInt32(out, protocol)
        writeString(out, version)
        writeString(out, levelName)
        writeVarInt32(out, playerCount)
        writeVarInt32(out, maxPlayerCount)
        writeVarInt32(out, gameType)
        writeBool(out, editorWorld)
        writeBool(out, hardcore)
        writeBool(out, acceptsOnlineAuth)
        writeBool(out, acceptsSelfSignedAuth)
        writeString(out, nonce)
        writeVarInt32(out, LAN_CONNECTION_TYPE)
        return out.toByteArray()
    }

    private fun encodeResponsePacket(senderId: Long, applicationData: ByteArray): ByteArray {
        val hexBytes = hexEncodeAscii(applicationData)
        val innerSize = 18 + 4 + hexBytes.size
        val totalPlaintextLen = 2 + innerSize

        val payload = ByteBuffer.allocate(totalPlaintextLen).order(ByteOrder.LITTLE_ENDIAN)
        payload.putShort(totalPlaintextLen.toShort())
        payload.putShort(ID_RESPONSE_PACKET.toShort())
        payload.putLong(senderId)
        payload.putLong(0L) // 8-byte padding
        payload.putInt(hexBytes.size)
        payload.put(hexBytes)

        return wrapEncryptedDiscoveryPacket(payload.array())
    }

    private fun encodeMessagePacket(senderId: Long, recipientId: Long, data: String): ByteArray {
        val dataBytes = data.toByteArray(StandardCharsets.UTF_8)
        val declaredDataLen = min(dataBytes.size, MAX_DECLARED_MESSAGE_DATA)
        val declaredTotalLen = MESSAGE_HEADER_LENGTH + declaredDataLen
        val actualPlaintextLen = MESSAGE_HEADER_LENGTH + dataBytes.size

        val payload = ByteBuffer.allocate(actualPlaintextLen).order(ByteOrder.LITTLE_ENDIAN)
        payload.putShort(declaredTotalLen.toShort())
        payload.putShort(ID_MESSAGE_PACKET.toShort())
        payload.putLong(senderId)
        payload.putLong(0L) // 8-byte padding
        payload.putLong(recipientId)
        payload.putInt(declaredDataLen)
        payload.put(dataBytes)

        return wrapEncryptedDiscoveryPacket(payload.array())
    }

    private fun wrapEncryptedDiscoveryPacket(plaintext: ByteArray): ByteArray {
        val hmac = computeHmacSha256(plaintext)
        val encrypted = encryptAesEcb(plaintext)
        val result = ByteArray(hmac.size + encrypted.size)
        System.arraycopy(hmac, 0, result, 0, hmac.size)
        System.arraycopy(encrypted, 0, result, hmac.size, encrypted.size)
        return result
    }

    @SuppressLint("GetInstance")
    private fun encryptAesEcb(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, AES_KEY_SPEC)
        return cipher.doFinal(plaintext)
    }

    @SuppressLint("GetInstance")
    private fun decryptAesEcb(ciphertext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, AES_KEY_SPEC)
        return cipher.doFinal(ciphertext)
    }

    private fun computeHmacSha256(data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(HMAC_KEY_SPEC)
        return mac.doFinal(data)
    }

    private fun hexEncodeAscii(bytes: ByteArray): ByteArray {
        val out = ByteArray(bytes.size * 2)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xFF
            out[i * 2] = HEX_CHARS[v ushr 4].code.toByte()
            out[i * 2 + 1] = HEX_CHARS[v and 0x0F].code.toByte()
        }
        return out
    }

    private fun writeBool(out: ByteArrayOutputStream, value: Boolean) {
        out.write(if (value) 1 else 0)
    }

    private fun writeString(out: ByteArrayOutputStream, value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        writeVarUInt32(out, bytes.size)
        out.write(bytes)
    }

    private fun writeVarInt32(out: ByteArrayOutputStream, value: Int) {
        val zigZag = (value shl 1) xor (value shr 31)
        writeVarUInt32(out, zigZag)
    }

    private fun writeVarUInt32(out: ByteArrayOutputStream, value: Int) {
        var u = value
        while ((u and 0xFFFFFF80.toInt()) != 0) {
            out.write((u and 0x7F) or 0x80)
            u = u ushr 7
        }
        out.write(u and 0x7F)
    }
}
