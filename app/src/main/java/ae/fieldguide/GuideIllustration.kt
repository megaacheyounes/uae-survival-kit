package ae.fieldguide

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.RectF
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.caverock.androidsvg.SVG
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.math.roundToInt

private fun loadIllustration(assets: AssetManager, name: String, isPhoto: Boolean): ImageBitmap {
    if (isPhoto) return assets.open("manual/$name").use { requireNotNull(BitmapFactory.decodeStream(it)).asImageBitmap() }
    val diagram = assets.open("manual/$name.svg").use { SVG.getFromInputStream(it) }
    val bounds = requireNotNull(diagram.documentViewBox)
    val width = 1440
    val height = (width * bounds.height() / bounds.width()).roundToInt()
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    diagram.renderToCanvas(Canvas(bitmap), RectF(0f, 0f, width.toFloat(), height.toFloat()))
    return bitmap.asImageBitmap()
}

@Composable
internal fun GuideIllustration(asset: String, heading: String, photo: JSONObject?) {
    val assets = LocalContext.current.assets
    val result by produceState<Result<ImageBitmap>?>(null, asset) {
        value = withContext(Dispatchers.IO) { runCatching { loadIllustration(assets, asset, photo != null) } }
    }
    val description = photo?.getString("taxon") ?: heading
    var expanded by rememberSaveable(asset) { mutableStateOf(false) }
    val image = result?.getOrNull()
    Box(Modifier.fillMaxWidth().aspectRatio(if (photo == null) 1.85f else 1.2f)
        .clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.surfaceVariant)
        .clickable(enabled = image != null, role = Role.Button, onClickLabel = "Enlarge illustration") { expanded = true },
        contentAlignment = Alignment.Center) {
        if (image != null) {
            Image(image, description, Modifier.fillMaxSize())
            Surface(Modifier.align(Alignment.TopEnd).padding(4.dp), shape = MaterialTheme.shapes.extraSmall,
                color = Shell, contentColor = Signal) {
                FieldIcon(R.drawable.map_zoom_in, "Enlarge illustration", Modifier.padding(4.dp).size(18.dp))
            }
        } else if (result?.isFailure == true) Hint("Illustration unavailable")
    }
    if (photo != null) Hint(description + " · " + photo.getString("place"))
    if (expanded && image != null) IllustrationViewer(image, description) { expanded = false }
}

@Composable
private fun IllustrationViewer(image: ImageBitmap, title: String, close: () -> Unit) {
    var scale by rememberSaveable { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val transform = rememberTransformableState { zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, 4f)
        val fit = minOf(size.width.toFloat() / image.width, size.height.toFloat() / image.height)
        val limitX = ((image.width * fit * scale - size.width) / 2).coerceAtLeast(0f)
        val limitY = ((image.height * fit * scale - size.height) / 2).coerceAtLeast(0f)
        offset = Offset((offset.x + pan.x).coerceIn(-limitX, limitX), (offset.y + pan.y).coerceIn(-limitY, limitY))
    }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Shell).safeDrawingPadding()) {
            ReaderBar(title, close) {
                TextButton(onClick = { scale = if (scale > 1f) 1f else 2f; offset = Offset.Zero }) {
                    Text(if (scale > 1f) "Fit" else "Zoom in")
                }
            }
            Box(Modifier.fillMaxSize().clipToBounds().onSizeChanged { size = it }.transformable(transform)) {
                Image(image, title, Modifier.fillMaxSize().graphicsLayer {
                    scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y
                })
            }
        }
    }
}
