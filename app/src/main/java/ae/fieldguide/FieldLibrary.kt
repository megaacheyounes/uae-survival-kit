package ae.fieldguide

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp

private val chapters = mapOf(
    "Navigation" to R.drawable.field_compass, "Water" to R.drawable.field_water,
    "Fire & shelter" to R.drawable.poi_fire, "Grid failure" to R.drawable.field_power,
    "Fallout" to R.drawable.field_fallout, "Plant & wildlife atlas" to R.drawable.tab_library
)

private data class LibraryRow(val key: String, val title: String, val detail: String = "",
    val destination: String? = null, val icon: Int? = null)

private fun browseLibrary(kit: Kit, tab: String, query: String): List<LibraryRow> = buildList {
    val manual = kit.book.manual
    if (query.isNotBlank()) {
        kit.store.passages(query).distinctBy { it.guide }.forEach { p ->
            add(LibraryRow("hit/" + p.guide, p.title, p.body.substringBefore(". ").take(100),
                "Guide/${p.guide}/${p.line}", R.drawable.tab_library))
        }
        kit.store.items().filter { it.title.contains(query,true) || it.topic.contains(query,true) }.forEach {
            add(LibraryRow("file/" + it.id, it.title, it.kind, "Item/" + it.id, R.drawable.field_folder))
        }
        booklets.filterValues { it.contains(query,true) }.forEach { (id,title) ->
            add(LibraryRow("book/$id", title, "Offline PDF", "Booklet/$id", R.drawable.tab_library))
        }
        if (isEmpty()) add(LibraryRow("hint", "No matching guides or files. Try another word."))
        return@buildList
    }
    when (tab) {
        "Guides" -> {
            add(LibraryRow("skills", "Skills"))
            chapters.forEach { (name, icon) ->
                val count = manual.lessons.values.count { it.getString("chapter") == name }
                add(LibraryRow("chapter/$name", name, if (count == 1) "1 guide" else "$count guides", "Chapter/$name", icon))
            }
            add(LibraryRow("quick", "Emergency checklists"))
            kit.book.guides.filter { it.source != "manual" }.forEach {
                add(LibraryRow("quick/" + it.id, it.title, destination = "Guide/" + it.id, icon = R.drawable.field_check))
            }
            add(LibraryRow("practice", "Field practice & checklists", destination = "Practice", icon = R.drawable.field_check))
        }
        "Saved" -> {
            manual.prefs.getString("recent",null)?.let(manual.lessons::get)?.let {
                add(LibraryRow("resume", "Continue reading", it.getString("title"), "Guide/" + it.getString("id"), R.drawable.tab_library))
            }
            add(LibraryRow("bookmarks","Saved steps"))
            val saved = manual.lessons.filterKeys { manual.prefs.contains("mark/$it") }
            if (saved.isEmpty()) add(LibraryRow("hint", "In a guide, open Tools and choose Save step."))
            saved.forEach { (id, l) ->
                val steps = l.getJSONArray("steps")
                val index = manual.prefs.getInt("mark/$id",0).coerceIn(0,steps.length()-1)
                add(LibraryRow("mark/$id", l.getString("title"), steps.getJSONObject(index).getString("heading"),
                    "Guide/$id/$index", R.drawable.field_saved))
            }
        }
        "Files" -> {
            add(LibraryRow("shelf", "Personal files", "Import PDFs and videos", "Shelf", R.drawable.field_folder))
            add(LibraryRow("booklets", "Included booklets"))
            booklets.forEach { (id,title) ->
                add(LibraryRow("book/$id", title, "Offline PDF", "Booklet/$id", R.drawable.tab_library))
            }
        }
    }
}

@Composable
fun ManualLibrary(kit: Kit, open: (String) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var tab by rememberSaveable { mutableStateOf("Guides") }
    val focus = LocalFocusManager.current
    val rows = remember(tab,query,kit.revision) { browseLibrary(kit, tab, query.trim()) }
    val positions = rememberSaveableStateHolder()
    var tabHistory by rememberSaveable { mutableStateOf(emptyArray<String>()) }
    fun chooseTab(next: String) {
        if(next!=tab){ tabHistory=(tabHistory.filterNot { it==next }+tab).toTypedArray();tab=next }
        focus.clearFocus()
    }
    androidx.activity.compose.BackHandler(query.isNotEmpty() || tabHistory.isNotEmpty()) {
        if(query.isNotEmpty()) query="" else { tab=tabHistory.last();tabHistory=tabHistory.dropLast(1).toTypedArray() }
        focus.clearFocus()
    }
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        CommandBar("Field library", R.drawable.tab_library) {
            IconButton(onClick = { open("Settings") }) { FieldIcon(R.drawable.field_controls, "Settings and downloads") }
        }
        Column(Modifier.widthIn(max = 760.dp).fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(query, { query = it.take(160) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                leadingIcon = { FieldIcon(R.drawable.map_search) },
                placeholder = { Text("Search guides and files") }, shape = MaterialTheme.shapes.small,
                trailingIcon = if (query.isNotEmpty()) { { IconButton(onClick = { query = "" }) {
                    FieldIcon(R.drawable.map_clear, "Clear search")
                } } } else null,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }))
            if (query.isBlank()) FieldTabs(listOf("Guides", "Saved", "Files"), tab, ::chooseTab)
            else Row(verticalAlignment=Alignment.CenterVertically) {
                Text(if(rows.firstOrNull()?.key=="hint") "No results" else "${rows.size} results",Modifier.weight(1f),style=MaterialTheme.typography.titleSmall)
                TextButton({query="";focus.clearFocus()}){Text("Back to " + tab)}
            }
        }
        positions.SaveableStateProvider(if (query.isBlank()) tab else "Search") {
            val state = rememberSaveable(saver = listSaver<LazyListState, Any>(
                save = { listOf(rows.getOrNull(it.firstVisibleItemIndex)?.key.orEmpty(), it.firstVisibleItemScrollOffset) },
                restore = { saved -> LazyListState(rows.indexOfFirst { it.key == saved[0] }.coerceAtLeast(0), saved[1] as Int) }
            )) { LazyListState() }
            LazyColumn(Modifier.widthIn(max = 760.dp).fillMaxSize(), state = state,
                contentPadding = PaddingValues(start=16.dp,end=16.dp,top=3.dp,bottom=16.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp)) {
                items(rows.size, key = { rows[it].key }) { i ->
                    val row = rows[i]
                    when {
                        row.key == "hint" -> Hint(row.title)
                        row.destination == null -> Section(row.title)
                        else -> Entry(row.title, row.detail, row.icon) { focus.clearFocus(); open(row.destination) }
                    }
                }
            }
        }
    }
}
