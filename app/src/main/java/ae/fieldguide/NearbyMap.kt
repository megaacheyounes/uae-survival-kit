package ae.fieldguide

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import kotlin.math.roundToInt
import org.mapsforge.core.model.LatLong
import org.mapsforge.core.model.Point
import org.mapsforge.map.android.graphics.AndroidGraphicFactory
import org.mapsforge.map.android.view.MapView
import org.mapsforge.map.layer.overlay.Marker

internal fun numberedMarker(
    map: MapView,
    rank: Int,
    place: Place,
    color: Int,
    select: () -> Unit,
): Marker {
    val size = (28 * map.resources.displayMetrics.density).roundToInt()
    val centre = size / 2f
    val bitmap = AndroidGraphicFactory.INSTANCE.createBitmap(size, size)
    val canvas = Canvas(AndroidGraphicFactory.getBitmap(bitmap))
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    paint.color = android.graphics.Color.WHITE
    canvas.drawCircle(centre, centre, centre, paint)
    paint.color = color
    canvas.drawCircle(centre, centre, centre * .86f, paint)
    paint.color = android.graphics.Color.WHITE
    paint.typeface = Typeface.DEFAULT_BOLD
    paint.textAlign = Paint.Align.CENTER
    paint.textSize = size * .47f
    canvas.drawText(rank.toString(), centre, centre - (paint.ascent() + paint.descent()) / 2, paint)
    return object : Marker(LatLong(place.lat, place.lon), bitmap, 0, 0) {
        override fun onTap(tapLatLong: LatLong, layerXY: Point, tapXY: Point): Boolean {
            if (!contains(layerXY, tapXY, map)) return false
            select()
            return true
        }
    }
}
