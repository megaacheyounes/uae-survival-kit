package ae.fieldguide

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal val Shell = Color(0xff18251f)
internal val Signal = Color(0xffffce66)
private val night = darkColorScheme(
    primary = Color(0xffabd6ad), onPrimary = Color(0xff122c1c),
    primaryContainer = Color(0xff294633), onPrimaryContainer = Color(0xffd4eed5),
    secondary = Color(0xffb5cbb5), onSecondary = Color(0xff182b1c),
    secondaryContainer = Color(0xff314637), onSecondaryContainer = Color(0xffdeecdc),
    tertiary = Signal, onTertiary = Color(0xff382700),
    background = Color(0xff101612), onBackground = Color(0xffe9eee4),
    surface = Color(0xff19221c), onSurface = Color(0xffe9eee4),
    surfaceVariant = Color(0xff26322a), onSurfaceVariant = Color(0xffbbc8b9),
    surfaceContainer = Color(0xff202b23), surfaceContainerHigh = Color(0xff26332a),
    surfaceContainerHighest = Color(0xff303d34),
    outline = Color(0xff849183), outlineVariant = Color(0xff455448), surfaceTint = Color.Transparent,
)
internal val Pine: Color @Composable get() = MaterialTheme.colorScheme.primary

@Composable
internal fun FieldTheme(content: @Composable () -> Unit) {
    val base = Typography()
    MaterialTheme(
        colorScheme = night,
        typography = base.copy(
            headlineMedium = base.headlineMedium.copy(fontSize = 27.sp, lineHeight = 32.sp, fontWeight = FontWeight.ExtraBold),
            titleLarge = base.titleLarge.copy(fontSize = 22.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold),
            titleMedium = base.titleMedium.copy(fontSize = 17.sp, lineHeight = 21.sp, fontWeight = FontWeight.Bold),
            bodyLarge = base.bodyLarge.copy(fontSize = 17.sp, lineHeight = 25.sp),
            bodySmall = base.bodySmall.copy(fontSize = 13.sp, lineHeight = 18.sp),
        ),
        shapes = Shapes(
            extraSmall = RoundedCornerShape(4.dp), small = RoundedCornerShape(6.dp),
            medium = RoundedCornerShape(8.dp), large = RoundedCornerShape(12.dp),
            extraLarge = RoundedCornerShape(16.dp)
        ), content = content
    )
}

@Composable
internal fun FieldIcon(id: Int, label: String? = null, modifier: Modifier = Modifier.size(24.dp), tint: Color = LocalContentColor.current) {
    Icon(painterResource(id), label, modifier, tint)
}

@Composable
fun Title(s: String) {
    Text(s, Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineMedium)
}

@Composable
fun Section(s: String) {
    Row(Modifier.fillMaxWidth().padding(top = 5.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(s.uppercase(java.util.Locale.ENGLISH), Modifier.weight(1f, fill = false).semantics { heading() },
            style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold, letterSpacing = 1.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider(Modifier.weight(.35f), color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
fun Hint(s: String) = Text(s, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
fun Page(scrollState: ScrollState = rememberScrollState(), content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 760.dp).fillMaxWidth().verticalScroll(scrollState)
            .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

@Composable
fun Entry(title: String, detail: String = "", icon: Int? = null, open: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(onClick = open, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small,
        color = colors.surface, contentColor = colors.onSurface, border = BorderStroke(1.dp, colors.outlineVariant)) {
        Row(Modifier.heightIn(min = 54.dp).padding(horizontal = 12.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) FieldIcon(icon, tint = colors.primary)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyLarge)
                if (detail.isNotEmpty()) Hint(detail)
            }
            FieldIcon(R.drawable.field_next, modifier = Modifier.size(16.dp), tint = colors.onSurfaceVariant)
        }
    }
}

@Composable
internal fun FieldTabs(labels: List<String>, selected: String, choose: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).background(colors.surfaceVariant)
        .padding(3.dp).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        labels.forEach { label ->
            val active = selected == label
            Box(Modifier.weight(1f).clip(MaterialTheme.shapes.extraSmall)
                .background(if (active) colors.primary else Color.Transparent)
                .selectable(active, role = Role.Tab) { choose(label) }
                .heightIn(min = 48.dp).padding(horizontal = 3.dp, vertical = 6.dp), contentAlignment = Alignment.Center) {
                Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
                    color = if (active) colors.onPrimary else colors.onSurface)
            }
        }
    }
}

@Composable
internal fun CommandBar(title: String, mark: Int, actions: @Composable RowScope.() -> Unit = {}) {
    Surface(color = Shell, contentColor = Color.White) {
        Row(Modifier.fillMaxWidth().heightIn(min = 54.dp).padding(start = 14.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            FieldIcon(mark, modifier = Modifier.size(23.dp), tint = Signal)
            Text(title, Modifier.weight(1f).padding(horizontal = 10.dp, vertical = 8.dp).semantics { heading() },
                style = MaterialTheme.typography.titleLarge)
            actions()
        }
    }
}

@Composable
fun ReaderBar(title: String, back: () -> Unit, actions: @Composable () -> Unit = {}) {
    Surface(color = Shell, contentColor = Color.White) {
        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(end = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = back) { FieldIcon(R.drawable.back_arrow, "Back") }
            Text(title, Modifier.weight(1f).padding(vertical = 7.dp).semantics { heading() },
                style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            // Text buttons in the dark reader bar need their own foreground.
            val scheme = MaterialTheme.colorScheme
            MaterialTheme(colorScheme = scheme.copy(primary = Signal)) { actions() }
        }
    }
}

private val destinations = listOf("Home" to R.drawable.tab_home, "Map" to R.drawable.tab_map,
    "Library" to R.drawable.tab_library, "Ask" to R.drawable.tab_ask)

@Composable
internal fun FieldDock(page: String, select: (String) -> Unit) {
    Surface(color = Shell, contentColor = Color.White) {
        Row(Modifier.fillMaxWidth().navigationBarsPadding().selectableGroup()) {
            destinations.forEach { (label, icon) ->
                val active = label == page
                Column(Modifier.weight(1f).selectable(active, role = Role.Tab) { select(label) },
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.width(32.dp).height(3.dp).background(if (active) Signal else Color.Transparent))
                    Column(Modifier.heightIn(min = 51.dp).padding(vertical = 4.dp),
                        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                        val ink = if (active) Signal else Color(0xffd4dfd4)
                        FieldIcon(icon, modifier = Modifier.size(21.dp), tint = ink)
                        Text(label, color = ink, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
internal fun ReaderFooter(index: Int, total: Int, previous: () -> Unit, next: () -> Unit, finish: (() -> Unit)? = null, savedStep: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    Surface(color = colors.surface) {
        Column {
            if (total > 0) LinearProgressIndicator(progress = { (index + 1f) / total }, modifier = Modifier.fillMaxWidth(),
                color = colors.primary, trackColor = colors.surfaceVariant)
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = previous, enabled = index > 0) { Text("Previous") }
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    if (savedStep) FieldIcon(R.drawable.field_saved, "Saved step", Modifier.padding(end = 4.dp).size(16.dp), colors.primary)
                    Text(if (total > 0) "${index + 1} / $total" else "Loading…", fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.labelLarge)
                }
                val last = total > 0 && index == total - 1
                Button(onClick = { if (last && finish != null) finish() else next() },
                    enabled = total > 0 && (!last || finish != null), shape = MaterialTheme.shapes.small) {
                    Text(if (last && finish != null) "Done" else if (finish != null) "Next step" else "Next")
                }
            }
        }
    }
}
