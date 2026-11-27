package ae.fieldguide

import org.bouncycastle.crypto.agreement.jpake.JPAKEParticipant
import org.bouncycastle.crypto.agreement.jpake.JPAKERound1Payload
import org.bouncycastle.crypto.agreement.jpake.JPAKERound2Payload
import org.bouncycastle.crypto.agreement.jpake.JPAKERound3Payload
import java.io.DataInputStream
import java.io.DataOutputStream
import java.math.BigInteger
import java.security.SecureRandom

/** Short codes authenticate a J-PAKE exchange inside the local TLS connection. */
internal object SpokenPair {
    private val random = SecureRandom()
    fun create() = random.nextInt(1_000_000).toString().padStart(6, '0')
    fun valid(code: String) = code.length == 6 && code.all { it in '0'..'9' }

    // Only the routing digit is advertised. The suffix distinguishes concurrent invitations.
    fun prefix(code: String) = "FieldPair2-" + code.first() + "-"
    fun name(code: String) = prefix(code) + android.os.Build.MODEL.take(22) + "-" + random.nextInt(10000)

    private fun DataOutputStream.numbers(vararg values: BigInteger) {
        for (value in values) {
            val bytes = value.toByteArray()
            writeShort(bytes.size)
            write(bytes)
        }
        flush()
    }
    private fun DataInputStream.number(): BigInteger {
        val size = readUnsignedShort()
        require(size in 1..385)
        return BigInteger(ByteArray(size).also(::readFully))
    }
    private fun DataInputStream.proof() = arrayOf(number(), number())

    fun exchange(code: String, host: Boolean, binding: String, input: DataInputStream, out: DataOutputStream) {
        require(valid(code))
        // Each side reconstructs the peer's ID from its own TLS certificate fingerprint and session nonces.
        val local = (if (host) "host/" else "guest/") + binding
        val peer = (if (host) "guest/" else "host/") + binding
        val participant = JPAKEParticipant(local, code.toCharArray())
        val one = participant.createRound1PayloadToSend()
        out.numbers(one.gx1, one.gx2, *one.knowledgeProofForX1, *one.knowledgeProofForX2)
        participant.validateRound1PayloadReceived(JPAKERound1Payload(peer, input.number(), input.number(), input.proof(), input.proof()))
        val two = participant.createRound2PayloadToSend()
        out.numbers(two.a, *two.knowledgeProofForX2s)
        participant.validateRound2PayloadReceived(JPAKERound2Payload(peer, input.number(), input.proof()))
        val key = participant.calculateKeyingMaterial()
        out.numbers(participant.createRound3PayloadToSend(key).macTag)
        participant.validateRound3PayloadReceived(JPAKERound3Payload(peer, input.number()), key)
    }
}
