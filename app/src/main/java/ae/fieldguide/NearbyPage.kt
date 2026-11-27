package ae.fieldguide

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

internal fun Context.rotating(): Boolean {
    var parent = this
    while (parent is android.content.ContextWrapper && parent !is android.app.Activity) parent = parent.baseContext
    return (parent as? android.app.Activity)?.isChangingConfigurations == true
}

@Composable
private fun PairInvitation(room: NearbyRoom) = Page {
    RadioStatus("Waiting for the other phone", "Keep this invitation open until they join.")
    if (room.hotspot.isNotEmpty()) {
        Section("Connect the other phone to this hotspot")
        val credentials = room.hotspot.split('\n', limit = 2)
        Text("Network  ·  " + credentials.first(), style = MaterialTheme.typography.bodyLarge)
        Text("Password  ·  " + credentials.getOrElse(1){""}, fontFamily = FontFamily.Monospace)
    }
    val uri = Uri.Builder().scheme("uae-room").authority("pair")
        .appendQueryParameter("host",room.address.substringBefore(" / ")).appendQueryParameter("port",room.port.toString())
        .appendQueryParameter("key",room.pin).appendQueryParameter("room",room.invitationId())
        .appendQueryParameter("service",room.serviceName).appendQueryParameter("code",room.inviteCode).build()
    val bitmap = remember(uri) {
        val matrix = QRCodeWriter().encode(uri.toString(), BarcodeFormat.QR_CODE, 500, 500)
        Bitmap.createBitmap(IntArray(500*500){ if(matrix[it%500,it/500]) android.graphics.Color.BLACK else android.graphics.Color.WHITE },
            500,500,Bitmap.Config.ARGB_8888).asImageBitmap()
    }
    Surface(shape=RoundedCornerShape(16.dp),color=MaterialTheme.colorScheme.surface,
        border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.fillMaxWidth().padding(16.dp),horizontalAlignment=Alignment.CenterHorizontally,
            verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text("Scan to pair",style=MaterialTheme.typography.titleLarge)
            Image(bitmap,"Pairing QR code",Modifier.size(208.dp))
            Text("On their phone, choose I have an invitation, then Scan QR code.",style=MaterialTheme.typography.bodyMedium)
            HorizontalDivider()
            Text("Or read out this code",style=MaterialTheme.typography.labelLarge,color=PairBlue)
            var expired by remember(room) { mutableStateOf(room.codeLifetime()==0L) }
            LaunchedEffect(room) { kotlinx.coroutines.delay(room.codeLifetime());expired=true }
            Text(if(expired || !room.spokenAvailable()) "Code closed · scan the QR or create a new invitation" else room.spokenCode.chunked(3).joinToString(" "),fontFamily=FontFamily.Monospace,
                style=MaterialTheme.typography.titleLarge)
        }
    }
    Hint("The first phone to use this invitation joins your pair. The spoken code lasts 10 minutes.")
}

