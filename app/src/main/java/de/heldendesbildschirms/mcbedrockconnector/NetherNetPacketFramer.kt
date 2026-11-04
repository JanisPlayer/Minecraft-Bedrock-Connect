package de.heldendesbildschirms.mcbedrockconnector

import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodecHelper
import org.cloudburstmc.protocol.bedrock.codec.v2225.Bedrock_v2225
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket
import org.cloudburstmc.protocol.common.util.VarInts
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.Inflater
import kotlin.math.min

/**
 * Handles NetherNet ReliableDataChannel packet framing and segment reassembly/splitting.
 */
class NetherNetPacketFramer(
    private val codec: BedrockCodec = Bedrock_v2225.CODEC,
) {
    companion object {
        private const val MAX_SEGMENT_SIZE = 10_000
        private const val COMPRESSION_ZLIB: Byte = 0x00
        private const val COMPRESSION_NONE: Byte = 0xFF.toByte()
    }

    private val helper: BedrockCodecHelper = codec.createHelper()
    private val reassemblyBuffer = ByteArrayOutputStream()

    @Volatile
    var compressionEnabled: Boolean = false

    /**
     * Receives a raw SCTP packet from ReliableDataChannel.
     * Returns a list of decoded BedrockPackets once the last segment (0) arrives.
     */
    fun onDataChannelMessage(rawBytes: ByteArray): List<BedrockPacket> {
        if (rawBytes.isEmpty()) return emptyList()

        val remainingSegments = rawBytes[0].toInt() and 0xFF
        reassemblyBuffer.write(rawBytes, 1, rawBytes.size - 1)

        if (remainingSegments > 0) {
            return emptyList()
        }

        val fullFrame = reassemblyBuffer.toByteArray()
        reassemblyBuffer.reset()

        val decompressed = if (compressionEnabled) {
            if (fullFrame.isEmpty()) return emptyList()
            when (fullFrame[0]) {
                COMPRESSION_ZLIB -> inflateRaw(fullFrame, 1, fullFrame.size - 1)
                COMPRESSION_NONE -> fullFrame.copyOfRange(1, fullFrame.size)
                else -> inflateRaw(fullFrame, 1, fullFrame.size - 1)
            }
        } else {
            fullFrame
        }

        val packets = mutableListOf<BedrockPacket>()
        val buf = Unpooled.wrappedBuffer(decompressed)
        try {
            while (buf.isReadable) {
                val packetLength = VarInts.readUnsignedInt(buf)
                val slice = buf.readSlice(packetLength)
                val header = VarInts.readUnsignedInt(slice)
                val packetId = header and 0x3FF
                val packet = codec.tryDecode(helper, slice, packetId)
                if (packet != null) {
                    packets.add(packet)
                }
            }
        } finally {
            buf.release()
        }
        return packets
    }

    /**
     * Encodes a BedrockPacket and splits it into <= 10,000-byte chunks with segment counter.
     */
    fun encodeToSegments(packet: BedrockPacket): List<ByteArray> {
        val packetBuf: ByteBuf = Unpooled.buffer()
        val batchBuf: ByteBuf = Unpooled.buffer()
        try {
            val def = codec.getPacketDefinition(packet.javaClass)
            val packetId = def?.id ?: error("Unknown packet definition for ${packet.javaClass}")
            VarInts.writeUnsignedInt(packetBuf, packetId and 0x3FF)
            codec.tryEncode(helper, packetBuf, packet)

            VarInts.writeUnsignedInt(batchBuf, packetBuf.readableBytes())
            batchBuf.writeBytes(packetBuf)

            val rawBatch = ByteArray(batchBuf.readableBytes())
            batchBuf.readBytes(rawBatch)

            val wirePayload = if (compressionEnabled) {
                val compressed = deflateRaw(rawBatch)
                ByteArray(1 + compressed.size).apply {
                    this[0] = COMPRESSION_ZLIB
                    System.arraycopy(compressed, 0, this, 1, compressed.size)
                }
            } else {
                rawBatch
            }

            val totalSegments = (wirePayload.size + MAX_SEGMENT_SIZE - 1) / MAX_SEGMENT_SIZE
            val segments = ArrayList<ByteArray>(totalSegments)
            var offset = 0
            for (i in 0 until totalSegments) {
                val chunkLen = min(MAX_SEGMENT_SIZE, wirePayload.size - offset)
                val remaining = (totalSegments - 1) - i
                val segment = ByteArray(1 + chunkLen)
                segment[0] = remaining.toByte()
                System.arraycopy(wirePayload, offset, segment, 1, chunkLen)
                segments.add(segment)
                offset += chunkLen
            }
            return segments
        } finally {
            packetBuf.release()
            batchBuf.release()
        }
    }

    private fun deflateRaw(data: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
        return try {
            deflater.setInput(data)
            deflater.finish()
            val out = ByteArrayOutputStream(data.size)
            val tmp = ByteArray(4096)
            while (!deflater.finished()) {
                val count = deflater.deflate(tmp)
                out.write(tmp, 0, count)
            }
            out.toByteArray()
        } finally {
            deflater.end()
        }
    }

    private fun inflateRaw(data: ByteArray, offset: Int, len: Int): ByteArray {
        val inflater = Inflater(true)
        return try {
            inflater.setInput(data, offset, len)
            val out = ByteArrayOutputStream(len * 2)
            val tmp = ByteArray(4096)
            while (!inflater.finished()) {
                val count = inflater.inflate(tmp)
                if (count == 0 && inflater.needsInput()) break
                out.write(tmp, 0, count)
            }
            out.toByteArray()
        } finally {
            inflater.end()
        }
    }
}
