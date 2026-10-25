package com.eirahoutmoss.nexyapi.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.eirahoutmoss.nexyapi.backup.BackupService
import com.eirahoutmoss.nexyapi.core.model.Plan
import com.eirahoutmoss.nexyapi.ui.appContainer
import com.eirahoutmoss.nexyapi.ui.components.FileImage
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Pro rapor için büro bilgileri. Ücretsiz sürümde görünür ama kilitlidir. */
@Composable
fun BrandingSection(plan: Plan, onMessage: (String) -> Unit) {
    val c = appContainer()
    val scope = rememberCoroutineScope()
    var s by remember { mutableStateOf(c.branding.load()) }
    val pro = plan == Plan.PRO
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            try { c.branding.setLogo(uri); s = c.branding.load(); onMessage("Logo kaydedildi.") }
            catch (e: Exception) { onMessage("Logo eklenemedi: ${e.message ?: e.javaClass.simpleName}") }
        }
    }

    if (!pro) {
        Text(
            "Profesyonel lisansla raporların başlığında büronuzun adı ve logosu, kapakta hazırlayan mühendis ve iletişim bilgisi yer alır.",
            style = MaterialTheme.typography.bodySmall,
        )
        return
    }
    Text("Bu bilgiler yalnızca Profesyonel raporlarda görünür ve cihazda saklanır.", style = MaterialTheme.typography.bodySmall)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(64.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
            if (s.hasLogo) {
                // FileImage dosyanın değişme zamanını izler: logo yenilenince görüntü de tazelenir.
                FileImage(c.branding.logoFile, 256, Modifier.fillMaxWidth().height(64.dp), contentScale = ContentScale.Fit) {}
            } else Text("Logo", style = MaterialTheme.typography.labelSmall)
        }
        Column {
            OutlinedButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                Text(if (s.hasLogo) "Logoyu değiştir" else "Logo seç")
            }
            if (s.hasLogo) TextButton(onClick = { c.branding.removeLogo(); s = c.branding.load() }) { Text("Logoyu kaldır") }
        }
    }
    OutlinedTextField(s.officeName, { s = s.copy(officeName = it.take(80)) }, label = { Text("Büro adı") },
        placeholder = { Text("Boş bırakılırsa lisans sahibi") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(s.preparedBy, { s = s.copy(preparedBy = it.take(80)) }, label = { Text("Hazırlayan") },
        placeholder = { Text("Ad Soyad") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(s.preparedByTitle, { s = s.copy(preparedByTitle = it.take(80)) }, label = { Text("Unvan / sicil no") },
        placeholder = { Text("Örn. İnşaat Mühendisi, Oda sicil no") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(s.contact, { s = s.copy(contact = it.take(160)) }, label = { Text("İletişim") },
        placeholder = { Text("Telefon, e-posta, adres") }, minLines = 2, modifier = Modifier.fillMaxWidth())
    Button(onClick = { c.branding.save(s); s = c.branding.load(); onMessage("Büro bilgileri kaydedildi.") }) { Text("Kaydet") }
}

/** Yedekleme ve içe aktarma. */
@Composable
fun BackupSection(onMessage: (String) -> Unit) {
    val c = appContainer()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf<String?>(null) }
    var confirmImport by remember { mutableStateOf<android.net.Uri?>(null) }

    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(BackupService.MIME)) { uri ->
        if (uri != null) scope.launch {
            busy = "Yedek hazırlanıyor…"
            try {
                val r = c.backup.export(uri, structureId = null)
                onMessage("Yedek kaydedildi: ${r.structures} yapı, ${r.measurements} ölçüm, ${r.files} dosya.")
            } catch (e: Exception) {
                onMessage("Yedek alınamadı: ${e.message ?: e.javaClass.simpleName}")
            } finally { busy = null }
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) confirmImport = uri }

    Text(
        "Telefon değiştirirken ya da kaybolmaya karşı bütün yapıları, ölçümleri, ham verileri ve raporları tek dosyaya alın. " +
            "Dosyayı bilgisayarınıza ya da bulut klasörünüze kaydedin. Otomatik bulut yedeği bilinçli olarak kapalıdır.",
        style = MaterialTheme.typography.bodySmall,
    )
    if (busy != null) {
        Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp); Text("  $busy") }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                val name = "NexYapi_yedek_${LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)}.nexyapi"
                exporter.launch(name)
            }) { Text("Yedek al") }
            OutlinedButton(onClick = { importer.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) }) { Text("Yedekten yükle") }
        }
    }

    confirmImport?.let { uri ->
        AlertDialog(
            onDismissRequest = { confirmImport = null },
            title = { Text("Yedekten yüklensin mi?") },
            text = {
                Text(
                    "Yedekteki yapı ve ölçümlerden bu cihazda olmayanlar eklenir. Var olan kayıtların üzerine yazılmaz, hiçbir şey silinmez. " +
                        "Yedek dosyasında bozulma varsa hiçbir şey eklenmez.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmImport = null
                    scope.launch {
                        busy = "Yedek denetleniyor ve yükleniyor…"
                        try {
                            val r = c.backup.import(uri)
                            onMessage(
                                "Yüklendi: ${r.structuresAdded} yeni yapı, ${r.measurementsAdded} ölçüm, ${r.reportsAdded} rapor." +
                                    if (r.structuresSkipped > 0) " ${r.structuresSkipped} yapı zaten vardı." else "",
                            )
                        } catch (e: Exception) {
                            onMessage("Yüklenemedi: ${e.message ?: e.javaClass.simpleName}")
                        } finally { busy = null }
                    }
                }) { Text("Yükle") }
            },
            dismissButton = { TextButton(onClick = { confirmImport = null }) { Text("Vazgeç") } },
        )
    }
}
