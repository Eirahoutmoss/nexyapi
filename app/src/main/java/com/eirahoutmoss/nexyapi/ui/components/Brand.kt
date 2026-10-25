package com.eirahoutmoss.nexyapi.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Newspaper
import androidx.compose.material.icons.outlined.NightsStay
import androidx.compose.material.icons.outlined.SignalCellularAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eirahoutmoss.nexyapi.R
import com.eirahoutmoss.nexyapi.ui.theme.NexColors
import kotlinx.coroutines.delay

const val TAGLINE = "Binanızın sağlık karnesi, cebinizde."

/** Uygulama simgesiyle aynı logo: lacivert zemin üzerinde bina + nabız çizgisi. */
@Composable
fun NexYapiLogo(size: Dp) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size * 0.24f))
            .background(Brush.linearGradient(listOf(NexColors.Navy, NexColors.NavyDeep)))
            .border(1.dp, NexColors.BlueLight.copy(alpha = 0.35f), RoundedCornerShape(size * 0.24f)),
        contentAlignment = Alignment.Center,
    ) {
        Image(painterResource(R.drawable.ic_launcher_foreground), contentDescription = "NexYapı", modifier = Modifier.fillMaxSize())
    }
}

/** "Nex" beyaz/koyu, "Yapı" turuncu yazı. */
@Composable
fun Wordmark(fontSize: TextUnit, nexColor: Color = MaterialTheme.colorScheme.onBackground) {
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(color = nexColor, fontWeight = FontWeight.Black)) { append("Nex") }
            withStyle(SpanStyle(color = NexColors.Orange, fontWeight = FontWeight.Black)) { append("Yapı") }
        },
        fontSize = fontSize,
    )
}

/** Açılış ekranı: kısa süre görünür, sonra solarak kaybolur. */
@Composable
fun SplashOverlay(onFinished: () -> Unit) {
    val alpha = remember { Animatable(1f) }
    LaunchedEffect(Unit) {
        delay(1100)
        alpha.animateTo(0f, tween(350))
        onFinished()
    }
    Box(
        Modifier.fillMaxSize().alpha(alpha.value)
            // Açılış ekranı görünürken alttaki ekrana dokunulmasın.
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } } }
            .background(Brush.verticalGradient(listOf(NexColors.NavyDeep, NexColors.Navy, NexColors.NavyDeep))),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            NexYapiLogo(112.dp)
            Spacer(Modifier.size(22.dp))
            Wordmark(44.sp, nexColor = Color.White)
            Spacer(Modifier.size(10.dp))
            Text(
                TAGLINE.uppercase(java.util.Locale.forLanguageTag("tr")),
                color = Color.White.copy(alpha = 0.8f),
                style = MaterialTheme.typography.labelMedium,
                letterSpacing = 2.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp),
            )
        }
    }
}

private data class FamilyApp(val name: String, val tagline: String, val icon: ImageVector?)

private val FAMILY = listOf(
    FamilyApp("NexSignal", "Her yerde bağlantı", Icons.Outlined.SignalCellularAlt),
    FamilyApp("NexSky", "Gökyüzü rehberin", Icons.Outlined.NightsStay),
    FamilyApp("NexYapı", "Binanızın sağlık karnesi", null),
    FamilyApp("NexNews", "Dünyayı takip et", Icons.Outlined.Newspaper),
    FamilyApp("NexView", "Görsellerini keşfet", Icons.Outlined.Image),
    FamilyApp("NexOrigin", "Soy ağacını keşfet", Icons.Outlined.AccountTree),
)

/** "Nex ailesinin bir parçası" bölümü. */
@Composable
fun NexFamily() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FAMILY.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { app ->
                    val current = app.icon == null
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = if (current) NexColors.Orange.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.weight(1f).then(
                            if (current) Modifier.border(1.5.dp, NexColors.Orange, RoundedCornerShape(14.dp)) else Modifier,
                        ),
                    ) {
                        Column(Modifier.fillMaxWidth().padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            if (app.icon != null) {
                                Icon(app.icon, null, tint = NexColors.BlueLight, modifier = Modifier.size(30.dp))
                            } else {
                                NexYapiLogo(30.dp)
                            }
                            Spacer(Modifier.size(6.dp))
                            Text(app.name, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                            Text(
                                app.tagline, style = MaterialTheme.typography.labelSmall, maxLines = 2, minLines = 2,
                                textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}
