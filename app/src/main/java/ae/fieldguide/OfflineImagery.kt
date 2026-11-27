package ae.fieldguide

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipFile

internal data class AerialSurvey(
    val name: String, val date: String,
    val south: Double, val west: Double, val north: Double, val east: Double
) {
    val latitude get() = (south + north) / 2
    val longitude get() = (west + east) / 2
    fun contains(lat: Double, lon: Double) = lat in south..north && lon in west..east
}

internal val aerialSurveys = listOf(
    AerialSurvey("Al Wagan", "24 Jan 2021", 23.620468, 55.345661, 23.629449, 55.358638),
    AerialSurvey("Cedre Villas Strip", "13 Jan 2021", 25.128537, 55.392749, 25.133507, 55.396998),
    AerialSurvey("Al Ghail", "6 Mar 2021", 25.376227, 56.060071, 25.380130, 56.067524)
)

internal class OfflineImagery(context: Context) {
    private val root = File(context.filesDir, "satellite-packs")
    private val prefs = context.getSharedPreferences("offline-imagery", Context.MODE_PRIVATE)
    val active: File?
        get() = prefs.getString("active", null)?.let { File(root, it) }?.takeIf { File(it, "manifest.json").isFile }

    fun cleanStaging() {
        val keep = active
        root.listFiles()?.filter { it != keep }?.forEach { it.deleteRecursively() }
    }

    // Called only after the downloaded archive has matched the pinned SHA-256.
    fun install(archive: File, sha: String) {
        check(root.isDirectory || root.mkdirs())
        val destination = File(root, sha)
        if (!destination.isDirectory) {
            val stage = Files.createTempDirectory(root.toPath(), "install-").toFile()
            try {
                ZipFile(archive).use { zip ->
                    val manifest = zip.getInputStream(requireNotNull(zip.getEntry("manifest.json"))).bufferedReader().use { JSONObject(it.readText()) }
                    val expected = manifest.getInt("tileCount")
                    require(expected > 0 && zip.size() == expected + 2)
                    require(root.usableSpace > archive.length() + expected * 4096L) { "Free more storage to install offline imagery, then retry." }
                    val pattern = Regex("""1[0-9]/[0-9]+/[0-9]+\.webp""")
                    for (entry in zip.entries()) {
                        require(entry.name == "manifest.json" || entry.name == "NOTICE.txt" || entry.name.matches(pattern))
                        val target = File(stage, entry.name)
                        target.parentFile!!.mkdirs()
                        zip.getInputStream(entry).use { input ->
                            target.outputStream().buffered().use { output -> check(input.copyTo(output) == entry.size) }
                        }
                    }
                }
                Files.move(stage.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } finally {
                if (stage.exists()) stage.deleteRecursively()
            }
        }
        check(prefs.edit().putString("active", sha).commit()) { "Could not activate the downloaded imagery." }
    }
}
