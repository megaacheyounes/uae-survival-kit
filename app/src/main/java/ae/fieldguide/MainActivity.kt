package ae.fieldguide

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import org.mapsforge.map.android.graphics.AndroidGraphicFactory

class Kit(ctx: Context) {
    internal var openChannel: OpenChannel? = null
    internal var nearbyRoom: NearbyRoom? = null
    val book = Fieldbook(ctx)
    val store = Store(ctx, book)
    val packs = Packs(ctx).apply { cleanStaging() }
    var revision by mutableIntStateOf(0)
    var busy by mutableStateOf("")
    var message by mutableStateOf("")
    var aiReady by mutableStateOf(packs.aiEnabled)
    internal var lastAsk: Pair<AskRequest, AskAnswer>? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    // An interrupted imagery install can leave thousands of files. Home can open while they are removed.
    private val imageryCleanup = scope.async(Dispatchers.IO) { packs.imagery.cleanStaging() }

    @Volatile var pauseDownload = false
    var downloadPercent by mutableIntStateOf(-1)

    fun download(resource: Resource) {
        if (busy.isNotEmpty()) return
        pauseDownload = false
        downloadPercent = 0
        work("Downloading " + resource.label, when (resource) {
            Resource.MAP -> "UAE map is ready."
            Resource.MODEL -> "Local AI is ready."
            Resource.SATELLITE -> "Offline imagery is ready. Open Layers for aerial close-ups."
        }, {
            if (resource == Resource.SATELLITE) imageryCleanup.await()
            resource.install(packs, { pauseDownload }) { percent ->
                scope.launch { downloadPercent = percent }
            }
        }, { if (resource == Resource.MODEL) aiReady = true })
    }

    fun work(label: String, done: String = "", action: suspend () -> Unit, after: () -> Unit = {}) {
        if (busy.isNotEmpty()) return
        busy = label
        message = ""
        scope.launch {
            try {
                withContext(Dispatchers.IO) { action() }
                revision++
                message = done
                after()
            } catch (e: Exception) {
                message = e.localizedMessage ?: "Operation failed."
            } catch (e: LinkageError) {
                message = "The native AI runtime could not load on this device."
            } finally {
                downloadPercent = -1
                busy = ""
            }
        }
    }
}

class MainActivity : ComponentActivity() {
    companion object {
        private var processKit: Kit? = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val bars = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
        AndroidGraphicFactory.createInstance(application)
        val kit = processKit ?: Kit(applicationContext).also { processKit = it }
        setContent { GuideApp(kit) }
    }
}

