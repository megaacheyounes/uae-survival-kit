package ae.fieldguide

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

internal val PairBlue = Color(0xffa9d8ff)
private val BroadcastAmber = Color(0xffffce76)
private val radioShape = RoundedCornerShape(16.dp)

@Composable
internal fun RadioHome(go: (String) -> Unit) = Page {
    Title("Who do you need to reach?")
    BoxWithConstraints {
        if (maxWidth >= 600.dp) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                ChannelTile(false, Modifier.weight(1f)) { go("Nearby/Open") }
                ChannelTile(true, Modifier.weight(1f)) { go("Nearby/Pair") }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                ChannelTile(false) { go("Nearby/Open") }
                ChannelTile(true) { go("Nearby/Pair") }
            }
        }
    }
}

@Composable
private fun ChannelTile(privatePair: Boolean, modifier: Modifier = Modifier, open: () -> Unit) {
    val ink = if (privatePair) PairBlue else BroadcastAmber
    val panel = if (privatePair) Color(0xff172a36) else Color(0xff302c20)
    Surface(onClick = open, modifier = modifier.fillMaxWidth(), shape = radioShape, color = panel,
        border = BorderStroke(1.dp, ink.copy(alpha = .38f))) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (privatePair) "Private Wi-Fi" else "Public Bluetooth", Modifier.weight(1f),
                    style = MaterialTheme.typography.labelLarge, color = ink)
                LinkDiagram(privatePair, ink)
            }
            Text(if (privatePair) "Trusted pair" else "Open channel", style = MaterialTheme.typography.headlineMedium)
            Text(if (privatePair) "Message or talk to one person." else "Text everyone within radio reach.",
                style = MaterialTheme.typography.bodyLarge)
            HorizontalDivider(Modifier.padding(top = 6.dp), color = ink.copy(alpha = .24f))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (privatePair) "Pair two phones" else "Listen nearby", Modifier.weight(1f),
                    style = MaterialTheme.typography.labelLarge, color = ink)
                FieldIcon(R.drawable.field_next, modifier = Modifier.size(20.dp), tint = ink)
            }
        }
    }
}

/** A broadcast reaches multiple receivers. A private link connects exactly two phones. */
@Composable
private fun LinkDiagram(privatePair: Boolean, ink: Color) = Canvas(Modifier.size(92.dp, 38.dp)) {
    fun point(x: Float, y: Float) = Offset(size.width*x, size.height*y)
    val origin = point(.16f,.5f)
    val peers = if (privatePair) listOf(point(.83f,.5f)) else listOf(point(.66f,.13f),point(.87f,.52f),point(.61f,.85f))
    for (peer in peers) {
        drawLine(ink.copy(alpha=.65f), origin, peer, 1.5.dp.toPx())
        drawCircle(ink, 3.5.dp.toPx(), peer)
    }
    if (privatePair) drawCircle(ink.copy(alpha=.55f), 8.dp.toPx(), peers.first(), style=Stroke(1.dp.toPx()))
    drawCircle(ink.copy(alpha=.5f), 9.dp.toPx(), origin, style=Stroke(1.dp.toPx()))
    drawCircle(ink, 4.dp.toPx(), origin)
}

@Composable
internal fun PairMethods(go: (String) -> Unit) = Page {
    Title("Connect with one person")
    Section("Create an invitation")
    Surface(color = MaterialTheme.colorScheme.surface, shape = radioShape,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column {
            MethodRow("Invite on this Wi-Fi", "Both phones already share a network.", R.drawable.radio_wifi) { go("Nearby/Pair/Wifi") }
            HorizontalDivider(Modifier.padding(start=66.dp))
            MethodRow("Start a hotspot", "Create a network when there’s no router.", R.drawable.radio_hotspot) { go("Nearby/Pair/Hotspot") }
        }
    }
    Spacer(Modifier.height(4.dp))
    Section("Join the other phone")
    Surface(color = Color(0xff192c37), shape = radioShape, border = BorderStroke(1.dp, PairBlue.copy(alpha=.42f))) {
        MethodRow("I have an invitation", "Scan their QR or enter their code.", R.drawable.radio_scan) { go("Nearby/Pair/Join") }
    }
}

@Composable
private fun MethodRow(name: String, detail: String, icon: Int, choose: () -> Unit) {
    Surface(onClick = choose, color = Color.Transparent, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.heightIn(min=90.dp).padding(horizontal=14.dp, vertical=14.dp),
            verticalAlignment=Alignment.CenterVertically, horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(PairBlue.copy(alpha=.1f)), contentAlignment=Alignment.Center) {
                FieldIcon(icon, tint=PairBlue)
            }
            Column(Modifier.weight(1f)) {
                Text(name, style=MaterialTheme.typography.titleMedium, fontWeight=FontWeight.Bold)
                Text(detail, style=MaterialTheme.typography.bodyMedium, color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            FieldIcon(R.drawable.field_next, modifier=Modifier.size(18.dp), tint=PairBlue)
        }
    }
}
