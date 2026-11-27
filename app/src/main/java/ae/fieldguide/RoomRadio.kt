package ae.fieldguide

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper

internal class RoomRadio(ctx: Context) {
    private val nsd = ctx.getSystemService(NsdManager::class.java)
    private val wifi = ctx.getSystemService(WifiManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val type = "_uaefield._tcp."
    private var advert: NsdManager.RegistrationListener? = null
    private var scan: NsdManager.DiscoveryListener? = null
    private var hotspot: WifiManager.LocalOnlyHotspotReservation? = null
    private var closed = false

    fun advertise(name: String, port: Int, error: (String) -> Unit) {
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(s: NsdServiceInfo) {}
            override fun onServiceUnregistered(s: NsdServiceInfo) {}
            override fun onRegistrationFailed(s: NsdServiceInfo, code: Int) { main.post { if (!closed) error("Wi-Fi discovery failed. Try scanning the QR code, or create a hotspot invitation.") } }
            override fun onUnregistrationFailed(s: NsdServiceInfo, code: Int) {}
        }
        advert = listener
        nsd.registerService(NsdServiceInfo().apply { serviceName=name; serviceType=type; setPort(port) }, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    fun discover(update: (String, NsdServiceInfo?) -> Unit, failed: () -> Unit) {
        if (scan != null) return
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(t: String) {}
            override fun onDiscoveryStopped(t: String) {}
            override fun onServiceFound(s: NsdServiceInfo) { main.post { if (!closed) update(s.serviceName,s) } }
            override fun onServiceLost(s: NsdServiceInfo) { main.post { if (!closed) update(s.serviceName,null) } }
            override fun onStartDiscoveryFailed(t: String, code: Int) { main.post { if (!closed) { stopScan(); failed() } } }
            override fun onStopDiscoveryFailed(t: String, code: Int) {}
        }
        scan = listener
        nsd.discoverServices(type,NsdManager.PROTOCOL_DNS_SD,listener)
    }

    fun resolve(info: NsdServiceInfo, done: (String?, Int, android.net.Network?) -> Unit) {
        nsd.resolveService(info,object : NsdManager.ResolveListener {
            override fun onServiceResolved(s: NsdServiceInfo) {
                val addresses = if(Build.VERSION.SDK_INT>=34) s.hostAddresses else listOfNotNull(s.host)
                val host = addresses.firstOrNull { it is java.net.Inet4Address && it.isSiteLocalAddress } ?: addresses.firstOrNull()
                val network = if(Build.VERSION.SDK_INT>=33) s.network else null
                main.post { if (!closed) done(host?.hostAddress,s.port,network) }
            }
            override fun onResolveFailed(s: NsdServiceInfo, code: Int) { main.post { if (!closed) done(null,0,null) } }
        })
    }
    fun stopScan() { scan?.let { runCatching { nsd.stopServiceDiscovery(it) } }; scan=null }
    fun localHotspot(ready: (String,String) -> Unit, failed: () -> Unit) {
        try { wifi.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
            override fun onStarted(r: WifiManager.LocalOnlyHotspotReservation) {
                if (closed) { r.close(); return }
                hotspot=r
                val config=r.softApConfiguration
                ready(config.ssid.orEmpty(),config.passphrase.orEmpty())
            }
            override fun onStopped() { if (!closed) failed() }
            override fun onFailed(reason: Int) { if (!closed) failed() }
        },main) } catch (_:SecurityException){failed()}
    }
    fun stopAdvert(){advert?.let { runCatching { nsd.unregisterService(it) } };advert=null}
    fun close() {
        closed=true; stopScan()
        stopAdvert()
        hotspot?.close()
    }
}
