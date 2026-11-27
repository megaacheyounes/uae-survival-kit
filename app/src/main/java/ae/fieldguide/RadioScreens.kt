package ae.fieldguide

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
internal fun RadioStatus(title:String, detail:String, icon:Int=R.drawable.pair_lock, attention:Boolean=false) {
    val ink=if(attention) Signal else PairBlue
    Row(Modifier.fillMaxWidth().background(if(attention) androidx.compose.ui.graphics.Color(0xff302c20)
        else androidx.compose.ui.graphics.Color(0xff192c37)).padding(14.dp),
        verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){
        FieldIcon(icon,tint=ink)
        Column {
            Text(title,style=MaterialTheme.typography.titleMedium,color=ink)
            Hint(detail)
        }
    }
}

@Composable
private fun ColumnScope.Timeline(size:Int,empty:String, item:@Composable (Int)->Unit) {
    if(size==0) {
        Box(Modifier.fillMaxWidth().weight(1f).padding(18.dp),contentAlignment=Alignment.Center){Hint(empty)}
    } else {
        val scroll=rememberLazyListState()
        LazyColumn(Modifier.weight(1f).fillMaxWidth(),state=scroll,reverseLayout=true,
            contentPadding=PaddingValues(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            items(size){ i -> item(size-1-i) }
        }
    }
}

@Composable
private fun MessageLine(sender:String,body:String,footer:String="", mine:Boolean=false, play:(()->Unit)?=null) {
    Box(Modifier.fillMaxWidth(),contentAlignment=if(mine) Alignment.CenterEnd else Alignment.CenterStart) {
    Surface(Modifier.widthIn(max=420.dp),shape=MaterialTheme.shapes.medium,color=if(mine)MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.padding(horizontal=14.dp,vertical=9.dp)) {
            Text(sender,style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.primary)
            if(play==null) Text(body,style=MaterialTheme.typography.bodyLarge)
            else TextButton(play){Text("Play voice note")}
            if(footer.isNotEmpty()) Hint(footer)
        }
    }
    }
}

@Composable
private fun Composer(label:String, enabled:Boolean, publicChannel:Boolean=false, send:(String)->Boolean) {
    var draft by rememberSaveable {mutableStateOf("")}
    val limit=if(publicChannel) OpenChannelWire.MAX_TEXT_BYTES else 1000
    val used=if(publicChannel) draft.toByteArray().size else draft.length
    val over=used>limit
    Row(Modifier.fillMaxWidth().padding(horizontal=10.dp,vertical=8.dp),
        verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(draft,{draft=it.take(4096)},Modifier.weight(1f),label={Text(label)},maxLines=3,isError=over,
            supportingText=if(used>limit*3/4) ({ Text(if(publicChannel) "$used / $limit bytes" else "$used / $limit characters") }) else null)
        Button({if(send(draft.trim()))draft=""},enabled=enabled && !over && draft.isNotBlank()){Text("Send")}
    }
}

@Composable
internal fun ColumnScope.PrivateMessages(room:NearbyRoom,checks:Map<String,String>) {
    Timeline(room.posts.size,"Your private conversation starts here."){ i ->
        val m=room.posts[i]
        val mine=m.sender==room.localName()
        MessageLine(if(mine)"You" else m.sender,checks[m.body].takeIf {m.kind=="check"} ?: m.body,
            if(m.received>0)"Received" else "Waiting for receipt",
            mine,
            if(m.kind=="note") ({room.playNote(m.body)}) else null)
    }
    var checkMenu by remember {mutableStateOf(false)}
    Box {
        TextButton({checkMenu=true}){Text("Send a quick check-in")}
        DropdownMenu(checkMenu,{checkMenu=false}){
            checks.forEach{(key,value)->DropdownMenuItem(text={Text(value)},onClick={room.send("check",key);checkMenu=false})}
        }
    }
    Composer("Private message",true){room.send("text",it)}
}

@Composable
internal fun OpenChannelPage(kit:Kit) {
    val ctx=LocalContext.current
    val channel=remember {kit.openChannel ?: OpenChannel(ctx.applicationContext).also {kit.openChannel=it}}
    val permissions=remember {
        if(Build.VERSION.SDK_INT>=31) arrayOf(Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_ADVERTISE,Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }
    fun allowed()=permissions.all {ctx.checkSelfPermission(it)==PackageManager.PERMISSION_GRANTED}
    val request=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){if(allowed())channel.start()}
    LaunchedEffect(channel){if(!channel.running && allowed())channel.start()}
    DisposableEffect(channel){ onDispose {if(!ctx.rotating()){channel.close();kit.openChannel=null}} }
    Column(Modifier.fillMaxSize()) {
        val count=channel.links
        RadioStatus(if(count==0 && channel.running)"Listening nearby" else if(count==0)"Bluetooth channel" else "Connected nearby",
            if(count==0)"Anyone nearby can join this public conversation." else "Your public name is "+channel.nickname,
            icon=R.drawable.radio_wave,attention=true)
        Text("Anyone can use a false name or give misleading directions. Check claims before acting.",
            Modifier.padding(horizontal=16.dp,vertical=10.dp),style=MaterialTheme.typography.bodySmall)
        if(channel.status.isNotEmpty()) Text(channel.status,Modifier.padding(horizontal=16.dp))
        if(!channel.running) Button({if(allowed())channel.start() else request.launch(permissions)},Modifier.padding(horizontal=16.dp)){Text("Enable Bluetooth channel")}
        Timeline(channel.lines.size,if(count==0)"Waiting for a nearby phone. Keep the channel open." else "Say hello to the channel."){ i ->
            val line=channel.lines[i];MessageLine(line.from,line.body,mine=line.mine)
        }
        Composer("Public message",count>0,true,channel::send)
    }
}
