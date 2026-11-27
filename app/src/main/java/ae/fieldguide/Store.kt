package ae.fieldguide

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

private fun JSONArray.strings() = (0 until length()).map { getString(it) }

data class Source(val name: String, val url: String)

data class Guide(
    val id: String,
    val title: String,
    val tags: String,
    val steps: List<String>,
    val source: String,
    val stepIds: Map<String, Int> = emptyMap(),
)

data class Contact(val title: String, val number: String, val source: String, val area: String)

data class Emirate(val name: String, val lat: Double, val lon: Double, val guides: List<String>)

data class Item(
    val id: Long,
    val title: String,
    val topic: String,
    val kind: String,
    val file: File,
    val position: Int,
)

data class Passage(val guide: String, val line: Int, val body: String, val title: String)

class Fieldbook(context: Context) {
    private val data =
        JSONObject(context.assets.open("fieldbook.json").bufferedReader().use { it.readText() })
    val checked = data.getString("checked")
    private val incidents = data.getJSONObject("incidentSources")
    fun incidentSources(id: String) = incidents.optJSONArray(id)?.strings().orEmpty()
    fun guideChecked(id: String) = if (incidents.has(id)) incidents.getString("checked") else checked
    fun isIncident(id: String) = incidents.has(id)

    private fun <T> rows(key: String, map: (JSONArray) -> T): List<T> {
        val list = data.getJSONArray(key)
        return (0 until list.length()).map { map(list.getJSONArray(it)) }
    }

    val manual = Manual(context)
    val guides =
        rows("guides") {
            val steps = it.getJSONArray(3)
            val ids = mutableMapOf<String, Int>()
            val bodies = (0 until steps.length()).map { index ->
                val entry = steps.optJSONObject(index)
                if (entry == null) steps.getString(index) else {
                    ids[entry.getString("id")] = index
                    entry.getString("body")
                }
            }
            Guide(
                it.getString(0),
                it.getString(1),
                it.getString(2),
                bodies,
                it.getString(4),
                ids,
            )
        } + manual.guides
    internal val examples by lazy { passageExamples(context) }
    val lookup by lazy { LibraryLookup(guides, examples) }
    private val meaningIndex by lazy { SemanticIndex(context.assets.open("search/semantic.bin")) }
    private val byStep by lazy {
        guides.flatMap { g -> g.steps.mapIndexed { i, body -> "${g.id}/$i" to Passage(g.id, i, body, g.title) } }.toMap()
    }
    internal fun meaningMatches(question: String) = meaningIndex.search(question, 4).mapNotNull { hit ->
        byStep[hit.key]?.let { it to hit.score }
    }
    val contacts =
        rows("contacts") {
            Contact(it.getString(0), it.getString(1), it.getString(2), it.getString(3))
        }
    val emirates =
        rows("emirates") {
            Emirate(it.getString(0), it.getDouble(1), it.getDouble(2), it.getJSONArray(3).strings())
        }

    fun source(id: String) =
        data.getJSONObject("refs").getJSONArray(id).let { Source(it.getString(0), it.getString(1)) }
}

