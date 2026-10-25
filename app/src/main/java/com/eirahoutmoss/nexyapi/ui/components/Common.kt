package com.eirahoutmoss.nexyapi.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Hardware
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Straighten
import androidx.compose.material.icons.outlined.Thermostat
import androidx.compose.material.icons.outlined.Vibration
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.eirahoutmoss.nexyapi.core.model.Disclaimer
import com.eirahoutmoss.nexyapi.core.model.ModuleType
import com.eirahoutmoss.nexyapi.ui.theme.NexColors
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

val ModuleType.icon: ImageVector
    get() = when (this) {
        ModuleType.VIBRATION -> Icons.Outlined.Vibration
        ModuleType.CRACK -> Icons.Outlined.Straighten
        ModuleType.MOTION -> Icons.Outlined.Visibility
        ModuleType.BEFORE_DRILL -> Icons.Outlined.Hardware
        ModuleType.ACOUSTICS -> Icons.Outlined.GraphicEq
        ModuleType.HEAT_LEAK -> Icons.Outlined.Thermostat
    }

private val dateFmt = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.forLanguageTag("tr"))
private val dateTimeFmt = DateTimeFormatter.ofPattern("d MMM yyyy HH:mm", Locale.forLanguageTag("tr"))

fun formatDate(ms: Long): String = dateFmt.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))
fun formatDateTime(ms: Long): String = dateTimeFmt.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

/** Metin alanının hata satırı: mesaj yoksa null (satır gösterilmez). */
fun errorText(message: String?): (@Composable () -> Unit)? =
    if (message == null) null else { { Text(message) } }

/** "Ölçüm yapar, değerlendirme yapmaz" şeridi. */
@Composable
fun DisclaimerBanner(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = NexColors.Amber.copy(alpha = 0.16f),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Info, contentDescription = null, tint = NexColors.Amber, modifier = Modifier.size(20.dp))
            Text(
                Disclaimer.SHORT + " Değerlendirmeyi yetkili mühendis yapar.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 10.dp),
            )
        }
    }
}

/** Lisans anahtarı giriş penceresi. [onSubmit] hata metni döndürürse pencere açık kalır. */
@Composable
fun LicenseDialog(
    title: String,
    explanation: String,
    onSubmit: (String) -> String?,
    onDismiss: () -> Unit,
) {
    var token by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(explanation, style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it; error = null },
                    label = { Text("Lisans anahtarı") },
                    placeholder = { Text("NXY1-…") },
                    isError = error != null,
                    supportingText = errorText(error),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    minLines = 3,
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = token.isNotBlank(),
                onClick = { error = onSubmit(token) },
            ) { Text("Etkinleştir") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Vazgeç") } },
    )
}
