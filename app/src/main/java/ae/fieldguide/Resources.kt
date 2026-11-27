package ae.fieldguide

import android.net.Uri
import android.system.Os
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import java.io.File
import java.io.IOException
import java.net.URL
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection

enum class Resource(val label: String, val url: String, val bytes: Long, val sha: String) {
    MAP("UAE map · 50 MB",
        "https://github.com/megaacheyounes/uae-field-guide-map/releases/download/2026-11-18-directory/UAE-map.zip",
        50038705L, "ef43fd4b9f908f0c98d4c47703868a80e85bce5f0313a040b5537e5300447c38"),
    SATELLITE("UAE offline imagery · 114 MB",
        "https://github.com/megaacheyounes/uae-field-guide-map/releases/download/2026-11-18-uae-offline-imagery/UAE-satellite.zip",
        113375856L, "5542ceb32ac8dfd0a764e46516110ac7a275a9e56d2eaaa9a76fc4c12ebd668d"),
    MODEL("AI model · 1.12 GB",
        "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/91cad51170dc346986eccefdc2dd33a9da36ead9/qwen2.5-1.5b-instruct-q4_k_m.gguf?download=true",
        1117320736L, "6a1a2eb6d15622bf3c96857206351ba97e1af16c30d7a74ee38970e434e9407e");

    fun install(packs: Packs, paused: () -> Boolean, progress: (Int) -> Unit) {
        val part = File(packs.model.parentFile, "${sha}.part")
        if (part.length() > bytes) check(part.delete())
        val offset = part.length()
        if (offset < bytes) {
            val connection = URL(url).openConnection() as HttpsURLConnection
            try {
                connection.connectTimeout = 20000
                connection.readTimeout = 30000
                connection.setRequestProperty("Accept-Encoding", "identity")
                if (offset > 0) connection.setRequestProperty("Range", "bytes=$offset-")
                val append = when (val code = connection.responseCode) {
                    200 -> false
                    206 -> {
                        require(connection.getHeaderField("Content-Range") == "bytes $offset-${bytes - 1}/$bytes") {
                            "Unexpected download range. Retry the download."
                        }
                        true
                    }
                    else -> throw IOException("Download returned HTTP $code. Retry when online.")
                }
                var total = if (append) offset else 0L
                require(part.parentFile!!.usableSpace > bytes - offset + when (this) {
                    MAP -> 160_000_000L
                    SATELLITE -> bytes + 120_000_000L
                    MODEL -> 16_000_000L
                }) {
                    "Free more device storage and retry."
                }
                connection.inputStream.use { input ->
                    java.io.FileOutputStream(part, append).buffered().use { out ->
                        val buffer = ByteArray(128 * 1024)
                        var last = -1
                        while (true) {
                            if (paused()) throw IOException("Download paused. Tap Download to resume.")
                            val n = input.read(buffer)
                            if (n < 0) break
                            total += n
                            require(total <= bytes) { "Download exceeds expected size." }
                            out.write(buffer, 0, n)
                            val percent = (100 * total / bytes).toInt()
                            if (last != percent) {
                                progress(percent)
                                last = percent
                            }
                        }
                    }
                }
            } finally {
                connection.disconnect()
            }
        }
        require(part.length() == bytes) { "Download interrupted. Tap Download to resume." }
        val digest = MessageDigest.getInstance("SHA-256")
        part.inputStream().use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        if (digest.digest().joinToString("") { "%02x".format(it) } != sha) {
            part.delete()
            throw IOException("Download checksum failed. Retry the download.")
        }
        when (this) {
            MAP -> { packs.importMap(Uri.fromFile(part)); part.delete() }
            SATELLITE -> { packs.imagery.install(part, sha); part.delete() }
            MODEL -> { Os.rename(part.path, packs.model.path); packs.probe() }
        }
    }
}

@Composable
fun DownloadButton(kit: Kit, resource: Resource) {
    Button(enabled = kit.busy.isEmpty(), onClick = { kit.download(resource) }) {
        Text("Download " + resource.label)
    }
}
