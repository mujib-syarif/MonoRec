package com.monorec.app

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.util.DisplayMetrics
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.monorec.app.data.Prefs
import com.monorec.app.data.Recording
import com.monorec.app.data.RecordingsRepo
import com.monorec.app.recorder.AudioMode
import com.monorec.app.recorder.QualityPreset
import com.monorec.app.recorder.RecorderConfig
import com.monorec.app.recorder.RecorderService
import com.monorec.app.recorder.RecorderState
import com.monorec.app.ui.AuraBackground
import com.monorec.app.ui.MainScreen
import com.monorec.app.ui.Mono
import com.monorec.app.ui.MonoTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)
        setContent {
            MonoTheme {
                AuraBackground { App() }
            }
        }
    }
}

@Composable
private fun App() {
    val context = LocalContext.current
    val rec by RecorderState.state.collectAsStateWithLifecycle()

    var quality by remember { mutableStateOf(Prefs.quality(context)) }
    var audio by remember { mutableStateOf(Prefs.audio(context)) }
    var recordings by remember { mutableStateOf(emptyList<Recording>()) }
    var pendingDelete by remember { mutableStateOf<Recording?>(null) }
    val scope = rememberCoroutineScope()

    val nativeRes = remember {
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        (context as Activity).windowManager.defaultDisplay.getRealMetrics(dm)
        "${dm.widthPixels}×${dm.heightPixels}"
    }

    fun refresh() {
        scope.launch {
            recordings = withContext(Dispatchers.IO) { RecordingsRepo.load(context) }
        }
    }
    LaunchedEffect(rec.savedCount) { refresh() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh() }

    // 1) Persetujuan perekaman layar -> jalankan service
    val projectionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            RecorderService.start(context, result.resultCode, data, RecorderConfig(quality, audio))
        }
    }
    fun launchCapture() {
        val mpm = context.getSystemService(MediaProjectionManager::class.java)
        projectionLauncher.launch(mpm.createScreenCaptureIntent())
    }

    // 2) Izin mikrofon / notifikasi
    fun micGranted() = ContextCompat.checkSelfPermission(
        context, Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (audio != AudioMode.NONE && !micGranted()) {
            Toast.makeText(
                context, "Izin mikrofon diperlukan untuk merekam audio", Toast.LENGTH_LONG
            ).show()
        } else {
            launchCapture()
        }
    }

    val onToggle = {
        if (rec.recording) {
            RecorderService.stop(context)
        } else {
            val needed = buildList {
                if (audio != AudioMode.NONE && !micGranted()) add(Manifest.permission.RECORD_AUDIO)
                if (Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(
                        context, Manifest.permission.POST_NOTIFICATIONS
                    ) != PackageManager.PERMISSION_GRANTED
                ) add(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (needed.isEmpty()) launchCapture() else permLauncher.launch(needed.toTypedArray())
        }
    }

    MainScreen(
        rec = rec,
        quality = quality,
        audio = audio,
        recordings = recordings,
        nativeRes = nativeRes,
        onQuality = { quality = it; Prefs.save(context, quality, audio) },
        onAudio = { audio = it; Prefs.save(context, quality, audio) },
        onToggle = onToggle,
        onOpen = { r ->
            val i = Intent(Intent.ACTION_VIEW)
                .setDataAndType(r.uri, "video/mp4")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            try {
                context.startActivity(i)
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(context, "Tidak ada aplikasi pemutar video", Toast.LENGTH_SHORT).show()
            }
        },
        onShare = { r ->
            val send = Intent(Intent.ACTION_SEND)
                .setType("video/mp4")
                .putExtra(Intent.EXTRA_STREAM, r.uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(Intent.createChooser(send, "Bagikan rekaman"))
        },
        onDelete = { pendingDelete = it }
    )

    pendingDelete?.let { r ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            containerColor = Color(0xFF151515),
            titleContentColor = Mono.White,
            textContentColor = Mono.Dim,
            title = { Text("Hapus rekaman?") },
            text = { Text(r.name) },
            confirmButton = {
                TextButton(onClick = {
                    RecordingsRepo.delete(context, r)
                    pendingDelete = null
                    refresh()
                }) { Text("Hapus", color = Mono.White) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Batal", color = Mono.Dim) }
            }
        )
    }
}
