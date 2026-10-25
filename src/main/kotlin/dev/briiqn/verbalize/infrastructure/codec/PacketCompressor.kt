package dev.briiqn.verbalize.infrastructure.codec

import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * Wire framing: byte 0 = 0x00 raw | 0x01 deflate-raw. Deflater/Inflater
 * instances are pooled per thread and explicitly reset — never leaked.
 */
object PacketCompressor {
    private const val HEADER_RAW: Byte = 0x00
    private const val HEADER_DEFLATE: Byte = 0x01
    private const val COMPRESSION_MIN_BYTES = 256

    private val deflaters = ThreadLocal.withInitial { Deflater(Deflater.DEFAULT_COMPRESSION, true) }
    private val inflaters = ThreadLocal.withInitial { Inflater(true) }

    fun pack(payload: ByteArray, isAudio: Boolean = false): ByteArray {
        if (isAudio || payload.size < COMPRESSION_MIN_BYTES) {
            val out = ByteArray(payload.size + 1)
            out[0] = HEADER_RAW
            payload.copyInto(out, 1)
            return out
        }
        val deflater = deflaters.get()
        deflater.reset()
        deflater.setInput(payload)
        deflater.finish()
        val buffer = ByteArray(payload.size + 64)
        val out = ByteArrayOutputStream(payload.size / 2 + 16)
        out.write(HEADER_DEFLATE.toInt())
        while (!deflater.finished()) {
            val n = deflater.deflate(buffer)
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    fun unpack(frame: ByteArray): ByteArray {
        require(frame.isNotEmpty()) { "empty frame" }
        val body = frame.copyOfRange(1, frame.size)
        if (frame[0] == HEADER_RAW) return body
        val inflater = inflaters.get()
        inflater.reset()
        inflater.setInput(body)
        val buffer = ByteArray(8192)
        val out = ByteArrayOutputStream(body.size * 3 + 16)
        while (!inflater.finished()) {
            val n = inflater.inflate(buffer)
            if (n == 0 && inflater.needsInput()) break
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }
}
