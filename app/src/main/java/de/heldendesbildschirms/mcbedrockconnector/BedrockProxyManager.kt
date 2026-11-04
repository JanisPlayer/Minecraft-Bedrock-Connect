package de.heldendesbildschirms.mcbedrockconnector

import android.content.Context
import android.util.Log
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.UnknownHostException
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

object BedrockProxyManager {
    private const val TAG = "BedrockProxyManager"
    const val DEFAULT_LOCAL_PORT = 19132
    const val NETHERNET_DISCOVERY_PORT = 7551
    private const val MAX_UDP_PACKET_SIZE = 65507
    private const val TCP_BUFFER_SIZE = 32768
    private const val UDP_BUFFER_SIZE = 2 * 1024 * 1024 // 2 MB socket buffer for zero packet loss
    private const val UDP_SESSION_TIMEOUT_MS = 120_000L // 2 minutes inactivity timeout
    private const val TCP_CONNECT_TIMEOUT_MS = 10_000

    private val isRunning = AtomicBoolean(false)

    @Volatile
    var destinationHost: String = "104.238.130.180"
        private set

    @Volatile
    var destinationPort: Int = 19132
        private set

    @Volatile
    private var resolvedTargetAddress: InetSocketAddress? = null

    private var udpSelector: Selector? = null
    private var localUdpChannel: DatagramChannel? = null
    private var discoveryUdpChannel: DatagramChannel? = null
    private var tcpServerSocket: ServerSocket? = null
    private var executor: ExecutorService? = null

    private class UdpClientSession(
        val clientAddress: InetSocketAddress,
        val localServerChannel: DatagramChannel,
        val upstreamChannel: DatagramChannel,
        val selectionKey: SelectionKey,
        @Volatile var lastActiveTime: Long = System.currentTimeMillis(),
    ) {
        fun close() {
            try {
                selectionKey.cancel()
            } catch (_: Exception) {
            }
            try {
                upstreamChannel.close()
            } catch (_: Exception) {
            }
        }
    }

    private val udpSessions = ConcurrentHashMap<InetSocketAddress, UdpClientSession>()
    private val activeTcpSockets = ConcurrentHashMap.newKeySet<Socket>()

    @Volatile
    private var appContext: Context? = null

    @Volatile
    var experimentalLanBridgeEnabled: Boolean = false
        private set

    private val netherNetLanBridge = NetherNetLanBridge(
        contextProvider = { appContext },
        sendDiscoveryUdp = { packetBuffer, recipientAddress ->
            try {
                discoveryUdpChannel?.send(packetBuffer, recipientAddress)
            } catch (e: IOException) {
                Log.w(TAG, "Failed to send LAN discovery packet to $recipientAddress: ${e.message}")
            }
        },
    )

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun isProxyRunning(): Boolean = isRunning.get()

    @Synchronized
    fun setExperimentalLanBridge(enabled: Boolean) {
        val changed = experimentalLanBridgeEnabled != enabled
        experimentalLanBridgeEnabled = enabled
        if (changed && isRunning.get()) {
            if (enabled) {
                netherNetLanBridge.start(destinationHost, destinationPort)
                Log.i(TAG, "Experimental NetherNet LAN Bridge enabled")
            } else {
                netherNetLanBridge.stop()
                Log.i(TAG, "Experimental NetherNet LAN Bridge disabled")
            }
        }
    }

    @Synchronized
    fun updateDestination(host: String, port: Int, resetConnections: Boolean = true) {
        destinationHost = host.trim()
        destinationPort = port
        if (experimentalLanBridgeEnabled) {
            netherNetLanBridge.onDestinationChanged(destinationHost, destinationPort)
        }
        val executorToUse = executor
        if ((executorToUse != null) && !executorToUse.isShutdown) {
            executorToUse.execute {
                resolveTargetBlocking()
                if (resetConnections && isRunning.get()) {
                    clearActiveSessions()
                }
            }
        } else {
            Thread {
                resolveTargetBlocking()
            }.start()
        }
    }

    private fun resolveTargetBlocking(): InetSocketAddress? {
        return try {
            val inetAddress = InetAddress.getByName(destinationHost)
            val socketAddr = InetSocketAddress(inetAddress, destinationPort)
            resolvedTargetAddress = socketAddr
            Log.d(TAG, "Resolved destination $destinationHost:$destinationPort -> $socketAddr")
            socketAddr
        } catch (e: UnknownHostException) {
            Log.e(TAG, "Failed to resolve hostname: $destinationHost", e)
            resolvedTargetAddress
        }
    }

