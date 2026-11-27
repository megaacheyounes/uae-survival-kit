package ae.fieldguide

import android.content.Context
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.IOException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray

data class ReferenceHit(val title: String, val excerpt: String, val route: String, val pageMatch: Boolean = false)

private suspend fun cachedPages(ctx: Context, item: Item): File {
    val target = File(ctx.cacheDir, "search-v1-${item.file.name}.jsonl")
    if (target.exists()) return target
    val stage = File.createTempFile("pdf-search-", ".tmp", ctx.cacheDir)
    try {
        stage.bufferedWriter().use { writer ->
            ParcelFileDescriptor.open(item.file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                PdfRenderer(fd).use { renderer ->
                    for (i in 0 until renderer.pageCount) {
                        currentCoroutineContext().ensureActive()
                        if (Build.VERSION.SDK_INT >= 35)
                            renderer.openPage(i).use { page ->
                                val text = page.textContents.joinToString(" ") { it.text }
                                writer.appendLine(
                                    JSONArray().put(item.id).put(i).put(text).toString()
                                )
                            }
                    }
                }
            }
        }
        android.system.Os.rename(stage.path, target.path)
        return target
    } finally {
        stage.delete()
    }
}

private val numbers = Regex("\\b\\d+\\b")
private val referenceFiller = setOf("page", "pages", "booklet", "pdf", "document", "manual", "mentions", "mention", "listed", "says", "read", "reference")

private fun referenceWords(s: String) = searchWords(s) + numbers.findAll(s).map { it.value }

suspend fun referenceHits(ctx: Context, items: List<Item>, query: String): List<ReferenceHit> {
    val words = referenceWords(query) - referenceFiller
    if (words.isEmpty()) return emptyList()
    val hits = mutableListOf<Pair<Double, ReferenceHit>>()
    fun score(text: String): Double {
        val sample = referenceWords(text)
        val common = sample.intersect(words).size
        if (common < minOf(2, words.size) || common.toDouble() / words.size < .60) return 0.0
        return common.toDouble() / words.size + common.toDouble() / (sample.size + 1)
    }
    fun matchPage(row: JSONArray, title: String, route: String) {
        val page = row.getInt(1)
        val passages = row.getString(2).split(Regex("\\s+")).windowed(28, 18, partialWindows = true)
        val best =
            passages
                .asSequence()
                .map { it.joinToString(" ") }
                .map { score(it) to it }
                .maxByOrNull { it.first }
        if (best != null && best.first > 0)
            hits.add(
                best.first to ReferenceHit("$title · page ${page+1}", best.second, "$route/$page", pageMatch = true)
            )
    }
    ctx.assets.open("manual/booklet-pages.jsonl").bufferedReader().useLines { lines ->
        for (line in lines) {
            currentCoroutineContext().ensureActive()
            val row = JSONArray(line)
            val id = row.getString(0)
            matchPage(row, booklets.getValue(id), "Booklet/$id")
        }
    }
    for ((id, title) in booklets) {
        val relevance = score(title)
        if (relevance > 0 && hits.none { it.second.route.startsWith("Booklet/$id/") })
            hits.add(relevance to ReferenceHit(title, "Bundled PDF", "Booklet/$id"))
    }
    for (item in items) {
        currentCoroutineContext().ensureActive()
        val relevance = score(item.title + " " + item.topic)
        if (relevance > 0)
            hits.add(relevance to ReferenceHit(item.title, item.kind, "Item/${item.id}"))
        if (item.kind != "PDF" || Build.VERSION.SDK_INT < 35) continue
        try {
            cachedPages(ctx, item).bufferedReader().useLines { lines ->
                for (line in lines) {
                    currentCoroutineContext().ensureActive()
                    matchPage(JSONArray(line), item.title, "Item/${item.id}")
                }
            }
        } catch (_: IOException) {
            /* The file remains discoverable by title. */
        } catch (_: SecurityException) {
            /* Encrypted documents may restrict extraction. */
        } catch (_: IllegalArgumentException) {
            /* An unsupported PDF can still open in the reader. */
        }
    }
    return hits.sortedByDescending { it.first }.take(5).map { it.second }
}
