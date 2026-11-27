package ae.fieldguide

import android.content.Context
import android.content.Intent
import androidx.compose.ui.res.stringResource
import android.os.BatteryManager
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.*
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import java.io.File
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date
import java.util.Locale

private val topics = mapOf(
    "inside" to (R.string.kit_inside to R.string.kit_inside_text),
    "stay" to (R.string.kit_stay to R.string.kit_stay_text),
    "outside" to (R.string.kit_outside to R.string.kit_outside_text),
    "family" to (R.string.kit_family to R.string.kit_family_text),
    "updates" to (R.string.kit_updates to R.string.kit_updates_text),
    "prepare" to (R.string.kit_prepare to R.string.kit_prepare_text),
    "low" to (R.string.kit_low to R.string.kit_low_text),
    "recharge" to (R.string.kit_recharge to R.string.kit_recharge_text)
)

@Composable
internal fun EmergencyKit(kit: Kit, route: String, back: () -> Unit, open: (String) -> Unit) {
    val ctx = LocalContext.current
    val prefs = kit.book.manual.prefs
    val lang = stringResource(R.string.kit_language_tag)
    val arabic = lang == "ar"
    val locale = Locale.forLanguageTag(lang)
    val res = ctx.resources
    val card = "shelter-card-$lang.pdf"
    val page = route.substringAfter('/')
    val title = when (page) {
        "shelter" -> R.string.kit_shelter
        "power" -> R.string.kit_power
        "log" -> R.string.kit_log
        "water" -> R.string.water_title
        "water-ledger" -> R.string.water_ledger
        "water-add" -> R.string.water_add
        "water-use" -> R.string.water_use
        "water-correct" -> R.string.water_correct
        "water-sources" -> R.string.kit_sources
        "sources" -> R.string.kit_sources
        else -> topics.getValue(page).first
    }
    fun link(page: String) = open("Kit/" + page)
    CompositionLocalProvider(LocalLayoutDirection provides if (arabic) LayoutDirection.Rtl else LayoutDirection.Ltr) {
        ProvideTextStyle(LocalTextStyle.current.copy(localeList = LocaleList(lang))) {
            Column {
                Surface(color = Shell, contentColor = androidx.compose.ui.graphics.Color.White) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        IconButton(onClick = back) { FieldIcon(R.drawable.back_arrow, res.getString(R.string.kit_back)) }
                        Text(res.getString(title), Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleMedium)
                    }
                }
                Page {
                    when (page) {
                        "water" -> WaterGuide(lang, open)
                        "water-ledger" -> WaterLedgerPage(kit.store, locale, open)
                        "water-add", "water-use", "water-correct" -> WaterEditPage(kit.store, page.removePrefix("water-"), back)
                        "water-sources" -> {
                            Text("CDC · Water, Sanitation, and Hygiene")
                            RefLink(res.getString(R.string.water_safe), "https://www.cdc.gov/water-emergency/about/index.html")
                            RefLink(res.getString(R.string.water_storage), "https://www.cdc.gov/water-emergency/about/how-to-create-and-store-an-emergency-water-supply.html")
                            RefLink(res.getString(R.string.water_return), "https://www.cdc.gov/water-emergency/about/drinking-water-advisories-an-overview.html")
                        }
                        "shelter" -> {
                            for (id in listOf("inside", "stay")) {
                                val (heading, body) = topics.getValue(id)
                                Title(res.getString(heading))
                                Text(res.getString(body))
                            }
                            Text(res.getString(R.string.kit_updates_text))
                            for (id in listOf("outside","family","updates")) Entry(res.getString(topics.getValue(id).first)) { link(id) }
                            Entry(res.getString(R.string.kit_power)) { link("power") }
                            var cardError by remember { mutableStateOf(false) }
                            Entry(res.getString(R.string.kit_print)) { cardError = runCatching { printFieldCard(ctx, card) }.isFailure }
                            Entry(res.getString(R.string.kit_card)) { cardError = runCatching { shareFieldCard(ctx, card) }.isFailure }
                            if (cardError) Text(res.getString(R.string.kit_card_error))
                            Entry(res.getString(R.string.kit_full)) { open("Guide/fallout/0") }
                            Entry(res.getString(R.string.kit_sources)) { link("sources") }
                        }
                        "power" -> {
                            for (id in listOf("low","prepare","recharge")) Entry(res.getString(topics.getValue(id).first)) { link(id) }
                            Entry(res.getString(R.string.kit_log)) { link("log") }
                            Entry(res.getString(R.string.kit_sources)) { link("sources") }
                        }
                        "log" -> {
                            var history by remember { mutableStateOf(prefs.getString("kit-charge-log","").orEmpty()) }
                            var unavailable by remember { mutableStateOf(false) }
                            Text(res.getString(R.string.kit_log_text))
                            Button(onClick = {
                                val pct = ctx.getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                                unavailable = pct !in 0..100
                                if (!unavailable) {
                                    history = (listOf("${System.currentTimeMillis()},$pct") + history.lines().filter { it.isNotEmpty() }).take(30).joinToString("\n")
                                    prefs.edit().putString("kit-charge-log", history).apply()
                                }
                            }) { Text(res.getString(R.string.kit_record)) }
                            if (unavailable) Text(res.getString(R.string.kit_unavailable))
                            val time = DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT,locale)
                            val percent = NumberFormat.getPercentInstance(locale)
                            history.lines().filter { it.isNotEmpty() }.forEach {
                                val (stamp, level) = it.split(',')
                                Text(time.format(Date(stamp.toLong())) + "   ·   " + percent.format(level.toInt()/100.0))
                            }
                        }
                        "sources" -> {
                            Text("CDC · Radiation Emergencies")
                            Text("cdc.gov/radiation-emergencies/response/stay-inside.html")
                            Text("cdc.gov/radiation-emergencies/infographic/decontamination.html")
                            Text("Google · Android Help")
                            Text("support.google.com/android/answer/7664692")
                        }
                        else -> {
                            Text(res.getString(topics.getValue(page).second), style = MaterialTheme.typography.bodyLarge)
                            if (page == "low") {
                                var fallback by remember { mutableStateOf(false) }
                                Button(onClick = {
                                    fallback = listOf(Intent.ACTION_POWER_USAGE_SUMMARY, Settings.ACTION_BATTERY_SAVER_SETTINGS, Settings.ACTION_SETTINGS).none {
                                        runCatching { ctx.startActivity(Intent(it)) }.isSuccess
                                    }
                                }) { Text(res.getString(R.string.kit_settings)) }
                                if (fallback) Text(res.getString(R.string.kit_fallback))
                            }
                            if (page in listOf("low","prepare","recharge")) Entry(res.getString(R.string.kit_log)) { link("log") }
                        }
                    }
                }
            }
        }
    }
}