    @Synchronized
    fun start(
        host: String = destinationHost,
        port: Int = destinationPort,
        localPort: Int = DEFAULT_LOCAL_PORT,
        onStateChanged: ((Boolean) -> Unit)? = null,
    ) {
        destinationHost = host.trim()
        destinationPort = port

        if (isRunning.getAndSet(true)) {
            updateDestination(destinationHost, destinationPort, resetConnections = true)
            onStateChanged?.invoke(true)
            return
        }

        val pool = Executors.newCachedThreadPool()
        executor = pool

        if (experimentalLanBridgeEnabled) {
            netherNetLanBridge.start(destinationHost, destinationPort)
        }

        pool.execute {
            resolveTargetBlocking()
        }

        // Start High-Performance NIO UDP Proxy (Ports 19132 + 7551)
        pool.execute {
            runUdpProxyLoop(localPort)
        }

        // Start Low-Latency TCP Proxy on the same port (19132)
        pool.execute {
            runTcpProxyLoop(localPort, pool)
        }

        onStateChanged?.invoke(true)
        Log.i(TAG, "Started UDP (19132 & 7551) + TCP (19132) Bedrock Proxy -> $destinationHost:$destinationPort")
    }

    @Synchronized
    fun stop(onStateChanged: ((Boolean) -> Unit)? = null) {
        if (!isRunning.getAndSet(false)) {
            onStateChanged?.invoke(false)
            return
        }

        netherNetLanBridge.stop()

        try {
            udpSelector?.wakeup()
        } catch (_: Exception) {
        }

        try {
            localUdpChannel?.close()
        } catch (_: Exception) {
        }
        localUdpChannel = null

        try {
            discoveryUdpChannel?.close()
        } catch (_: Exception) {
        }
        discoveryUdpChannel = null

        try {
            tcpServerSocket?.close()
        } catch (_: Exception) {
        }
        tcpServerSocket = null

        clearActiveSessions()

        try {
            udpSelector?.close()
        } catch (_: Exception) {
        }
        udpSelector = null

        executor?.shutdownNow()
        executor = null

        onStateChanged?.invoke(false)
        Log.i(TAG, "Stopped UDP + TCP Bedrock Proxy")
    }

    @Synchronized
    fun restart(
        host: String = destinationHost,
        port: Int = destinationPort,
        localPort: Int = DEFAULT_LOCAL_PORT,
        onStateChanged: ((Boolean) -> Unit)? = null,
    ) {
        stop()
        start(host, port, localPort, onStateChanged)
    }

    private fun clearActiveSessions() {
        for (session in udpSessions.values) {
            session.close()
        }
        udpSessions.clear()
        udpSelector?.wakeup()

        for (socket in activeTcpSockets) {
            try {
                socket.close()
            } catch (_: Exception) {
            }
        }
        activeTcpSockets.clear()
    }

