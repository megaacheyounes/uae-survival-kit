package ae.fieldguide

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.*

internal fun fingerprint(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes).joinToString("") { "%02x".format(it) }

internal fun roomServer(): Pair<SSLServerSocket, String> {
    val alias = "field-nearby-tls"
    val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    if (!keys.containsAlias(alias)) {
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
            initialize(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                // Conscrypt signs an already-hashed TLS transcript with NONEwithECDSA.
                .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512).build())
            generateKeyPair()
        }
    }
    val cert=keys.getCertificate(alias) as X509Certificate
    val privateKey=keys.getKey(alias,null) as java.security.PrivateKey
    val manager=object : X509ExtendedKeyManager(){
        override fun getPrivateKey(name:String)=if(name==alias)privateKey else null
        override fun getCertificateChain(name:String)=if(name==alias) arrayOf(cert) else null
        override fun getServerAliases(type:String,issuers:Array<out java.security.Principal>?)=if(type=="EC") arrayOf(alias) else null
        override fun chooseServerAlias(type:String,issuers:Array<out java.security.Principal>?,socket:java.net.Socket?)=getServerAliases(type,issuers)?.firstOrNull()
        override fun chooseEngineServerAlias(type:String,issuers:Array<out java.security.Principal>?,engine:SSLEngine?)=getServerAliases(type,issuers)?.firstOrNull()
        override fun getClientAliases(type:String,issuers:Array<out java.security.Principal>?):Array<String>?=null
        override fun chooseClientAlias(types:Array<out String>,issuers:Array<out java.security.Principal>?,socket:java.net.Socket?):String?=null
    }
    val ssl = SSLContext.getInstance("TLS").apply { init(arrayOf(manager), null, SecureRandom()) }
    return (ssl.serverSocketFactory.createServerSocket(0, 8) as SSLServerSocket) to fingerprint(keys.getCertificate(alias).encoded)
}

// Room discovery has no public CA. A QR pin or an out-of-band comparison on both
// screens authenticates the presented key before either side releases room data.
internal fun roomClient(transport:java.net.Socket,ip:String,port:Int): SSLSocket {
    val trust = object : X509TrustManager {
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        override fun checkClientTrusted(chain: Array<X509Certificate>, auth: String) = error("Client authentication is handled by invitation")
        override fun checkServerTrusted(chain: Array<X509Certificate>, auth: String) { chain.first().checkValidity() }
    }
    return SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), SecureRandom()) }
        .socketFactory.createSocket(transport,ip,port,true) as SSLSocket
}
