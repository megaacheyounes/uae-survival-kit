package ae.fieldguide

import androidx.compose.ui.res.stringResource
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
private fun dialAction(kit: Kit): (Contact) -> Unit {
    val ctx = LocalContext.current
    return { contact ->
        runCatching { ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", contact.number, null))) }
            .onFailure { kit.message = "No dialer is available on this device." }
    }
}

@Composable
fun ContactRow(c: Contact, kit: Kit) {
    val dial = dialAction(kit)
    Surface(onClick = { dial(c) }, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Row(Modifier.heightIn(min = 58.dp).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FieldIcon(R.drawable.field_phone, tint = Pine)
            Text(c.title, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
            Text(c.number, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = Pine)
        }
    }
}

private data class Action(val title: String, val icon: Int, val route: String)
private val actions = listOf(
    Action("Find water", R.drawable.field_water, "Guide/findwater/0"),
    Action("Power outage", R.drawable.field_power, "Guide/blackout/0"),
    Action("Flooded road", R.drawable.field_flood, "Guide/wadi/0"),
    Action("Find north", R.drawable.field_compass, "North")
)

@Composable
internal fun FieldHome(kit: Kit, open: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column {
        CommandBar(stringResource(R.string.app_name), R.drawable.shelter_case) {
            IconButton(onClick = { open("Settings") }) { FieldIcon(R.drawable.field_controls, "Settings and downloads") }
        }
        Page {
            Surface(onClick = { open("Kit/shelter") },
                color = Color(0xff342d1d),
                contentColor = Signal,
                border = BorderStroke(1.dp, Color(0xff76623b)),
                shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.heightIn(min = 82.dp).padding(horizontal = 15.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    FieldIcon(R.drawable.field_fallout, modifier = Modifier.size(36.dp))
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.kit_shelter), style = MaterialTheme.typography.titleLarge)
                    }
                    FieldIcon(R.drawable.field_next, modifier = Modifier.size(20.dp))
                }
            }
            Entry(stringResource(R.string.water_title), icon = R.drawable.field_water) { open("Kit/water") }
            BoxWithConstraints {
                val power = stringResource(R.string.kit_power)
                val tools = listOf(Action(power, R.drawable.field_power, "Kit/power"), Action("Field radio", R.drawable.radio_wave, "Nearby"))
                if (maxWidth < 330.dp || LocalDensity.current.fontScale > 1.4f) {
                    Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
                        tools.forEach { Entry(it.title, icon=it.icon) { open(it.route) } }
                    }
                } else Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    tools.forEach { tool ->
                        Surface(onClick={ open(tool.route) }, modifier=Modifier.weight(1f).fillMaxHeight(),
                            shape=MaterialTheme.shapes.medium, color=colors.surfaceVariant) {
                            Column(Modifier.heightIn(min=84.dp).padding(12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                                FieldIcon(tool.icon, tint=Pine)
                                Text(tool.title,style=MaterialTheme.typography.titleSmall)
                            }
                        }
                    }
                }
            }
            Section("Immediate actions")
            BoxWithConstraints {
                val columns = if (maxWidth < 295.dp || LocalDensity.current.fontScale > 1.6f) 1 else 2
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    actions.chunked(columns).forEach { line ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            line.forEach { a ->
                                Surface(onClick = { open(a.route) }, modifier = Modifier.weight(1f),
                                    color = colors.surface, border = BorderStroke(1.dp, colors.outlineVariant),
                                    shape = MaterialTheme.shapes.small) {
                                    Row(Modifier.heightIn(min = 56.dp).padding(horizontal = 10.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        FieldIcon(a.icon, modifier = Modifier.size(22.dp), tint = colors.primary)
                                        Text(a.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Entry("More emergencies", icon = R.drawable.field_alert) { open("Situation") }
            Section("Emergency calls")
            HomeContacts(kit)
            TextButton(onClick = { open("Contacts") }) { Text("All emergency numbers") }
            Section("Field tools")
            Entry("Field practice & checklists", icon = R.drawable.field_check) { open("Practice") }
            Entry("Emirate guides", "Local contacts, hazards and maps", R.drawable.field_pin) { open("Emirates") }
        }
    }
}

@Composable
private fun HomeContacts(kit: Kit) {
    val contacts = kit.book.contacts.filter { it.area == "UAE" }.take(3)
    val dial = dialAction(kit)
    if (LocalDensity.current.fontScale > 1.5f) {
        contacts.forEach { ContactRow(it, kit) }
    } else {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            contacts.forEach { c ->
                Surface(onClick = { dial(c) }, color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.weight(1f), shape = MaterialTheme.shapes.small) {
                    Column(Modifier.heightIn(min = 64.dp).padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(c.number, fontSize = 23.sp, lineHeight = 26.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                        Text(c.title.substringBefore(" ·"), style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}
