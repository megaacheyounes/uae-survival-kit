package ae.fieldguide

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.CancellationSignal
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.*
import kotlin.coroutines.resume
import org.mapsforge.core.model.LatLong

private const val nearbyWater = "Where can I find drinking water near me?"
private const val findNorth = "How do I find north?"
private val starterQuestions = listOf(
    nearbyWater to R.drawable.field_water,
    findNorth to R.drawable.field_compass,
    "How long do I boil drinking water?" to R.drawable.poi_fire,
    "What should I do if the road is flooded?" to R.drawable.field_flood,
)

@Composable
internal fun NorthChoices(open: (String) -> Unit) {
    Entry("By day", "Use a stick and its shadow", R.drawable.field_sun) { open("Guide/shadow/0") }
    Entry("At night", "Find the North Star", R.drawable.field_moon) { open("Guide/stars/1") }
}
internal data class AskRequest(val question: String, val useModel: Boolean, val revision: Int, val location: LatLong?)
internal data class AskAnswer(
    val contacts: List<Contact> = emptyList(),
    val direct: Passage? = null,
    val related: List<Passage> = emptyList(),
    val local: LocalPlaces? = null,
    val references: List<ReferenceHit> = emptyList(),
    val notices: List<String> = emptyList(),
    val searching: Boolean = true,
    val modelBusy: Boolean = false,
    val explanation: String? = null,
    val modelSelected: Boolean = false,
)

private val contactTerms = mapOf(
    "999" to listOf("police"), "998" to listOf("ambulance", "medical emergency"),
    "997" to listOf("fire brigade", "fire department", "civil defence", "civil defense", "house fire", "building fire"),
    "996" to listOf("coastguard", "coast guard"), "995" to listOf("search and rescue", "find and rescue"),
)

private fun Fieldbook.contactMatches(question: String, service: MapService?): List<Contact> {
    val q = " " + placeWords(question) + " "
    val national = contacts.filter { it.area == "UAE" }
    val numberRequest = Regex("\\b(number|call|dial|contact|hotline|telephone|phone number)\\b").containsMatchIn(q)
    if (service != null && !numberRequest) return emptyList()
    val matching = national.filter { c ->
        " ${c.number} " in q || contactTerms[c.number].orEmpty().any { " $it " in q } ||
            (numberRequest && c.number == "997" && " fire " in q)
    }
    if (matching.isNotEmpty()) return matching
    if (numberRequest && listOf("power", "electric", "utility", "water").any { it in q })
        return contacts.filter { it.area != "UAE" && it.area.lowercase() in q }
    return if (numberRequest && "emergency" in q &&
        terms(q).all { it in setOf("emergency", "services", "uae", "national", "emergencies") }) national else emptyList()
}

