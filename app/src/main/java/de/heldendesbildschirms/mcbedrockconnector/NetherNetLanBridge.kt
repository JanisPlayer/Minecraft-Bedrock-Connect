package de.heldendesbildschirms.mcbedrockconnector

import android.annotation.SuppressLint
import android.content.Context
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
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.min

/**
 * Bridges Minecraft: Bedrock Edition NetherNet LAN Discovery & Signaling (UDP 7551).
 *
 * For LAN clients without a Microsoft identity (consoles/local LAN join), it accepts the WebRTC connection
 * locally on the smartphone via [LocalTransferSession] and issues an immediate Bedrock TransferPacket
 * to transfer the client to the real remote server.
 */
class NetherNetLanBridge(
    private val contextProvider: () -> Context?,
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
    private val activeSessions = ConcurrentHashMap<String, LocalTransferSession>()
    private val clientAddresses = ConcurrentHashMap<Long, InetSocketAddress>()

    fun start(host: String, port: Int) {
        stop()
        val pool = Executors.newScheduledThreadPool(3)
        scheduler = pool
        refreshDefaultStatusAsync(host, port)
    }

    fun stop() {
        activeSessions.values.forEach { it.close() }
        activeSessions.clear()
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
        activeSessions.values.forEach { it.close() }
        activeSessions.clear()
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
                    buf.int // Skip 4-byte declared data length
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

        when (signalType) {
            SIGNAL_OFFER -> {
                Log.i(
                    TAG,
                    "Starting local transfer session for client ${java.lang.Long.toUnsignedString(senderId)} (conn=$connectionId) at $senderAddress",
                )
                startLocalTransferSession(
                    clientSenderId = senderId,
                    connectionId = connectionId,
                    clientAddress = senderAddress,
                    sdpOffer = signalPayload,
                    targetHost = targetHost,
                    targetPort = targetPort,
                )
            }
            SIGNAL_CANDIDATE -> {
                Log.d(TAG, "Forwarding remote candidate to local transfer session (conn=$connectionId): $signalPayload")
                activeSessions[connectionId]?.addRemoteCandidate(signalPayload.trim())
            }
            SIGNAL_ERROR -> {
                Log.w(TAG, "Received LAN CONNECTERROR (conn=$connectionId): $signalPayload")
                activeSessions.remove(connectionId)?.close()
            }
        }
    }

    private fun startLocalTransferSession(
        clientSenderId: Long,
        connectionId: String,
        clientAddress: InetSocketAddress,
        sdpOffer: String,
        targetHost: String,
        targetPort: Int,
    ) {
        val context = contextProvider()
        if (context == null) {
            Log.e(TAG, "Cannot start LocalTransferSession: Application Context is null")
            sendSignalToClient(clientSenderId, clientAddress, SIGNAL_ERROR, connectionId, "21")
            return
        }

        activeSessions.remove(connectionId)?.close()

        val session = LocalTransferSession(
            context = context,
            clientSenderId = clientSenderId,
            connectionId = connectionId,
            targetHost = targetHost,
            targetPort = targetPort,
            sendLanSignal = { type, connId, payload ->
                when (type) {
                    SIGNAL_ANSWER -> {
                        deliverLocalAnswerToLanClient(clientSenderId, clientAddress, connId, payload)
                    }
                    SIGNAL_CANDIDATE -> {
                        deliverLocalCandidateToLanClient(clientSenderId, clientAddress, connId, payload)
                    }
                    else -> {
                        sendSignalToClient(clientSenderId, clientAddress, type, connId, payload)
                    }
                }
            },
            onClosed = { closedConnId ->
                activeSessions.remove(closedConnId)
            },
        )

        activeSessions[connectionId] = session
        try {
            session.acceptOffer(sdpOffer)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to accept WebRTC SDP offer locally: ${e.message}", e)
            session.close()
            sendSignalToClient(clientSenderId, clientAddress, SIGNAL_ERROR, connectionId, "21")
        }
    }

    private fun deliverLocalAnswerToLanClient(
        clientSenderId: Long,
        clientAddress: InetSocketAddress,
        connectionId: String,
        rawSdpAnswer: String,
    ) {
        val rawLines = rawSdpAnswer.replace("\r\n", "\n").split("\n")
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

        Log.i(
            TAG,
            "Sending local CONNECTRESPONSE (conn=$connectionId) to $clientAddress",
        )
        sendSignalToClient(
            clientSenderId = clientSenderId,
            clientAddress = clientAddress,
            signalType = SIGNAL_ANSWER,
            connectionId = connectionId,
            data = cleanSdpAnswer,
        )

        for ((index, rawCandidate) in extractedCandidates.withIndex()) {
            val formattedCandidate = ensureCandidateMetadata(rawCandidate, serverIceUfrag, index + 1)
            sendSignalToClient(
                clientSenderId = clientSenderId,
                clientAddress = clientAddress,
                signalType = SIGNAL_CANDIDATE,
                connectionId = connectionId,
                data = formattedCandidate,
            )
        }
    }

    private fun deliverLocalCandidateToLanClient(
        clientSenderId: Long,
        clientAddress: InetSocketAddress,
        connectionId: String,
        rawCandidate: String,
    ) {
        val cleanCandidate = rawCandidate.removePrefix("a=").trim()
        val formatted = ensureCandidateMetadata(cleanCandidate, null, 1)
        sendSignalToClient(
            clientSenderId = clientSenderId,
            clientAddress = clientAddress,
            signalType = SIGNAL_CANDIDATE,
            connectionId = connectionId,
            data = formatted,
        )
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
        // LAN answers tell the client that self-signed / local auth is accepted
        val onlineAuth = false
        val selfSignedAuth = true
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
            acceptsOnlineAuth = false,
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
