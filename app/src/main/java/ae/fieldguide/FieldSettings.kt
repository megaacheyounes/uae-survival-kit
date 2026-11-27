package ae.fieldguide

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import org.json.JSONObject

@Composable
internal fun SettingSwitch(title: String, enabled: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(enabled, role = Role.Switch, onValueChange = change)
        .heightIn(min = 52.dp).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, Modifier.weight(1f))
        Switch(checked = enabled, onCheckedChange = null)
    }
}

@Composable
internal fun SettingsPage(open: (String) -> Unit) {
    Page {
        Section("On this device")
        Entry("Offline downloads", "Street map, satellite imagery and optional AI", R.drawable.field_download) { open("Downloads") }
        Entry("Sources & credits", icon = R.drawable.field_info) { open("Sources") }
    }
}

@Composable
internal fun DownloadsPage(kit: Kit) {
    val map = remember(kit.revision) { kit.packs.active }
    val meta = map?.let(kit.packs::manifestFor)
    val aerial = remember(kit.revision) { kit.packs.imagery.active }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) kit.work("Opening map pack…", "UAE map is ready.", { kit.packs.importMap(uri) })
    }
    Page {
        Section("Street map")
        Text(if (meta == null) "Included with the app" else "Saved on device · " + meta.optString("date"))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(enabled = kit.busy.isEmpty(), onClick = { kit.download(Resource.MAP) }) { Text("Download map") }
            OutlinedButton(enabled = kit.busy.isEmpty(), onClick = { pick.launch(arrayOf("*/*")) }) { Text("Import pack") }
        }
        if (meta != null && meta.optInt("directory_schema") < 2)
            TextButton(enabled = kit.busy.isEmpty(), onClick = {
                kit.work("Opening included map…", "UAE map is ready.", kit.packs::installBundledMap)
            }) { Text("Use included UAE directory") }
        Section("Satellite imagery")
        if (aerial == null) {
            Hint("Whole UAE · 10 m imagery and three aerial close-ups")
            DownloadButton(kit, Resource.SATELLITE)
        } else {
            Text("UAE imagery and three aerial surveys saved")
        }
        Section("On-device AI")
        if (kit.aiReady) Text("Qwen is ready to answer offline") else ModelOptions(kit)
    }
}

@Composable
internal fun RefLink(label: String, url: String) {
    Text(buildAnnotatedString { withLink(LinkAnnotation.Url(url)) { append(label) } },
        Modifier.padding(vertical = 4.dp), color = Pine, style = MaterialTheme.typography.bodyLarge,
        textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline)
}

@Composable
private fun PhotoCredit(photo: JSONObject) {
    Text(photo.getString("taxon") + " · " + photo.getString("place"), style = MaterialTheme.typography.titleSmall)
    Hint(photo.getString("credit"))
    RefLink("Original observation", photo.getString("observation"))
}

@Composable
internal fun SourcesPage(kit: Kit, page: String, open: (String) -> Unit) {
    val book = kit.book
    Page {
        when {
            page == "Sources" -> {
                Entry("Maps & imagery", icon = R.drawable.field_layers) { open("Sources/maps") }
                Entry("Guide references", icon = R.drawable.tab_library) { open("Sources/guides") }
                Entry("Emergency contact authorities", icon = R.drawable.field_phone) { open("Sources/contacts") }
                Entry("Photo credits", icon = R.drawable.field_info) { open("Sources/photos") }
            }
            page == "Sources/maps" -> {
                val meta = kit.packs.active?.let(kit.packs::manifestFor)
                Section("Street map")
                Text(meta?.optString("attribution") ?: "© OpenStreetMap contributors")
                meta?.optString("date")?.let { Hint("Map extract · $it") }
                RefLink("OpenStreetMap · ODbL", "https://www.openstreetmap.org/copyright")
                Text("Regional outlines from Natural Earth, public domain.")
                Section("Offline satellite")
                Text("Contains modified Copernicus Sentinel data (2021) processed by the ESA WorldCover consortium. The 10 m composite was cropped, colour adjusted, reprojected and resampled.")
                RefLink("ESA WorldCover data", "https://esa-worldcover.org/en/data-access")
                RefLink("Creative Commons Attribution 4.0", "https://creativecommons.org/licenses/by/4.0/")
                Section("Local aerial surveys")
                Text("Eric Horring via OpenAerialMap. Cropped, reprojected and resampled under CC BY 4.0.")
                aerialSurveys.forEach { Hint(it.name + " · " + it.date) }
                RefLink("OpenAerialMap", "https://openaerialmap.org/")
            }
            page == "Sources/contacts" -> {
                Hint("Numbers checked " + book.checked)
                book.contacts.groupBy { it.source }.forEach { (id, entries) ->
                    val source = book.source(id)
                    Section(source.name)
                    entries.forEach { Text(it.title + " · " + it.number + " · " + it.area) }
                    RefLink("Authority directory", source.url)
                }
            }
            page == "Sources/guides" -> book.guides.sortedBy { it.title }.forEach {
                Entry(it.title) { open("Sources/guide/" + it.id) }
            }
            page == "Sources/photos" -> book.manual.photos.values.forEach { photo ->
                PhotoCredit(photo)
                HorizontalDivider()
            }
            page.startsWith("Sources/guide/") -> {
                val id = page.substringAfterLast('/')
                val guide = book.guides.first { it.id == id }
                Title(guide.title)
                if (guide.source == "manual") {
                    val lesson = book.manual.lessons.getValue(id)
                    RefLink(lesson.getString("ref"), lesson.getString("url"))
                    val steps = lesson.getJSONArray("steps")
                    val images = (0 until steps.length()).mapNotNull {
                        book.manual.photos[steps.getJSONObject(it).optString("image")]
                    }.distinctBy { it.getString("file") }
                    if (images.isNotEmpty()) Section("Photographs")
                    images.forEach { PhotoCredit(it) }
                } else {
                    val source = book.source(guide.source)
                    RefLink(source.name, source.url)
                    book.incidentSources(id).map(book::source).forEach { RefLink(it.name, it.url) }
                    Hint("Checked " + book.guideChecked(id))
                }
            }
        }
    }
}