private fun screenName(page: String) = when {
    page == "Nearby" -> "Field radio"
    page == "Nearby/Open" -> "Open channel"
    page == "Nearby/Pair" -> "Trusted pair"
    page == "Settings" -> "Settings"
    page == "Downloads" -> "Offline downloads"
    page == "AskModel" -> "On-device AI"
    page == "Sources" -> "Sources & credits"
    page == "Sources/maps" -> "Map information"
    page == "Sources/guides" -> "Guide references"
    page == "Sources/contacts" -> "Contact authorities"
    page == "Sources/photos" -> "Photo credits"
    page.startsWith("Sources/guide/") -> "Guide source"
    page == "Contacts" -> "Emergency contacts"
    page == "Emirates" -> "Emirate guides"
    page == "Practice" -> "Field practice"
    page == "Shelf" -> "Personal files"
    page == "North" -> "Find north"
    page == "Situation" -> "Emergency guides"
    page.startsWith("Area/") || page.startsWith("Chapter/") -> page.substringAfter('/')
    page.startsWith("Map/") || page.startsWith("Places/") -> "Offline map"
    page.startsWith("Drill/") -> "Practice decisions"
    else -> "Field checklist"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GuideApp(kit: Kit) {
    FieldTheme {
        var stack by rememberSaveable { mutableStateOf(arrayOf("Home")) }
        val screens = rememberSaveableStateHolder()
        val bars = listOf("Home", "Map", "Library", "Ask")
        val page = stack.last()
        val dispatcher = androidx.activity.compose.LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
        fun go(route: String) {
            if (page != route) stack = stack + route
        }
        fun back() {
            if (stack.size <= 1) return
            if (page !in bars) screens.removeState(page)
            stack = stack.copyOfRange(0, stack.size - 1)
        }
        BackHandler(stack.size > 1) { back() }
        Box(Modifier.fillMaxSize().background(Shell).windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
            Scaffold(
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                containerColor = MaterialTheme.colorScheme.background,
                bottomBar = {
                    if (page in bars && !WindowInsets.isImeVisible) FieldDock(page) { label ->
                        if (page != label) {
                            if (label == "Home") {
                                stack.filter { it !in bars }.forEach { screens.removeState(it) }
                                stack = arrayOf("Home")
                            } else {
                                // Preserve the screen that launched this tab, including a contextual map or guide.
                                val split = stack.indexOfLast { it !in bars } + 1
                                stack = (stack.take(split) + stack.drop(split).filterNot { it == label } + label).toTypedArray()
                            }
                        }
                    }
                },
            ) { insets ->
                Column(Modifier.fillMaxSize().padding(insets)) {
                    val readerOwnsHeader = page.startsWith("Booklet/") || page.startsWith("Item/") || page.startsWith("Guide/")
                    if (page !in bars && !readerOwnsHeader && !page.startsWith("Kit/") && !page.startsWith("Nearby/Pair/")) ReaderBar(screenName(page), { if(dispatcher!=null) dispatcher.onBackPressed() else back() }) {
                        if (page == "Contacts") IconButton(onClick = { go("Sources/contacts") }) {
                            FieldIcon(R.drawable.field_info, "Contact authorities")
                        }
                    }
                    if (kit.busy.isNotEmpty()) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(if (kit.downloadPercent == 100) "Verifying and installing…" else kit.busy, Modifier.padding(horizontal = 16.dp))
                        if (kit.downloadPercent in 0..99) TextButton(onClick = { kit.pauseDownload = true }) {
                            Text("Pause download · " + kit.downloadPercent + "%")
                        }
                    }
                    if (kit.message.isNotEmpty())
                        Surface(color = MaterialTheme.colorScheme.primaryContainer) {
                            Row(
                                Modifier.fillMaxWidth().padding(start = 16.dp),
                                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                            ) {
                                Text(kit.message, Modifier.weight(1f))
                                TextButton(onClick = { kit.message = "" }) { Text("Dismiss") }
                            }
                        }
                    screens.SaveableStateProvider(page) {
                        when {
                            page == "Home" -> FieldHome(kit, ::go)
                            page == "Nearby" -> RadioHome(::go)
                            page == "Nearby/Open" -> OpenChannelPage(kit)
                            page == "Nearby/Pair" -> PairMethods(::go)
                            page.startsWith("Nearby/Pair/") -> NearbyPage(kit, page.substringAfterLast('/'), ::back)
                            page.startsWith("Kit/") -> EmergencyKit(kit, page, ::back, ::go)
                            page == "Settings" -> SettingsPage(::go)
                            page == "Downloads" -> DownloadsPage(kit)
                            page == "AskModel" -> Page { if (kit.aiReady) Text("Qwen is ready to answer offline") else ModelOptions(kit) }
                            page == "Sources" || page.startsWith("Sources/") -> SourcesPage(kit, page, ::go)
                            page == "Emirates" -> Page {
                                kit.book.emirates.forEach { area ->
                                    Entry(area.name, icon = R.drawable.field_pin) { go("Area/" + area.name) }
                                }
                            }
                            page == "Contacts" -> Page {
                                kit.book.contacts.groupBy { it.area }.forEach { (area, list) ->
                                    Section(area)
                                    list.forEach { ContactRow(it, kit) }
                                }
                            }
                            page.startsWith("Guide/") -> {
                                val pieces = page.split("/")
                                val guide = kit.book.guides.first { it.id == pieces[1] }
                                val cited = pieces.getOrNull(2)?.toIntOrNull()
                                val sources = { go("Sources/guide/" + guide.id) }
                                if (guide.source == "manual") LessonReader(kit, guide.id, cited, ::back, sources)
                                else QuickGuideReader(kit, guide, cited, ::back, sources)
                            }
                            page.startsWith("Area/") -> {
                                val area =
                                    kit.book.emirates.first { it.name == page.substringAfter("/") }
                                Page {
                                    Entry("Open offline map", area.name) { go("Map/" + area.name) }
                                    Section("Local services")
                                    kit.book.contacts
                                        .filter { it.area == area.name }
                                        .forEach { ContactRow(it, kit) }
                                    Entry("National emergency contacts") { go("Contacts") }
                                    Section("Field guides")
                                    area.guides.forEach { id ->
                                        Entry(kit.book.guides.first { it.id == id }.title) {
                                            go("Guide/" + id)
                                        }
                                    }
                                }
                            }
                            page == "Map" || page.startsWith("Map/") ->
                                MapPage(
                                    kit,
                                    kit.book.emirates.find { it.name == page.substringAfter("/", "") }, open = ::go,
                                ) { go("Guide/treat") }
                            page.startsWith("Places/") ->
                                MapPage(kit, null, AskMapTarget.fromRoute(page), open = ::go) { go("Guide/treat") }
                            page == "Library" -> ManualLibrary(kit, ::go)
                            page == "Practice" -> PracticeHub(kit.book.manual, ::go)
                            page.startsWith("Checklist/") || page.startsWith("Drill/") -> {
                                val manual = kit.book.manual
                                val scenario = manual.scenarios.first { it.id == page.substringAfter("/") }
                                if (page.startsWith("Drill/")) ScenarioDrill(scenario, manual.prefs, ::go)
                                else ScenarioChecklist(scenario, manual.prefs, ::go)
                            }
                            page == "Shelf" -> LibraryPage(kit, ::go)
                            page == "Situation" -> SituationPage(::go)
                            page == "North" -> Page { NorthChoices(::go) }
                            page.startsWith("Booklet/") -> Booklet(kit, page.split("/")[1], ::back, page.split("/").getOrNull(2)?.toIntOrNull())
                            page.startsWith("Chapter/") -> ChapterPage(kit, page.substringAfter("/"), ::go)
                            page.startsWith("Item/") ->
                                ReaderPage(kit, page.split("/")[1].toLong(), ::back, page.split("/").getOrNull(2)?.toIntOrNull())
                            page == "Ask" -> AskPage(kit, ::go)
                        }
                    }
                }
            }
        }
    }
}
