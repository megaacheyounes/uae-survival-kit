package ae.fieldguide

import android.app.ActivityManager
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.system.Os
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipInputStream
import org.json.JSONObject
import org.mapsforge.core.model.LatLong
import org.mapsforge.map.reader.MapFile

private fun copyChecked(input: InputStream, file: File, limit: Long): Pair<Long, String> {
    val digest = MessageDigest.getInstance("SHA-256")
    var total = 0L
    file.outputStream().buffered().use { output ->
        val buffer = ByteArray(128 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            total += n
            require(total <= limit) { "File exceeds the supported size." }
            output.write(buffer, 0, n)
            digest.update(buffer, 0, n)
        }
    }
    return total to digest.digest().joinToString("") { "%02x".format(it) }
}

data class Place(
    val title: String,
    val ar: String,
    val kind: String,
    val lat: Double,
    val lon: Double,
    val detail: String = "",
    val area: String = "",
    val emirate: String = "",
    val access: String = "",
) {
    val description
        get() =
            listOf(detail, area, emirate, access)
                .filter { it.isNotBlank() && it != title }
                .distinct()
                .joinToString(" · ")
}

internal data class PlaceDistance(val id: Long, val metres: Double)

internal data class DirectoryRow(val place: Place, val metres: Double)

class Packs(private val ctx: Context) {
    private val prefs = ctx.getSharedPreferences("local-packs", Context.MODE_PRIVATE)
    private val root = File(ctx.filesDir, "maps").apply { mkdirs() }
    @Volatile private var lastManifest: Pair<File, JSONObject>? = null
    private val selectedDirectory: File?
        get() = prefs.getString("map", null)?.let { File(root, it) }
    val active: File?
        get() = selectedDirectory?.takeIf { manifestFor(it) != null }

    internal fun manifestFor(dir: File): JSONObject? {
        lastManifest?.takeIf { it.first == dir }?.let { return it.second }
        if (!dir.isDirectory) return null
        return try {
            val manifest = JSONObject(File(dir, "manifest.json").readText())
            val files = manifest.getJSONObject("files")
            for (name in listOf("uae.map", "places.sqlite")) {
                val expected = files.getJSONObject(name).getLong("bytes")
                if (expected <= 0 || File(dir, name).length() != expected) return null
            }
            lastManifest = dir to manifest
            manifest
        } catch (_: java.io.IOException) {
            null
        } catch (_: org.json.JSONException) {
            null
        }
    }

    internal val imagery = OfflineImagery(ctx)
    val model = File(ctx.filesDir, "qwen-q4.gguf")
    val aiEnabled get() = model.isFile && prefs.getLong("tested-answer-model", -1L) == model.lastModified()

    // Only run at process startup, before a view can hold the previous map open.
    fun cleanStaging() {
        val selected = selectedDirectory
        root.listFiles()?.filter { it != selected }?.forEach { it.deleteRecursively() }
        File(ctx.filesDir, "model.part").delete()
    }

    fun needsBundledMap(dir: File?): Boolean {
        if (dir == null) return true
        if (prefs.getBoolean("directoryV2Installed", false)) return false
        val files = manifestFor(dir)?.getJSONObject("files") ?: return true
        return files.getJSONObject("places.sqlite").getString("sha256") ==
            "fc65075a7b1e371354d6a6c1b4be4e41d7d39b4a00a6aea422445dfe50616f8f" &&
            files.getJSONObject("uae.map").getString("sha256") ==
                "531d218c28c9959ea7ee0b3d622db7f0387a39b11b5e1d7bf173aa26cc0b5135"
    }

    fun installBundledMap() = ctx.assets.open("UAE-map.zip").use { installMap(it, bundled = true) }

    @Synchronized
    internal fun directoryForLookup(): File {
        val current = active
        if (current != null && !needsBundledMap(current)) return current
        installBundledMap()
        return checkNotNull(active)
    }

    fun importMap(uri: Uri) =
        requireNotNull(ctx.contentResolver.openInputStream(uri)).use {
            installMap(it, bundled = false)
        }

