package com.eirahoutmoss.nexyapi.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.eirahoutmoss.nexyapi.core.integrity.Sha256
import com.eirahoutmoss.nexyapi.report.VerifyResult
import com.eirahoutmoss.nexyapi.ui.appContainer
import com.eirahoutmoss.nexyapi.ui.components.formatDateTime
import com.eirahoutmoss.nexyapi.ui.theme.NexColors
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VerifyScreen(onBack: () -> Unit) {
    val c = appContainer()
    val scope = rememberCoroutineScope()
    var input by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<VerifyResult?>(null) }

    fun run(text: String) {
        busy = true
        scope.launch {
            result = try { c.verifier.verify(text) } catch (e: Exception) { VerifyResult.BadInput("Doğrulama yapılamadı: ${e.message}") }
            busy = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Rapor doğrulama") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Geri") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                "Raporun her sayfasının altındaki \"Rapor parmak izi\"ni girin. Uygulama bu cihazdaki (ya da yedekten alınmış) " +
                    "kayıtları raporun üretildiği andaki verilerle karşılaştırır ve ölçümlerin ham veri dosyalarını denetler.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                value = input,
                onValueChange = { input = it.take(120); result = null },
                label = { Text("Parmak izi") },
                placeholder = { Text("Örn. 3F2A 9C10 B7E4 …") },
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(onClick = { run(input) }, enabled = !busy && input.isNotBlank(), modifier = Modifier.fillMaxWidth().height(50.dp)) {
                if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text("Doğrula")
            }
            when (val r = result) {
                null -> Unit
                is VerifyResult.BadInput -> Text(r.message, color = MaterialTheme.colorScheme.error)
                VerifyResult.NotFound -> ResultCard(
                    Icons.AutoMirrored.Outlined.HelpOutline, NexColors.Amber, "Kayıt bulunamadı",
                    listOf(
                        "Bu parmak izine sahip bir rapor bu cihazda yok.",
                        "Rapor başka bir cihazda üretildiyse, o cihazdan alınan yedeği içe aktarıp tekrar deneyin.",
                        "Parmak izini yanlış yazmış olabilirsiniz; karakterleri kontrol edin.",
                    ),
                )
                is VerifyResult.Ambiguous -> Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Birden fazla rapor eşleşti; birini seçin ya da daha fazla karakter girin.", style = MaterialTheme.typography.bodySmall)
                        r.matches.forEach { m ->
                            TextButton(onClick = { input = m.fingerprint; run(m.fingerprint) }) {
                                Text("${formatDateTime(m.generatedAt)} · ${Sha256.grouped(m.fingerprint).take(19)}…")
                            }
                        }
                    }
                }
                is VerifyResult.Checked -> {
                    val lines = ArrayList<String>()
                    lines += "Yapı: ${r.structureName ?: "(silinmiş)"}"
                    lines += "Üretim: ${formatDateTime(r.report.generatedAt)} · NexYapı ${r.report.appVersion}"
                    r.report.holder?.let { lines += "Hazırlayan: $it" }
                    lines += "Ölçüm sayısı: ${r.report.measurementCount}"
                    when (r.dataIntact) {
                        true -> ResultCard(Icons.Outlined.CheckCircle, NexColors.Green, "Rapor doğrulandı",
                            lines + "Rapordaki veriler bugünkü kayıtlarla birebir aynı.")
                        false -> ResultCard(Icons.Outlined.ErrorOutline, NexColors.Red, "Rapordan sonra veriler değişmiş",
                            lines + r.changes.map { "• $it" } +
                                "Not: Yapı adının ya da adresinin sonradan düzenlenmesi de değişiklik olarak görünür.")
                        null -> ResultCard(Icons.AutoMirrored.Outlined.HelpOutline, NexColors.Amber, "Kayıt bulundu (ayrıntılı denetim yok)",
                            lines + "Bu rapor eski bir sürümle üretildiği için veri kümesi kaydedilmemiş; yalnızca raporun bu cihazda üretildiği doğrulanabildi.")
                    }
                    if (r.rawChecked > 0) {
                        if (r.rawProblems.isEmpty()) {
                            ResultCard(Icons.Outlined.CheckCircle, NexColors.Green, "Ham veriler bütün", listOf("${r.rawChecked} ham veri dosyasının özeti kayıtla aynı."))
                        } else {
                            ResultCard(Icons.Outlined.ErrorOutline, NexColors.Red, "Ham veri sorunu", r.rawProblems)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultCard(icon: androidx.compose.ui.graphics.vector.ImageVector, color: Color, title: String, lines: List<String>) {
    Card(colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.12f))) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = color)
                Text("  $title", style = MaterialTheme.typography.titleMedium, color = color)
            }
            lines.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
