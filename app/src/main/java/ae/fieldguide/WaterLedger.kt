package ae.fieldguide

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date
import java.util.Locale

internal fun createWaterLedger(db: SQLiteDatabase) {
    db.execSQL("""CREATE TABLE water_ledger(
        id INTEGER PRIMARY KEY AUTOINCREMENT, kind TEXT NOT NULL, unit_ml INTEGER NOT NULL,
        containers INTEGER NOT NULL, balance_ml INTEGER NOT NULL CHECK(balance_ml >= 0), stamp INTEGER NOT NULL)""")
}

private data class WaterEntry(val id: Long, val kind: String, val unit: Long, val count: Long, val balance: Long, val stamp: Long)

private fun Store.recentWater() = readableDatabase.rawQuery(
    "SELECT id,kind,unit_ml,containers,balance_ml,stamp FROM water_ledger ORDER BY id DESC LIMIT 5", null
).use { c -> buildList {
    while (c.moveToNext()) add(WaterEntry(c.getLong(0), c.getString(1), c.getLong(2), c.getLong(3), c.getLong(4), c.getLong(5)))
} }

private fun Store.saveWater(kind: String, unit: Long, count: Long): Int? {
    val db = writableDatabase
    db.beginTransaction()
    try {
        val previous = db.rawQuery("SELECT balance_ml FROM water_ledger ORDER BY id DESC LIMIT 1", null)
            .use { if (it.moveToFirst()) it.getLong(0) else 0L }
        val balance = when (kind) {
            "add" -> Math.addExact(previous, Math.multiplyExact(unit, count))
            "use" -> if (unit > previous) return R.string.water_over else previous - unit
            "correct" -> unit
            else -> error("Unknown water entry type")
        }
        db.insertOrThrow("water_ledger", null, ContentValues().apply {
            put("kind", kind)
            put("unit_ml", unit)
            put("containers", count)
            put("balance_ml", balance)
            put("stamp", System.currentTimeMillis())
        })
        db.setTransactionSuccessful()
        return null
    } catch (e: ArithmeticException) {
        return R.string.water_invalid
    } finally { db.endTransaction() }
}

private fun digits(input: String) = input.trim().map { c ->
    c.digitToIntOrNull()?.digitToChar() ?: if (c == '٫') '.' else c
}.joinToString("")

private fun millilitres(input: String): Long? {
    val text = digits(input)
    if (!Regex("""(?:[0-9]+(?:\.[0-9]{0,3})?|\.[0-9]{1,3})""").matches(text)) return null
    return try { BigDecimal(text).movePointRight(3).longValueExact() } catch (e: ArithmeticException) { null }
}

@Composable
internal fun WaterGuide(lang: String, open: (String) -> Unit) {
    val ctx = LocalContext.current
    Title(stringResource(R.string.water_safe))
    Text(stringResource(R.string.water_safe_body))
    Entry(stringResource(R.string.water_ledger), icon = R.drawable.field_water) { open("Kit/water-ledger") }
    Title(stringResource(R.string.water_storage))
    Text(stringResource(R.string.water_storage_body))
    Image(androidx.compose.ui.res.painterResource(R.drawable.water_containers), contentDescription = null)
    Hint(stringResource(R.string.water_example))
    Title(stringResource(R.string.water_refill))
    Text(stringResource(R.string.water_refill_body))
    Entry(stringResource(R.string.water_map), icon = R.drawable.field_pin) { open("Places/DRINKING") }
    Title(stringResource(R.string.water_return))
    Text(stringResource(R.string.water_return_body))
    val card = "water-card-$lang.pdf"
    var failed by remember { mutableStateOf(false) }
    Entry(stringResource(R.string.water_print)) { failed = runCatching { printFieldCard(ctx, card) }.isFailure }
    Entry(stringResource(R.string.water_share)) { failed = runCatching { shareFieldCard(ctx, card) }.isFailure }
    if (failed) Text(stringResource(R.string.kit_card_error))
    Entry(stringResource(R.string.kit_sources)) { open("Kit/water-sources") }
}

