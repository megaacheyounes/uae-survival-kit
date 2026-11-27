package ae.fieldguide

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.widget.VideoView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.ui.Alignment
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.*

@Composable
fun LibraryPage(kit: Kit, open: (String) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var topic by rememberSaveable { mutableStateOf("General") }
    val items = remember(kit.revision) { kit.store.items() }
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null)
                kit.work(
                    "Copying into your offline library…",
                    "File imported.",
                    { kit.store.importItem(uri, topic) },
                )
        }
    Page {
        var importOptions by rememberSaveable { mutableStateOf(false) }
        Button(onClick = { importOptions = true }, enabled = kit.busy.isEmpty(), shape = MaterialTheme.shapes.small) {
            FieldIcon(R.drawable.field_folder); Spacer(Modifier.width(8.dp)); Text("Import PDF or video")
        }
        OutlinedTextField(
            query,
            { query = it },
            label = { Text("Search file titles and topics") },
            modifier = Modifier.fillMaxWidth(),
        )
        if (importOptions) AlertDialog(onDismissRequest = { importOptions = false },
            title = { Text("Import to personal files") },
            text = { OutlinedTextField(topic, { topic = it.take(80) }, label = { Text("Topic") }, singleLine = true) },
            confirmButton = { Button(onClick = {
                importOptions = false
                picker.launch(arrayOf("application/pdf", "video/*"))
            }) { Text("Choose file") } },
            dismissButton = { TextButton(onClick = { importOptions = false }) { Text("Cancel") } })
        val filtered =
            items.filter { it.title.contains(query, true) || it.topic.contains(query, true) }
        filtered
            .groupBy { it.topic }
            .forEach { (group, rows) ->
                Section(group)
                rows.forEach { Entry(it.title, it.kind) { open("Item/" + it.id) } }
            }
        if (items.isEmpty()) Hint("Your imported files will be available offline here.")
        if (query.isNotBlank() && filtered.isEmpty())
            Text("No matching files.")
    }
}

@Composable
fun ReaderPage(kit: Kit, id: Long, back: () -> Unit, startPage: Int? = null) {
    val item = remember(id) { kit.store.items().find { it.id == id } }
    if (item == null) {
        ReaderBar("File unavailable", back)
        return
    }
    var editing by rememberSaveable { mutableStateOf(false) }
    var topic by rememberSaveable { mutableStateOf(item.topic) }
    if (editing) {
        var draft by rememberSaveable { mutableStateOf(topic) }
        AlertDialog(onDismissRequest = { editing = false }, title = { Text("Edit topic") },
            text = { OutlinedTextField(draft, { draft = it.take(80) }, singleLine = true, label = { Text("Topic") }) },
            confirmButton = { TextButton(onClick = {
                kit.store.topic(id, draft)
                topic = draft.trim().ifBlank { "General" }
                kit.revision++
                editing = false
            }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { editing = false }) { Text("Cancel") } })
    }
    if (item.kind == "PDF") PdfPage(kit, item, back, startPage = startPage, editTopic = { editing = true })
    else Column(Modifier.fillMaxSize()) {
        ReaderBar(item.title, back) { TextButton(onClick = { editing = true }) { Text("Edit topic") } }
        VideoPage(kit, item)
    }
}

private data class PdfImage(val bitmap: Bitmap, val count: Int)

@Composable
fun PdfPage(kit: Kit, item: Item, back: () -> Unit, editTopic: (() -> Unit)? = null, startPage: Int? = null, savePage: (Int) -> Unit = { kit.store.position(item.id, it) }) {
    var page by rememberSaveable { mutableIntStateOf(startPage ?: item.position) }
    var bookmark by rememberSaveable { mutableIntStateOf(item.position) }
    var zoom by rememberSaveable { mutableIntStateOf(1) }
    var result by remember { mutableStateOf<PdfImage?>(null) }
    var error by remember { mutableStateOf("") }
    LaunchedEffect(page, zoom) {
        result = null
        try {
            result =
                withContext(Dispatchers.IO) {
                    ParcelFileDescriptor.open(item.file, ParcelFileDescriptor.MODE_READ_ONLY).use {
                        fd ->
                        PdfRenderer(fd).use { renderer ->
                            renderer.openPage(page.coerceIn(0, renderer.pageCount - 1)).use { p ->
                                val scale = (if (zoom == 1) 1600f else 3200f) / maxOf(p.width, p.height, 1)
                                val bmp =
                                    Bitmap.createBitmap(
                                        maxOf(1, (p.width * scale).toInt()),
                                        maxOf(1, (p.height * scale).toInt()),
                                        Bitmap.Config.ARGB_8888,
                                    )
                                bmp.eraseColor(android.graphics.Color.WHITE)
                                p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                PdfImage(bmp, renderer.pageCount)
                            }
                        }
                    }
                }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.localizedMessage ?: "Cannot open this PDF."
        }
    }
    val context = androidx.compose.ui.platform.LocalContext.current
    val image = result
    var menu by remember { mutableStateOf(false) }
    var count by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(image) { if (image != null) count = image.count }
    Column(Modifier.fillMaxSize()) {
        ReaderBar(item.title, back) {
            Box {
                TextButton(onClick = { menu = true }) { Text("Tools") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(if (zoom == 1) "Enlarge page" else "Fit page") },
                        onClick = { zoom = if (zoom == 1) 3 else 1; menu = false })
                    DropdownMenuItem(text = { Text("Bookmark this page") }, onClick = {
                        savePage(page); bookmark = page; menu = false
                        android.widget.Toast.makeText(context, "Page ${page + 1} bookmarked", android.widget.Toast.LENGTH_SHORT).show()
                    })
                    if (bookmark != page) DropdownMenuItem(text = { Text("Open bookmark · page ${bookmark+1}") },
                        onClick = { page = bookmark; menu = false })
                    if (editTopic != null) DropdownMenuItem(text = { Text("Edit topic") }, onClick = { menu = false; editTopic() })
                }
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center) {
            if (image != null) key(page, zoom) {
                val ratio = image.bitmap.width.toFloat() / image.bitmap.height
                val width = minOf(maxWidth, maxHeight * ratio) * zoom
                Box(Modifier.verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState())) {
                    val paper = androidx.compose.ui.graphics.ColorFilter.tint(
                        androidx.compose.ui.graphics.Color(0xffb8b8b8), androidx.compose.ui.graphics.BlendMode.Modulate
                    )
                    Image(image.bitmap.asImageBitmap(), "Page ${page+1}", Modifier.width(width).aspectRatio(ratio), colorFilter = paper)
                }
            } else if (error.isNotEmpty()) Text(error) else CircularProgressIndicator()
        }
        ReaderFooter(page, count, { page-- }, { page++ })
    }
}

