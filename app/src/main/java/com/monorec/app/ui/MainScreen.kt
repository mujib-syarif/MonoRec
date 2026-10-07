package com.monorec.app.ui

import android.net.Uri
import android.os.Build
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.monorec.app.data.Recording
import com.monorec.app.recorder.AudioMode
import com.monorec.app.recorder.QualityPreset
import com.monorec.app.recorder.RecState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ------------------------------------------------------------------ format

fun formatElapsed(ms: Long): String {
    val s = ms / 1000
    return "%02d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
}

fun formatShortDuration(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

fun formatBytes(b: Long): String = when {
    b < 1024L -> "$b B"
    b < 1024L * 1024 -> "%.0f KB".format(b / 1024.0)
    b < 1024L * 1024 * 1024 -> "%.1f MB".format(b / 1048576.0)
    else -> "%.2f GB".format(b / 1073741824.0)
}

private val dateFmt = SimpleDateFormat("d MMM · HH:mm", Locale("id", "ID"))

// -------------------------------------------------------------------- layar

@Composable
fun MainScreen(
    rec: RecState,
    quality: QualityPreset,
    audio: AudioMode,
    recordings: List<Recording>,
    nativeRes: String,
    onQuality: (QualityPreset) -> Unit,
    onAudio: (AudioMode) -> Unit,
    onToggle: () -> Unit,
    onOpen: (Recording) -> Unit,
    onShare: (Recording) -> Unit,
    onDelete: (Recording) -> Unit
) {
    val bars = WindowInsets.systemBars.asPaddingValues()
    val qualities = QualityPreset.values()
    val audios = AudioMode.values()
    val locked = rec.recording

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 18.dp,
            end = 18.dp,
            top = bars.calculateTopPadding() + 14.dp,
            bottom = bars.calculateBottomPadding() + 32.dp
        ),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Header
        item {
            Column(Modifier.padding(horizontal = 6.dp)) {
                Text(
                    "MonoRec",
                    color = Mono.White,
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Light,
                    letterSpacing = (-0.5).sp
                )
                Text("Perekam layar minimalis", color = Mono.Dim, fontSize = 13.sp)
            }
        }

        // Hero: status, timer, tile data
        item {
            GlassCard {
                Column(Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusPill(rec.recording)
                        Spacer(Modifier.weight(1f))
                        Text(audio.caption, color = Mono.Dim, fontSize = 12.sp)
                    }
                    Spacer(Modifier.height(22.dp))
                    Text(
                        formatElapsed(rec.elapsedMs),
                        color = Mono.White,
                        fontSize = 52.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Light,
                        letterSpacing = (-1).sp
                    )
                    Spacer(Modifier.height(20.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StatTile(
                            "Ukuran",
                            if (rec.recording) formatBytes(rec.bytes) else "—",
                            Modifier.weight(1f)
                        )
                        StatTile(
                            "Resolusi",
                            if (rec.recording) "${rec.width}×${rec.height}" else quality.label,
                            Modifier.weight(1f)
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StatTile(
                            "Frame rate",
                            "${if (rec.recording) rec.fps else quality.fps} fps",
                            Modifier.weight(1f)
                        )
                        StatTile(
                            "Bitrate",
                            "%.1f Mbps".format(
                                (if (rec.recording) rec.videoBitrate else quality.videoBitrate) / 1_000_000.0
                            ),
                            Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        // Tombol rekam
        item {
            Column(
                Modifier.fillMaxWidth().padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                RecordButton(recording = rec.recording, onClick = onToggle)
                Text(
                    if (rec.recording) "Ketuk untuk berhenti" else "Ketuk untuk mulai merekam",
                    modifier = Modifier.padding(top = 12.dp),
                    color = Mono.Dim,
                    fontSize = 12.sp
                )
            }
        }

        // Error
        rec.error?.let { msg ->
            item {
                GlassCard {
                    Text(msg, modifier = Modifier.padding(16.dp), color = Mono.White, fontSize = 13.sp)
                }
            }
        }

        // Pengaturan audio
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionLabel("Sumber audio")
                SegmentedControl(
                    options = audios.map { it.label },
                    selected = audios.indexOf(audio),
                    enabled = !locked,
                    onSelect = { onAudio(audios[it]) }
                )
                if (audio == AudioMode.DEVICE_MIC) {
                    Text(
                        "Pakai earphone agar suara HP tidak terekam dua kali lewat mikrofon.",
                        modifier = Modifier.padding(horizontal = 6.dp),
                        color = Mono.Faint,
                        fontSize = 11.sp
                    )
                }
            }
        }

        // Pengaturan kualitas
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionLabel("Kualitas video")
                SegmentedControl(
                    options = qualities.map { it.label },
                    selected = qualities.indexOf(quality),
                    enabled = !locked,
                    onSelect = { onQuality(qualities[it]) }
                )
                Text(
                    "Layar ini $nativeRes · resolusi tidak dinaikkan melebihi layar asli.",
                    modifier = Modifier.padding(horizontal = 6.dp),
                    color = Mono.Faint,
                    fontSize = 11.sp
                )
            }
        }

        // Info perangkat
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionLabel("Perangkat")
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatTile("Model", Build.MODEL.take(16), Modifier.weight(1f))
                    StatTile("Android", Build.VERSION.RELEASE, Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatTile("Layar", nativeRes, Modifier.weight(1f))
                    StatTile("Codec", "H.264·AAC", Modifier.weight(1f))
                }
            }
        }

        // Riwayat
        item {
            Row(
                Modifier.fillMaxWidth().padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SectionLabel("Riwayat")
                Spacer(Modifier.weight(1f))
                Text(
                    "${recordings.size} rekaman",
                    color = Mono.Faint,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(end = 6.dp)
                )
            }
        }
        if (recordings.isEmpty()) {
            item {
                GlassCard {
                    Text(
                        "Belum ada rekaman. Hasil tersimpan di Movies/MonoRec.",
                        modifier = Modifier.padding(20.dp),
                        color = Mono.Dim,
                        fontSize = 13.sp
                    )
                }
            }
        } else {
            items(recordings, key = { it.id }) { r ->
                RecordingRow(r, onOpen, onShare, onDelete)
            }
        }
    }
}

