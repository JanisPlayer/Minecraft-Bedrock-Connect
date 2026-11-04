package de.heldendesbildschirms.mcbedrockconnector

import android.content.Context
import android.util.Log
import org.cloudburstmc.math.vector.Vector2f
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.math.vector.Vector3i
import org.cloudburstmc.nbt.NbtList
import org.cloudburstmc.nbt.NbtMap
import org.cloudburstmc.nbt.NbtType
import org.cloudburstmc.protocol.bedrock.data.AuthoritativeMovementMode
import org.cloudburstmc.protocol.bedrock.data.ChatRestrictionLevel
import org.cloudburstmc.protocol.bedrock.data.GamePublishSetting
import org.cloudburstmc.protocol.bedrock.data.GameType
import org.cloudburstmc.protocol.bedrock.data.PacketCompressionAlgorithm
import org.cloudburstmc.protocol.bedrock.data.PlayerPermission
import org.cloudburstmc.protocol.bedrock.data.SpawnBiomeType
import org.cloudburstmc.protocol.bedrock.data.biome.BiomeDefinitions
import org.cloudburstmc.protocol.bedrock.data.editor.EditorLevelMigrationVersion
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket
import org.cloudburstmc.protocol.bedrock.packet.BiomeDefinitionListPacket
import org.cloudburstmc.protocol.bedrock.packet.ClientCacheStatusPacket
import org.cloudburstmc.protocol.bedrock.packet.LoginPacket
import org.cloudburstmc.protocol.bedrock.packet.NetworkSettingsPacket
import org.cloudburstmc.protocol.bedrock.packet.PlayStatusPacket
import org.cloudburstmc.protocol.bedrock.packet.RequestNetworkSettingsPacket
import org.cloudburstmc.protocol.bedrock.packet.ResourcePackClientResponsePacket
import org.cloudburstmc.protocol.bedrock.packet.ResourcePackStackPacket
import org.cloudburstmc.protocol.bedrock.packet.ResourcePacksInfoPacket
import org.cloudburstmc.protocol.bedrock.packet.StartGamePacket
import org.cloudburstmc.protocol.bedrock.packet.TransferPacket
import org.cloudburstmc.protocol.common.util.OptionalBoolean
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Handles a local NetherNet WebRTC connection from a LAN client (console/PC)
 * and issues an immediate Bedrock TransferPacket to redirect the client to the real target server.
 */
