package ae.fieldguide

import android.content.Context
import android.print.PrintManager
import android.text.TextUtils
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }

private fun String.html() = TextUtils.htmlEncode(this)

class Manual(private val ctx: Context) {
    private fun read(name: String) =
        ctx.assets.open("manual/$name").bufferedReader().use { JSONArray(it.readText()).objects() }

    fun diagram(key: String): String = ctx.assets.open("manual/$key.svg").bufferedReader().use { it.readText() }

    val lessons = read("lessons.json").associateBy { it.getString("id") }
    internal val scenarios by lazy {
        ctx.assets.open("manual/practice.json").bufferedReader().use { readScenarios(JSONArray(it.readText())) }
    }
    val photos = read("photos.json").associateBy { it.getString("file") }
    val prefs = ctx.getSharedPreferences("field-manual", Context.MODE_PRIVATE)
    val guides =
        lessons.map { (id, l) ->
            val steps = l.getJSONArray("steps").objects()
            Guide(
                id,
                l.getString("title"),
                l.getString("tags"),
                steps.map {
                    it.getString("heading") + ". " + it.getString("body")
                },
                "manual",
                buildMap { steps.forEachIndexed { index, step -> if (step.has("id")) put(step.getString("id"), index) } },
            )
        }
}

internal val booklets =
    mapOf(
        "dubai-refill" to "Dubai Can drinking-water refill map (2024)",
        "abu-dhabi-fishing" to "Abu Dhabi recreational fishing",
        "emergency-cards" to "Emergency preparedness sheets",
    )

@Composable
fun SituationPage(open: (String) -> Unit) {
    Page {
        Entry("Fallout shelter", icon = R.drawable.field_fallout) { open("Guide/fallout/0") }
        Entry("Running low on water", icon = R.drawable.field_water) { open("Guide/findwater/0") }
        Entry("Lost after dark", icon = R.drawable.field_compass) { open("Guide/stars/0") }
        Entry("Power or AC failed", icon = R.drawable.field_power) { open("Guide/blackout/0") }
        Entry("Vehicle stranded", icon = R.drawable.poi_repair) { open("Guide/shade/0") }
        Entry("Flooded road or wadi", icon = R.drawable.field_flood) { open("Guide/wadi/0") }
        Entry("Extreme heat", icon = R.drawable.field_sun) { open("Guide/heat/0") }
        Entry("Household fire and evacuation", icon = R.drawable.poi_fire) { open("Guide/home/0") }
    }
}

@Composable
fun ChapterPage(kit: Kit, chapter: String, open: (String) -> Unit) {
    Page {
        kit.book.manual.lessons
            .filterValues { it.getString("chapter") == chapter }
            .forEach { (id, l) ->
                Entry(l.getString("title"), "${l.getJSONArray("steps").length()} steps") {
                    open("Guide/$id")
                }
            }
        if (chapter == "Water")
            Entry(booklets.getValue("dubai-refill"), "Named fountains and city map") {
                open("Booklet/dubai-refill")
            }
        if (chapter == "Navigation")
            Entry("My coordinates", "Request a satellite fix") { open("Map") }
        if (chapter == "Plant & wildlife atlas")
            Entry(booklets.getValue("abu-dhabi-fishing"), "Illustrated species sheet") {
                open("Booklet/abu-dhabi-fishing")
            }
    }
}

private fun printableArticle(manual: Manual, lesson: JSONObject): String {
    val title = lesson.getString("title").html()
    val steps = lesson.getJSONArray("steps").objects()
    val content = buildString {
        append("<h1>$title</h1>")
        steps.forEachIndexed { i, s ->
            append("<section><small>Step ${i + 1} of ${steps.size}</small>")
            append("<h2>${s.getString("heading").html()}</h2><p>${s.getString("body").html()}</p>")
            if (s.has("image")) {
                val key = s.getString("image")
                val photo = manual.photos[key]
                if (photo == null) append(manual.diagram(key)) else {
                    append("<img alt='${photo.getString("taxon").html()}' src='$key'>")
                    append("<aside>${photo.getString("taxon").html()} · ${photo.getString("place").html()}</aside>")
                    append("<aside>${photo.getString("credit").html()} · ${photo.getString("observation").html()}</aside>")
                }
            }
            append("</section>")
        }
        append("<aside>${lesson.getString("ref").html()}<br>${lesson.getString("url").html()}</aside>")
    }
    return """<!doctype html><html lang="en"><meta charset="UTF-8"><meta name="viewport" content="width=device-width, initial-scale=1">
        <style>*{box-sizing:border-box}body{max-width:44rem;margin:0 auto;padding:18px 16px;font:19px/1.55 Roboto,sans-serif}small{letter-spacing:.12em;font-size:13px}
        h1,h2{font-size:1.45em;line-height:1.18;margin:0 0 .45em;font-weight:750}p{margin:.7em 0}img,svg{display:block;width:100%;height:auto;object-fit:contain}
        aside{font-size:13px;overflow-wrap:anywhere;margin:8px 0}
        @media print{body{font:11pt/1.35 sans-serif;color:black;background:white}section{break-inside:avoid}img,svg{width:55%;max-height:135px}details{display:none}}</style><body>$content</body></html>"""
}

