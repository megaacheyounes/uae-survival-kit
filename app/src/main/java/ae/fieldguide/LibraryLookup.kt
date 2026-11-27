package ae.fieldguide

import android.content.Context
import kotlin.math.ln
import org.json.JSONObject

private val ignored =
    """
    a an the of at to from with for on in into by my me our we i you your someone it its
    is are was can could should would do does did am be been being have has how what which
    please about help tell need want use using when where who why if after before during many much
    show shows offers offer get some any something just really safely safe safest best properly correctly
    and or but so as of that this these those there here now currently actually wondering know anyone
    us im ive like still very then while got will might may must shall
"""
        .trim()
        .split(Regex("\\s+"))
        .toSet()

private val equivalents =
    listOf(
            "water freshwater",
            "drink drinking potable drinkable",
            "boil boiled boiling",
            "time long duration minutes seconds hours",
            "muddy cloudy murky sediment grit",
            "refill refilled refilling topup fountains fountain dispenser",
            "salt salty seawater saltwater",
            "fuel petrol gasoline diesel",
            "treat treating treatment",
            "light lighting ignite igniting start starting",
            "fire campfire campfires",
            "extinguish douse smother extinguishing extinguished",
            "ashes ash embers coals",
            "wood firewood sticks twigs",
            "kindling kindlings",
            "fallout radioactive radiation radiological nuclear",
            "shelter sheltering sheltered",
            "clothes clothing garments jacket coat",
            "wash washing rinse rinsing shower showering",
            "dust particles",
            "inside indoors indoor",
            "room rooms",
            "building buildings tower flat apartment",
            "leave leaving exit emerge",
            "north northern",
            "star stars",
            "shadow shadows",
            "stick pole gnomon",
            "map maps",
            "polaris northstar",
            "direction directions bearing bearings",
            "find locate locating finding",
            "power electricity blackout outage blackouts outages",
            "fridge refrigerator refrigerated",
            "vehicle car truck",
            "navigator navigation navigate",
            "confirm verify check",
            "carry pack",
            "recognize recognise identify identification",
            "dead dies dying",
            "food foods groceries",
            "plant plants",
            "fruit fruits",
            "locust locusts",
            "route routes",
            "sun sunlight",
            "purify purifying purification disinfect disinfection",
            "cool cold chilled",
            "broken breakdown stranded",
            "keep keeps keeping stays last lasts",
        )
        .flatMap { group -> group.split(' ').map { it to group.substringBefore(' ') } }
        .toMap()
private val tokens = Regex("[\\p{L}\\p{N}]+")

internal fun searchWords(input: String): Set<String> {
    val text =
        input
            .lowercase()
            .replace("put out", "extinguish")
            .replace("fresh water", "freshwater")
            .replace("power cut", "blackout")
            .replace("power went out", "blackout")
            .replace("power goes out", "blackout")
            .replace("electricity goes off", "blackout")
            .replace("goes out", "dies")
            .replace("north star", "polaris")
            .replace("top up", "refill")
    return tokens
        .findAll(text)
        .map { it.value }
        .filter { it.length > 1 && it !in ignored }
        .map { equivalents[it] ?: it }
        .toSet()
}

private data class IndexedStep(
    val passage: Passage,
    val body: Set<String>,
    val heading: Set<String>,
    val allWords: Set<String>,
    val phrases: List<Set<String>>,
    val topics: Set<String>,
)

private data class Score(
    val passage: Passage,
    val rank: Double,
    val confidence: Double,
    val coverage: Double,
)

data class Lookup(
    val direct: Passage?,
    val candidates: List<Passage>,
    val modelCandidates: List<Passage>,
)

internal fun passageExamples(context: Context): Map<String, Map<String, List<String>>> {
    val data = context.assets.open("manual/search-phrases.json").bufferedReader().use { JSONObject(it.readText()) }
    return data.keys().asSequence().associateWith { id ->
        val headings = data.getJSONObject(id)
        headings.keys().asSequence().associateWith { heading ->
            val values = headings.getJSONArray(heading)
            (0 until values.length()).map(values::getString)
        }
    }
}

class LibraryLookup(guides: List<Guide>, examples: Map<String, Map<String, List<String>>>) {
    private val steps: List<IndexedStep>
    private val frequency: Map<String, Int>
    private val vocabulary: Map<Int, List<String>>

