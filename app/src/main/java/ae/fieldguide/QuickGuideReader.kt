package ae.fieldguide

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun QuickGuideReader(kit: Kit, guide: Guide, cited: Int?, back: () -> Unit, sources: () -> Unit) {
    val steps = guide.steps
    var index by rememberSaveable(guide.id, cited) { mutableIntStateOf((cited ?: 0).coerceIn(steps.indices)) }
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        ReaderBar(guide.title, back) {
            Box {
                TextButton(onClick = { menu = true }) { Text("Tools") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Guide source") }, onClick = { menu = false; sources() })
                }
            }
        }
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.TopCenter) {
            key(index) {
                Page {
                    val step = steps[index]
                    val heading = step.substringBefore(". ")
                    Title(heading)
                    if (heading != step) Text(step.substringAfter(". "), style = MaterialTheme.typography.bodyLarge)
                    if (guide.id == "home") ContactRow(kit.book.contacts.first { it.number == "997" }, kit)
                }
            }
        }
        ReaderFooter(index, steps.size, { index-- }, { index++ }, back)
    }
}