private const val manualBase = "https://manual.invalid/"

private open class ManualImages(private val ctx: Context, private val manual: Manual, private val html: String) : WebViewClient() {
    override fun shouldInterceptRequest(view: WebView, request: android.webkit.WebResourceRequest): android.webkit.WebResourceResponse {
        val uri = request.url
        if (uri.host == "manual.invalid" && uri.path == "/lesson") return android.webkit.WebResourceResponse("text/html", "UTF-8", html.byteInputStream())
        val file = uri.lastPathSegment
        if (uri.host == "manual.invalid" && file != null && manual.photos.containsKey(file)) {
            return android.webkit.WebResourceResponse("image/jpeg", null, ctx.assets.open("manual/$file"))
        }
        return android.webkit.WebResourceResponse("text/plain", "UTF-8", null)
    }
}

@Composable
fun LessonReader(kit: Kit, id: String, cited: Int?, back: () -> Unit, sources: () -> Unit) {
    val manual = kit.book.manual
    val ctx = LocalContext.current
    val lesson = manual.lessons.getValue(id)
    val count = lesson.getJSONArray("steps").length()
    var index by
        rememberSaveable(id, cited) {
            mutableIntStateOf((cited ?: manual.prefs.getInt("step/$id", 0)).coerceIn(0, count - 1))
        }
    var marked by remember(id) { mutableIntStateOf(manual.prefs.getInt("mark/$id", -1)) }
    var menu by remember { mutableStateOf(false) }
    LaunchedEffect(index, id) {
        manual.prefs.edit().putString("recent", id).putInt("step/$id", index).apply()
    }
    Column(Modifier.fillMaxSize()) {
        ReaderBar(lesson.getString("title"), back) {
            Box {
                TextButton(onClick = { menu = true }) { Text("Tools") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(if (marked == index) "Remove bookmark" else "Save step") },
                        onClick = {
                            marked = if (marked == index) -1 else index
                            manual.prefs
                                .edit()
                                .apply {
                                    if (marked >= 0) putInt("mark/$id", marked)
                                    else remove("mark/$id")
                                }
                                .apply()
                            menu = false
                        },
                    )
                    DropdownMenuItem(text = { Text("Guide source") }, onClick = { menu = false; sources() })
                    DropdownMenuItem(
                        text = { Text("Print full lesson") },
                        onClick = {
                            menu = false
                            WebView(ctx).apply {
                                webViewClient =
                                    object : ManualImages(ctx, manual, printableArticle(manual, lesson)) {
                                        override fun onPageFinished(view: WebView, url: String) {
                                            val title = lesson.getString("title")
                                            ctx.getSystemService(PrintManager::class.java)
                                                .print(
                                                    title,
                                                    view.createPrintDocumentAdapter(title),
                                                    null,
                                                )
                                        }
                                    }
                                loadUrl(manualBase + "lesson")
                            }
                        },
                    )
                }
            }
        }
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.TopCenter) {
            key(id, index) {
                val step = lesson.getJSONArray("steps").getJSONObject(index)
                val heading = step.getString("heading")
                Page {
                    Title(heading)
                    Text(step.getString("body"), style = MaterialTheme.typography.bodyLarge)
                    if (step.has("image")) {
                        val asset = step.getString("image")
                        GuideIllustration(asset, heading, manual.photos[asset])
                    }
                }
            }
        }
        ReaderFooter(index, count, { index-- }, { index++ }, back, savedStep = index == marked)
    }
}

@Composable
fun Booklet(kit: Kit, id: String, back: () -> Unit, startPage: Int? = null) {
    val ctx = LocalContext.current
    val prefs = kit.book.manual.prefs
    var file by remember(id) { mutableStateOf<File?>(null) }
    var error by remember(id) { mutableStateOf("") }
    LaunchedEffect(id) {
        try {
            file =
                withContext(Dispatchers.IO) {
                    require(booklets.containsKey(id))
                    val out = File(ctx.cacheDir, "manual-1.2-$id.pdf")
                    if (!out.exists()) {
                        val temp = File.createTempFile("booklet-", ".part", ctx.cacheDir)
                        try {
                            ctx.assets.open("manual/$id.pdf").use { input ->
                                temp.outputStream().use { input.copyTo(it) }
                            }
                            android.system.Os.rename(temp.path, out.path)
                        } finally {
                            temp.delete()
                        }
                    }
                    out
                }
        } catch (e: java.io.IOException) {
            error = "Could not open this booklet. Check free storage and reopen it."
        }
    }
    Column(Modifier.fillMaxSize()) {
        if (file == null) ReaderBar(booklets.getValue(id), back)
        file?.let {
            PdfPage(
                kit,
                Item(-1, booklets.getValue(id), "", "PDF", it, prefs.getInt("pdf/$id", 0)), back, startPage = startPage,
            ) { page ->
                prefs.edit().putInt("pdf/$id", page).apply()
            }
        } ?: Text(error.ifEmpty { "Opening booklet…" })
    }
}
