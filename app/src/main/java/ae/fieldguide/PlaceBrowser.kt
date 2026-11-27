package ae.fieldguide

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import org.mapsforge.core.model.LatLong
import org.mapsforge.core.model.MapPosition
import org.mapsforge.core.model.Rotation

internal const val NEARBY_RADIUS_KM = 50
internal const val DIRECTORY_PAGE_SIZE = 50
internal const val MAP_PIN_LIMIT = 12

internal enum class MapService(
    val kind: String,
    val label: String,
    val group: String,
    val icon: Int,
    val argb: Int,
) {
    ALL("", "All mapped places", "", R.drawable.poi_catalog, 0xff20473f.toInt()),
    GROCERY("grocery", "Groceries", "Food & water", R.drawable.poi_groceries, 0xff47613d.toInt()),
    RESTAURANT(
        "restaurant",
        "Restaurants & cafés",
        "Food & water",
        R.drawable.poi_food,
        0xff80522e.toInt(),
    ),
    DRINKING(
        "drinking_water",
        "Drinking water",
        "Food & water",
        R.drawable.poi_water,
        0xff28647b.toInt(),
    ),
    SOURCES(
        "water_source",
        "Other water sources",
        "Food & water",
        R.drawable.poi_spring,
        0xff41676d.toInt(),
    ),
    HOSPITAL(
        "hospital",
        "Hospitals",
        "Health & safety",
        R.drawable.poi_medical,
        0xff963f39.toInt(),
    ),
    PHARMACY(
        "pharmacy",
        "Pharmacies",
        "Health & safety",
        R.drawable.poi_medical,
        0xff306654.toInt(),
    ),
    CLINIC("clinic", "Clinics", "Health & safety", R.drawable.poi_medical, 0xff87544c.toInt()),
    POLICE("police", "Police", "Health & safety", R.drawable.poi_police, 0xff385976.toInt()),
    FIRE(
        "fire_station",
        "Fire stations",
        "Health & safety",
        R.drawable.poi_fire,
        0xff8c4535.toInt(),
    ),
    TRANSPORT(
        "transport",
        "Transport",
        "Travel & supplies",
        R.drawable.poi_transport,
        0xff435f78.toInt(),
    ),
    FUEL("fuel", "Fuel", "Travel & supplies", R.drawable.poi_fuel, 0xff795a34.toInt()),
    REPAIR(
        "repair",
        "Hardware & repairs",
        "Travel & supplies",
        R.drawable.poi_repair,
        0xff675339.toInt(),
    ),
    SHELTER(
        "shelter",
        "Shelters & camps",
        "Travel & supplies",
        R.drawable.poi_shelter,
        0xff526741.toInt(),
    ),
    SHOPPING("shopping", "Shopping", "Everyday services", R.drawable.poi_shop, 0xff765675.toInt()),
    BANK("bank", "Banks & ATMs", "Everyday services", R.drawable.poi_bank, 0xff4d6353.toInt()),
    TOILETS("toilets", "Toilets", "Everyday services", R.drawable.poi_toilets, 0xff405e6e.toInt()),
    POST(
        "post",
        "Post offices & boxes",
        "Everyday services",
        R.drawable.poi_post,
        0xff785a3d.toInt(),
    ),
}

internal enum class DirectoryView {
    CLOSED,
    CATEGORIES,
    NOTES,
    LIST,
    PINS,
    PLACE,
}

@Stable
internal class PlaceBrowser {
    var view by mutableStateOf(DirectoryView.CLOSED)
    var service by mutableStateOf(MapService.ALL)
    var countrywide by mutableStateOf(true)
    var emirate by mutableStateOf("")
    var search by mutableStateOf("")
    var page by mutableIntStateOf(0)
    var anchor by mutableStateOf(MapPosition(LatLong(24.45, 54.1), 7))
    var fromCategories by mutableStateOf(false)
    var returnToNotes by mutableStateOf(false)

    fun open(position: MapPosition, category: MapService? = null, text: String = "") {
        anchor = position
        countrywide = category == null
        service = category ?: MapService.ALL
        fromCategories = category == null && text.isBlank()
        page = 0
        search = text
        emirate = ""
        view = if (fromCategories) DirectoryView.CATEGORIES else DirectoryView.LIST
    }

    fun choose(category: MapService) {
        service = category
        countrywide = true
        search = ""
        emirate = ""
        page = 0
        fromCategories = true
        view = DirectoryView.LIST
    }

    companion object {
        val saver =
            listSaver<PlaceBrowser, Any>(
                save = {
                    listOf(
                        it.view.name,
                        it.service.name,
                        it.countrywide,
                        it.emirate,
                        it.search,
                        it.page,
                        it.anchor.latLong.latitude,
                        it.anchor.latLong.longitude,
                        it.anchor.zoom,
                        it.fromCategories,
                        it.returnToNotes,
                    )
                },
                restore = { values ->
                    PlaceBrowser().apply {
                        view = DirectoryView.valueOf(values[0] as String)
                        service = MapService.valueOf(values[1] as String)
                        countrywide = values[2] as Boolean
                        emirate = values[3] as String
                        search = values[4] as String
                        page = values[5] as Int
                        anchor =
                            MapPosition(
                                LatLong(values[6] as Double, values[7] as Double),
                                values[8] as Double,
                                Rotation.NULL_ROTATION,
                            )
                        fromCategories = values[9] as Boolean
                        returnToNotes = values.getOrNull(10) as? Boolean ?: false
                    }
                },
            )
    }
}
