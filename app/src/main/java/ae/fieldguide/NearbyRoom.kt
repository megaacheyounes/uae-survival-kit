package ae.fieldguide

import android.content.Context
import android.net.nsd.NsdServiceInfo
import androidx.compose.runtime.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.net.*
import java.util.UUID
import javax.net.ssl.SSLSocket

private const val maxFrame=540000 // Includes JSON escaping for a full 12-second base64 note.
private fun InetAddress.isRoomAddress(): Boolean =
    isSiteLocalAddress || isLinkLocalAddress || isLoopbackAddress ||
        (this is Inet6Address && address[0].toInt().and(0xfe) == 0xfc)

private fun uid()=UUID.randomUUID().toString()
private fun packet(type:String,vararg fields:Pair<String,Any>)=JSONObject().put("type",type).also { obj->fields.forEach { obj.put(it.first,it.second) } }
private fun encode(out:DataOutputStream,obj:JSONObject) {
    val bytes=obj.toString().toByteArray()
    require(bytes.size in 1..maxFrame)
    out.writeInt(bytes.size); out.write(bytes); out.flush()
}
private fun decode(input:DataInputStream):JSONObject {
    val size=input.readInt(); require(size in 1..maxFrame)
    return JSONObject(ByteArray(size).also(input::readFully).decodeToString())
}
internal data class RoomPost(val id:String,val sender:String,val kind:String,val body:String,val received:Int,val total:Int)
private class Link(val socket:SSLSocket) {
    val output=Channel<JSONObject>(24)
    fun send(p:JSONObject) { if(output.trySend(p).isFailure) close() }
    fun close(){output.close();runCatching { socket.close() }}
}
private data class Member(val name:String,var link:Link?)
private data class Stored(val key:String,val owner:String,val name:String,val kind:String,val body:String,
    val recipients:Set<String>,val read:MutableSet<String>)