    @Synchronized
    private fun installMap(input: InputStream, bundled: Boolean) {
        val dir = File(root, UUID.randomUUID().toString()).apply { check(mkdir()) }
        try {
            val limits =
                mapOf(
                    "manifest.json" to 16384L,
                    "uae.map" to 750_000_000L,
                    "places.sqlite" to 150_000_000L,
                )
            val hashes = mutableMapOf<String, Pair<Long, String>>()
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    require(e.name in limits && e.name !in hashes && !e.isDirectory) {
                        "Unexpected map pack entry."
                    }
                    hashes[e.name] = copyChecked(zip, File(dir, e.name), limits.getValue(e.name))
                    zip.closeEntry()
                }
            }
            require(hashes.keys == limits.keys) { "Incomplete map pack." }
            val manifest = JSONObject(File(dir, "manifest.json").readText())
            require(manifest.getInt("format") == 1 && manifest.getString("region") == "AE") {
                "Choose a UAE Survival Kit map pack."
            }
            for (name in listOf("uae.map", "places.sqlite")) {
                val entry = manifest.getJSONObject("files").getJSONObject(name)
                require(hashes[name] == (entry.getLong("bytes") to entry.getString("sha256"))) {
                    "Map checksum failed."
                }
            }
            require(
                manifest.getString("date").matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")) &&
                    manifest.getString("version").isNotBlank()
            ) {
                "Map pack has an invalid date or version."
            }
            val reader = MapFile(File(dir, "uae.map"))
            reader.close()
            // Android SQLite validates FTS using its writable integrity-check command.
            // Check a temporary copy so the published, checksum-verified index stays exact.
            val checkFile = File(dir, "validate.sqlite")
            File(dir, "places.sqlite").copyTo(checkFile)
            SQLiteDatabase.openDatabase(
                    checkFile.path,
                    null,
                    SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
                )
                .use { db ->
                    db.rawQuery("PRAGMA quick_check", null).use {
                        val status = if (it.moveToFirst()) it.getString(0) else "no result"
                        require(status == "ok") {
                            "Place index verification failed: " + status.take(160)
                        }
                    }
                    if (manifest.optInt("directory_schema") >= 2) require(db.version >= 2) { "Missing expanded directory." }
                    if (db.version >= 2) db.rawQuery("SELECT detail,area,emirate,access FROM places LIMIT 1", null).use {
                        require(it.moveToFirst()) { "Place details are missing." }
                    }
                    db.rawQuery(
                            "SELECT p.name,p.arabic,p.kind,p.lat,p.lon FROM search JOIN places p ON p.rowid=search.docid WHERE search MATCH 'Dubai' LIMIT 1",
                            null,
                        )
                        .use { require(it.moveToFirst()) { "Place index is missing." } }
                }
            SQLiteDatabase.deleteDatabase(checkFile)
            val activate = prefs.edit().putString("map", dir.name)
            if (bundled) activate.putBoolean("directoryV2Installed", true)
            check(activate.commit()) { "Could not activate pack." }
            lastManifest = dir to manifest
        } catch (e: Exception) {
            dir.deleteRecursively()
            throw e
        }
    }

    private fun db(dir: File) =
        SQLiteDatabase.openDatabase(
            File(dir, "places.sqlite").path,
            null,
            SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
        )

    private fun tokens(text: String) =
        Regex("[\\p{L}\\p{N}]+").findAll(text).take(8).joinToString(" ") { it.value + "*" }

    internal fun categoryCounts(dir: File): Map<String, Int> =
        db(dir).use { db ->
            db.rawQuery("SELECT kind,count(*) FROM places GROUP BY kind", null).use { rows ->
                buildMap { while (rows.moveToNext()) put(rows.getString(0), rows.getInt(1)) }
            }
        }

    /** Match complete locality names from this pack; service and address text are not anchors. */
    internal fun namedLocality(dir: File, question: String): Place? = db(dir).use { database ->
        val text = " " + placeWords(question) + " "
        val matches = mutableListOf<Pair<Int, Place>>()
        val kinds = "'city','town','village','hamlet','suburb','district','quarter','neighbourhood','locality','island'"
        database.rawQuery(
            "SELECT ${placeColumns(database)} FROM places p WHERE kind IN ($kinds) ORDER BY p.rowid", null
        ).use { rows ->
            while (rows.moveToNext()) {
                val place = rows.place()
                val length = listOf(place.title, place.ar).maxOf { name ->
                    val n = placeWords(name)
                    if (n.length >= 2 && n != place.kind && " $n " in text) n.length else 0
                }
                if (length != 0) matches.add(length to place)
            }
        }
        val districtsInNamedEmirate = matches.filter { (_, p) ->
            val emirate = placeWords(p.emirate)
            emirate.isNotEmpty() && placeWords(p.title) != emirate && " $emirate " in text
        }
        val candidates = districtsInNamedEmirate.ifEmpty { matches }
        val longest = candidates.maxOfOrNull { it.first } ?: return@use null
        val named = candidates.filter { it.first == longest }.map { it.second }
        val region = named.filter { it.emirate.isNotBlank() && " ${placeWords(it.emirate)} " in text }
        val choices = region.ifEmpty { named }
        val priority = listOf("city", "town", "village")
        val best = choices.minBy { priority.indexOf(it.kind).let { n -> if (n < 0) priority.size else n } }
        val origin = LatLong(best.lat, best.lon)
        // Point and boundary features can represent the same town. Distant namesakes need a more specific question.
        if (choices.any { it.kind == best.kind && origin.sphericalDistance(LatLong(it.lat, it.lon)) > 10_000 }) null else best
    }

    /** Rank compact row references once. Only the visible page gets its full text loaded. */
    internal fun rankPlaces(
        dir: File,
        service: MapService,
        centre: LatLong,
        countrywide: Boolean,
        emirate: String,
        query: String,
    ): List<PlaceDistance> =
        db(dir).use { db ->
            val conditions = mutableListOf<String>()
            val args = mutableListOf<String>()
            if (service != MapService.ALL) {
                conditions.add("kind = ?")
                args.add(service.kind)
            }
            if (countrywide && emirate.isNotEmpty()) {
                if (db.version < 2) return@use emptyList()
                conditions.add("emirate = ?")
                args.add(emirate)
            }
            if (!countrywide) {
                // Half a degree of latitude safely encloses the 50 km circle. Apply the exact
                // distance below.
                conditions.add("lat BETWEEN ? AND ?")
                args.add((centre.latitude - 0.5).toString())
                args.add((centre.latitude + 0.5).toString())
            }
            if (query.isNotBlank()) {
                val search = tokens(query)
                if (search.isEmpty()) return@use emptyList()
                conditions.add("rowid IN (SELECT docid FROM search WHERE search MATCH ?)")
                args.add(search)
            }
            val where =
                if (conditions.isEmpty()) ""
                else conditions.joinToString(" AND ", prefix = " WHERE ")
            db.rawQuery("SELECT rowid,lat,lon FROM places" + where, args.toTypedArray()).use { rows
                ->
                buildList {
                        while (rows.moveToNext()) {
                            val distance =
                                centre.sphericalDistance(
                                    LatLong(rows.getDouble(1), rows.getDouble(2))
                                )
                            if (countrywide || distance <= NEARBY_RADIUS_KM * 1000)
                                add(PlaceDistance(rows.getLong(0), distance))
                        }
                    }
                    .sortedWith(compareBy({ it.metres }, { it.id }))
            }
        }

    internal fun readPlaces(dir: File, refs: List<PlaceDistance>): List<DirectoryRow> {
        if (refs.isEmpty()) return emptyList()
        return db(dir).use { db ->
            val columns = placeColumns(db)
            val ids = refs.joinToString(",") { "?" }
            val found =
                db.rawQuery(
                        "SELECT $columns,p.rowid FROM places p WHERE p.rowid IN ($ids)",
                        refs.map { it.id.toString() }.toTypedArray(),
                    )
                    .use { rows ->
                        buildMap { while (rows.moveToNext()) put(rows.getLong(9), rows.place()) }
                    }
            refs.map { DirectoryRow(found.getValue(it.id), it.metres) }
        }
    }

    private fun placeColumns(db: SQLiteDatabase) =
        "p.name,p.arabic,p.kind,p.lat,p.lon," +
            if (db.version >= 2) "p.detail,p.area,p.emirate,p.access" else "'','','',''"

    private fun Cursor.place() =
        Place(
            getString(0),
            getString(1),
            getString(2),
            getDouble(3),
            getDouble(4),
            getString(5),
            getString(6),
            getString(7),
            getString(8),
        )

    fun places(query: String, latitude: Double, longitude: Double): List<Place> {
        val dir = active ?: return emptyList()
        val expr = tokens(query)
        if (expr.isEmpty()) return emptyList()
        return db(dir).use { db ->
            db.rawQuery(
                    """SELECT ${placeColumns(db)}
                   FROM search JOIN places p ON p.rowid = search.docid
                   WHERE search MATCH ?
                   ORDER BY (lower(p.name) = lower(?) OR p.arabic = ?) DESC,
                       (p.kind IN ('city','town','village')) DESC,
                       (p.lat-?4)*(p.lat-?4) + 0.83*(p.lon-?5)*(p.lon-?5)
                   LIMIT 40""",
                    arrayOf(
                        expr,
                        query.trim(),
                        query.trim(),
                        latitude.toString(),
                        longitude.toString(),
                    ),
                )
                .use { c -> buildList { while (c.moveToNext()) add(c.place()) } }
        }
    }

    fun importModel(uri: Uri) {
        val stage = File(ctx.filesDir, "model.part")
        try {
            require(ctx.filesDir.usableSpace > 1_140_000_000L) {
                "Free at least 1.14 GB before importing."
            }
            val result =
                requireNotNull(ctx.contentResolver.openInputStream(uri)).use {
                    copyChecked(it, stage, 1117320736L)
                }
            require(
                result ==
                    (1117320736L to
                        "6a1a2eb6d15622bf3c96857206351ba97e1af16c30d7a74ee38970e434e9407e")
            ) {
                "Select the supplied Qwen2.5 1.5B Q4_K_M model."
            }
            Os.rename(stage.path, model.path)
            probe(model)
        } finally {
            stage.delete()
        }
    }

    fun memoryCheck() {
        val info = ActivityManager.MemoryInfo()
        requireNotNull(ctx.getSystemService(ActivityManager::class.java)).getMemoryInfo(info)
        require(!info.lowMemory && info.availMem - info.threshold > 1_500_000_000L) {
            "Too little memory is free. Close other apps and retry."
        }
    }

    fun probe(file: File = model) {
        memoryCheck()
        require(
            LocalAI.complete(
                    file.path,
                    LocalAI.prompt("Reply with a single digit.", "What is one plus one?"),
                    9,
                )
                .trim() == "2"
        ) {
            "The local generation test did not pass."
        }
        prefs.edit().putLong("tested-answer-model", file.lastModified()).apply()
    }
}