    init {
        steps =
            guides.flatMap { g ->
                g.steps.mapIndexed { line, body ->
                    val heading = body.substringBefore(". ")
                    val phrases = buildList {
                        add(searchWords(heading))
                        examples[g.id]?.get(heading).orEmpty().forEach { add(searchWords(it)) }
                    }
                    val bodyWords = searchWords(body)
                    val allWords = bodyWords + searchWords(g.title) + phrases.flatten()
                    IndexedStep(
                        Passage(g.id, line, body, g.title),
                        bodyWords,
                        searchWords(heading),
                        allWords,
                        phrases,
                        searchWords(g.tags),
                    )
                }
            }
        frequency = steps.flatMap { it.allWords + it.topics }.groupingBy { it }.eachCount()
        vocabulary = (frequency.keys + equivalents.filterValues { it in frequency }.keys).groupBy { it.length }
    }

    private fun weight(word: String) = 1.0 + ln((steps.size + 1.0) / ((frequency[word] ?: 0) + 1.0))

    fun find(query: String): Lookup {
        val words = searchWords(query).mapTo(mutableSetOf()) { word ->
            if (word in frequency || word.length !in 5..24) word else {
                val alternatives = (word.length-1..word.length+1).asSequence()
                    .flatMap { vocabulary[it].orEmpty().asSequence() }
                    .filter { oneEditApart(word, it) }.map { equivalents[it] ?: it }.distinct().take(2).toList()
                alternatives.singleOrNull() ?: word
            }
        }
        if (words.isEmpty()) return Lookup(null, emptyList(), emptyList())
        val total = words.sumOf(::weight)
        val discoveryWeight = words.sumOf { if (it in frequency) weight(it) else 2.0 }
        val scores =
            steps
                .mapNotNull { step ->
                    val phrases = step.phrases
                    val matched = words.intersect(step.allWords)
                    val topicMatches = (words - matched).intersect(step.topics)
                    val matchedEvidence = matched.sumOf(::weight)
                    val discovery = (matchedEvidence + .45 * topicMatches.sumOf(::weight)) / discoveryWeight
                    if (matched.isEmpty() && (words.size != 1 || topicMatches.isEmpty())) return@mapNotNull null
                    val coverage = matched.sumOf(::weight) / total
                    if (discovery < .40 || (words.size > 1 && matched.size + topicMatches.size < 2))
                        return@mapNotNull null
                    var confidence = 0.0
                    val bestPhrase =
                        phrases.maxOf { phrase ->
                            val shared = phrase.intersect(words)
                            val evidence = shared.sumOf(::weight)
                            val recall = evidence / total
                            val precision = evidence / phrase.sumOf(::weight).coerceAtLeast(1.0)
                            val similarity = recall * precision
                            // A question can combine an action from one example with context from
                            // another.
                            val supported =
                                recall >= .87 ||
                                    (coverage >= .95 && recall >= .55 && precision >= .70) ||
                                    (precision >= .98 && recall >= .60 &&
                                        (words - shared).all { it in step.topics })
                            if (shared.size >= 2 && supported && precision >= .48)
                                confidence = maxOf(confidence, precision * (recall + coverage) / 2)
                            similarity
                        }
                    val body = words.intersect(step.body).sumOf(::weight) / total
                    if (body >= .85 && words.intersect(step.body).size >= 3 && words.any { it in step.heading })
                        confidence = maxOf(confidence, body * .75)
                    val rank =
                        5 * bestPhrase +
                            2 * body +
                            coverage + .45 * discovery +
                            words.intersect(step.heading).sumOf(::weight) / total
                    Score(step.passage, rank, confidence, coverage)
                }
                .sortedByDescending { it.rank }
        val best = scores.firstOrNull()
        val runnerUp = scores.getOrNull(1)
        val direct =
            best
                ?.takeIf {
                    val margin = it.rank - (runnerUp?.rank ?: 0.0)
                    it.confidence >= .48 && (margin >= .6 || (margin >= .25 &&
                        it.confidence >= .80 && it.confidence - (runnerUp?.confidence ?: 0.0) >= .20))
                }
                ?.passage
        return Lookup(
            direct,
            scores.distinctBy { it.passage.guide }.take(5).map { it.passage },
            scores.filter { it.coverage >= .75 && words.size >= 2 }.take(4).map { it.passage },
        )
    }
}

// Only unambiguous, single-edit misspellings are corrected. An unknown technical term remains unknown.
private fun oneEditApart(a: String, b: String): Boolean {
    if (a.length > b.length) return oneEditApart(b, a)
    val i = a.indices.firstOrNull { a[it] != b[it] } ?: return b.length == a.length + 1
    if (a.length < b.length) return a.regionMatches(i, b, i + 1, a.length - i)
    if (a.regionMatches(i + 1, b, i + 1, a.length - i - 1)) return true
    return i + 1 < a.length && a[i] == b[i+1] && a[i+1] == b[i] &&
        a.regionMatches(i + 2, b, i + 2, a.length - i - 2)
}