class LocalTransferSession(
    context: Context,
    private val clientSenderId: Long,
    private val connectionId: String,
    private val targetHost: String,
    private val targetPort: Int,
    private val sendLanSignal: (signalType: String, connectionId: String, payload: String) -> Unit,
    private val onClosed: (connectionId: String) -> Unit,
) {
    companion object {
        private const val TAG = "LocalTransferSession"
        private var factoryInitialized = false
        private var peerFactory: PeerConnectionFactory? = null

        @Synchronized
        fun getFactory(context: Context): PeerConnectionFactory {
            if (!factoryInitialized) {
                PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                        .createInitializationOptions(),
                )
                factoryInitialized = true
            }
            return peerFactory ?: PeerConnectionFactory.builder()
                .createPeerConnectionFactory()
                .also { peerFactory = it }
        }
    }

    private val framer = NetherNetPacketFramer()
    private val isClosed = AtomicBoolean(false)
    private var peerConnection: PeerConnection? = null
    private var reliableChannel: DataChannel? = null

    init {
        val factory = getFactory(context)
        val rtcConfig = PeerConnection.RTCConfiguration(emptyList()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        peerConnection = factory.createPeerConnection(
            rtcConfig,
            object : PeerConnection.Observer {
                override fun onIceCandidate(candidate: IceCandidate) {
                    val sdpCandidate = candidate.sdp.removePrefix("a=").trim()
                    Log.d(TAG, "Local WebRTC gathered ICE candidate: $sdpCandidate")
                    sendLanSignal("CANDIDATEADD", connectionId, sdpCandidate)
                }

                override fun onDataChannel(channel: DataChannel) {
                    Log.i(TAG, "Incoming WebRTC DataChannel: ${channel.label()}")
                    if (channel.label() == "ReliableDataChannel") {
                        reliableChannel = channel
                        attachDataChannelListener(channel)
                    }
                }

                override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
                override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                    Log.i(TAG, "ICE Connection state changed: $state")
                    if (state == PeerConnection.IceConnectionState.DISCONNECTED ||
                        state == PeerConnection.IceConnectionState.FAILED
                    ) {
                        close()
                    }
                }
                override fun onIceConnectionReceivingChange(receiving: Boolean) {}
                override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
                override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
                override fun onAddStream(stream: MediaStream?) {}
                override fun onRemoveStream(stream: MediaStream?) {}
                override fun onRenegotiationNeeded() {}
            },
        )
    }

    fun acceptOffer(sdpOffer: String) {
        val pc = peerConnection ?: return
        val remoteDesc = SessionDescription(SessionDescription.Type.OFFER, sdpOffer)
        pc.setRemoteDescription(
            object : SimpleSdpObserver() {
                override fun onSetSuccess() {
                    pc.createAnswer(
                        object : SimpleSdpObserver() {
                            override fun onCreateSuccess(answer: SessionDescription) {
                                pc.setLocalDescription(
                                    object : SimpleSdpObserver() {
                                        override fun onSetSuccess() {
                                            Log.i(TAG, "Local SDP answer created for conn=$connectionId")
                                            sendLanSignal("CONNECTRESPONSE", connectionId, answer.description)
                                        }
                                    },
                                    answer,
                                )
                            }
                        },
                        MediaConstraints(),
                    )
                }
            },
            remoteDesc,
        )
    }

    fun addRemoteCandidate(rawCandidate: String) {
        val sdpLine = if (rawCandidate.startsWith("candidate:")) rawCandidate else "candidate:$rawCandidate"
        peerConnection?.addIceCandidate(IceCandidate("0", 0, sdpLine))
    }

    private fun attachDataChannelListener(channel: DataChannel) {
        channel.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) {}
            override fun onStateChange() {
                Log.i(TAG, "ReliableDataChannel state: ${channel.state()}")
            }

            override fun onMessage(buffer: DataChannel.Buffer) {
                val bytes = ByteArray(buffer.data.remaining())
                buffer.data.get(bytes)
                val packets = try {
                    framer.onDataChannelMessage(bytes)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to decode Bedrock packet: ${e.message}")
                    emptyList()
                }
                packets.forEach { handleBedrockPacket(it) }
            }
        })
    }

    private fun handleBedrockPacket(packet: BedrockPacket) {
        Log.d(TAG, "Received Bedrock packet: ${packet.javaClass.simpleName}")
        when (packet) {
            is RequestNetworkSettingsPacket -> {
                val settings = NetworkSettingsPacket().apply {
                    compressionThreshold = 1
                    compressionAlgorithm = PacketCompressionAlgorithm.ZLIB
                    isClientThrottleEnabled = false
                    clientThrottleThreshold = 0
                    clientThrottleScalar = 0f
                }
                sendPacket(settings)
                framer.compressionEnabled = true
            }

            is LoginPacket -> {
                sendPacket(PlayStatusPacket().apply {
                    status = PlayStatusPacket.Status.LOGIN_SUCCESS
                })
                sendPacket(ResourcePacksInfoPacket().apply {
                    isForcedToAccept = false
                    isHasAddonPacks = false
                    isScriptingEnabled = false
                    isVibrantVisualsForceDisabled = false
                    worldTemplateId = UUID(0L, 0L)
                    worldTemplateVersion = ""
                })
            }

            is ClientCacheStatusPacket -> {
                Log.d(TAG, "ClientCacheStatusPacket received: isSupported=${packet.isSupported}")
            }

            is ResourcePackClientResponsePacket -> {
                Log.d(TAG, "ResourcePackClientResponsePacket: status=${packet.status}")
                when (packet.status) {
                    ResourcePackClientResponsePacket.Status.HAVE_ALL_PACKS -> {
                        sendPacket(ResourcePackStackPacket().apply {
                            isForcedToAccept = false
                            gameVersion = "*"
                        })
                    }
                    ResourcePackClientResponsePacket.Status.COMPLETED -> {
                        try {
                            sendPacket(buildMinimalStartGame())
                            sendPacket(BiomeDefinitionListPacket().apply {
                                biomes = BiomeDefinitions(emptyMap())
                                definitions = NbtMap.EMPTY
                            })
                            sendPacket(PlayStatusPacket().apply {
                                status = PlayStatusPacket.Status.PLAYER_SPAWN
                            })
                        } catch (e: Exception) {
                            Log.w(TAG, "StartGame/Biome send failed (continuing to transfer): ${e.message}")
                        }
                        sendPacket(TransferPacket().apply {
                            address = targetHost
                            port = targetPort
                            isReloadWorld = false
                        })
                        Log.i(TAG, "Successfully sent TransferPacket -> $targetHost:$targetPort to client $clientSenderId!")
                    }
                    else -> {}
                }
            }
        }
    }

    private fun sendPacket(packet: BedrockPacket) {
        val ch = reliableChannel ?: return
        try {
            Log.d(TAG, "Sending Bedrock packet: ${packet.javaClass.simpleName}")
            for (segment in framer.encodeToSegments(packet)) {
                ch.send(DataChannel.Buffer(ByteBuffer.wrap(segment), true))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send packet ${packet.javaClass.simpleName}: ${e.message}", e)
        }
    }

    private fun buildMinimalStartGame(): StartGamePacket {
        return StartGamePacket().apply {
            uniqueEntityId = 1
            runtimeEntityId = 1
            playerGameType = GameType.SURVIVAL
            playerPosition = Vector3f.from(0f, 64f, 0f)
            rotation = Vector2f.ZERO
            seed = 0L
            spawnBiomeType = SpawnBiomeType.DEFAULT
            customBiomeName = ""
            dimensionId = 0
            generatorId = 1
            levelGameType = GameType.SURVIVAL
            difficulty = 1
            defaultSpawn = Vector3i.from(0, 64, 0)
            isAchievementsDisabled = true
            dayCycleStopTime = 0
            eduEditionOffers = 0
            isEduFeaturesEnabled = false
            educationProductionId = ""
            rainLevel = 0f
            lightningLevel = 0f
            isPlatformLockedContentConfirmed = false
            isMultiplayerGame = true
            isBroadcastingToLan = true
            xblBroadcastMode = GamePublishSetting.PUBLIC
            platformBroadcastMode = GamePublishSetting.PUBLIC
            isCommandsEnabled = true
            isTexturePacksRequired = false
            isBonusChestEnabled = false
            isStartingWithMap = false
            isTrustingPlayers = true
            defaultPlayerPermission = PlayerPermission.MEMBER
            serverChunkTickRange = 4
            isBehaviorPackLocked = false
            isResourcePackLocked = false
            isFromLockedWorldTemplate = false
            isUsingMsaGamertagsOnly = false
            isFromWorldTemplate = false
            isWorldTemplateOptionLocked = false
            isOnlySpawningV1Villagers = false
            limitedWorldWidth = 0
            limitedWorldHeight = 0
            isNetherType = false
            forceExperimentalGameplay = OptionalBoolean.empty()
            chatRestrictionLevel = ChatRestrictionLevel.NONE
            isDisablingPlayerInteractions = false
            serverId = ""
            worldId = ""
            scenarioId = ""
            ownerId = ""
            multiplayerCorrelationId = ""
            serverEngine = "MCBedrockConnector"
            levelId = "transfer"
            setLevelName("Transferring...")
            premiumWorldTemplateId = ""
            isTrial = false
            authoritativeMovementMode = AuthoritativeMovementMode.SERVER
            rewindHistorySize = 0
            isServerAuthoritativeBlockBreaking = false
            currentTick = 0L
            enchantmentSeed = 0
            playerPropertyData = NbtMap.EMPTY
            blockRegistryChecksum = 0L
            worldTemplateId = UUID(0L, 0L)
            editorLevelMigrationVersion = EditorLevelMigrationVersion.LEGACY
            blockPalette = NbtList(NbtType.COMPOUND, emptyList<NbtMap>())
            isClientSideGenerationEnabled = false
            isBlockNetworkIdsHashed = true
            vanillaVersion = "1.26.60"
        }
    }

    fun close() {
        if (!isClosed.compareAndSet(false, true)) return
        Log.i(TAG, "Closing LocalTransferSession conn=$connectionId")
        try {
            reliableChannel?.close()
        } catch (_: Exception) {}
        reliableChannel = null
        try {
            peerConnection?.close()
        } catch (_: Exception) {}
        peerConnection = null
        onClosed(connectionId)
    }

    private open class SimpleSdpObserver : SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(err: String?) {
            Log.e(TAG, "SDP create error: $err")
        }
        override fun onSetFailure(err: String?) {
            Log.e(TAG, "SDP set error: $err")
        }
    }
}
