package com.eirahoutmoss.nexyapi.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.eirahoutmoss.nexyapi.data.MeasurementEntity
import com.eirahoutmoss.nexyapi.data.MeasurementQuality
import com.eirahoutmoss.nexyapi.ui.theme.NexColors
import java.util.Locale

/** Türkçe ondalık biçim: 2,84 */
fun fmtNum(v: Double, decimals: Int): String = String.format(Locale.forLanguageTag("tr"), "%.${decimals}f", v)

val MeasurementQuality.label: String
    get() = when (this) {
        MeasurementQuality.OK -> "Normal"
        MeasurementQuality.LOW -> "Düşük kalite"
        MeasurementQuality.UNRELIABLE -> "Güvenilmez"
    }

val MeasurementQuality.color: Color
    get() = when (this) {
        MeasurementQuality.OK -> NexColors.Green
        MeasurementQuality.LOW -> NexColors.Amber
        MeasurementQuality.UNRELIABLE -> NexColors.Red
    }

@Composable
fun QualityChip(q: MeasurementQuality) {
    Surface(shape = RoundedCornerShape(8.dp), color = q.color.copy(alpha = 0.16f)) {
        Text(q.label, color = q.color, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
    }
}

private const val ALL = "\u0000hepsi"

/**
 * Bir modülün ölçüm geçmişi: konuya (yer / çatlak adı) göre süzme, geçmiş grafiği ve liste.
 * Grafik yalnızca tek bir konu seçiliyken çizilir; farklı yerlerin değerleri tek eğride birleştirilmez.
 * Güvenilmez ölçümler grafikte gösterilmez (listede kalır).
 */
@Composable
fun ModuleHistory(
    items: List<MeasurementEntity>,
    unit: String,
    decimals: Int,
    accent: Color,
    onDelete: (MeasurementEntity) -> Unit,
) {
    if (items.isEmpty()) {
        Text("Henüz ölçüm yok.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val subjects = remember(items) { items.mapNotNull { it.subject?.takeIf { s -> s.isNotBlank() } }.distinct().sorted() }
    var chosen by rememberSaveable { mutableStateOf(subjects.firstOrNull() ?: ALL) }
    // Seçili konu silinmişse ilk konuya düş (bileşim sırasında durum yazmadan).
    val selected = if (chosen != ALL && chosen !in subjects) subjects.firstOrNull() ?: ALL else chosen
    var confirm by remember { mutableStateOf<MeasurementEntity?>(null) }

    val shown = if (selected == ALL) items else items.filter { it.subject == selected }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (subjects.size > 1 || (subjects.size == 1 && items.any { it.subject.isNullOrBlank() })) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                subjects.forEach { s -> FilterChip(selected = selected == s, onClick = { chosen = s }, label = { Text(s) }) }
                FilterChip(selected = selected == ALL, onClick = { chosen = ALL }, label = { Text("Hepsi") })
            }
        }
        val chartPoints = shown.filter { it.value != null && it.quality != MeasurementQuality.UNRELIABLE }
            .map { it.takenAt to it.value!! }
        if (selected != ALL || subjects.isEmpty()) {
            if (chartPoints.size >= 2) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Zaman içindeki değişim", style = MaterialTheme.typography.titleSmall)
                        HistoryChart(chartPoints, unit, decimals, accent, Modifier.padding(top = 8.dp))
                    }
                }
            } else if (chartPoints.size == 1) {
                Text(
                    "Değişimi görmek için aynı yerde zaman içinde tekrar ölçüm yapın.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        shown.forEach { m ->
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Row(Modifier.padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(m.summary, style = MaterialTheme.typography.titleMedium)
                        Text(
                            listOfNotNull(formatDateTime(m.takenAt), m.subject?.takeIf { it.isNotBlank() }).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(Modifier.padding(top = 4.dp)) { QualityChip(m.quality) }
                    }
                    IconButton(onClick = { confirm = m }) { Icon(Icons.Outlined.Delete, contentDescription = "Ölçümü sil") }
                }
            }
        }
    }

    confirm?.let { m ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Ölçüm silinsin mi?") },
            text = { Text("${formatDateTime(m.takenAt)} tarihli ölçüm ve ham verisi silinir. Bu işlem geri alınamaz.") },
            confirmButton = { TextButton(onClick = { onDelete(m); confirm = null }) { Text("Sil", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Vazgeç") } },
        )
    }
}