@Composable
internal fun NearbyPage(kit: Kit, method: String, back: () -> Unit) {
    val ctx=LocalContext.current
    val room=remember { kit.nearbyRoom ?: NearbyRoom(ctx.applicationContext).also { kit.nearbyRoom=it } }
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    DisposableEffect(room,lifecycle){
        val observer=LifecycleEventObserver{_,event->if(event==Lifecycle.Event.ON_PAUSE)room.releaseMic()}
        lifecycle.addObserver(observer)
        onDispose{ lifecycle.removeObserver(observer); if(!ctx.rotating()){ room.close(); kit.nearbyRoom=null } }
    }
    val joining = method == "Join"
    val connected = room.hasPair()
    var confirmLeave by rememberSaveable { mutableStateOf(false) }
    val messageState = rememberSaveableStateHolder()
    var code by rememberSaveable { mutableStateOf("") }
    var section by rememberSaveable { mutableStateOf("Messages") }
    var alert by remember { mutableStateOf("") }
    var pending by rememberSaveable { mutableStateOf("") }
    fun perform(which:String) {
        when(which) { "hot" -> room.start(true); "note" -> room.note(); "talk" -> room.talk() }
    }
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){ granted ->
        if(granted) perform(pending) else alert="Permission declined. You can still exchange text on existing Wi-Fi."
        pending=""
    }
    fun action(which:String) {
        val needed=if(which!="hot") Manifest.permission.RECORD_AUDIO else if(Build.VERSION.SDK_INT>=33) Manifest.permission.NEARBY_WIFI_DEVICES else Manifest.permission.ACCESS_FINE_LOCATION
        if(ctx.checkSelfPermission(needed)==PackageManager.PERMISSION_GRANTED) perform(which)
        else { pending=which;permission.launch(needed) }
    }
    // A host invitation owns its socket, discovery and optional hotspot for this destination.
    // Re-entering a method creates a fresh room. A configuration change keeps the existing one.
    var launched by remember { mutableStateOf(false) }
    LaunchedEffect(room) {
        if (!joining && room.phase == "lobby" && !launched && pending.isEmpty()) {
            launched = true
            if(method=="Hotspot") action("hot") else room.start(false)
        }
    }
    fun navigateBack() {
        if (connected && section=="Voice") {room.releaseMic(); section="Messages"}
        else if(connected) confirmLeave=true
        else back()
    }
    BackHandler { navigateBack() }
    val scanner=rememberLauncherForActivityResult(ScanContract()){ result ->
        result.contents?.let { runCatching {
            val uri=Uri.parse(it)
            require(uri.scheme=="uae-room" && uri.host=="pair")
            val secret=uri.getQueryParameter("code").orEmpty()
            val key=uri.getQueryParameter("key").orEmpty()
            require(PairInvite.valid(secret) && Regex("[a-f0-9]{64}").matches(key))
            val id=java.util.UUID.fromString(uri.getQueryParameter("room")).toString()
            val host=uri.getQueryParameter("host").orEmpty()
            if(host.isEmpty()) {
                val service=uri.getQueryParameter("service") ?: ("FieldPair-" + fingerprint(secret.toByteArray()).take(12))
                room.findPair(secret,service,key,id)
            } else room.connect(host,requireNotNull(uri.getQueryParameter("port")).toInt(),key,id,secret)
        }.onFailure { alert="Scan the code from the other phone’s Trusted pair screen." } }
    }
    val checks=mapOf("safe" to stringResource(R.string.near_safe),"help" to stringResource(R.string.near_help),"meet" to stringResource(R.string.near_meet))
    Column(Modifier.fillMaxSize()) {
        ReaderBar(when {
            connected -> "Trusted pair"
            joining -> "Join a pair"
            method=="Hotspot" -> "Hotspot invitation"
            else -> "Wi-Fi invitation"
        }, ::navigateBack)
        if(alert.isNotEmpty()) Text(alert,Modifier.padding(12.dp))
        if(room.status.isNotEmpty()) Text(room.status,Modifier.padding(12.dp))
        when {
            room.phase=="lobby" -> Page {
                if(joining) {
                    Title("Join the other phone")
                    Hint("Connect to their Wi-Fi or hotspot first.")
                    Button({scanner.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setBeepEnabled(false).setOrientationLocked(false)
                        .setPrompt("Scan the trusted phone’s invitation"))},Modifier.fillMaxWidth().heightIn(min=54.dp)){
                        FieldIcon(R.drawable.radio_scan); Spacer(Modifier.width(10.dp)); Text("Scan QR code")
                    }
                    Spacer(Modifier.height(6.dp))
                    Section("Use a code instead")
                    OutlinedTextField(code,{code=PairInvite.normalize(it).filter { digit -> digit in '0'..'9' }.take(6)},singleLine=true,
                        label={Text("Pairing code")},placeholder={Text("123 456")},
                        keyboardOptions=androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType=androidx.compose.ui.text.input.KeyboardType.NumberPassword,
                            autoCorrectEnabled=false),
                        modifier=Modifier.fillMaxWidth())
                    OutlinedButton({room.findPair(code)},Modifier.fillMaxWidth(),enabled=SpokenPair.valid(code)){Text("Pair with code")}
                    if(room.chooseHost) room.candidates().forEach { (name, info) -> OutlinedButton({room.resolve(info)}) { Text(name.substringAfter("-").substringAfter("-")) } }
                    TextButton({ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_WIFI_SETTINGS))}){Text("Open Wi-Fi settings")}
                } else {
                    Title(if(method=="Hotspot")"Create a local hotspot" else "Invite on this network")
                    Button({alert="";if(method=="Hotspot")action("hot") else room.start(false)}){Text("Try again")}
                }
            }
            !connected && room.phase in listOf("starting","connecting") -> Page { LinearProgressIndicator();Text("Connecting phones…") }
            room.phase=="host" && room.joining -> PairInvitation(room)
            connected -> {
                val offline=room.phase in listOf("disconnected","connecting") || room.roster.any { it.endsWith(" · offline") }
                RadioStatus(if(offline)"Connection interrupted" else "Paired · private Wi-Fi",
                    if(offline)"Messages wait for reconnection." else "Only your paired phone can join.",attention=offline)
                if(room.phase=="disconnected") TextButton({room.reconnect()}){Text("Reconnect")}
                FieldTabs(listOf("Messages","Voice"),section){ if(section=="Voice")room.releaseMic();section=it }
                if(section=="Messages") messageState.SaveableStateProvider("conversation") { PrivateMessages(room,checks) }
                else if(offline) {
                    Page {
                        Title("Voice is waiting")
                        Hint("Keep both phones on the same Wi-Fi. You can leave a text message while the other phone reconnects.")
                        OutlinedButton({section="Messages"}){Text("Write a message")}
                    }
                } else {
                    Box(Modifier.weight(1f)) {
                        Page {
                            val status = when {
                                room.mic=="live" -> "You’re talking"
                                room.mic=="note" -> "Recording voice note"
                                room.speaker.isNotEmpty() -> "Listening"
                                else -> "Ready to talk"
                            }
                            RadioStatus(status, when {
                                room.mic=="note" -> "Send the recording when you finish."
                                room.speaker.isNotEmpty() && room.mic!="live" -> "The other phone has the channel."
                                else -> "Hold the microphone to speak. Release to listen."
                            },icon=R.drawable.radio_mic)
                            val live=room.mic in listOf("request","live")
                            TextButton({if(live)room.releaseMic() else action("talk")},enabled=live || room.mic.isEmpty() && room.speaker.isEmpty()){Text(if(live)"Stop talking" else "Tap to talk hands-free")}
                            Section("Voice note")
                            val recording=room.mic=="note"
                            OutlinedButton({if(recording)room.finishNote() else action("note")},Modifier.fillMaxWidth(),enabled=recording || room.mic.isEmpty()){Text(if(recording)"Send voice note" else "Record voice note")}
                            if(recording) TextButton({room.releaseMic()}){Text("Cancel recording")}
                            TextButton({room.toggleMute()}){Text(if(room.muted)"Unmute live audio" else "Mute live audio")}
                        }
                    }
                    val colors=MaterialTheme.colorScheme
                    Surface(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=8.dp),shape=MaterialTheme.shapes.medium,
                        color=if(room.mic=="live")colors.primary else colors.primaryContainer) {
                        Box(Modifier.fillMaxWidth().heightIn(min=80.dp).pointerInput(room){
                            detectTapGestures(onPress={
                                if(ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED) action("holdPermission")
                                else if(room.mic.isEmpty() && room.speaker.isEmpty()) {
                                    room.talk();try {tryAwaitRelease()} finally {room.releaseMic()}
                                }
                            })
                        },contentAlignment=Alignment.Center){
                            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){
                                FieldIcon(R.drawable.radio_mic)
                                Text("Hold to talk",style=MaterialTheme.typography.titleLarge)
                            }
                        }
                    }
                }
            }
            else -> Page { Button(back){Text("Choose another pairing method")} }
        }
    }
    if (confirmLeave) AlertDialog(onDismissRequest={confirmLeave=false},
        title={Text("Leave this pair?")},text={Text("This phone will disconnect and clear its conversation. Pair again to start a new one.")},
        confirmButton={TextButton({confirmLeave=false; back()}){Text("Leave pair")}},
        dismissButton={TextButton({confirmLeave=false}){Text("Stay connected")}})

}
