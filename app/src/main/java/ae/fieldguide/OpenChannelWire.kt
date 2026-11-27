package ae.fieldguide

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.zip.Inflater
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer

/** BitChat v1 signed, broadcast ANNOUNCE and MESSAGE frames. Each visit has a fresh identity. */
internal class OpenChannelWire {
    private val random = SecureRandom()
    private val signingKey = Ed25519PrivateKeyParameters(random)
    private val noiseKey = X25519PrivateKeyParameters(random).generatePublicKey().encoded
    val id: ByteArray = peerId(noiseKey)
    val nickname = "Scout " + fingerprint(id).take(4).uppercase()
    private data class Peer(val name: String, val key: ByteArray)
    private val identities = LinkedHashMap<String, Peer>()
    private val seen = LinkedHashSet<String>()
    data class Incoming(val sender: String, val text: String?, val relay: ByteArray?)

    private fun peerId(key: ByteArray) = MessageDigest.getInstance("SHA-256").digest(key).copyOf(8)
    private fun padded(bytes: ByteArray): ByteArray {
        val target = listOf(256, 512, 1024, 2048).firstOrNull { it >= bytes.size + 16 } ?: return bytes
        val count = target - bytes.size
        return if (count in 1..255) bytes + ByteArray(count) { count.toByte() } else bytes
    }
    private fun signed(type: Int, payload: ByteArray): ByteArray {
        val broadcast = if (type == 2) ByteArray(8) { -1 } else byteArrayOf()
        val unsigned = ByteBuffer.allocate(22 + broadcast.size + payload.size)
            .put(1).put(type.toByte()).put(0).putLong(System.currentTimeMillis())
            .put(if (broadcast.isEmpty()) 0 else 1).putShort(payload.size.toShort())
            .put(id).put(broadcast).put(payload).array()
        val signer = Ed25519Signer()
        signer.init(true, signingKey)
        val canonical = padded(unsigned)
        signer.update(canonical, 0, canonical.size)
        return unsigned.also { it[2] = 3; it[11] = (it[11].toInt() or 2).toByte() } + signer.generateSignature()
    }
    fun announce(): ByteArray {
        fun field(type: Byte, value: ByteArray) = byteArrayOf(type, value.size.toByte()) + value
        return signed(1, field(1, nickname.toByteArray()) + field(2, noiseKey) + field(3, signingKey.generatePublicKey().encoded))
    }
    fun message(text: String): ByteArray {
        val bytes = text.toByteArray()
        require(text.isNotBlank() && bytes.size <= MAX_TEXT_BYTES)
        return signed(2, bytes)
    }

    // Ignore unrelated BitChat features (including all addressed private traffic).
    fun receive(frame: ByteArray): Incoming? = runCatching {
        val buf = ByteBuffer.wrap(frame)
        require(buf.get().toInt() == 1)
        val type = buf.get().toInt()
        require(type == 1 || type == 2)
        val ttl = buf.get().toInt() and 255
        val time = buf.long
        require(time in (System.currentTimeMillis() - 600_000)..(System.currentTimeMillis() + 600_000))
        val flags = buf.get().toInt() and 255
        require(flags in 2..7 && flags and 2 != 0)
        val size = buf.short.toInt() and 65535
        val sender = ByteArray(8).also(buf::get)
        require(!sender.contentEquals(id))
        if (flags and 1 != 0) require(ByteArray(8).also(buf::get).all { it == (-1).toByte() })
        require(size > 0 && size <= buf.remaining() - 64)
        val wirePayload = ByteArray(size).also(buf::get)
        val end = buf.position()
        val signature = ByteArray(64).also(buf::get)
        val stamp = fingerprint(signature)
        if (stamp in seen) return null
        val payload = if (flags and 4 == 0) wirePayload else inflate(wirePayload)
        val peerKey = fingerprint(sender)
        val claimed = if (type == 1) identity(sender, payload) else identities[peerKey] ?: return null
        val previous = identities[peerKey]
        require(previous == null || previous.key.contentEquals(claimed.key))
        val canonical = padded(frame.copyOf(end).also { it[2] = 0; it[11] = (flags and 2.inv()).toByte() })
        val verifier = Ed25519Signer()
        verifier.init(false, Ed25519PublicKeyParameters(claimed.key, 0))
        verifier.update(canonical, 0, canonical.size)
        require(verifier.verifySignature(signature))
        identities[peerKey] = claimed
        if (identities.size > 256) identities.remove(identities.keys.first())
        seen.add(stamp)
        if (seen.size > 512) seen.remove(seen.first())
        val relay = if (ttl in 2..7) frame.copyOf(end + 64).also { it[2] = (ttl - 1).toByte() } else null
        Incoming(claimed.name, if (type == 2) payload.decodeToString(throwOnInvalidSequence = true) else null, relay)
    }.getOrNull()

    private fun inflate(wire: ByteArray): ByteArray {
        val size = ByteBuffer.wrap(wire).short.toInt() and 65535
        require(size in 1..8192)
        val inflater = Inflater(true)
        return try {
            inflater.setInput(wire, 2, wire.size - 2)
            val output = ByteArray(size + 1)
            require(inflater.inflate(output) == size && inflater.finished() && inflater.remaining == 0)
            output.copyOf(size)
        } finally { inflater.end() }
    }
    private fun identity(sender: ByteArray, payload: ByteArray): Peer {
        val buf = ByteBuffer.wrap(payload)
        val fields = mutableMapOf<Int, ByteArray>()
        while (buf.hasRemaining()) {
            val type = buf.get().toInt()
            val size = buf.get().toInt() and 255
            fields[type] = ByteArray(size).also(buf::get)
        }
        val noise = fields.getValue(2)
        require(noise.size == 32 && peerId(noise).contentEquals(sender))
        val key = fields.getValue(3)
        require(key.size == 32)
        return Peer(fields.getValue(1).decodeToString().filterNot(Char::isISOControl).take(32), key)
    }
    companion object { const val MAX_TEXT_BYTES = 140 }
}