internal class NearbyRoom(ctx:Context) {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val radio=RoomRadio(ctx.applicationContext)
    private val audio=RoomAudio(scope,ctx.applicationContext)
    private val connectivity=ctx.getSystemService(android.net.ConnectivityManager::class.java)
    private var wifiListener:android.net.ConnectivityManager.NetworkCallback?=null
    private fun watchWifi() {
        if(wifiListener!=null)return
        val callback=object:android.net.ConnectivityManager.NetworkCallback(){
            override fun onAvailable(network:android.net.Network){scope.launch {
                if(trusted.isNotEmpty() && phase=="disconnected") reconnect()
            }}
        }
        val request=android.net.NetworkRequest.Builder().addTransportType(android.net.NetworkCapabilities.TRANSPORT_WIFI).build()
        connectivity.registerNetworkCallback(request,callback)
        wifiListener=callback
    }
    var phase by mutableStateOf("lobby"); private set
    var status by mutableStateOf(""); private set
    var nickname by mutableStateOf("")
    var address by mutableStateOf(""); private set
    var port=0; private set
    var pin=""; private set
    var joining by mutableStateOf(true); private set
    var hotspot by mutableStateOf(""); private set
    val found=mutableStateMapOf<String,NsdServiceInfo>()
    val roster=mutableStateListOf<String>()
    val posts=mutableStateListOf<RoomPost>()
    var speaker by mutableStateOf(""); private set
    var mic by mutableStateOf(""); private set
    var muted by mutableStateOf(false); private set
    private val pending=LinkedHashMap<String,JSONObject>()
    var waiting by mutableIntStateOf(0); private set
    private var roomId=uid()
    private val token=uid()+uid()
    private var trusted=""
    private var remote:Link?=null
    private var host=false
    private var server:ServerSocket?=null
    private val sockets=java.util.concurrent.ConcurrentHashMap.newKeySet<SSLSocket>()
    private val members=linkedMapOf<String,Member>()
    private val history=linkedMapOf<String,Stored>()
    private var owner=""
    private var lease:Job?=null
    private var roomName=""
    private var target=""
    private var retry:Job?=null
    private var retryCount=0
    val inviteCode = PairInvite.create()
    val spokenCode = SpokenPair.create()
    val serviceName = SpokenPair.name(spokenCode)
    private val codeDeadline = android.os.SystemClock.elapsedRealtime() + 10 * 60_000
    private var attempts by mutableIntStateOf(0)
    fun spokenAvailable() = attempts < 5 && android.os.SystemClock.elapsedRealtime() < codeDeadline
    fun codeLifetime() = (codeDeadline - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(0)
    var chooseHost by mutableStateOf(false); private set
    fun candidates() = found.filterKeys { searchCode.isNotEmpty() && it.startsWith(SpokenPair.prefix(searchCode)) }
    private var settle: Job? = null
    private fun checkCandidates() {
        settle?.cancel()
        settle=scope.launch {
            delay(750)
            if(phase!="lobby" || searchCode.isEmpty()) return@launch
            val matches=candidates()
            if(matches.size==1) resolve(matches.values.first())
            else if(matches.size>1) { chooseHost=true;searchTimeout?.cancel();status="Choose the phone showing your code." }
        }
    }
    private var searchCode = ""
    private var qrDiscovery: String? = null
    private var qrPin = ""
    private var qrRoom = ""
    private var searchTimeout: Job? = null
    fun findPair(input: String, qrName: String? = null, expectedPin: String = "", expectedRoom: String = "") {
        val code = PairInvite.normalize(input)
        if (qrName == null && !SpokenPair.valid(code)) { status="Enter the six digits from the other phone.";return }
        qrDiscovery=qrName;qrPin=expectedPin;qrRoom=expectedRoom
        searchCode=code;status="Looking for the other phone on this Wi-Fi..."
        discover()
        if(qrName!=null) found[qrName]?.let(::resolve) else checkCandidates()
        searchTimeout?.cancel()
        searchTimeout=scope.launch {
            delay(15000)
            if(phase=="lobby" && searchCode==code) {
                stopScan()
                status="No matching invitation found. Check the code and make sure both phones share Wi-Fi."
            }
        }
    }
    fun invitationId()=roomId
    fun hasPair() = trusted.isNotEmpty() || (host && !joining)
    fun discover() {
        runCatching { radio.discover({name,info-> if(info==null) found.remove(name) else if(found.size<64) {
                found[name]=info
                if(phase=="lobby" && name==qrDiscovery) resolve(info)
                else if(qrDiscovery==null && searchCode.isNotEmpty() && name.startsWith(SpokenPair.prefix(searchCode)) && phase=="lobby") checkCandidates()
            }},
            {status="Couldn’t find phones on Wi-Fi. Try scanning the invitation."}) }.onFailure {status="Couldn’t find phones on Wi-Fi. Try scanning the invitation."}
    }
    fun resolve(info:NsdServiceInfo) {
        chooseHost=false
        searchTimeout?.cancel()
        if(phase!="lobby") return
        phase="connecting"
        runCatching { radio.resolve(info){ip,p,network->phase="lobby";if(ip==null) status="Room unavailable." else connect(ip,p,qrPin,qrRoom,searchCode,network) } }
            .onFailure {phase="lobby";status="Could not resolve room."}
    }
    fun start(hot:Boolean) {
        if(phase!="lobby")return
        phase="starting";radio.stopScan()
        if(hot) runCatching { radio.localHotspot({ssid,pass->hotspot=ssid+"\n"+pass;listen()},{
            hotspot=""
            status="Hotspot unavailable. Connect both phones to the same Wi-Fi.";if(server==null)phase="lobby"
        }) }.onFailure {phase="lobby";status="Allow nearby Wi-Fi access and enable Wi-Fi to start a hotspot."}
        else listen()
    }
    private fun listen()=scope.launch {
        try {
            val (listener,key)=withContext(Dispatchers.IO){roomServer().also { server=it.first;if(!scope.isActive)it.first.close() }}
            pin=key;port=listener.localPort;host=true
            roomName="Trusted pair"
            val ips=withContext(Dispatchers.IO){NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp }
                .sortedBy { if(it.name.startsWith("ap") || it.name.startsWith("swlan"))0 else if(it.name.startsWith("wlan"))1 else 2 }
                .flatMap { it.inetAddresses.toList() }.filter { it is Inet4Address && it.isSiteLocalAddress }.mapNotNull { it.hostAddress }}
            address=ips.joinToString(" / ");phase="host";refresh()
            runCatching {radio.advertise(serviceName,port){status=it}}
            while(isActive) {
                val socket=withContext(Dispatchers.IO){listener.accept() as SSLSocket}
                val peer=socket.inetAddress
                if(sockets.size>=7 || !peer.isRoomAddress()) {socket.close();continue}
                sockets.add(socket);scope.launch { serve(socket) }
            }
        } catch(e:Exception){if(isActive){status="Room could not start. Leave and retry.";phase="error"}}
    }
    private suspend fun serve(socket:SSLSocket) {
        val link=Link(socket)
        var id=""
        try {
            socket.soTimeout=55000
            withContext(Dispatchers.IO){socket.startHandshake()}
            val input=DataInputStream(BufferedInputStream(socket.inputStream))
            val out=DataOutputStream(BufferedOutputStream(socket.outputStream))
            val challenge=uid()
            withContext(Dispatchers.IO){encode(out,packet("hello","room" to roomId,"name" to roomName,"challenge" to challenge))}
            val knock=withContext(Dispatchers.IO){decode(input)}
            require(knock.getString("type")=="knock")
            val nonce=UUID.fromString(knock.getString("nonce")).toString()
            val name=knock.getString("name").take(24)
            val returning=knock.optBoolean("return")
            if (!returning) {
                require(joining && members.isEmpty())
                if (knock.optBoolean("spoken")) {
                    require(spokenAvailable())
                    attempts++
                    withContext(Dispatchers.IO) { SpokenPair.exchange(spokenCode,true,"$pin/$roomId/$challenge/$nonce",input,out) }
                } else {
                    require(PairInvite.matches(knock.optString("proof"),PairInvite.proof(inviteCode,"guest",pin,roomId,challenge,nonce)))
                    withContext(Dispatchers.IO) { encode(out,packet("pair","proof" to PairInvite.proof(inviteCode,"host",pin,roomId,challenge,nonce))) }
                }
            }
            val auth=withContext(Dispatchers.IO){decode(input)}
            require(auth.getString("type")=="join")
            id=auth.getString("token");require(id.length==72)
            val member=members[id]
            if(member==null) {
                require(!returning && joining && members.isEmpty())
                members[id]=Member(name,link); joining=false;radio.stopAdvert()
            } else {member.link?.close();member.link=link}
            withContext(Dispatchers.IO){encode(out,packet("welcome","name" to roomName))}
            refresh()
            // Start the writer before replay so a full history cannot overflow its queue.
            coroutineScope {
                val writer=launch(Dispatchers.IO){try {for(p in link.output)encode(out,p)}finally{link.close()}}
                try {
                    history.values.filter {id in it.recipients || id==it.owner}.forEach {link.output.send(post(it))}
                    pump(link,input){p->from(id,p)}
                } finally {link.close();writer.cancel()}
            }
        } catch(e:Exception){android.util.Log.d("FieldRoom","Guest connection ended: "+e.javaClass.simpleName, e.takeIf {it is javax.net.ssl.SSLException})} finally {
            link.close();sockets.remove(socket)
            if(members[id]?.link===link){members[id]?.link=null;if(owner==id)endTalk();refresh()}
        }
    }
    fun connect(ip:String,p:Int,expected:String,expectedRoom:String="",pairCode:String="",discoveredOn:android.net.Network?=null) {
        if(phase !in listOf("lobby","disconnected"))return
        if(p !in 1..65535 || ip.length>80 || !Regex("[0-9a-fA-F:.%a-zA-Z0-9_-]+").matches(ip) || !ip.contains('.') && !ip.contains(':')){
            status="Enter the host address and port.";return
        }
        radio.stopScan();phase="connecting";target=ip;port=p;status=""
        scope.launch {
            var socket:SSLSocket?=null
            var reachedHost=false
            try{
                watchWifi()
                val s=withContext(Dispatchers.IO){
                    val inet=android.net.InetAddresses.parseNumericAddress(ip)
                    require(inet.isRoomAddress())
                    val wifi=connectivity.allNetworks.filter {
                        connectivity.getNetworkCapabilities(it)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)==true
                    }
                    val network=discoveredOn ?: wifi.firstOrNull { n->connectivity.getLinkProperties(n)?.routes?.any { !it.isDefaultRoute && it.matches(inet) }==true } ?: wifi.firstOrNull()
                    val active=connectivity.activeNetwork
                    val vpn=active?.let(connectivity::getNetworkCapabilities)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)==true
                    // Android can forbid binding directly to the underlying Wi-Fi on a VPN.
                    // Let the system route ordinary LAN traffic. Bind only for a separate local-only network.
                    val plain=if(inet.isLoopbackAddress || vpn || active==network) Socket()
                        else network?.socketFactory?.createSocket() ?: Socket()
                    try {
                        plain.connect(InetSocketAddress(inet,p),6000)
                        roomClient(plain,ip,p).also { socket=it;sockets.add(it);it.soTimeout=55000;it.startHandshake() }
                    } catch(e:Exception){plain.close();throw e}
                }
                val key=fingerprint(s.session.peerCertificates.first().encoded)
                require(expected.isEmpty() || expected==key)
                val input=DataInputStream(BufferedInputStream(s.inputStream));val out=DataOutputStream(BufferedOutputStream(s.outputStream))
                val hello=withContext(Dispatchers.IO){decode(input)}
                require(hello.getString("type")=="hello")
                reachedHost=true
                require(expectedRoom.isEmpty() || expectedRoom==hello.getString("room"))
                if(trusted.isNotEmpty()) require(trusted==key && roomId==hello.getString("room"))
                val nonce=uid();val room=hello.getString("name").take(32)
                val returning=trusted.isNotEmpty()
                val challenge=hello.getString("challenge");val remoteRoom=hello.getString("room")
                val spoken = !returning && SpokenPair.valid(pairCode)
                val proof=if(returning || spoken) "" else PairInvite.proof(pairCode,"guest",key,remoteRoom,challenge,nonce)
                withContext(Dispatchers.IO) {
                    encode(out,packet("knock","name" to nick(),"nonce" to nonce,"return" to returning,"proof" to proof,"spoken" to spoken))
                    if (spoken) SpokenPair.exchange(pairCode,false,"$key/$remoteRoom/$challenge/$nonce",input,out)
                    else if (!returning) {
                        val confirmation=decode(input)
                        require(confirmation.optString("type")=="pair" && PairInvite.matches(confirmation.optString("proof"),PairInvite.proof(pairCode,"host",key,remoteRoom,challenge,nonce)))
                    }
                }
                withContext(Dispatchers.IO){encode(out,packet("join","token" to token));require(decode(input).getString("type")=="welcome")}
                roomId=hello.getString("room");roomName=room;trusted=key
                val link=Link(s);remote=link;phase="guest";status=""
                retryCount=0
                coroutineScope {
                    val writer=launch(Dispatchers.IO){try{for(obj in link.output)encode(out,obj)}finally{link.close()}}
                    try{pending.values.forEach {link.output.send(it)};pump(link,input,::receive)}
                    finally{link.close();writer.cancel()}
                }
            }catch(e:Exception){
                val errno=generateSequence<Throwable>(e){it.cause}.filterIsInstance<android.system.ErrnoException>().firstOrNull()?.errno
                android.util.Log.d("FieldRoom","Connection ended (hello=$reachedHost, IPv6=${ip.contains(':')}, errno=$errno): "+e.javaClass.simpleName)
            }finally{
                socket?.let {runCatching {it.close()};sockets.remove(it)}
                remote=null;releaseMic();speaker="";audio.stopPlayback()
                if(scope.isActive){
                    phase=if(trusted.isEmpty())"lobby" else "disconnected"
                    status=when {
                        trusted.isNotEmpty() -> "Connection lost. Reconnecting on Wi-Fi..."
                        reachedHost -> "Pairing failed. Check the six digits or ask for a fresh invitation."
                        else -> "Couldn’t reach the other phone. Check Wi-Fi or try a local hotspot."
                    }
                    if(trusted.isNotEmpty() && retryCount<3){
                        retryCount++
                        retry=scope.launch { delay(2000L*retryCount);connect(target,port,trusted) }
                    }
                }
            }
        }
    }
    fun reconnect(){retry?.cancel();retryCount=0;connect(target,port,trusted)}
    fun stopScan(){chooseHost=false;settle?.cancel();searchTimeout?.cancel();searchCode="";radio.stopScan();found.clear();status=""}
    private suspend fun pump(link:Link,input:DataInputStream,handle:(JSONObject)->Unit)=coroutineScope {
        link.socket.soTimeout=25000
        val ping=launch {while(isActive){delay(8000);link.send(packet("ping"))}}
        try{while(isActive){val p=withContext(Dispatchers.IO){decode(input)};if(p.optString("type")!="ping")handle(p)}}
        finally{ping.cancel()}
    }
    private fun nick()=nickname.trim().take(24).ifEmpty {if(host)"Host" else "Guest "+token.take(4)}
    fun localName()=nick()
    private fun broadcast(p:JSONObject){members.values.forEach {it.link?.send(p)}}
    private fun refresh(){
        roster.clear();roster.add(nick()+" · host")
        members.values.forEach {roster.add(it.name+if(it.link==null)" · offline" else "")}
        broadcast(packet("members","list" to JSONArray(roster.toList()),"speaker" to speaker))
    }
    fun roomTitle()=roomName
    fun send(kind:String,body:String):Boolean {
        if(!host && pending.size>=12){status="Outbox full. Reconnect before sending more.";return false}
        val p=packet("send","id" to uid(),"kind" to kind,"body" to body)
        if(host)from("host",p) else {pending[p.getString("id")]=p;waiting=pending.size;remote?.send(p)}
        return true
    }
    private fun post(m:Stored)=packet("post","id" to m.key,"name" to m.name,"kind" to m.kind,"body" to m.body,"got" to m.read.size,"total" to m.recipients.size)
    private fun display(p:JSONObject){
        val m=RoomPost(p.getString("id"),p.getString("name").take(24),p.getString("kind"),p.getString("body"),p.getInt("got"),p.getInt("total"))
        val i=posts.indexOfFirst {it.id==m.id}
        if(i>=0)posts[i]=m else {posts.add(m);if(posts.size>40)posts.removeAt(0)}
    }
    private fun from(id:String,p:JSONObject){
        when(p.getString("type")){
            "send"->{
                val key=UUID.fromString(p.getString("id")).toString()
                history[key]?.let {if(it.owner==id) members[id]?.link?.send(post(it));return}
                val kind=p.getString("kind");val b=p.getString("body")
                require(when(kind){"text"->b.isNotBlank() && b.length<=1000;"check"->b in listOf("safe","help","meet");"note"->b.length in 1..256000;else->false})
                val to=(members.keys+"host").filter {it!=id}.toSet()
                val m=Stored(key,id,if(id=="host")nick() else members.getValue(id).name,kind,b,to,mutableSetOf())
                if("host" in to)m.read.add("host")
                history[key]=m;if(history.size>40)history.remove(history.keys.first())
                val msg=post(m);display(msg);broadcast(msg)
            }
            "ack"->{val m=history[p.getString("id")]?:return
                if(id in m.recipients && m.read.add(id)){
                    display(post(m))
                    val update=packet("receipt","id" to m.key,"got" to m.read.size,"total" to m.recipients.size)
                    members.forEach{(peer,member)->if(peer==m.owner || peer in m.recipients)member.link?.send(update)}
                }}
            "floor"->{
                if(owner.isNotEmpty()){if(id=="host")status="Channel busy" else members[id]?.link?.send(packet("busy"));return}
                owner=id;speaker=if(id=="host")nick() else members.getValue(id).name
                refresh();lease=scope.launch{delay(32000);endTalk()}
                if(id=="host")beginLive() else members[id]?.link?.send(packet("grant"))
            }
            "stop"->if(owner==id)endTalk()
            "chunk"->if(owner==id){
                val data=p.getString("data");require(data.length<=3000)
                if(id!="host" && !muted)audio.play(data,false)
                members.filterKeys {it!=id}.values.forEach {it.link?.send(p)}
            }
            "leave"->{members.remove(id)?.link?.close();if(owner==id)endTalk();refresh()}
        }
    }
    private fun receive(p:JSONObject){
        when(p.getString("type")){
            "members"->{roster.clear();val arr=p.getJSONArray("list");for(i in 0 until minOf(arr.length(),4))roster.add(arr.getString(i).take(40))
                val previous=speaker
                speaker=p.optString("speaker")
                if(previous.isNotEmpty() && speaker.isEmpty())audio.finishStream()
                if(speaker.isEmpty() && mic=="live"){audio.stopCapture();mic=""}}
            "post"->{display(p);val id=p.getString("id");pending.remove(id);waiting=pending.size;remote?.send(packet("ack","id" to id))}
            "receipt"->{val i=posts.indexOfFirst {it.id==p.getString("id")}
                if(i>=0)posts[i]=posts[i].copy(received=p.getInt("got"))}
            "grant"->if(mic=="request")beginLive() else remote?.send(packet("stop"))
            "busy"->{mic="";status="Channel busy"}
            "chunk"->if(!muted)audio.play(p.getString("data"),false)
        }
    }
    private fun endTalk(){lease?.cancel();lease=null;if(owner=="host"){audio.stopCapture();mic=""};audio.finishStream();owner="";speaker="";refresh()}
    private fun command(p:JSONObject){if(host)from("host",p)else remote?.send(p)}
    fun talk(){if(mic.isNotEmpty() || speaker.isNotEmpty())return;mic="request";command(packet("floor"))}
    private fun beginLive(){mic="live";audio.stopPlayback();audio.record(false,{command(packet("chunk","data" to it))},{releaseMic()},{status="Microphone unavailable";releaseMic()})}
    fun note(){if(mic.isNotEmpty())return;mic="note";audio.record(true,{}, {finishNote()}, {status="Microphone unavailable";releaseMic()})}
    fun finishNote(){if(mic=="note"){val b=audio.stopCapture();mic="";if(b!=null)send("note",b)}}
    fun releaseMic(){val was=mic;mic="";audio.stopCapture();if(was=="live" || was=="request")command(packet("stop"))}
    fun toggleMute(){muted=!muted;if(muted)audio.stopPlayback()}
    fun playNote(data:String){if(mic.isEmpty())audio.play(data,true)}
    fun close(){
        releaseMic();audio.stopPlayback();radio.close()
        wifiListener?.let {runCatching {connectivity.unregisterNetworkCallback(it)}}
        runCatching {server?.close()};sockets.forEach {runCatching {it.close()}};scope.cancel()
    }
}