internal fun shareFieldCard(ctx: Context, card: String) {
    val dir = File(ctx.cacheDir,"field-cards").apply { mkdirs() }
    val file = File(dir, card)
    ctx.assets.open(card).use { input -> file.outputStream().use { input.copyTo(it) } }
    val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".cards", file)
    val send = Intent(Intent.ACTION_SEND).setType("application/pdf").putExtra(Intent.EXTRA_STREAM,uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    ctx.startActivity(Intent.createChooser(send,null))
}

internal fun printFieldCard(ctx: Context, card: String) {
    ctx.getSystemService(PrintManager::class.java).print(card, object : PrintDocumentAdapter() {
        override fun onLayout(old: PrintAttributes?, new: PrintAttributes?, cancel: CancellationSignal, callback: LayoutResultCallback, extras: android.os.Bundle?) {
            if (cancel.isCanceled) callback.onLayoutCancelled() else callback.onLayoutFinished(
                PrintDocumentInfo.Builder(card).setPageCount(1).setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).build(),old != new)
        }
        override fun onWrite(pages: Array<out PageRange>, dest: ParcelFileDescriptor, cancel: CancellationSignal, callback: WriteResultCallback) {
            if (cancel.isCanceled) { callback.onWriteCancelled(); return }
            runCatching {
                ctx.assets.open(card).use { input -> java.io.FileOutputStream(dest.fileDescriptor).use { input.copyTo(it) } }
            }.onSuccess { callback.onWriteFinished(arrayOf(PageRange(0,0))) }.onFailure { callback.onWriteFailed(it.localizedMessage) }
        }
    }, PrintAttributes.Builder().setMediaSize(PrintAttributes.MediaSize.ISO_A4).build())
}
