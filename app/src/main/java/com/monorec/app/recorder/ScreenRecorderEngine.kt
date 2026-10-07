package com.monorec.app.recorder

import android.content.ContentValues
import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.MediaStore
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.Surface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Inti perekam layar.
 *
 *  - Video : layar -> VirtualDisplay -> Surface -> MediaCodec (H.264)
 *  - Audio : AudioPlaybackCapture (suara HP) [+ mikrofon] -> dicampur -> MediaCodec (AAC)
 *  - Keluar: MediaMuxer -> file MP4 di Movies/MonoRec lewat MediaStore
 */
class ScreenRecorderEngine(
    private val context: Context,
    private val projection: MediaProjection,
    private val config: RecorderConfig
) {
    var width = 0
        private set
    var height = 0
        private set
    private var densityDpi = 320

    val fps: Int get() = config.quality.fps
    val videoBitrate: Int get() = config.quality.videoBitrate
    val bytesWritten: Long get() = muxer?.bytes?.get() ?: 0L

    /** Dipanggil bila perekaman dihentikan dari luar (mis. lewat chip sistem Android). */
    var onProjectionStopped: (() -> Unit)? = null

    @Volatile
    private var running = false
    @Volatile
    private var stopRequestedAt = 0L

    private var baseUs = 0L
    private var videoEncoder: MediaCodec? = null
    private var audioEncoder: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var deviceRecord: AudioRecord? = null
    private var micRecord: AudioRecord? = null
    private var muxer: SafeMuxer? = null
    private var pfd: ParcelFileDescriptor? = null
    private var outputUri: Uri? = null
    private var projectionCallback: MediaProjection.Callback? = null

    private val threads = ArrayList<Thread>()

    // ------------------------------------------------------------------ start

    fun start() {
        baseUs = System.nanoTime() / 1000
        computeSize()
        val hasAudio = config.audioMode != AudioMode.NONE

        val name = "MonoRec_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "$name.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/MonoRec")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Tidak bisa membuat file keluaran")
        outputUri = uri

        try {
            val p = context.contentResolver.openFileDescriptor(uri, "rw")
                ?: throw IllegalStateException("Tidak bisa membuka file keluaran")
            pfd = p
            muxer = SafeMuxer(p.fileDescriptor, if (hasAudio) 2 else 1)

            setupVideo()
            if (hasAudio) setupAudio()

            running = true
            threads += Thread({ drainLoop(videoEncoder!!) }, "monorec-video-out")
            if (hasAudio) {
                threads += Thread({ audioInputLoop() }, "monorec-audio-in")
                threads += Thread({ drainLoop(audioEncoder!!) }, "monorec-audio-out")
            }
            threads.forEach { it.start() }
        } catch (t: Throwable) {
            running = false
            releaseAll()
            discardOutput()
            throw t
        }
    }

    private fun computeSize() {
        val dm = context.getSystemService(DisplayManager::class.java)
        val display = dm.getDisplay(Display.DEFAULT_DISPLAY)
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        display.getRealMetrics(metrics)

        val sw = metrics.widthPixels
        val sh = metrics.heightPixels
        densityDpi = metrics.densityDpi

        val shortSide = min(sw, sh)
        val target = min(config.quality.shortSide, shortSide) // jangan di-upscale
        val scale = target.toFloat() / shortSide
        width = align16(sw * scale)
        height = align16(sh * scale)
    }

    private fun align16(v: Float): Int = max(16, (v / 16f).roundToInt() * 16)

    private fun setupVideo() {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, videoBitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            // Layar diam tetap menghasilkan frame supaya durasi video tidak terpotong.
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 200_000L)
        }
        val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        enc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = enc.createInputSurface()
        enc.start()
        videoEncoder = enc

        // Android 14 mewajibkan callback terdaftar sebelum membuat VirtualDisplay.
        val cb = object : MediaProjection.Callback() {
            override fun onStop() {
                onProjectionStopped?.invoke()
            }
        }
        projectionCallback = cb
        projection.registerCallback(cb, Handler(Looper.getMainLooper()))

        virtualDisplay = projection.createVirtualDisplay(
            "MonoRec",
            width, height, densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            inputSurface, null, null
        )
    }

    private fun setupAudio() {
        val pcmFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()

        val captureCfg = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        val minDev = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT
        )
        deviceRecord = AudioRecord.Builder()
            .setAudioFormat(pcmFormat)
            .setBufferSizeInBytes(max(minDev, CHUNK_FRAMES * 4 * 4))
            .setAudioPlaybackCaptureConfig(captureCfg)
            .build()
        check(deviceRecord!!.state == AudioRecord.STATE_INITIALIZED) {
            "Perekam audio internal gagal dibuat"
        }

        if (config.audioMode == AudioMode.DEVICE_MIC) {
            val minMic = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            micRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                max(minMic, CHUNK_FRAMES * 2 * 4)
            )
            check(micRecord!!.state == AudioRecord.STATE_INITIALIZED) {
                "Mikrofon gagal dibuka"
            }
        }

        val aacFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 2).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BITRATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
        }
        val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        enc.configure(aacFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        enc.start()
        audioEncoder = enc
    }

    // ---------------------------------------------------------------- threads

    /** Membaca audio HP (+ mic), mencampur, lalu menyuapkan ke encoder AAC. */
    private fun audioInputLoop() {
        val enc = audioEncoder ?: return
        val dev = deviceRecord ?: return
        val mic = micRecord

        val devBuf = ShortArray(CHUNK_FRAMES * 2)
        val micBuf = ShortArray(CHUNK_FRAMES)
        val out = ByteArray(CHUNK_FRAMES * 4)

        var startUs = -1L
        var frames = 0L

        try {
            dev.startRecording()
            mic?.startRecording()

            while (running) {
                if (!readFully(dev, devBuf)) break
                if (mic != null && !readFully(mic, micBuf)) micBuf.fill(0)
                if (startUs < 0) startUs = System.nanoTime() / 1000

                for (f in 0 until CHUNK_FRAMES) {
                    var l = devBuf[2 * f].toInt()
                    var r = devBuf[2 * f + 1].toInt()
                    if (mic != null) {
                        val m = micBuf[f].toInt()
                        l += m
                        r += m
                    }
                    l = l.coerceIn(-32768, 32767)
                    r = r.coerceIn(-32768, 32767)
                    out[4 * f] = (l and 0xFF).toByte()
                    out[4 * f + 1] = ((l shr 8) and 0xFF).toByte()
                    out[4 * f + 2] = (r and 0xFF).toByte()
                    out[4 * f + 3] = ((r shr 8) and 0xFF).toByte()
                }

                val pts = startUs + frames * 1_000_000L / SAMPLE_RATE
                if (!queueAudio(enc, out, out.size, pts, 0)) break
                frames += CHUNK_FRAMES
            }
        } catch (e: Exception) {
            Log.e(TAG, "Loop audio berhenti: ${e.message}")
        } finally {
            val base = if (startUs < 0) System.nanoTime() / 1000 else startUs
            queueAudio(enc, ByteArray(0), 0, base + frames * 1_000_000L / SAMPLE_RATE, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            try { dev.stop() } catch (_: Exception) {}
            try { mic?.stop() } catch (_: Exception) {}
        }
    }

    private fun readFully(rec: AudioRecord, buf: ShortArray): Boolean {
        var off = 0
        while (off < buf.size) {
            val n = rec.read(buf, off, buf.size - off)
            if (n <= 0) return false
            off += n
        }
        return true
    }

    private fun queueAudio(enc: MediaCodec, data: ByteArray, size: Int, ptsUs: Long, flags: Int): Boolean {
        val isEos = flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
        var tries = 0
        while (tries < (if (isEos) 100 else 20)) {
            val idx = enc.dequeueInputBuffer(10_000)
            if (idx >= 0) {
                val ib = enc.getInputBuffer(idx) ?: return false
                ib.clear()
                if (size > 0) ib.put(data, 0, size)
                enc.queueInputBuffer(idx, 0, size, ptsUs, flags)
                return true
            }
            tries++
            if (!running && !isEos) return false
        }
        return false
    }

    /** Mengambil hasil encoder (video atau audio) dan menulisnya ke muxer. */
    private fun drainLoop(enc: MediaCodec) {
        val m = muxer ?: return
        val info = MediaCodec.BufferInfo()
        var track = -1
        try {
            while (true) {
                val idx = enc.dequeueOutputBuffer(info, 10_000)
                when {
                    idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        track = m.addTrack(enc.outputFormat)
                    }
                    idx >= 0 -> {
                        val buf = enc.getOutputBuffer(idx)
                        val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        if (buf != null && info.size > 0 && !isConfig && track >= 0) {
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            info.presentationTimeUs = max(0L, info.presentationTimeUs - baseUs)
                            m.write(track, buf, info)
                        }
                        enc.releaseOutputBuffer(idx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                    else -> {
                        // INFO_TRY_AGAIN_LATER: keluar bila sudah lama diminta berhenti.
                        if (!running && SystemClock.elapsedRealtime() - stopRequestedAt > 3_000) break
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Drain encoder berhenti: ${e.message}")
        }
    }

    // ------------------------------------------------------------------- stop

    /** Menghentikan perekaman dan memfinalisasi file. Mengembalikan Uri bila berhasil. Panggil dari thread latar. */
    fun stop(): Uri? {
        if (!running) return null
        onProjectionStopped = null
        running = false
        stopRequestedAt = SystemClock.elapsedRealtime()

        try { videoEncoder?.signalEndOfInputStream() } catch (_: Exception) {}
        threads.forEach { try { it.join(5_000) } catch (_: InterruptedException) {} }

        val ok = muxer?.wasStarted == true
        releaseAll()

        val uri = outputUri
        return if (ok && uri != null) {
            val cv = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
            try { context.contentResolver.update(uri, cv, null, null) } catch (_: Exception) {}
            uri
        } else {
            discardOutput()
            null
        }
    }

    private fun releaseAll() {
        muxer?.release()
        try { virtualDisplay?.release() } catch (_: Exception) {}
        try { inputSurface?.release() } catch (_: Exception) {}
        listOf(videoEncoder, audioEncoder).forEach {
            try { it?.stop() } catch (_: Exception) {}
            try { it?.release() } catch (_: Exception) {}
        }
        try { deviceRecord?.release() } catch (_: Exception) {}
        try { micRecord?.release() } catch (_: Exception) {}
        projectionCallback?.let { try { projection.unregisterCallback(it) } catch (_: Exception) {} }
        try { projection.stop() } catch (_: Exception) {}
        try { pfd?.close() } catch (_: Exception) {}
    }

    private fun discardOutput() {
        outputUri?.let {
            try { context.contentResolver.delete(it, null, null) } catch (_: Exception) {}
        }
        outputUri = null
    }

    private companion object {
        const val TAG = "MonoRec"
        const val SAMPLE_RATE = 44_100
        const val CHUNK_FRAMES = 1024
        const val AUDIO_BITRATE = 128_000
    }
}