@Composable
private fun RecordingRow(
    r: Recording,
    onOpen: (Recording) -> Unit,
    onShare: (Recording) -> Unit,
    onDelete: (Recording) -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .glass(RoundedCornerShape(22.dp), 0.8f)
            .tap { onOpen(r) }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Thumb(r.uri, Modifier.size(56.dp))
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(
                r.name.removeSuffix(".mp4"),
                color = Mono.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                "${formatShortDuration(r.durationMs)} · ${formatBytes(r.sizeBytes)}",
                color = Mono.Dim,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(top = 2.dp)
            )
            Text(
                dateFmt.format(Date(r.dateMs)),
                color = Mono.Faint,
                fontSize = 11.sp
            )
        }
        Box(Modifier.size(38.dp).tap { onShare(r) }, contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.IosShare, contentDescription = "Bagikan", tint = Mono.Dim, modifier = Modifier.size(20.dp))
        }
        Box(Modifier.size(38.dp).tap { onDelete(r) }, contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.DeleteOutline, contentDescription = "Hapus", tint = Mono.Dim, modifier = Modifier.size(20.dp))
        }
    }
}

/** Thumbnail video, dibuat hitam-putih agar sesuai tema monokrom. */
@Composable
private fun Thumb(uri: Uri, modifier: Modifier) {
    val ctx = LocalContext.current
    val bmp by produceState<ImageBitmap?>(null, uri) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                ctx.contentResolver.loadThumbnail(uri, Size(192, 192), null).asImageBitmap()
            }.getOrNull()
        }
    }
    Box(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(androidx.compose.ui.graphics.Color.White.copy(alpha = 0.08f))
    ) {
        bmp?.let {
            Image(
                bitmap = it,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                colorFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })
            )
        }
    }
}
