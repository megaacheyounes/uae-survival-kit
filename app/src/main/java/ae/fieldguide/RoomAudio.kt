package ae.fieldguide

import android.media.*
import android.util.Base64
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.io.ByteArrayOutputStream

internal const val noteBytes = 16000 * 12 // 8 kHz, mono, 16-bit PCM

internal class RoomAudio(private val scope: CoroutineScope,private val context:android.content.Context) {
    private val rate=8000
    private var capture: Job?=null
    private var playback: Job?=null
    private var queue: Channel<ByteArray>?=null
    private var collected: ByteArrayOutputStream?=null

    fun record(note: Boolean, frame: (String)->Unit, finished: ()->Unit, failed: ()->Unit) {
        stopCapture()
        val buffer=if (note) ByteArrayOutputStream() else null
        collected=buffer
        capture=scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val min=AudioRecord.getMinBufferSize(rate,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT)
                    if(context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)!=android.content.pm.PackageManager.PERMISSION_GRANTED)
                        throw SecurityException("Microphone permission required")
                    val mic=AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION,rate,AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,maxOf(min,3200))
                    try {
                        mic.startRecording()
                        check(mic.recordingState==AudioRecord.RECORDSTATE_RECORDING)
                        val bytes=ByteArray(800)
                        var count=0
                        while (isActive && count < if(note) noteBytes else 16000*30) {
                            val n=mic.read(bytes,0,bytes.size)
                            check(n>0)
                            if (buffer!=null) synchronized(buffer) { buffer.write(bytes,0,n) }
                            else withContext(Dispatchers.Main) { frame(Base64.encodeToString(bytes,0,n,Base64.NO_WRAP)) }
                            count+=n
                        }
                    } finally { runCatching { mic.stop() }; mic.release() }
                }
                finished()
            } catch (e: CancellationException) { throw e } catch (_: Exception) { failed() }
        }
    }
    fun stopCapture(): String? {
        capture?.cancel(); capture=null
        val b=collected; collected=null
        return b?.let { synchronized(it) { if(it.size()>0) Base64.encodeToString(it.toByteArray(),Base64.NO_WRAP) else null } }
    }
    fun stopPlayback() { queue?.close(); queue=null; playback?.cancel(); playback=null }
    fun finishStream(){queue?.close();queue=null}
    fun play(data: String, note: Boolean) {
        if (note) stopPlayback()
        val bytes=runCatching { Base64.decode(data,Base64.NO_WRAP) }.getOrNull() ?: return
        if (bytes.isEmpty() || bytes.size>if(note) noteBytes else 2048) return
        val ch=queue ?: Channel<ByteArray>(12).also { channel ->
            queue=channel
            playback=scope.launch(Dispatchers.IO) {
                runCatching {
                    val track=AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
                        .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                        .setBufferSizeInBytes(maxOf(3200,AudioTrack.getMinBufferSize(rate,AudioFormat.CHANNEL_OUT_MONO,AudioFormat.ENCODING_PCM_16BIT))).build()
                    try {
                        track.play()
                        for (chunk in channel) {
                            ensureActive()
                            var offset=0
                            while(offset<chunk.size){
                                ensureActive()
                                val n=track.write(chunk,offset,minOf(800,chunk.size-offset))
                                check(n>0);offset+=n
                            }
                        }
                        if(note) delay(250)
                    } finally { track.release() }
                }
            }
        }
        ch.trySend(bytes)
        if(note) { ch.close(); queue=null }
    }
}
