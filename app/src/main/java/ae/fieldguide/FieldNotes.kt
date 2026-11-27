package ae.fieldguide

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class FieldNote(val place: Place, val body: String, val checked: Long) {
    fun date(): String = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(checked))
}

@Composable
internal fun EditFieldNote(store: Store, place: Place, close: () -> Unit) {
    var draft by rememberSaveable { mutableStateOf<String?>(null) }
    var existed by remember { mutableStateOf(false) }
    var removing by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    LaunchedEffect(place) {
        val previous = withContext(Dispatchers.IO) { store.loadNote(place) }
        existed = previous != null
        if (draft == null) draft = previous.orEmpty()
    }
    fun save(remove: Boolean) {
        busy = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if (remove) store.removeNote(place) else store.saveNote(place, draft.orEmpty())
                }
                close()
            } catch (e: android.database.SQLException) {
                busy = false
                error = "The note could not be saved on this phone."
            }
        }
    }
    AlertDialog(
        onDismissRequest = { if (!busy) close() },
        title = { Text(if (removing) "Remove this field note?" else "Field note") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(place.title, style = MaterialTheme.typography.titleSmall)
                if (!removing) {
                    Text(
                        "Record what you found at this location. Saving updates the date.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(
                        draft.orEmpty(),
                        { draft = it },
                        Modifier.fillMaxWidth(),
                        enabled = !busy && draft != null,
                        minLines = 3,
                        maxLines = 7,
                        label = { Text("Your observation") },
                    )
                    if (existed)
                        TextButton(enabled = !busy, onClick = { removing = true }) {
                            Text("Remove note")
                        }
                }
                if (error.isNotEmpty()) Text(error)
            }
        },
        confirmButton = {
            Button(
                enabled = !busy && (removing || !draft.isNullOrBlank()),
                onClick = { save(removing) },
            ) {
                Text(if (removing) "Remove" else "Save note")
            }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = { if (removing) removing = false else close() }) {
                Text("Cancel")
            }
        },
    )
}

@Composable
internal fun FieldNotesDialog(store: Store, back: () -> Unit, select: (Place) -> Unit) {
    val notes by
        produceState<List<FieldNote>?>(null, store) {
            value = withContext(Dispatchers.IO) { store.fieldNotes() }
        }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf("") }
    val export =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) {
            uri ->
            if (uri != null)
                scope.launch {
                    val entries = notes.orEmpty()
                    message =
                        try {
                            withContext(Dispatchers.IO) {
                                requireNotNull(ctx.contentResolver.openOutputStream(uri))
                                    .bufferedWriter()
                                    .use { out ->
                                        for (note in entries) {
                                            val p = note.place
                                            out.appendLine(p.title).appendLine(note.date())
                                            out.appendLine(
                                                String.format(
                                                    Locale.ROOT,
                                                    "%.5f, %.5f",
                                                    p.lat,
                                                    p.lon,
                                                )
                                            )
                                            out.appendLine(p.description)
                                                .appendLine(note.body)
                                                .appendLine()
                                        }
                                    }
                            }
                            "Field notes exported."
                        } catch (e: Exception) {
                            "Could not export to that file."
                        }
                }
        }
    Dialog(
        onDismissRequest = back,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(Modifier.fillMaxSize()) {
            Column {
                ReaderBar("My field notes", back) {
                    if (!notes.isNullOrEmpty())
                        TextButton(onClick = { export.launch("UAE-field-notes.txt") }) {
                            Text("Export text")
                        }
                }
                if (message.isNotEmpty()) Text(message, Modifier.padding(12.dp))
                val entries = notes
                if (entries == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                else if (entries.isEmpty())
                    Text(
                        "Open a place on the map and tap Field note to record a visit.",
                        Modifier.padding(16.dp),
                    )
                else
                    LazyColumn {
                        items(entries.size) { index ->
                            val note = entries[index]
                            Column(
                                Modifier.fillMaxWidth()
                                    .clickable { select(note.place) }
                                    .padding(16.dp)
                            ) {
                                Text(note.place.title, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    note.date(),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = Pine,
                                )
                                Text(note.body, Modifier.padding(top = 4.dp))
                            }
                            HorizontalDivider()
                        }
                    }
            }
        }
    }
}
