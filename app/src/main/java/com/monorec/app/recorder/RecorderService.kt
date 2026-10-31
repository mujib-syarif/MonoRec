package com.monorec.app.recorder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.monorec.app.MainActivity
import com.monorec.app.R

class RecorderService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var engine: ScreenRecorderEngine? = null
    private var startedAt = 0L
    private var stopping = false

    private val ticker = object : Runnable {
        override fun run() {
            val e = engine ?: return
            RecorderState.state.value = RecorderState.state.value.copy(
                elapsedMs = SystemClock.elapsedRealtime() - startedAt,
                bytes = e.bytesWritten
            )
            handler.postDelayed(this, 500)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRecording(intent)
            ACTION_STOP -> stopRecording()
        }
        return START_NOT_STICKY
    }

    private fun startRecording(intent: Intent) {
        if (engine != null) return

        val quality = runCatching { QualityPreset.valueOf(intent.getStringExtra(EXTRA_QUALITY) ?: "") }
            .getOrDefault(QualityPreset.HD)
        val audio = runCatching { AudioMode.valueOf(intent.getStringExtra(EXTRA_AUDIO) ?: "") }
            .getOrDefault(AudioMode.NONE)
        val config = RecorderConfig(quality, audio)

        // Foreground service wajib aktif SEBELUM MediaProjection dibuat.
        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        if (audio != AudioMode.NONE && Build.VERSION.SDK_INT >= 30) {
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        startedAt = SystemClock.elapsedRealtime()
        try {
            ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(), type)
        } catch (e: Exception) {
            // Mis. SecurityException di Android 14 bila izin mikrofon dicabut.
            fail("Tidak bisa memulai layanan perekaman. Periksa izin mikrofon dan notifikasi.")
            return
        }

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        if (data == null) {
            fail("Izin perekaman layar tidak valid")
            return
        }

        try {
            val mpm = getSystemService(MediaProjectionManager::class.java)
            val projection: MediaProjection = mpm.getMediaProjection(resultCode, data)
            val e = ScreenRecorderEngine(applicationContext, projection, config)
            e.onProjectionStopped = { stopRecording() }
            e.start()
            engine = e

            RecorderState.state.value = RecorderState.state.value.copy(
                recording = true,
                elapsedMs = 0L,
                bytes = 0L,
                width = e.width,
                height = e.height,
                fps = e.fps,
                videoBitrate = e.videoBitrate,
                audioMode = audio,
                error = null
            )
            handler.post(ticker)
        } catch (t: Throwable) {
            fail(t.message ?: "Gagal memulai perekaman")
        }
    }

    private fun stopRecording() {
        val e = engine
        if (e == null || stopping) return
        stopping = true
        handler.removeCallbacks(ticker)

        Thread({
            val uri = runCatching { e.stop() }.getOrNull()
            handler.post {
                val cur = RecorderState.state.value
                RecorderState.state.value = cur.copy(
                    recording = false,
                    savedCount = if (uri != null) cur.savedCount + 1 else cur.savedCount,
                    error = if (uri == null) "Rekaman tidak tersimpan (terlalu singkat?)" else null
                )
                engine = null
                stopping = false
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }, "monorec-stop").start()
    }

    private fun fail(message: String) {
        RecorderState.state.value = RecorderState.state.value.copy(recording = false, error = message)
        engine = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Perekaman layar", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, RecorderService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_rec)
            .setContentTitle("MonoRec sedang merekam")
            .setContentText("Ketuk Berhenti untuk menyimpan rekaman")
            .setOngoing(true)
            .setUsesChronometer(true)
            .setWhen(System.currentTimeMillis())
            .setContentIntent(open)
            .addAction(0, "Berhenti", stop)
            .build()
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        super.onDestroy()
    }

    companion object {
        private const val ACTION_START = "com.monorec.app.START"
        private const val ACTION_STOP = "com.monorec.app.STOP"
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_RESULT_DATA = "result_data"
        private const val EXTRA_QUALITY = "quality"
        private const val EXTRA_AUDIO = "audio"
        private const val CHANNEL_ID = "monorec_recording"
        private const val NOTIF_ID = 1001

        fun start(context: Context, resultCode: Int, data: Intent, config: RecorderConfig) {
            val i = Intent(context, RecorderService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, data)
                .putExtra(EXTRA_QUALITY, config.quality.name)
                .putExtra(EXTRA_AUDIO, config.audioMode.name)
            context.startForegroundService(i)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, RecorderService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}
