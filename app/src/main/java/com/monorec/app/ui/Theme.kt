package com.monorec.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

object Mono {
    val Black = Color(0xFF000000)
    val White = Color(0xFFFFFFFF)
    val Dim = Color(0x99FFFFFF)      // teks sekunder
    val Faint = Color(0x59FFFFFF)    // label kecil
    val Hairline = Color(0x1AFFFFFF)
}

@Composable
fun MonoTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Mono.White,
            onPrimary = Mono.Black,
            background = Mono.Black,
            onBackground = Mono.White,
            surface = Color(0xFF111111),
            onSurface = Mono.White
        ),
        content = content
    )
}

/**
 * Efek kaca (glassmorphism): lapisan putih translusen bergradasi + garis tepi tipis.
 * Sengaja tidak memakai blur sungguhan agar tetap ringan di Android 10/11.
 */
fun Modifier.glass(
    shape: Shape = RoundedCornerShape(28.dp),
    strength: Float = 1f
): Modifier = this
    .clip(shape)
    .background(
        Brush.linearGradient(
            listOf(
                Color.White.copy(alpha = 0.14f * strength),
                Color.White.copy(alpha = 0.04f * strength)
            )
        )
    )
    .border(
        1.dp,
        Brush.linearGradient(
            listOf(
                Color.White.copy(alpha = 0.34f),
                Color.White.copy(alpha = 0.05f),
                Color.White.copy(alpha = 0.16f)
            )
        ),
        shape
    )

/** Latar hitam dengan dua cahaya abu lembut supaya lapisan kaca terlihat. */
@Composable
fun AuraBackground(content: @Composable BoxScope.() -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Mono.Black)
            .drawBehind {
                val r1 = size.width * 0.95f
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(Color.White.copy(alpha = 0.17f), Color.Transparent),
                        center = Offset(size.width * 0.1f, size.height * 0.06f),
                        radius = r1
                    ),
                    radius = r1,
                    center = Offset(size.width * 0.1f, size.height * 0.06f)
                )
                val r2 = size.width * 0.85f
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(Color.White.copy(alpha = 0.10f), Color.Transparent),
                        center = Offset(size.width * 1.0f, size.height * 0.55f),
                        radius = r2
                    ),
                    radius = r2,
                    center = Offset(size.width * 1.0f, size.height * 0.55f)
                )
            },
        content = content
    )
}

/** Klik tanpa ripple, terasa lebih seperti iOS. */
@Composable
fun Modifier.tap(enabled: Boolean = true, onClick: () -> Unit): Modifier =
    this.clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        enabled = enabled,
        onClick = onClick
    )