    private fun runUdpProxyLoop(localPort: Int) {
        var selector: Selector? = null
        var mainChannel: DatagramChannel? = null
        var lanDiscoveryChannel: DatagramChannel? = null

        try {
            selector = Selector.open()
            udpSelector = selector

            // 1. Bind UDP 19132 (Gameplay & RakNet)
            mainChannel = DatagramChannel.open().apply {
                configureBlocking(false)
                socket().reuseAddress = true
                try {
                    socket().receiveBufferSize = UDP_BUFFER_SIZE
                    socket().sendBufferSize = UDP_BUFFER_SIZE
                } catch (_: Exception) {
                }
                socket().bind(InetSocketAddress("0.0.0.0", localPort))
                register(selector, SelectionKey.OP_READ, null)
            }
            localUdpChannel = mainChannel

            // 2. Bind UDP 7551 (NetherNet LAN Discovery)
            try {
                lanDiscoveryChannel = DatagramChannel.open().apply {
                    configureBlocking(false)
                    socket().reuseAddress = true
                    socket().broadcast = true
                    socket().bind(InetSocketAddress("0.0.0.0", NETHERNET_DISCOVERY_PORT))
                    register(selector, SelectionKey.OP_READ, "DISCOVERY_7551")
                }
                discoveryUdpChannel = lanDiscoveryChannel
                Log.i(TAG, "Bound NetherNet LAN discovery UDP port $NETHERNET_DISCOVERY_PORT")
            } catch (e: Exception) {
                Log.w(TAG, "Could not bind UDP $NETHERNET_DISCOVERY_PORT: ${e.message}")
            }

            val packetBuffer = ByteBuffer.allocateDirect(MAX_UDP_PACKET_SIZE)
            var lastCleanupTime = System.currentTimeMillis()

            while (isRunning.get()) {
                val readyCount = selector.select(1000L)
                if (!isRunning.get()) break

                val now = System.currentTimeMillis()

                if (readyCount > 0) {
                    val selectedKeys = selector.selectedKeys().iterator()
                    while (selectedKeys.hasNext()) {
                        val key = selectedKeys.next()
                        selectedKeys.remove()

                        if (!key.isValid) continue

                        if (key.isReadable) {
                            when (val attachment = key.attachment()) {
                                null -> {
                                    handleClientToServerUdp(
                                        serverChannel = mainChannel,
                                        targetPortOverride = null,
                                        selector = selector,
                                        buffer = packetBuffer,
                                        now = now,
                                    )
                                }
                                "DISCOVERY_7551" -> {
                                    val discChannel = lanDiscoveryChannel ?: continue
                                    handleClientToServerUdp(
                                        serverChannel = discChannel,
                                        targetPortOverride = NETHERNET_DISCOVERY_PORT,
                                        selector = selector,
                                        buffer = packetBuffer,
                                        now = now,
                                    )
                                }
                                is UdpClientSession -> {
                                    handleServerToClientUdp(attachment, packetBuffer, now)
                                }
                            }
                        }
                    }
                }

                // Periodically clean up inactive UDP sessions
                if ((now - lastCleanupTime) > 15_000L) {
                    lastCleanupTime = now
                    cleanupExpiredUdpSessions(now)
                }
            }
        } catch (e: Exception) {
            if (isRunning.get()) {
                Log.e(TAG, "UDP Proxy error: ${e.message}", e)
            }
        } finally {
            for (session in udpSessions.values) {
                session.close()
            }
            udpSessions.clear()
            try {
                mainChannel?.close()
            } catch (_: Exception) {
            }
            try {
                lanDiscoveryChannel?.close()
            } catch (_: Exception) {
            }
            try {
                selector?.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun handleClientToServerUdp(
        serverChannel: DatagramChannel,
        targetPortOverride: Int?,
        selector: Selector,
        buffer: ByteBuffer,
        now: Long,
    ) {
        while (isRunning.get()) {
            buffer.clear()
            val sender = try {
                serverChannel.receive(buffer) as? InetSocketAddress
            } catch (_: IOException) {
                null
            } ?: break

            buffer.flip()
            val size = buffer.remaining()
            if (size == 0) continue

            if (targetPortOverride == NETHERNET_DISCOVERY_PORT) {
                if (experimentalLanBridgeEnabled) {
                    val rawBytes = ByteArray(size)
                    buffer.get(rawBytes)
                    netherNetLanBridge.handleIncomingPacket(
                        rawBytes = rawBytes,
                        senderAddress = sender,
                        targetHost = destinationHost,
                        targetPort = destinationPort,
                    )
                }
                continue
            }

            val baseTarget = resolvedTargetAddress ?: resolveTargetBlocking() ?: continue
            val target = if (targetPortOverride != null) {
                InetSocketAddress(baseTarget.address, targetPortOverride)
            } else {
                baseTarget
            }

            var session = udpSessions[sender]
            if ((session == null) || !session.upstreamChannel.isOpen) {
                Log.d(TAG, "New UDP session from $sender -> $target ($size bytes)")
                session = createUdpSession(sender, serverChannel, target, selector, now)
                if (session != null) {
                    udpSessions[sender] = session
                }
            }

            if (session != null) {
                session.lastActiveTime = now
                try {
                    session.upstreamChannel.write(buffer)
                } catch (e: IOException) {
                    Log.w(TAG, "UDP upstream write error for $sender -> $target: ${e.message}")
                    udpSessions.remove(sender)?.close()
                }
            }
        }
    }

    private fun createUdpSession(
        clientAddress: InetSocketAddress,
        localServerChannel: DatagramChannel,
        targetAddress: InetSocketAddress,
        selector: Selector,
        now: Long,
    ): UdpClientSession? {
        return try {
            val upstream = DatagramChannel.open()
            upstream.configureBlocking(false)
            try {
                upstream.socket().receiveBufferSize = UDP_BUFFER_SIZE
                upstream.socket().sendBufferSize = UDP_BUFFER_SIZE
                upstream.socket().trafficClass = 0x10 // IPTOS_LOWDELAY
            } catch (_: Exception) {
            }
            upstream.connect(targetAddress)
            val key = upstream.register(selector, SelectionKey.OP_READ)
            val session = UdpClientSession(clientAddress, localServerChannel, upstream, key, now)
            key.attach(session)
            session
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create upstream UDP session for $clientAddress", e)
            null
        }
    }

    private fun handleServerToClientUdp(
        session: UdpClientSession,
        buffer: ByteBuffer,
        now: Long,
    ) {
        while (isRunning.get()) {
            buffer.clear()
            val bytesRead = try {
                session.upstreamChannel.read(buffer)
            } catch (_: IOException) {
                -1
            }

            if (bytesRead <= 0) {
                if (bytesRead < 0) {
                    udpSessions.remove(session.clientAddress)?.close()
                }
                break
            }

            buffer.flip()
            session.lastActiveTime = now
            try {
                session.localServerChannel.send(buffer, session.clientAddress)
            } catch (_: IOException) {
            }
        }
    }

    private fun cleanupExpiredUdpSessions(now: Long) {
        val iterator = udpSessions.entries.iterator()
        while (iterator.hasNext()) {
            val (_, session) = iterator.next()
            if ((now - session.lastActiveTime) > UDP_SESSION_TIMEOUT_MS) {
                session.close()
                iterator.remove()
            }
        }
    }

    private fun runTcpProxyLoop(localPort: Int, pool: ExecutorService) {
        var serverSocket: ServerSocket? = null
        try {
            serverSocket = ServerSocket()
            tcpServerSocket = serverSocket
            serverSocket.reuseAddress = true
            serverSocket.bind(InetSocketAddress("0.0.0.0", localPort))

            while (isRunning.get()) {
                val clientSocket = try {
                    serverSocket.accept()
                } catch (e: IOException) {
                    if (isRunning.get()) {
                        Log.e(TAG, "TCP accept error: ${e.message}")
                    }
                    break
                }

                pool.execute {
                    handleTcpConnection(clientSocket, pool)
                }
            }
        } catch (e: Exception) {
            if (isRunning.get()) {
                Log.e(TAG, "TCP Proxy loop error: ${e.message}", e)
            }
        } finally {
            try {
                serverSocket?.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun handleTcpConnection(clientSocket: Socket, pool: ExecutorService) {
        var upstreamSocket: Socket? = null
        activeTcpSockets.add(clientSocket)

        try {
            clientSocket.tcpNoDelay = true
            clientSocket.keepAlive = true
            try {
                clientSocket.receiveBufferSize = 262144
                clientSocket.sendBufferSize = 262144
                clientSocket.trafficClass = 0x10 // IPTOS_LOWDELAY
            } catch (_: Exception) {
            }

            val target = resolvedTargetAddress ?: resolveTargetBlocking()
            if (target == null) {
                clientSocket.close()
                return
            }

            Log.i(TAG, "Accepted TCP connection from ${clientSocket.remoteSocketAddress} -> forwarding to $target")

            upstreamSocket = Socket()
            activeTcpSockets.add(upstreamSocket)
            upstreamSocket.tcpNoDelay = true
            upstreamSocket.keepAlive = true
            try {
                upstreamSocket.receiveBufferSize = 262144
                upstreamSocket.sendBufferSize = 262144
                upstreamSocket.trafficClass = 0x10 // IPTOS_LOWDELAY
            } catch (_: Exception) {
            }

            upstreamSocket.connect(target, TCP_CONNECT_TIMEOUT_MS)
            Log.d(TAG, "Connected TCP upstream to $target for client ${clientSocket.remoteSocketAddress}")

            val remote = upstreamSocket
            // Pipe Server -> Client in separate thread
            pool.execute {
                pipeTcpStream(remote, clientSocket, "Server->Client")
            }

            // Pipe Client -> Server in current thread
            pipeTcpStream(clientSocket, remote, "Client->Server")
        } catch (e: Exception) {
            Log.w(TAG, "TCP connection error (${clientSocket.remoteSocketAddress}): ${e.message}")
        } finally {
            activeTcpSockets.remove(clientSocket)
            try {
                clientSocket.close()
            } catch (_: Exception) {
            }
            if (upstreamSocket != null) {
                activeTcpSockets.remove(upstreamSocket)
                try {
                    upstreamSocket.close()
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun pipeTcpStream(source: Socket, sink: Socket, direction: String) {
        val buffer = ByteArray(TCP_BUFFER_SIZE)
        var totalBytes = 0L
        try {
            val input: InputStream = source.getInputStream()
            val output: OutputStream = sink.getOutputStream()
            while (isRunning.get() && !source.isClosed && !sink.isClosed) {
                val read = input.read(buffer)
                if (read == -1) break
                totalBytes += read
                output.write(buffer, 0, read)
                if (input.available() == 0) {
                    output.flush()
                }
            }
        } catch (_: IOException) {
        } finally {
            Log.d(TAG, "TCP stream $direction closed after $totalBytes bytes")
            try {
                sink.shutdownOutput()
            } catch (_: Exception) {
            }
            try {
                source.close()
            } catch (_: Exception) {
            }
            try {
                sink.close()
            } catch (_: Exception) {
            }
        }
    }
}
