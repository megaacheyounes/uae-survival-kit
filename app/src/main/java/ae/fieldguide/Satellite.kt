package ae.fieldguide

import androidx.compose.runtime.*
import org.mapsforge.core.util.Parameters
import org.mapsforge.map.android.graphics.AndroidGraphicFactory
import org.mapsforge.map.android.view.MapView
import org.mapsforge.map.layer.cache.TileStore
import org.mapsforge.map.layer.tilestore.TileStoreLayer
import org.mapsforge.map.model.common.Observer
import java.io.File

@Composable
internal fun satelliteLabel(map: MapView): String {
    val position = map.model.mapViewPosition
    fun labelForView(): String {
        val zoom = position.zoomLevel.toInt()
        val point = position.center
        val label = when {
            zoom in 10..14 -> "Satellite · 2021"
            zoom in 15..19 && aerialSurveys.any { it.contains(point.latitude, point.longitude) } -> "Aerial · 2021"
            else -> "Street detail"
        }
        return label
    }
    var value by remember(position) { mutableStateOf(labelForView()) }
    DisposableEffect(position) {
        val observer = Observer { value = labelForView() }
        position.addObserver(observer)
        onDispose { position.removeObserver(observer) }
    }
    return value
}

@Composable
internal fun SatelliteLayers(map: MapView, pack: File) {
    DisposableEffect(map, pack) {
        val graphics = AndroidGraphicFactory.INSTANCE
        val tiles = TileStore(pack, ".webp", graphics)
        val layer = TileStoreLayer(tiles, map.model.mapViewPosition, graphics, true)
        layer.setParentTilesRendering(Parameters.ParentTilesRendering.OFF)
        val layers = map.layerManager.layers
        layers.add(1, layer)
        onDispose {
            if (layers.remove(layer)) {
                layer.onDestroy()
                tiles.destroy()
            }
        }
    }
}
