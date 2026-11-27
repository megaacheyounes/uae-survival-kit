package ae.fieldguide

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext
import org.mapsforge.core.model.LatLong
import org.mapsforge.core.model.MapPosition
import org.mapsforge.core.util.MercatorProjection
import org.mapsforge.map.android.view.MapView
import org.mapsforge.map.model.common.Observer

private fun number(value: Int) = String.format(Locale.ENGLISH, "%,d", value)

private fun distance(metres: Double) =
    if (metres < 1000) "${metres.roundToInt()} m" else "%.1f km".format(metres / 1000)

private val quickServices =
    listOf(
        MapService.HOSPITAL,
        MapService.PHARMACY,
        MapService.POLICE,
        MapService.FUEL,
        MapService.DRINKING,
        MapService.GROCERY,
        MapService.TOILETS,
        MapService.TRANSPORT,
    )

@Composable
internal fun ServiceFilters(browse: () -> Unit, choose: (MapService) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilledTonalButton(onClick = browse, contentPadding = PaddingValues(horizontal = 10.dp)) {
            Icon(painterResource(R.drawable.poi_catalog), null, Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Directory")
        }
        Spacer(Modifier.width(6.dp))
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(end = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            for (service in quickServices) Surface(
                onClick = { choose(service) },
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Row(Modifier.heightIn(min = 48.dp).padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    FieldIcon(service.icon, modifier = Modifier.size(17.dp), tint = Pine)
                    Text(service.label, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

@Composable
internal fun BoxScope.PlaceDirectory(
    browser: PlaceBrowser,
    map: MapView,
    dir: File,
    packs: Packs,
    store: Store,
    openWaterGuide: () -> Unit,
    emirates: List<String>,
    date: String,
    modern: Boolean,
    originName: String,
    onClose: () -> Unit,
    select: (Place) -> Unit,
) {
    val catalogueScroll = rememberLazyGridState()
    val resultScroll =
        key(
            browser.service,
            browser.countrywide,
            browser.search,
            browser.emirate,
            browser.page,
            browser.anchor,
        ) {
            rememberLazyListState()
        }
    when (browser.view) {
        DirectoryView.CLOSED,
        DirectoryView.PLACE -> return
        DirectoryView.NOTES -> {
            FieldNotesDialog(store, { browser.view = DirectoryView.CATEGORIES }, select)
            return
        }
        DirectoryView.PINS -> {
            DirectoryPins(browser, map, packs, dir, select)
            return
        }
        else -> Unit
    }

    fun back() {
        browser.view =
            if (browser.view == DirectoryView.LIST && browser.fromCategories)
                DirectoryView.CATEGORIES
            else DirectoryView.CLOSED
        if(browser.view==DirectoryView.CLOSED) onClose()
    }
    Dialog(
        onDismissRequest = ::back,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        val focus = LocalFocusManager.current
        val keyboard = LocalSoftwareKeyboardController.current
        fun leaveResults(action: () -> Unit) {
            focus.clearFocus(force = true)
            keyboard?.hide()
            action()
        }
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().imePadding()) {
                if (browser.view == DirectoryView.CATEGORIES) {
                    ReaderBar("Place directory", ::back) {
                        TextButton(onClick = { browser.view = DirectoryView.NOTES }) {
                            Text("Field notes")
                        }
                    }
                    val counts by
                        produceState<Map<String, Int>?>(null, dir) {
                            value = withContext(Dispatchers.IO) { packs.categoryCounts(dir) }
                        }
                    PlaceCategories(counts, date, modern, catalogueScroll, browser::choose)
                } else {
                    ReaderBar(browser.service.label, { leaveResults(::back) }) {
                        TextButton(onClick = { leaveResults { browser.view = DirectoryView.PINS } }) {
                            Text("Nearby pins")
                        }
                    }
                    DirectoryResults(
                        browser,
                        packs,
                        dir,
                        emirates,
                        date,
                        modern,
                        originName,
                        resultScroll,
                        { leaveResults(openWaterGuide) },
                        { place -> leaveResults { select(place) } },
                    )
                }
            }
        }
    }
}

@Composable
private fun rankedPlaces(
    packs: Packs,
    dir: File,
    service: MapService,
    centre: LatLong,
    countrywide: Boolean,
    region: String,
    search: String,
): List<PlaceDistance>? =
    key(dir, service, centre, countrywide, region, search) {
        val result by
            produceState<List<PlaceDistance>?>(null) {
                if (search.isNotBlank()) delay(160)
                value =
                    withContext(Dispatchers.IO) {
                        packs.rankPlaces(dir, service, centre, countrywide, region, search)
                    }
            }
        result
    }

@Composable
private fun placeRows(
    packs: Packs,
    dir: File,
    refs: List<PlaceDistance>?,
    start: Int,
    end: Int,
): List<DirectoryRow>? =
    key(dir, refs, start, end) {
        val result by
            produceState<List<DirectoryRow>?>(null) {
                if (refs != null && start <= end)
                    value =
                        withContext(Dispatchers.IO) {
                            packs.readPlaces(dir, refs.subList(start, end))
                        }
            }
        result
    }

@Composable
private fun BoxScope.DirectoryPins(
    browser: PlaceBrowser,
    map: MapView,
    packs: Packs,
    dir: File,
    select: (Place) -> Unit,
) {
    var mapCentre by remember(map) { mutableStateOf(map.model.mapViewPosition.center) }
    LaunchedEffect(map) {
        val camera = map.model.mapViewPosition
        callbackFlow {
                val observer = Observer { trySend(camera.center) }
                camera.addObserver(observer)
                trySend(camera.center)
                awaitClose { camera.removeObserver(observer) }
            }
            .conflate()
            .distinctUntilChanged()
            .collectLatest {
                delay(200)
                mapCentre = it
            }
    }
    val service = browser.service
    val ranks =
        rankedPlaces(
            packs,
            dir,
            service,
            mapCentre,
            false,
            if (browser.countrywide) browser.emirate else "",
            browser.search,
        )
    val count = ranks?.size ?: 0
    val pageRows = placeRows(packs, dir, ranks, 0, minOf(count, MAP_PIN_LIMIT))
    // A directory opened at country scale would otherwise stack all local pins on one dot.
    // Zoom around the same search origin once. Subsequent pans and zooms belong to the reader.
    var framed by remember { mutableStateOf(false) }
    LaunchedEffect(pageRows) {
        val rows = pageRows ?: return@LaunchedEffect
        if (framed) return@LaunchedEffect
        framed = true
        val camera = map.model.mapViewPosition
        val current = camera.mapPosition
        val centre = browser.anchor.latLong
        if (rows.isEmpty() || current.latLong != centre || map.width <= 0 || map.height <= 0) return@LaunchedEffect
        val tile = map.model.displayModel.tileSize.toLong()
        val centreY = MercatorProjection.latitudeToPixelY(centre.latitude, tile)
        val extentX = rows.maxOf { abs(it.place.lon - centre.longitude) * tile / 360 }
        val extentY = rows.maxOf { abs(MercatorProjection.latitudeToPixelY(it.place.lat, tile) - centreY) }
        val scale = minOf(map.width * .39 / extentX.coerceAtLeast(.001), map.height * .35 / extentY.coerceAtLeast(.001))
        val zoom = log2(scale).coerceIn(6.0, 15.0)
        if (zoom > current.zoom + .25) camera.setMapPosition(MapPosition(centre, zoom, current.rotation), false)
    }
    val onSelect by rememberUpdatedState(select)
    DisposableEffect(map, pageRows, service) {
        val layers = map.layerManager.layers
        val markers =
            pageRows.orEmpty().mapIndexed { index, row ->
                numberedMarker(map, index + 1, row.place, service.argb) { onSelect(row.place) }
            }
        layers.addAll(markers.asReversed())
        onDispose {
            for (marker in markers) if (layers.remove(marker, false)) marker.onDestroy()
            map.layerManager.redrawLayers()
        }
    }
    fun backToResults() {
        map.model.mapViewPosition.setMapPosition(browser.anchor,false)
        browser.view = DirectoryView.LIST
    }
    BackHandler { backToResults() }
    Surface(
        Modifier.align(Alignment.TopCenter)
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 4.dp),
        shape = RoundedCornerShape(14.dp),
        shadowElevation = 3.dp,
    ) {
        Row(
            Modifier.padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(service.label, style = MaterialTheme.typography.titleSmall)
                Text(
                    when {
                        ranks == null -> "Searching this area…"
                        count == 0 -> "No matching pins within 50 km"
                        else -> "${minOf(count, MAP_PIN_LIMIT)} nearest pins · 50 km"
                    },
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            TextButton(
                onClick = ::backToResults
            ) {
                Text("Back to results")
            }
            IconButton(onClick = { browser.view = DirectoryView.CLOSED }) {
                Icon(painterResource(R.drawable.map_clear), "Clear filter")
            }
        }
    }
}

@Composable
private fun ColumnScope.DirectoryResults(
    browser: PlaceBrowser,
    packs: Packs,
    dir: File,
    emirates: List<String>,
    date: String,
    modern: Boolean,
    originName: String,
    resultScroll: LazyListState,
    openWaterGuide: () -> Unit,
    select: (Place) -> Unit,
) {
    val service = browser.service
    val ranks =
        rankedPlaces(
            packs,
            dir,
            service,
            browser.anchor.latLong,
            browser.countrywide,
            if (browser.countrywide) browser.emirate else "",
            browser.search,
        )
    val count = ranks?.size ?: 0
    val start = browser.page * DIRECTORY_PAGE_SIZE
    val end = minOf(count, start + DIRECTORY_PAGE_SIZE)
    val pageRows = placeRows(packs, dir, ranks, start, end)
    LaunchedEffect(ranks, start) {
        if (ranks != null && start > 0 && start >= count) browser.page = 0
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            FilterChip(
                selected = !browser.countrywide,
                onClick = {
                    browser.countrywide = false
                    browser.page = 0
                },
                label = { Text("Nearby · 50 km") },
            )
            FilterChip(
                selected = browser.countrywide,
                onClick = {
                    browser.countrywide = true
                    browser.page = 0
                },
                label = { Text("All UAE") },
            )
        }
        if (browser.countrywide && modern) {
            var menu by remember { mutableStateOf(false) }
            Box {
                TextButton(onClick = { menu = true }) {
                    Text((browser.emirate.ifBlank { "All emirates" }) + " ▾")
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    for (name in listOf("") + emirates) DropdownMenuItem(
                        text = { Text(name.ifEmpty { "All emirates" }) },
                        onClick = {
                            browser.emirate = name
                            browser.page = 0
                            menu = false
                        },
                    )
                }
            }
        }
        OutlinedTextField(
            browser.search,
            {
                browser.search = it.take(120)
                browser.page = 0
            },
            Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text("Filter by name, type or area") },
            leadingIcon = { Icon(painterResource(R.drawable.map_search), null) },
            trailingIcon =
                if (browser.search.isBlank()) null
                else {
                    {
                        IconButton(
                            onClick = {
                                browser.search = ""
                                browser.page = 0
                            }
                        ) {
                            Icon(painterResource(R.drawable.map_clear), "Clear directory search")
                        }
                    }
                },
            shape = RoundedCornerShape(14.dp),
        )
        val summary =
            if (ranks == null) "Searching offline…"
            else "${number(count)} entries · distance from ${originName.ifBlank { "map centre" }}"
        Text(
            summary,
            Modifier.padding(vertical = 8.dp),
            style = MaterialTheme.typography.labelLarge,
            color = Pine,
        )
        if (service == MapService.DRINKING || service == MapService.SOURCES)
            Text(
                "Mapped $date. Current water quality and supply need checking on site.",
                Modifier.padding(bottom = 8.dp),
                style = MaterialTheme.typography.bodySmall,
            )
    }
    if (service == MapService.DRINKING || service == MapService.SOURCES)
        TextButton(onClick = openWaterGuide, modifier = Modifier.padding(start = 8.dp)) {
            Text("Open water treatment guide")
        }
    HorizontalDivider()
    val rows = pageRows
    Box(Modifier.weight(1f).fillMaxWidth()) {
        when {
            rows == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
            rows.isEmpty() ->
                Text(
                    if (!browser.countrywide)
                        "No matches within 50 km of the map centre. Try All UAE."
                    else "No matches in this selection.",
                    Modifier.padding(16.dp),
                )
            else ->
                LazyColumn(state = resultScroll) {
                    items(rows.size) { index ->
                        val row = rows[index]
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable { select(row.place) }
                                .heightIn(min = 58.dp)
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    row.place.title,
                                    style = MaterialTheme.typography.titleSmall,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                val subtitle =
                                    row.place.description.ifBlank {
                                        row.place.kind.replace('_', ' ').replaceFirstChar {
                                            it.uppercase()
                                        }
                                    }
                                Text(
                                    subtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Text(distance(row.metres), style = MaterialTheme.typography.labelMedium)
                        }
                        HorizontalDivider()
                    }
                }
        }
    }
    if (count > DIRECTORY_PAGE_SIZE) {
        HorizontalDivider()
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            TextButton(enabled = start > 0, onClick = { browser.page-- }) { Text("Previous") }
            Text(
                "${number(start+1)}–${number(end)} of ${number(count)}",
                style = MaterialTheme.typography.labelMedium,
            )
            TextButton(enabled = end < count, onClick = { browser.page++ }) { Text("Next") }
        }
    }
}

@Composable
private fun PlaceCategories(
    counts: Map<String, Int>?,
    date: String,
    modern: Boolean,
    scroll: LazyGridState,
    choose: (MapService) -> Unit,
) {
    if (counts == null) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
        return
    }
    val groups = remember {
        MapService.entries.filter { it != MapService.ALL }.groupBy { it.group }
    }
    LazyVerticalGrid(
        GridCells.Adaptive(150.dp),
        state = scroll,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column {
                Text(
                    "Directory updated $date",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (!modern)
                    Text(
                        "This pack has a limited index. Open Offline downloads to restore the included directory.",
                        style = MaterialTheme.typography.bodySmall,
                    )
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Surface(
                onClick = { choose(MapService.ALL) },
                shape = RoundedCornerShape(12.dp),
                color = Pine,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("All mapped places", style = MaterialTheme.typography.titleMedium)
                    Text(number(counts.values.sum()), style = MaterialTheme.typography.titleMedium)
                }
            }
        }
        for ((group, services) in groups) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    group,
                    Modifier.padding(top = 6.dp),
                    style = MaterialTheme.typography.titleSmall,
                    color = Pine,
                )
            }
            items(services.size) { index ->
                val service = services[index]
                Surface(
                    onClick = { choose(service) },
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Row(
                        Modifier.fillMaxWidth()
                            .heightIn(min = 72.dp)
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            painterResource(service.icon),
                            null,
                            Modifier.size(22.dp),
                            tint = Pine,
                        )
                        Column {
                            Text(
                                service.label,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                number(counts[service.kind] ?: 0),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }
    }
}