@Composable
internal fun WaterLedgerPage(store: Store, locale: Locale, open: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var rows by remember { mutableStateOf<List<WaterEntry>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(store) {
        try { rows = withContext(Dispatchers.IO) { store.recentWater() } }
        catch (e: android.database.SQLException) { failed = true }
    }
    if (failed) Text(stringResource(R.string.water_error))
    val entries = rows ?: run { if (!failed) Text(stringResource(R.string.water_loading)); return }
    val numbers = remember(locale) { NumberFormat.getNumberInstance(locale) }
    fun litres(ml: Long) = ctx.getString(R.string.water_litres, numbers.format(BigDecimal.valueOf(ml, 3)))
    Section(stringResource(R.string.water_balance))
    Title(litres(entries.firstOrNull()?.balance ?: 0L))
    if (entries.isEmpty()) Hint(stringResource(R.string.water_empty))
    Button(onClick = { open("Kit/water-add") }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.water_add)) }
    OutlinedButton(onClick = { open("Kit/water-use") }, enabled = !busy && (entries.firstOrNull()?.balance ?: 0) > 0,
        modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.water_use)) }
    TextButton(onClick = { open("Kit/water-correct") }, enabled = !busy) { Text(stringResource(R.string.water_correct)) }
    if (entries.isNotEmpty()) {
        Section(stringResource(R.string.water_recent))
        val date = remember(locale) { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, locale) }
        for ((index, row) in entries.withIndex()) {
            Text(when (row.kind) {
                "add" -> ctx.getString(R.string.water_added, numbers.format(row.count), litres(row.unit))
                "use" -> ctx.getString(R.string.water_used, litres(row.unit))
                else -> ctx.getString(R.string.water_set, litres(row.unit))
            })
            Hint(date.format(Date(row.stamp)))
            if (index == 0) TextButton(enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    try {
                        rows = withContext(Dispatchers.IO) {
                            store.writableDatabase.delete("water_ledger",
                                "id=? AND id=(SELECT MAX(id) FROM water_ledger)", arrayOf(row.id.toString()))
                            store.recentWater()
                        }
                        failed = false
                    } catch (e: android.database.SQLException) { failed = true }
                    finally { busy = false }
                }
            }) { Text(stringResource(R.string.water_undo)) }
        }
    }
}

@Composable
internal fun WaterEditPage(store: Store, kind: String, back: () -> Unit) {
    var amount by rememberSaveable { mutableStateOf("") }
    var count by rememberSaveable { mutableStateOf("1") }
    var problem by rememberSaveable { mutableStateOf<Int?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val label = when (kind) { "add" -> R.string.water_size; "use" -> R.string.water_amount; else -> R.string.water_measured }
    OutlinedTextField(amount, { amount = it; problem = null }, Modifier.fillMaxWidth(),
        label = { Text(stringResource(label)) }, singleLine = true, enabled = !busy,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
    if (kind == "add") OutlinedTextField(count, { count = it; problem = null }, Modifier.fillMaxWidth(),
        label = { Text(stringResource(R.string.water_count)) }, singleLine = true, enabled = !busy,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
    problem?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
    Button(enabled = !busy, onClick = {
        val unit = millilitres(amount)
        val n = if (kind == "add") digits(count).toLongOrNull() else 1L
        when {
            unit == null || (unit == 0L && kind != "correct") -> problem = R.string.water_invalid
            n == null || n <= 0 -> problem = R.string.water_bad_count
            else -> {
                busy = true
                scope.launch {
                    try {
                        problem = withContext(Dispatchers.IO) { store.saveWater(kind, unit, n) }
                        if (problem == null) back()
                    } catch (e: android.database.SQLException) { problem = R.string.water_error }
                    finally { busy = false }
                }
            }
        }
    }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.water_save)) }
}