class Store(private val ctx: Context, private val book: Fieldbook) :
    SQLiteOpenHelper(ctx, "fieldguide.db", null, 6) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE items(id INTEGER PRIMARY KEY,title TEXT NOT NULL,topic TEXT NOT NULL,kind TEXT NOT NULL,path TEXT UNIQUE NOT NULL,position INTEGER NOT NULL DEFAULT 0)"
        )
        db.execSQL(
            "CREATE VIRTUAL TABLE passages USING fts4(guide,line,title,body,tags,tokenize=unicode61)"
        )
        indexGuides(db)
        createFieldNotes(db)
        createWaterLedger(db)
    }

    private fun indexGuides(db: SQLiteDatabase) {
        for (g in book.guides) g.steps.forEachIndexed { i, s ->
            db.execSQL(
                "INSERT INTO passages VALUES(?,?,?,?,?)",
                arrayOf<Any>(g.id, i, g.title, s, g.tags),
            )
        }
    }

    private fun createFieldNotes(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE field_notes(
            lat REAL NOT NULL, lon REAL NOT NULL, kind TEXT NOT NULL, title TEXT NOT NULL,
            detail TEXT NOT NULL, body TEXT NOT NULL, checked INTEGER NOT NULL,
            PRIMARY KEY(lat,lon,kind))""")
    }

    internal fun fieldNotes(): List<FieldNote> =
        readableDatabase.rawQuery("SELECT title,kind,lat,lon,detail,body,checked FROM field_notes ORDER BY checked DESC,title", null)
            .use { rows ->
                buildList {
                    while (rows.moveToNext()) add(FieldNote(
                        Place(rows.getString(0),"",rows.getString(1),rows.getDouble(2),rows.getDouble(3),detail=rows.getString(4)),
                        rows.getString(5),rows.getLong(6)))
                }
            }

    internal fun loadNote(p: Place): String? =
        readableDatabase.rawQuery("SELECT body FROM field_notes WHERE lat=? AND lon=? AND kind=?",
            arrayOf(p.lat.toString(),p.lon.toString(),p.kind)).use { if (it.moveToFirst()) it.getString(0) else null }

    internal fun saveNote(p: Place, body: String) {
        require(body.isNotBlank())
        writableDatabase.execSQL("INSERT OR REPLACE INTO field_notes(lat,lon,kind,title,detail,body,checked) VALUES(?,?,?,?,?,?,?)",
            arrayOf<Any>(p.lat, p.lon, p.kind, p.title, p.description, body.trim(), System.currentTimeMillis()))
    }

    internal fun removeNote(p: Place) {
        writableDatabase.delete("field_notes","lat=? AND lon=? AND kind=?",arrayOf(p.lat.toString(),p.lon.toString(),p.kind))
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 5) {
            db.execSQL("DELETE FROM passages")
            indexGuides(db)
        }
        if (oldVersion < 3) createFieldNotes(db)
        if (oldVersion < 6) createWaterLedger(db)
    }

    fun passages(query: String): List<Passage> {
        val words = terms(query)
        if (words.isEmpty()) return emptyList()
        val match = words.joinToString(" OR ") { it + "*" }
        return readableDatabase
            .rawQuery(
                "SELECT guide,line,body,title,tags FROM passages WHERE passages MATCH ?",
                arrayOf(match),
            )
            .use { c ->
                buildList<Pair<Passage, Int>> {
                        while (c.moveToNext()) {
                            val p =
                                Passage(c.getString(0), c.getInt(1), c.getString(2), c.getString(3))
                            val rank =
                                words.fold(0) { score, w ->
                                    score + (if (p.body.contains(w, true)) 5 else 0) +
                                        (if (p.title.contains(w, true)) 3 else 0) +
                                        (if (c.getString(4).contains(w, true)) 1 else 0)
                                }
                            add(p to rank)
                        }
                    }
                    .sortedByDescending { it.second }
                    .map { it.first }
            }
    }

    fun items(): List<Item> =
        readableDatabase
            .rawQuery(
                "SELECT id,title,topic,kind,path,position FROM items ORDER BY topic COLLATE NOCASE,title COLLATE NOCASE",
                null,
            )
            .use { c ->
                buildList {
                    while (c.moveToNext()) add(
                        Item(
                            c.getLong(0),
                            c.getString(1),
                            c.getString(2),
                            c.getString(3),
                            File(ctx.filesDir, c.getString(4)),
                            c.getInt(5),
                        )
                    )
                }
            }

    fun importItem(uri: Uri, topic: String) {
        val r = ctx.contentResolver
        val name =
            r.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            } ?: "Imported file"
        val mime = r.getType(uri).orEmpty()
        val kind =
            when {
                mime == "application/pdf" || name.endsWith(".pdf", true) -> "PDF"
                mime.startsWith("video/") ||
                    name.substringAfterLast('.').lowercase() in
                        setOf("mp4", "webm", "mkv", "mov", "m4v") -> "Video"
                else -> error("Choose a PDF or a video.")
            }
        val dir = File(ctx.filesDir, "library").apply { mkdirs() }
        val file = File(dir, UUID.randomUUID().toString() + if (kind == "PDF") ".pdf" else ".video")
        try {
            requireNotNull(r.openInputStream(uri)).use { input ->
                file.outputStream().use { input.copyTo(it) }
            }
            require(file.length() > 0) { "The selected file is empty." }
            if (kind == "PDF")
                android.os.ParcelFileDescriptor.open(
                        file,
                        android.os.ParcelFileDescriptor.MODE_READ_ONLY,
                    )
                    .use { fd ->
                        android.graphics.pdf.PdfRenderer(fd).use { require(it.pageCount > 0) }
                    }
            writableDatabase.insertOrThrow(
                "items",
                null,
                ContentValues().apply {
                    put("title", name)
                    put("topic", topic.trim().ifBlank { "General" })
                    put("kind", kind)
                    put("path", file.relativeTo(ctx.filesDir).path)
                },
            )
        } catch (e: Exception) {
            file.delete()
            throw e
        }
    }

    fun position(id: Long, n: Int) {
        writableDatabase.execSQL("UPDATE items SET position=? WHERE id=?", arrayOf<Any>(n, id))
    }

    fun topic(id: Long, s: String) {
        writableDatabase.execSQL(
            "UPDATE items SET topic=? WHERE id=?",
            arrayOf<Any>(s.trim().ifBlank { "General" }, id),
        )
    }
}

private val stopWords =
    "a an and are as at be by can do does for from how i if in is it me my of on or should that the to was what when where which who with you your number call phone about would could tell has have need during"
        .split(" ")
        .toSet()

fun terms(s: String) =
    Regex("[\\p{L}\\p{N}]+")
        .findAll(s.lowercase())
        .map { it.value }
        .filter { it.length > 1 && it !in stopWords }
        .distinct()
        .take(16)
        .toList()
