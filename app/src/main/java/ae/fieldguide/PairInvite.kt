package ae.fieldguide

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal object PairInvite {
    private const val alphabet = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"
    fun create(): String {
        val rng = SecureRandom()
        return buildString { repeat(16) { append(alphabet[rng.nextInt(alphabet.length)]) } }
    }
    fun normalize(input: String) = input.uppercase(java.util.Locale.ROOT).replace("-", "").filterNot(Char::isWhitespace)
    fun valid(code: String) = code.length == 16 && code.all { it in alphabet }
    fun proof(code: String, role: String, key: String, room: String, challenge: String, nonce: String): String {
        require(valid(code))
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(code.toByteArray(), "HmacSHA256"))
        return android.util.Base64.encodeToString(mac.doFinal(listOf("FieldPair1",role,key,room,challenge,nonce).joinToString("|").toByteArray()),android.util.Base64.NO_WRAP)
    }
    fun matches(a: String,b: String) = MessageDigest.isEqual(a.toByteArray(), b.toByteArray())
}