@Composable
fun AskPage(kit: Kit, open: (String) -> Unit) {
    var draft by rememberSaveable { mutableStateOf("") }
    var submitted by rememberSaveable { mutableStateOf("") }
    var attempt by rememberSaveable { mutableIntStateOf(0) }
    var moreReferences by rememberSaveable(submitted) { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val inputFocus = remember { FocusRequester() }
    val scroll = rememberScrollState()
    val questionScroll = rememberScrollState()
    var editing by rememberSaveable { mutableStateOf(false) }
    fun cancelEdit() {
        editing=false;draft=submitted
        focus.clearFocus(force=true);keyboard?.hide()
    }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current.applicationContext
    var fix by rememberSaveable(submitted) { mutableStateOf<DoubleArray?>(null) }
    val nearMe = " near me " in " ${placeWords(submitted)} " && askedService(submitted) == MapService.DRINKING
    val north = placeWords(submitted) == placeWords(findNorth)
    val request = AskRequest(submitted, kit.aiReady, kit.revision, fix?.let { LatLong(it[0], it[1]) })
    val activeAttempt = attempt
    val answer by key(request, activeAttempt) {
        produceState<AskAnswer?>(kit.lastAsk?.takeIf { it.first == request }?.second) {
            if (submitted.isBlank() || value != null) return@produceState
            val q = request.question
            val incident = incidentGuide(q)?.let { id -> kit.book.guides.first { it.id == id } }
            if (incident != null) {
                val line = incidentStep(incident.id, q)
                val first = Passage(incident.id, line, incident.steps[line], incident.title)
                val result = AskAnswer(direct = first, searching = false)
                value = result
                kit.lastAsk = request to result
                return@produceState
            }
            val service = withContext(Dispatchers.IO) {
                askedService(q) {
                    try { kit.packs.namedLocality(kit.packs.directoryForLookup(), q) != null }
                    catch (e: java.io.IOException) { false }
                    catch (e: android.database.sqlite.SQLiteException) { false }
                }
            }
            val contacts = kit.book.contactMatches(q, service)
            val lookup = withContext(Dispatchers.Default) { kit.book.lookup.find(q) }
            val general = service == null && contacts.isEmpty() && !north
            val meaning = if (general && lookup.direct == null) withContext(Dispatchers.Default) {
                kit.book.meaningMatches(q)
            } else emptyList()
            val strong = meaning.firstOrNull()?.takeIf { (_, score) ->
                score >= .69f && score - (meaning.getOrNull(1)?.second ?: 0f) >= .07f
            }?.first
            val guide = if (general) kit.book.roadSafetyAnswer(q) ?: lookup.direct ?: strong else null
            val similar = meaning.filter { it.second >= .46f }.map { it.first }
            var state = AskAnswer(contacts, if (contacts.isEmpty()) guide else null,
                if (general) (similar + lookup.candidates).distinct() else emptyList())
            value = state
            fun update(change: (AskAnswer) -> AskAnswer) { state = change(state); value = state }
            suspend fun trySource(message: String, read: suspend () -> Unit) {
                try { read() } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    android.util.Log.w("FieldGuideAsk", message, e)
                    update { it.copy(notices = it.notices + message) }
                }
            }
            if (contacts.isEmpty()) coroutineScope {
                if (service != null) launch {
                    trySource("The offline place directory could not be read. Open Map to check the installed pack.") {
                        val places = withContext(Dispatchers.IO) { kit.packs.findForQuestion(service, q, request.location) }
                        update { it.copy(local = places, direct = kit.book.waterPassage(service, q, places.target.name)) }
                    }
                    if (state.local == null) update { it.copy(direct = kit.book.waterPassage(service, q, "")) }
                }
                launch {
                    trySource("Some reference pages could not be searched.") {
                        val pages = withContext(Dispatchers.IO) { referenceHits(context, kit.store.items(), q) }
                        update { it.copy(references = pages) }
                    }
                }
                if (!north && service == null && request.useModel) launch {
                    trySource("The local model is unavailable. You can still open the matching sources.") {
                        update { it.copy(modelBusy = true) }
                        try {
                            val chosen = guide ?: runInterruptible(Dispatchers.IO) {
                                LocalAI.choose(kit.packs, kit.book, q, meaning.map { it.first }.ifEmpty { lookup.modelCandidates })
                            }
                            if (chosen != null) {
                                update { it.copy(direct = chosen, modelSelected = guide == null) }
                                val explanation = runInterruptible(Dispatchers.IO) { LocalAI.explain(kit.packs, q, chosen) }
                                update { it.copy(explanation = explanation) }
                            } else update { it.copy(related = emptyList()) }
                        } catch (e: LinkageError) { throw IllegalStateException(e) }
                        finally { update { it.copy(modelBusy = false) } }
                    }
                }
            }
            update { it.copy(searching = false) }
            if (state.notices.isEmpty() && submitted == q && attempt == activeAttempt) {
                kit.lastAsk = request to state
            }
        }
    }
    fun submit(question: String = draft) {
        if (question.isBlank()) return
        editing=false
        if(submitted!=question.trim()) scope.launch { scroll.scrollTo(0) }
        draft = question
        focus.clearFocus(force = true)
        keyboard?.hide()
        val q = question.trim()
        if (submitted == q && answer?.notices?.isNotEmpty() == true) attempt++
        submitted = q
    }
    fun clearQuestion() {
        editing=false
        attempt++
        draft = ""
        submitted = ""
        moreReferences = false
        kit.lastAsk = null
        scope.launch { scroll.scrollTo(0) }
    }
    androidx.activity.compose.BackHandler(submitted.isNotEmpty() || draft.isNotEmpty()) {
        when {
            editing -> cancelEdit()
            submitted.isNotEmpty() -> { draft=submitted;submitted="";focus.clearFocus() }
            else -> clearQuestion()
        }
    }
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        CommandBar("Ask the library", R.drawable.tab_ask) {
            if (submitted.isNotEmpty()) TextButton(onClick = {
                clearQuestion(); focus.clearFocus(force = true); keyboard?.hide()
            }) { Text("Reset Ask", color = Signal) }
            IconButton(onClick = { open("Settings") }) { FieldIcon(R.drawable.field_controls, "Settings and downloads") }
        }
        var editRequested by remember { mutableStateOf(false) }
        LaunchedEffect(editRequested, submitted) {
            if (editRequested && editing) {
                inputFocus.requestFocus()
                keyboard?.show()
                editRequested = false
            }
        }
        Page(scrollState = if(submitted.isEmpty() || editing) questionScroll else scroll) {
            if (submitted.isNotBlank() && !editing) {
                Entry(submitted, "Edit question", R.drawable.tab_ask) {
                    editing=true; editRequested = true
                }
            } else {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { text -> draft=text.take(320) },
                    modifier = Modifier.fillMaxWidth().focusRequester(inputFocus),
                    label = { Text("Ask a question") },
                    placeholder = { Text("Where can I find water in Hatta?") },
                    maxLines = 3,
                    trailingIcon =
                        if (draft.isEmpty()) null else {
                            {
                                IconButton(onClick = {
                                    draft=""
                                    inputFocus.requestFocus()
                                    keyboard?.show()
                                }) {
                                    Icon(painterResource(R.drawable.map_clear), contentDescription = "Clear question")
                                }
                            }
                        },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { submit() }),
                )
                if (draft.isNotBlank()) Button(onClick = { submit() }, modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.small) { Text("Look up") }
                if(editing) TextButton(::cancelEdit){Text("Back to answer")}
                if (draft.isEmpty() && !editing) {
                    if (kit.aiReady) Hint("On-device AI ready · Qwen runs offline")
                    else Entry("Set up on-device AI", "Offline guides and first aid are ready now", R.drawable.tab_ask) { open("AskModel") }
                    Section("Quick questions")
                    Surface(shape=MaterialTheme.shapes.medium,color=MaterialTheme.colorScheme.surface,
                        border=androidx.compose.foundation.BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant)) {
                        Column {
                            starterQuestions.forEachIndexed { index,(question,icon) ->
                                if(index>0) HorizontalDivider(Modifier.padding(start=52.dp))
                                Surface(onClick={submit(question)},color=androidx.compose.ui.graphics.Color.Transparent) {
                                    Row(Modifier.fillMaxWidth().heightIn(min=58.dp).padding(12.dp),verticalAlignment=Alignment.CenterVertically,
                                        horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                                        FieldIcon(icon,tint=Pine)
                                        Text(question,Modifier.weight(1f),style=MaterialTheme.typography.bodyLarge)
                                        FieldIcon(R.drawable.field_next,modifier=Modifier.size(16.dp),tint=Pine)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            val result = answer
            if (submitted.isNotEmpty() && !editing) {
                if (nearMe && fix == null) key(submitted) {
                    NearbyOrigin(kit.book.emirates.map { it.name }, { fix = doubleArrayOf(it.latitude, it.longitude) }) {
                        submit("Where can I find drinking water near $it?")
                    }
                }
                if (north) {
                    Section("Find north")
                    NorthChoices(open)
                }
                if (result?.modelBusy == true) Hint("Qwen is reading on this phone…")
                else if (result == null || result.searching) Hint("Searching your offline library…")
                if (result != null) {
                    result.contacts.forEach { ContactRow(it, kit) }
                    result.local?.takeIf { it.target.centre != null }?.let { LocalAnswer(it, open) }
                    result.direct?.let { p ->
                        val incident = kit.book.isIncident(p.guide)
                        val label = when {
                            result.explanation != null -> "On-device AI answer"
                            incident -> "Offline field guidance"
                            result.modelSelected -> "Guide selected by on-device AI"
                            else -> "From the field guide"
                        }
                        Section(label)
                        Surface(color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.medium,
                            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(p.title, style = MaterialTheme.typography.titleMedium)
                                if (incident) {
                                    Text(p.body.substringBefore(". "), style = MaterialTheme.typography.titleLarge)
                                    Text(p.body.substringAfter(". "), style = MaterialTheme.typography.bodyLarge)
                                } else Text(result.explanation ?: p.body, style = MaterialTheme.typography.bodyLarge)
                                Button(onClick = { open("Guide/${p.guide}/${p.line}") }, shape = MaterialTheme.shapes.small) {
                                    Text("Open guide step " + (p.line + 1))
                                }
                            }
                        }
                        if (incident) {
                            val source = kit.book.guides.first { it.id == p.guide }
                            source.steps.forEachIndexed { i, body ->
                                if (i != p.line) Entry(body.substringBefore(". "), body.substringAfter(". ")) { open("Guide/${p.guide}/$i") }
                            }
                            if (p.guide != "dimsky") ContactRow(kit.book.contacts.first { it.area == "UAE" && it.number == "998" }, kit)
                        }
                    }
                    result.local?.takeIf { it.target.centre == null }?.let { LocalAnswer(it, open) }
                    val promoteReferences = result.direct == null && result.local == null && !north
                    val visible = if (moreReferences) result.references
                        else if (promoteReferences) result.references.take(2) else emptyList()
                    if (visible.isNotEmpty()) {
                        Section("Reference pages and files")
                        visible.forEach { hit ->
                            Entry(hit.title, if (hit.pageMatch) hit.excerpt else "${hit.excerpt} · Title or topic match") { open(hit.route) }
                        }
                    }
                    if (result.direct == null && result.related.isNotEmpty()) {
                        Section("Possible guide steps")
                        if (!result.searching) Hint("I couldn’t confirm an exact answer. Check whether one of these steps fits your situation.")
                        result.related.take(4).forEach { p ->
                            Entry(p.title, p.body.substringBefore(". ")) { open("Guide/${p.guide}/${p.line}") }
                        }
                    }
                    if (result.references.size > visible.size || moreReferences)
                        TextButton(onClick = { moreReferences = !moreReferences }) {
                            Text(if (moreReferences) "Show fewer references" else "Reference pages and files (${result.references.size})")
                        }
                    result.notices.forEach { Hint(it) }
                    if (!north && !result.searching && result.contacts.isEmpty() && result.direct == null &&
                        result.local == null && result.related.isEmpty() && result.references.isEmpty() && result.notices.isEmpty()) {
                        Text("I don’t have enough evidence in the library to answer that. Try describing the task you need help with, or browse the field manual.")
                        if (!kit.aiReady) TextButton({ open("AskModel") }) { Text("Set up on-device AI") }
                        Entry("Browse the field manual", "Explore the installed skills and checklists") { open("Library") }
                    }
                }
            }
        }
    }
}

@SuppressLint("MissingPermission")
@Composable
private fun NearbyOrigin(towns: List<String>, onFix: (LatLong) -> Unit, onTown: (String) -> Unit) {
    val context = LocalContext.current
    fun permitted() = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    var locating by remember { mutableStateOf(permitted()) }
    var message by remember { mutableStateOf("Use GPS or choose a town to find mapped drinking water.") }
    var menu by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        locating = permitted()
        if (!locating) message = "Precise location is unavailable. Choose a town to search offline."
    }
    LaunchedEffect(locating) {
        if (!locating) return@LaunchedEffect
        val manager = context.getSystemService(LocationManager::class.java)
        val location = try {
            if (manager == null || !manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) null
            else withTimeoutOrNull(25000) {
                suspendCancellableCoroutine<android.location.Location?> { continuation ->
                    val signal = CancellationSignal()
                    continuation.invokeOnCancellation { signal.cancel() }
                    manager.getCurrentLocation(LocationManager.GPS_PROVIDER, signal, context.mainExecutor) {
                        continuation.resume(it)
                    }
                }
            }
        } catch (e: SecurityException) { null } catch (e: IllegalArgumentException) { null }
        locating = false
        if (location == null) message = "No GPS fix available. Choose a town or try GPS again outdoors."
        else onFix(LatLong(location.latitude, location.longitude))
    }
    Hint(if (locating) "Waiting for a GPS fix… You can also choose a town." else message)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(enabled = !locating, onClick = {
            if (permitted()) locating = true else permission.launch(arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }) { Text("Use GPS") }
        Box {
            OutlinedButton(onClick = { menu = true }) { Text("Choose a town") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                (towns + listOf("Hatta", "Khor Fakkan")).forEach { town ->
                    DropdownMenuItem(text = { Text(town) }, onClick = { menu = false; onTown(town) })
                }
            }
        }
    }
}

private fun Fieldbook.waterPassage(service: MapService, question: String, town: String): Passage? {
    if (service != MapService.DRINKING && service != MapService.SOURCES) return null
    val namesCitySite = Regex("\\b(kite beach|marina walk|zabeel park)\\b").containsMatchIn(placeWords(question))
    val key = when {
        service == MapService.SOURCES -> "water-symbol"
        town.equals("Dubai", true) || (town.isEmpty() && namesCitySite) -> "dubai-refill"
        else -> "known-supply"
    }
    val water = guides.first { it.id == "findwater" }
    val line = water.stepIds.getValue(key)
    return Passage(water.id, line, water.steps[line], water.title)
}

@Composable
private fun LocalAnswer(local: LocalPlaces, open: (String) -> Unit) {
    val target = local.target
    val service = target.service.label
    if (target.centre == null) {
        Section("Offline place directory")
        Hint("Choose the countrywide directory to browse all mapped locations.")
        Entry("Browse the UAE directory", "$service · ${local.count} entries across the UAE · ${local.date}") { open(target.route()) }
        return
    }
    Section("$service near ${target.name}")
    val origin = if (target.name == "your GPS fix") target.name else "${target.name} centre"
    Hint("${local.count} mapped within $NEARBY_RADIUS_KM km · Straight-line distance from $origin · ${local.date}")
    for (row in local.nearest) {
        val distance = if (row.metres < 1000) "${row.metres.roundToInt()} m" else String.format(Locale.ENGLISH, "%.1f km", row.metres / 1000)
        Column {
            Text("${row.place.title} · $distance", style = MaterialTheme.typography.titleSmall)
            if (row.place.description.isNotBlank()) Hint(row.place.description)
        }
    }
    if (target.service == MapService.DRINKING) Hint("The map records points tagged for drinking. Check access, flow and current water quality on site.")
    Entry("Open map results", if (local.count > 0) "Browse these results around ${target.name}" else "Explore this area or switch to All UAE") { open(target.route()) }
}

@Composable
internal fun ModelOptions(kit: Kit) {
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) kit.work("Checking model and testing generation…", "Local AI is ready.",
            { kit.packs.importModel(uri) }, { kit.aiReady = true })
    }
    Hint("Ask already searches guides by meaning offline. Qwen can help choose between possible steps and explain supported navigation and supply-planning steps. First aid and urgent instructions come directly from the guides.")
    if (kit.packs.model.isFile)
        Button(enabled = kit.busy.isEmpty(), onClick = {
            kit.work("Testing local generation…", "Local AI is ready.", { kit.packs.probe() }, { kit.aiReady = true })
        }) { Text("Enable local AI") }
    else {
        DownloadButton(kit, Resource.MODEL)
        OutlinedButton(enabled = kit.busy.isEmpty(), onClick = { pick.launch(arrayOf("*/*")) }) { Text("Import optional AI model") }
    }
}