@Composable
private fun ColumnScope.VideoPage(kit: Kit, item: Item) {
    var view by remember { mutableStateOf<VideoView?>(null) }
    var position by rememberSaveable { mutableIntStateOf(item.position) }
    var prepared by remember { mutableStateOf(false) }
    var media by remember { mutableStateOf<android.media.MediaPlayer?>(null) }
    var seekPending by remember { mutableStateOf(false) }
    fun seekPrecisely(target: Int) {
        val engine = media ?: return
        position = target
        seekPending = true
        kit.store.position(item.id, target)
        engine.seekTo(target.toLong(), android.media.MediaPlayer.SEEK_CLOSEST)
    }
    var duration by remember { mutableIntStateOf(0) }
    var seeking by remember { mutableStateOf<Float?>(null) }
    var error by remember { mutableStateOf("") }
    var aspect by remember { mutableFloatStateOf(16f / 9f) }
    Box(Modifier.fillMaxWidth().weight(1f).background(androidx.compose.ui.graphics.Color.Black), contentAlignment = Alignment.Center) {
        AndroidView(
            modifier = Modifier.aspectRatio(aspect),
            factory = { ctx ->
                VideoView(ctx).apply {
                    val player = this
                    view = player

                    setOnPreparedListener { engine ->
                        if (engine.videoWidth > 0 && engine.videoHeight > 0) aspect = engine.videoWidth.toFloat() / engine.videoHeight
                        media = engine
                        prepared = true
                        duration = engine.duration.coerceAtLeast(0)
                        engine.setOnSeekCompleteListener { seekPending = false }
                        if (position > 0) seekPrecisely(position)
                    }
                    setOnCompletionListener {
                        position = 0
                        kit.store.position(item.id, 0)
                        prepared = false
                    }
                    setOnErrorListener { _, _, _ ->
                        error = "Android could not play this video format."
                        true
                    }
                    setVideoPath(item.file.path)
                }
            },
        )
    }
    if (error.isNotEmpty()) Text(error, Modifier.padding(12.dp))
    val player = view
    var playing by remember { mutableStateOf(false) }
    if (player != null && error.isEmpty()) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Button(onClick = {
                if (player.isPlaying) player.pause() else player.start()
                playing = player.isPlaying
            }) { Text(if (playing) "Pause" else "Play") }
            val seconds = (seeking?.toInt() ?: position) / 1000
            val end = duration / 1000
            Text("%d:%02d / %d:%02d".format(seconds / 60, seconds % 60, end / 60, end % 60),
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
        }
    }
    if (player != null && duration > 0 && error.isEmpty()) {
        Slider(
            value = seeking ?: position.coerceIn(0, duration).toFloat(),
            valueRange = 0f..duration.toFloat(),
            onValueChange = { seeking = it },
            onValueChangeFinished = {
                seeking?.toInt()?.let { next ->
                    seekPrecisely(next)
                }
                seeking = null
            },
            modifier = Modifier.padding(horizontal = 16.dp).semantics { contentDescription = "Video position" },
        )
    }
    LaunchedEffect(player) {
        if (player != null)
            while (isActive) {
                playing = player.isPlaying
                if (playing) prepared = true
                if (prepared && !seekPending && player.currentPosition > 0 && player.currentPosition != position) {
                    position = player.currentPosition
                    kit.store.position(item.id, position)
                }
                delay(750)
            }
    }
    DisposableEffect(player) {
        onDispose {
            if (player != null) {
                if (prepared && !seekPending && player.currentPosition > 0)
                    kit.store.position(item.id, player.currentPosition)
                player.stopPlayback()
            }
        }
    }

}
