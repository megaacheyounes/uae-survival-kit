package ae.fieldguide

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MapControls(
    satellite: Boolean, installed: Boolean, largeLabels: Boolean, busy: Boolean,
    setSatellite: (Boolean) -> Unit, setLargeLabels: (Boolean) -> Unit,
    overview: () -> Unit, chooseSurvey: (AerialSurvey) -> Unit,
    download: () -> Unit, manage: () -> Unit, close: () -> Unit,
) {
    var surveys by rememberSaveable { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = close, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.background, dragHandle = null) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (surveys) IconButton(onClick = { surveys = false }) { FieldIcon(R.drawable.back_arrow, "Back to map options") }
            Text(if (surveys) "Aerial close-ups" else "Map options", Modifier.weight(1f).padding(start = 4.dp),
                style = MaterialTheme.typography.titleLarge)
            IconButton(onClick = close) { FieldIcon(R.drawable.map_clear, "Close map options") }
        }
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)
            .padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (surveys) {
                aerialSurveys.forEach { area ->
                    Entry(area.name, area.date, R.drawable.field_pin) { chooseSurvey(area) }
                }
            } else {
                FieldTabs(listOf("Street", "Satellite"), if (satellite) "Satellite" else "Street") {
                    if (it == "Satellite" && !installed) manage() else setSatellite(it == "Satellite")
                }
                if (installed) Entry("Aerial close-ups", icon = R.drawable.field_layers) { surveys = true }
                else Button(onClick = download, enabled = !busy, modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.small) { Text("Save satellite imagery · " + Resource.SATELLITE.label.substringAfter(" · ")) }
                Entry("Show UAE overview", icon = R.drawable.tab_map, open = overview)
                SettingSwitch("Larger map labels", largeLabels, setLargeLabels)
                TextButton(onClick = manage) { Text("Manage offline maps") }
            }
        }
    }
}
