package ae.fieldguide

import android.net.Uri
import java.text.Normalizer
import org.mapsforge.core.model.LatLong

private val wordBreaks = Regex("[^\\p{L}\\p{N}]+")
private val accents = Regex("\\p{M}+")
internal fun placeWords(text: String): String =
    wordBreaks.replace(accents.replace(Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD), ""), " ").trim()

private val serviceTerms = mapOf(
    MapService.SOURCES to "water wells?|wells?|springs?",
    MapService.DRINKING to "water|freshwater|refills?|fountains?|thirsty",
    MapService.GROCERY to "grocer(?:y|ies)|supermarkets?|baker(?:y|ies)|food shops?",
    MapService.RESTAURANT to "restaurants?|cafes?|places? to eat",
    MapService.PHARMACY to "pharmac(?:y|ies)|chemists?|drugstores?",
    MapService.HOSPITAL to "hospitals?|emergency departments?",
    MapService.CLINIC to "clinics?",
    MapService.POLICE to "police(?: stations?)?",
    MapService.FIRE to "fire stations?",
    MapService.TRANSPORT to "transport|bus stops?|bus stations?|metro|train stations?|ferr(?:y|ies)",
    MapService.FUEL to "fuel|petrol|gasoline|gas stations?",
    MapService.REPAIR to "hardware|repair shops?|mechanics?",
    MapService.SHELTER to "campsites?|campgrounds?|shelters?",
    MapService.SHOPPING to "shopping|malls?|shops?",
    MapService.BANK to "banks?|atms?",
    MapService.TOILETS to "toilets?|bathrooms?|restrooms?",
    MapService.POST to "post offices?|post boxes?|parcel lockers?",
).mapValues { (_, terms) -> Regex("\\b(?:$terms)\\b") }
private val findingPlace = Regex("\\b(where|near|nearby|nearest|closest|around|find|locate|looking for|locations?|places to|in my area|ran out of|run out of|need|thirsty)\\b")
private val waterTask = Regex("\\b(boil\\w*|purif\\w*|treat\\w*|filter\\w*|seawater|saltwater|contaminat\\w*|disinfect\\w*|flood\\w*|store|storing|how long|how much|drink from|safe to drink)\\b")

internal fun askedService(question: String, namesLocality: () -> Boolean = { false }): MapService? {
    val q = placeWords(question)
    if (Regex("\\b(fallout|radiation|nuclear|campfire|evacuat\\w*)\\b").containsMatchIn(q)) return null
    val service = serviceTerms.entries.firstOrNull { it.value.containsMatchIn(q) }?.key ?: return null
    if (service in listOf(MapService.DRINKING, MapService.SOURCES) && waterTask.containsMatchIn(q)) return null
    if (findingPlace.containsMatchIn(q)) return service
    return service.takeIf { Regex("\\b(?:in|at)\\s+\\S+").containsMatchIn(q) && namesLocality() }
}

internal data class AskMapTarget(val service: MapService, val centre: LatLong?, val name: String = "") {
    fun route(): String = "Places/${service.name}" +
        (centre?.let { "/${it.latitude}/${it.longitude}/${Uri.encode(name)}" } ?: "")

    companion object {
        fun fromRoute(route: String): AskMapTarget {
            val parts = route.split('/')
            val service = MapService.valueOf(parts[1])
            return if (parts.size == 5) AskMapTarget(service,
                LatLong(parts[2].toDouble(), parts[3].toDouble()), Uri.decode(parts[4]))
            else AskMapTarget(service, null)
        }
    }
}

internal data class LocalPlaces(
    val target: AskMapTarget,
    val date: String,
    val count: Int,
    val nearest: List<DirectoryRow>,
)

internal fun Packs.findForQuestion(service: MapService, question: String, location: LatLong? = null): LocalPlaces {
    val dir = directoryForLookup()
    val date = checkNotNull(manifestFor(dir)).getString("date")
    val target = if (location != null) AskMapTarget(service, location, "your GPS fix") else {
        val origin = namedLocality(dir, question)
        AskMapTarget(service, origin?.let { LatLong(it.lat, it.lon) }, origin?.title.orEmpty())
    }
    val centre = target.centre ?: return LocalPlaces(target, date, categoryCounts(dir)[service.kind] ?: 0, emptyList())
    val refs = rankPlaces(dir, service, centre, false, "", "")
    return LocalPlaces(target, date, refs.size, readPlaces(dir, refs.take(2)))
}
