package ae.fieldguide

// These first actions must be available even on a phone with no model or free memory.
// Match an exposure together with the person's observation, before place or document search.
internal fun incidentGuide(question: String): String? {
    val words = placeWords(question).split(' ').toSet()
    fun has(vararg terms: String) = terms.any(words::contains)
    val unwell = has("sick", "ill", "strange", "weird", "funny", "dizzy", "woozy", "lightheaded",
        "lightheadedness", "nauseous", "nausea", "vomit", "vomited", "vomiting", "diarrhea", "diarrhoea",
        "cramps", "cramping", "confused", "faint", "headache", "retching", "queasy", "nauseated", "breathless", "spinning")
    val ate = has("ate", "eaten", "eating", "tasted", "nibbled", "swallowed", "chewed", "consumed")
    val enclosed = has("cave", "cavern", "tunnel", "cellar", "mine", "cistern", "shaft", "basement", "tank", "manhole", "bunker") ||
        (has("well", "pit") && has("inside", "down", "entered", "climbed"))
    val bitten = has("bit", "bitten", "bite", "bites", "stung", "sting", "stings", "scratched")
    val mushroomCloud = "mushroom cloud" in placeWords(question)
    val daytime = has("daytime", "afternoon", "midday", "daylight", "noon", "lunchtime", "pm") ||
        (has("lunch") && has("suddenly", "sky", "outside"))
    return when {
        enclosed && (unwell || has("fumes", "gasping", "collapsed", "unconscious", "hazy", "stuffy") ||
            (has("air", "gas", "mist") && has("cloudy", "foggy", "bad", "thick", "odd", "smell", "breathing"))) -> "caveair"
        !mushroomCloud && has("mushroom", "mushrooms", "toadstool", "toadstools", "fungus", "fungi", "shroom", "shrooms") &&
            (ate || has("eat", "edible", "safe", "wild", "picked", "found", "poisonous", "cap", "caps") || unwell) -> "wildmushroom"
        (ate || has("drank", "ingested", "licked", "sipped", "gulp")) &&
            has("cleaner", "bleach", "detergent", "pesticide", "poison", "battery", "magnet", "magnets", "kerosene", "disinfectant") -> "swallowedpoison"
        bitten && has("bug", "insect", "insects", "bee", "wasp", "hornet", "mosquito", "ant", "tick", "spider") -> "bugwound"
        bitten && has("dog", "cat", "bat", "fox", "stray", "monkey") && !ate -> "animalbite"
        (has("meal", "leftovers", "meat", "food", "lunch", "dinner", "breakfast", "chicken", "beef", "fish", "seafood") ||
            (ate && has("dog", "paw", "foot", "animal", "carcass", "roadkill", "goat", "rabbit", "lamb"))) &&
            (unwell || has("vomit", "throwing", "retch", "cramp")) -> "sickfood"
        (daytime && has("dark", "darkness", "dim", "black", "gloomy", "disappeared")) ||
            (has("sun", "sunlight") && has("no", "hidden", "gone", "missing") && has("birds", "sky", "afternoon", "pm")) -> "dimsky"
        else -> null
    }
}

internal fun incidentStep(id: String, question: String): Int {
    val words = placeWords(question).split(' ').toSet()
    if (id == "swallowedpoison" && words.any { it in setOf("battery", "magnet", "magnets") }) return 1
    // The heading is conditional so quoted, hypothetical or negated ingestion remains safe.
    if (id == "wildmushroom" && words.any { it in setOf("ate", "tasted", "nibbled", "swallowed", "eaten", "chewed") }) return 1
    return 0
}