object LocalAI {
    init {
        System.loadLibrary("fieldguide")
    }

    external fun complete(path: String, input: String, maxLabel: Int): String

    fun prompt(system: String, user: String) =
        "<|im_start|>system\n" +
            system +
            "<|im_end|>\n<|im_start|>user\n" +
            user +
            "<|im_end|>\n<|im_start|>assistant\n"

    fun choose(packs: Packs, book: Fieldbook, query: String, candidates: List<Passage>): Passage? {
        if (candidates.isEmpty()) return null
        packs.memoryCheck()
        val lines = candidates.take(9)
        val choices = lines.map { p ->
            val heading = p.body.substringBefore(". ")
            val intents = book.examples[p.guide]?.get(heading).orEmpty().take(2).joinToString(" / ")
            "$heading | $intents | " + clean(p.body.substringAfter(". ").take(190))
        }
        val response =
            complete(
                packs.model.path,
                prompt(PassagePrompt.SYSTEM, PassagePrompt.question(choices, query)),
                lines.size,
            )
        return response.trim().toIntOrNull()?.let { lines.getOrNull(it - 1) }
    }

    private fun clean(text: String) = text.take(1200).replace('<', ' ').replace('>', ' ')

    fun explain(packs: Packs, question: String, passage: Passage): String? {
        // For medical, food identification and emergency procedures show the authored steps verbatim.
        if (passage.guide !in setOf("route", "stars", "shadow", "noon", "supplies")) return null
        packs.memoryCheck()
        val reply = complete(packs.model.path, prompt(
            "Answer the person's question in one or two plain English sentences using only the guide excerpt. Keep its limits. Never add measurements, medical advice, causes or facts from memory. If the excerpt cannot answer, reply UNKNOWN.",
            "Guide excerpt: ${clean(passage.body)}\nQuestion: ${clean(question)}"), 0).trim()
        if (reply.isEmpty() || reply.contains("UNKNOWN", true) || reply.last() !in ".!?" || reply.length > 600) return null
        val numbers = Regex("""\d+(?:\.\d+)?""")
        val supported = numbers.findAll(passage.body).map { it.value }.toSet()
        return reply.takeIf { numbers.findAll(reply).all { it.value in supported } }
    }
}
