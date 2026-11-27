package ae.fieldguide

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.CancellationSignal
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale
import kotlin.math.log2
import kotlinx.coroutines.*
import org.mapsforge.core.graphics.Style
import org.mapsforge.core.model.BoundingBox
import org.mapsforge.core.model.LatLong
import org.mapsforge.core.model.MapPosition
import org.mapsforge.core.model.Rotation
import org.mapsforge.core.util.MercatorProjection
import org.mapsforge.core.util.Parameters
import org.mapsforge.map.android.graphics.AndroidGraphicFactory
import org.mapsforge.map.android.rendertheme.AssetsRenderTheme
import org.mapsforge.map.android.util.AndroidUtil
import org.mapsforge.map.android.view.MapView
import org.mapsforge.map.datastore.MultiMapDataStore
import org.mapsforge.map.layer.cache.InMemoryTileCache
import org.mapsforge.map.layer.overlay.FixedPixelCircle
import org.mapsforge.map.model.MapViewPosition
import org.mapsforge.map.reader.MapFile
import org.mapsforge.map.scalebar.DefaultMapScaleBar

private val uaeBounds = BoundingBox(22.60, 51.50, 26.20, 56.55)

@SuppressLint("MissingPermission")
@Composable
internal fun MapPage(kit: Kit, area: Emirate?, askTarget: AskMapTarget? = null, open: (String) -> Unit, openWaterGuide: () -> Unit) {
    val ctx = LocalContext.current
    val dir = remember(kit.revision) { kit.packs.active }
    val needsIncludedMap = remember(dir) { kit.packs.needsBundledMap(dir) }
    var attempted by remember(dir) { mutableStateOf(false) }
    fun openIncludedMap() =
        kit.work("Opening the map included with the app…", action = kit.packs::installBundledMap)
    LaunchedEffect(dir, kit.busy) {
        if (needsIncludedMap && !attempted && kit.busy.isEmpty()) {
            attempted = true
            kit.work("Opening the map included with the app…", action = { kit.packs.directoryForLookup() })
        }
    }
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null)
                kit.work(
                    "Verifying UAE map and place index…",
                    "UAE map is ready.",
                    { kit.packs.importMap(uri) },
                )
        }
    var query by rememberSaveable { mutableStateOf("") }
    var previousSearch by rememberSaveable { mutableStateOf("") }
    var directorySearch by rememberSaveable { mutableStateOf("") }
    var previousCamera by rememberSaveable { mutableStateOf<DoubleArray?>(null) }
    var fromPins by rememberSaveable { mutableStateOf(false) }
    var pinViewport by rememberSaveable { mutableStateOf<DoubleArray?>(null) }
    val searchScroll = androidx.compose.foundation.lazy.rememberLazyListState()
    var results by remember(query, dir) { mutableStateOf<List<Place>?>(null) }
    val start = askTarget?.centre ?: area?.let { LatLong(it.lat, it.lon) }
    val camera =
        rememberSaveable(saver = MapCamera.saver) {
            MapCamera(
                MapPosition(start ?: uaeBounds.centerPoint, if (start == null) 6 else 12),
                fitOnLayout = start == null,
            )
        }
    val browser = rememberSaveable(saver = PlaceBrowser.saver) {
        PlaceBrowser().apply {
            if (askTarget != null) {
                open(camera.position, askTarget.service)
                countrywide = askTarget.centre == null
            }
        }
    }
    var lat by rememberSaveable { mutableDoubleStateOf(area?.lat ?: 24.45) }
    var lon by rememberSaveable { mutableDoubleStateOf(area?.lon ?: 54.1) }
    var label by rememberSaveable { mutableStateOf(area?.name ?: "") }
    var kind by rememberSaveable { mutableStateOf("") }
    var editingNote by rememberSaveable { mutableStateOf(false) }
    var placeInfo by rememberSaveable { mutableStateOf("") }
    val cancel = remember { mutableStateOf<CancellationSignal?>(null) }
    fun locate() {
        cancel.value?.cancel()
        val signal = CancellationSignal()
        cancel.value = signal
        val manager = ctx.getSystemService(LocationManager::class.java)
        if (manager == null) {
            cancel.value = null
            kit.message = "Location service is unavailable."
            return
        }
        try {
            if (!manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                kit.message = "Turn on GPS to get a location fix."
                cancel.value = null
                return
            }
            kit.message = "Waiting for a satellite fix…"
            manager.getCurrentLocation(LocationManager.GPS_PROVIDER, signal, ctx.mainExecutor) { fix
                ->
                if (!signal.isCanceled) {
                    cancel.value = null
                    if (fix == null) kit.message = "No GPS fix available."
                    else {
                        lat = fix.latitude
                        lon = fix.longitude
                        camera.go(LatLong(lat, lon), 15)
                        previousSearch="";previousCamera=null
                    kind = "GPS"
                        placeInfo = ""
                        if (browser.view == DirectoryView.PLACE) browser.view = DirectoryView.CLOSED
                        label = "GPS · ±${fix.accuracy.toInt()} m · %.5f, %.5f".format(lat, lon)
                        kit.message = ""
                    }
                }
            }
        } catch (e: Exception) {
            cancel.value = null
            kit.message = "GPS is unavailable on this device."
        }
    }
    DisposableEffect(Unit) { onDispose { cancel.value?.cancel() } }
    LaunchedEffect(cancel.value) {
        val pending = cancel.value
        if (pending != null) {
            delay(25000)
            pending.cancel()
            cancel.value = null
            kit.message = "No GPS fix yet. Try again outdoors."
        }
    }
    val request =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            granted ->
            if (granted[Manifest.permission.ACCESS_FINE_LOCATION] == true) locate()
            else
                kit.message =
                    "Precise location is needed for a GPS fix. You can still browse the map."
        }
    fun requestLocation() {
        if (
            ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        )
            locate()
        else
            request.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                )
            )
    }
    if (dir == null) {
        Page {
            Title("Offline UAE map")
            TextButton(onClick = ::requestLocation) { Text("My location") }
            if (label.startsWith("GPS")) Text(label)
            if (kit.busy.isEmpty() && attempted) {
                Button(onClick = ::openIncludedMap) { Text("Open included map") }
                DownloadButton(kit, Resource.MAP)
                TextButton(onClick = { picker.launch(arrayOf("*/*")) }) {
                    Text("Import UAE map pack")
                }
            }
        }
        return
    }
    LaunchedEffect(dir, query) {
        if (query.isNotBlank()) {
            delay(160)
            val centre = camera.position.latLong
            results =
                withContext(Dispatchers.IO) {
                    kit.packs.places(query, centre.latitude, centre.longitude)
                }
        }
    }
    val meta = remember(dir) { kit.packs.manifestFor(dir) } ?: return
    val backdrop by
        produceState<Result<File>?>(null) {
            value =
                withContext(Dispatchers.IO) {
                    try {
                        val file = File(ctx.cacheDir, "uae-coast-1.5-r3.map")
                        if (!file.isFile) {
                            val temp = File.createTempFile("coast-", ".part", ctx.cacheDir)
                            try {
                                ctx.assets.open("map/coast.map").use { input ->
                                    temp.outputStream().use(input::copyTo)
                                }
                                Files.move(
                                    temp.toPath(),
                                    file.toPath(),
                                    StandardCopyOption.ATOMIC_MOVE,
                                    StandardCopyOption.REPLACE_EXISTING,
                                )
                            } finally {
                                temp.delete()
                            }
                        }
                        Result.success(file)
                    } catch (e: IOException) {
                        Result.failure(e)
                    }
                }
        }
    val prefs = remember { ctx.getSharedPreferences("map-display", 0) }
    var large by remember { mutableStateOf(prefs.getBoolean("large-labels", false)) }
    var satellite by remember { mutableStateOf(prefs.getBoolean("satellite", false)) }
    val installedImagery = remember(kit.revision) { kit.packs.imagery.active }
    var options by rememberSaveable { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(browser.view) {
        if (browser.view == DirectoryView.PINS || browser.view == DirectoryView.PLACE) {
            focus.clearFocus(force = true)
            keyboard?.hide()
        }
    }
    fun selectPlace(p: Place, fromDirectory: Boolean = false) {
        fromPins=fromDirectory && browser.view==DirectoryView.PINS
        if(!fromDirectory && query.isNotBlank()){
            previousSearch=query
            val pos=camera.position
            previousCamera=doubleArrayOf(pos.latLong.latitude,pos.latLong.longitude,pos.zoom)
        }
        browser.returnToNotes = fromDirectory && browser.view == DirectoryView.NOTES
        if (fromDirectory && browser.view == DirectoryView.PINS) {
            val position=camera.position
            pinViewport=doubleArrayOf(position.latLong.latitude,position.latLong.longitude,position.zoom)
        }
        placeInfo = p.description
        focus.clearFocus()
        lat = p.lat
        lon = p.lon
        kind = p.kind
        label = p.title
        val level =
            when (kind) {
                "city" -> 12
                "town",
                "village",
                "island" -> 13
                "hospital",
                "fuel",
                "police",
                "pharmacy" -> 16
                else -> 14
            }
        camera.go(LatLong(lat, lon), level)
        query = ""
        browser.view = if (fromDirectory) DirectoryView.PLACE else DirectoryView.CLOSED
    }
    fun returnToList() {
        val pins=pinViewport
        camera.restore(if(fromPins && pins!=null) MapPosition(LatLong(pins[0],pins[1]),pins[2],Rotation.NULL_ROTATION) else browser.anchor)
        browser.view = if (browser.returnToNotes) DirectoryView.NOTES else if(fromPins) DirectoryView.PINS else DirectoryView.LIST
    }
    fun leaveSearchPlace() {
        label="";kind="";placeInfo=""
        query=previousSearch;previousSearch=""
        previousCamera?.let { camera.restore(MapPosition(LatLong(it[0],it[1]),it[2],Rotation.NULL_ROTATION)) }
        previousCamera=null
    }
    BackHandler(browser.view==DirectoryView.PLACE || browser.view==DirectoryView.CLOSED &&
        (query.isNotBlank() || previousSearch.isNotEmpty() || kind.isNotEmpty())) {
        when {
            browser.view==DirectoryView.PLACE -> returnToList()
            query.isNotBlank() -> { query="";focus.clearFocus();keyboard?.hide() }
            else -> leaveSearchPlace()
        }
    }
    val coastFile = backdrop?.getOrNull()
    if (backdrop == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }
    LaunchedEffect(coastFile) {
        if (coastFile == null)
            kit.message = "Coastal backdrop unavailable. Free some storage to restore it."
    }
    val map =
        remember(dir, coastFile) {
            Parameters.FRACTIONAL_ZOOM = true
            MapView(ctx).apply {
                isClickable = true
                setBuiltInZoomControls(false)
                (mapScaleBar as? DefaultMapScaleBar)?.scaleBarMode =
                    DefaultMapScaleBar.ScaleBarMode.SINGLE
                val position = model.mapViewPosition
                position.zoomLevelMin = 6
                position.zoomLevelMax = 19
                camera.attach(position)
                addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
                    if (camera.fitOnLayout && view.width > 0 && view.height > 0)
                        camera.overview(this)
                }
            }
        }
    val renderer =
        remember(map) {
            val data =
                MultiMapDataStore(MultiMapDataStore.DataPolicy.RETURN_ALL).apply {
                    coastFile?.let { addMapDataStore(MapFile(it), false, false) }
                    addMapDataStore(MapFile(File(dir, "uae.map"), "en"), false, false)
                }
            AndroidUtil.createTileRendererLayer(
                    InMemoryTileCache(48),
                    map.model.mapViewPosition,
                    data,
                    AssetsRenderTheme(ctx.assets, "map/", "field-night.xml"),
                )
                .also { map.layerManager.layers.add(it) }
        }
    LaunchedEffect(renderer, large) {
        renderer.textScale = if (large) 1.35f else 1f
        renderer.requestRedraw()
    }
    if (satellite && installedImagery != null) SatelliteLayers(map, installedImagery)
    val showSelection = label.isNotEmpty() && (browser.view != DirectoryView.PINS || kind == "GPS")
    DisposableEffect(map, showSelection, lat, lon, kind) {
        val paint = AndroidGraphicFactory.INSTANCE
        val pin =
            if (!showSelection) null
            else
                FixedPixelCircle(
                    LatLong(lat, lon),
                    5.5f,
                    paint.createPaint().apply {
                        setColor(if (kind == "GPS") 0xff336ead.toInt() else 0xffa33e36.toInt())
                    },
                    paint.createPaint().apply {
                        setColor(android.graphics.Color.WHITE)
                        setStyle(Style.STROKE)
                        strokeWidth = 2.4f * map.model.displayModel.scaleFactor
                    },
                )
        pin?.let { map.layerManager.layers.add(it) }
        onDispose { pin?.let { map.layerManager.layers.remove(it) } }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            OutlinedTextField(
                query,
                {
                    if(query.isEmpty() && it.isNotEmpty()){previousSearch="";previousCamera=null}
                    query = it.take(120)
                    if (query.isNotBlank()) browser.view = DirectoryView.CLOSED
                },
                singleLine = true,
                placeholder = { Text("Search UAE map") },
                leadingIcon = { Icon(painterResource(R.drawable.map_search), null) },
                trailingIcon =
                    if (query.isEmpty()) null
                    else {
                        { MapButton(R.drawable.map_clear, "Clear search") { query = "" } }
                    },
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { focus.clearFocus(); keyboard?.hide(); options = true }) {
                FieldIcon(R.drawable.field_layers, "Map options", Modifier.size(20.dp))
                Spacer(Modifier.width(4.dp))
                Text("Layers")
            }
        }
        fun openDirectory(category: MapService? = null, text: String = "") {
            focus.clearFocus()
            directorySearch=query
            query = ""
            browser.open(camera.position, category, text)
        }
        ServiceFilters({ openDirectory() }, { openDirectory(it) })
        Box(Modifier.fillMaxSize().clipToBounds()) {
            key(map) {
                AndroidView(
                    factory = { map },
                    modifier = Modifier.fillMaxSize(),
                    onReset = null,
                    onRelease = {
                        camera.detach(it.model.mapViewPosition)
                        it.destroyAll()
                    },
                )
            }
            Column(
                Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = 54.dp),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MapSurface {
                    Column {
                        MapButton(R.drawable.map_zoom_in, "Zoom in") {
                            map.model.mapViewPosition.zoomIn()
                        }
                        HorizontalDivider(Modifier.width(48.dp))
                        MapButton(R.drawable.map_zoom_out, "Zoom out") {
                            map.model.mapViewPosition.zoomOut()
                        }
                    }
                }
                MapSurface { MapButton(R.drawable.map_locate, "My location", ::requestLocation) }
            }
            Surface(onClick = { open("Sources/maps") }, modifier = Modifier.align(Alignment.BottomEnd),
                color = MaterialTheme.colorScheme.surface.copy(alpha = .96f),
                shape = RoundedCornerShape(topStart = 6.dp)) {
                Row(Modifier.heightIn(min = 48.dp).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (satellite && installedImagery != null) Text(satelliteLabel(map), style = MaterialTheme.typography.labelMedium)
                    FieldIcon(R.drawable.field_info, "Map information", Modifier.size(19.dp))
                }
            }
            if (query.isNotBlank()) {
                MapSurface(
                    Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(horizontal = 10.dp)
                ) {
                    Column {
                        TextButton(onClick = { openDirectory(text = query) }) {
                            Text("Browse all matches")
                        }
                        val matches = results
                        if (matches == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                        else if (matches.isEmpty())
                            Text("No matching places", Modifier.padding(16.dp))
                        else
                            LazyColumn(Modifier.heightIn(max = 230.dp),state=searchScroll) {
                                items(matches.size) { i ->
                                    val p = matches[i]
                                    Entry(
                                        p.title,
                                        listOf(p.kind.replace('_', ' ').replaceFirstChar { it.uppercase() }, p.ar)
                                            .filter { it.isNotBlank() && it != p.title }
                                            .joinToString(" · "),
                                    ) {
                                        selectPlace(p)
                                    }
                                }
                            }
                    }
                }
            } else if (
                label.isNotBlank() &&
                    browser.view in listOf(DirectoryView.CLOSED, DirectoryView.PLACE)
            ) {
                MapSurface(Modifier.align(Alignment.TopStart).padding(10.dp)) {
                    Row(
                        Modifier.widthIn(max = 300.dp).padding(start = 14.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Column(Modifier.weight(1f, fill = false)) {
                            Text(
                                label,
                                modifier = Modifier.padding(top=10.dp),
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            val detail = placeInfo.ifBlank { kind.takeUnless { it=="GPS" }.orEmpty().replace('_',' ').replaceFirstChar { it.uppercase() } }
                            if (detail.isNotBlank())
                                Text(
                                    detail,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            if (browser.view == DirectoryView.PLACE) {
                                Text(
                                    String.format(Locale.ROOT, "%.5f, %.5f", lat, lon),
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            }
                            Column(horizontalAlignment=Alignment.Start) {
                                if (browser.view == DirectoryView.PLACE)
                                    TextButton(onClick = ::returnToList) {
                                        Text(
                                            if (browser.returnToNotes) "Back to notes"
                                            else "Back to results"
                                        )
                                    }
                                if (browser.view==DirectoryView.CLOSED && previousSearch.isNotEmpty())
                                    TextButton(onClick = ::leaveSearchPlace){Text("Back to results")}
                                TextButton(onClick = { editingNote = true }) { Text("Field note") }
                            }
                        }
                        MapButton(R.drawable.map_clear, "Clear selection") {
                            if(browser.view==DirectoryView.PLACE) returnToList() else leaveSearchPlace()
                        }
                    }
                }
            }
            PlaceDirectory(
                browser,
                map,
                dir,
                kit.packs,
                kit.store,
                openWaterGuide,
                kit.book.emirates.map { it.name },
                meta.getString("date"),
                meta.optInt("directory_schema") >= 2,
                askTarget?.takeIf { it.centre == browser.anchor.latLong }?.name.orEmpty(),
                { query=directorySearch;directorySearch="" },
            ) {
                selectPlace(it, fromDirectory = true)
            }
        }
    }
    if (editingNote && label.isNotBlank()) {
        val target =
            remember(label, kind, lat, lon, placeInfo) {
                Place(label, "", kind, lat, lon, detail = placeInfo)
            }
        EditFieldNote(kit.store, target) { editingNote = false }
    }
    if (options) {
        fun changeLayer(value: Boolean) {
            satellite = value
            prefs.edit().putBoolean("satellite", value).apply()
        }
        fun clearSelection() {
            query = ""; previousSearch=""; previousCamera=null; directorySearch=""
            label = ""
            kind = ""
            placeInfo = ""
            browser.view = DirectoryView.CLOSED
        }
        MapControls(
            satellite && installedImagery != null, installedImagery != null, large, kit.busy.isNotEmpty(),
            setSatellite = { changeLayer(it); options = false },
            setLargeLabels = { large = it; prefs.edit().putBoolean("large-labels", it).apply() },
            overview = { clearSelection(); camera.overview(map); options = false },
            chooseSurvey = { survey ->
                clearSelection()
                changeLayer(true)
                camera.go(LatLong(survey.latitude, survey.longitude), 18)
                options = false
            },
            download = {
                changeLayer(true)
                options = false
                kit.download(Resource.SATELLITE)
            },
            manage = { options = false; open("Downloads") },
            close = { options = false },
        )
    }
}

@Composable
private fun MapButton(icon: Int, description: String, click: () -> Unit) {
    IconButton(onClick = click, modifier = Modifier.size(48.dp)) {
        Icon(painterResource(icon), description, tint = Pine)
    }
}

@Composable
private fun MapSurface(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        modifier,
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 3.dp,
        content = content,
    )
}

// Read the live viewport when Compose saves the tab, including user pans and pinch zooms.
private class MapCamera(private var last: MapPosition, var fitOnLayout: Boolean = false) {
    private var live: MapViewPosition? = null
    val position: MapPosition
        get() = live?.mapPosition ?: last

    fun attach(view: MapViewPosition) {
        last = position
        live = view
        view.setMapPosition(last, false)
    }

    fun detach(view: MapViewPosition) {
        if (live === view) {
            last = view.mapPosition
            live = null
        }
    }

    fun overview(map: MapView) {
        val tile = map.model.displayModel.tileSize.toLong()
        val width = (uaeBounds.maxLongitude - uaeBounds.minLongitude) * tile / 360
        val height =
            MercatorProjection.latitudeToPixelY(uaeBounds.minLatitude, tile) -
                MercatorProjection.latitudeToPixelY(uaeBounds.maxLatitude, tile)
        val scale = minOf(map.width * 0.93 / width, map.height * 0.88 / height)
        fitOnLayout = false
        last =
            MapPosition(
                uaeBounds.centerPoint,
                log2(scale).coerceIn(6.0, 10.0),
                Rotation.NULL_ROTATION,
            )
        live?.setMapPosition(last, false)
    }

    fun go(point: LatLong, zoom: Int) {
        restore(MapPosition(point, zoom.toByte()))
    }

    fun restore(position: MapPosition) {
        fitOnLayout = false
        last = position
        live?.setMapPosition(last, false)
    }

    companion object {
        val saver =
            listSaver<MapCamera, Any>(
                save = {
                    val p = it.position
                    listOf(p.latLong.latitude, p.latLong.longitude, p.zoom, it.fitOnLayout)
                },
                restore = {
                    MapCamera(
                        MapPosition(
                            LatLong(it[0] as Double, it[1] as Double),
                            (it[2] as Number).toDouble(),
                            Rotation.NULL_ROTATION,
                        ),
                        it[3] as Boolean,
                    )
                },
            )
    }
}
