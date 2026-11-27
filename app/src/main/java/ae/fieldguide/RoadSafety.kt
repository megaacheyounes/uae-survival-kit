package ae.fieldguide

// Match crossing decisions narrowly. Questions about forecasts, road closures,
// insurance or vehicle repairs continue through ordinary library retrieval.
private val roadWords = setOf("road", "roads", "street", "streets", "highway", "lane", "underpass", "causeway", "route", "crossing")
private val floodWords = setOf("flood", "floods", "flooded", "flooding", "floodwater", "floodwaters", "submerged", "inundated", "underwater")
private val crossingWords = setOf("drive", "driving", "cross", "crossing", "wade", "walk", "walking", "proceed", "continue", "turn", "stop")
private val safetyVocabulary = ("""
    a an the this that there here is are looks seems ahead of on to into onto at if has been being
    my our we i im am it its s t do does should would could can may must what how when or and
    safe safely dangerous danger unsafe advice help please now next encounter encountering
    see find come reach stuck blocked blocking blocks covered covering covers cover across over under
    with by through back around go going keep going past shallow deep just little too much water
    flowing moving rushing running standing rising rain heavy after in out car vehicle truck suv
    bridge wadi valley path get way any for me us approach approaching approaching take actions
    waterlogged completely already still ok okay the best thing to do instead avoid turn around
""".trim().split(Regex("\\s+")) + roadWords + floodWords + crossingWords).toSet()

private val wordPattern = Regex("[\\p{L}\\p{N}]+")
internal fun Fieldbook.roadSafetyAnswer(query: String): Passage? {
    val words = wordPattern.findAll(query.lowercase()).map { it.value }.toSet()
    if (!safetyVocabulary.containsAll(words)) return null
    val road = words.any { it in roadWords }
    val flood = words.any { it in floodWords }
    val crossing = words.any { it in crossingWords }
    val waterOnRoad = "water" in words && road &&
        words.any { it in setOf("on", "over", "across", "under", "covered", "covering", "covers", "flowing", "through", "blocking") }
    if (!(road && flood || crossing && flood || waterOnRoad)) return null
    val guide = guides.first { it.id == "wadi" }
    val line = guide.stepIds.getValue("flooded-road")
    return Passage(guide.id, line, guide.steps[line], guide.title)
}
