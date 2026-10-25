package com.eirahoutmoss.nexyapi.ui.screens

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarHost
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eirahoutmoss.nexyapi.BuildConfig
import com.eirahoutmoss.nexyapi.core.license.LicenseCheck
import com.eirahoutmoss.nexyapi.core.model.Disclaimer
import com.eirahoutmoss.nexyapi.core.model.Plan
import com.eirahoutmoss.nexyapi.diagnostics.CrashLogger
import com.eirahoutmoss.nexyapi.ui.appContainer
import com.eirahoutmoss.nexyapi.ui.components.LicenseDialog
import com.eirahoutmoss.nexyapi.ui.components.NexFamily
import com.eirahoutmoss.nexyapi.ui.components.NexYapiLogo
import com.eirahoutmoss.nexyapi.ui.components.TAGLINE
import com.eirahoutmoss.nexyapi.ui.components.Wordmark
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.sp
import com.eirahoutmoss.nexyapi.update.UpdateState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, onVerify: () -> Unit) {
    val c = appContainer()
    val context = LocalContext.current
    val license by c.licenses.state.collectAsStateWithLifecycle()
    val update by c.updateManager.state.collectAsStateWithLifecycle()
    var showLicense by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    var hasCrashLog by remember { mutableStateOf(CrashLogger.hasLog(context)) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Ayarlar") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Geri") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // ---------- Güncelleme ----------
            Section("Güncelleme") {
                Text("Yüklü sürüm: ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyMedium)
                UpdateBody(
                    state = update,
                    onCheck = { c.updateManager.check() },
                    onInstall = { cand -> c.updateManager.downloadAndInstall(cand) },
                    onOpenPermission = { context.startActivity(c.updateManager.installPermissionIntent()) },
                )
            }

            // ---------- Lisans ----------
            Section("Lisans") {
                Text("Sürüm: ${license.plan.displayName}", style = MaterialTheme.typography.bodyMedium)
                license.license?.let { l ->
                    Text("Lisans sahibi: ${l.holder}", style = MaterialTheme.typography.bodySmall)
                    Text("Lisans no: ${l.id}", style = MaterialTheme.typography.bodySmall)
                    Text("Geçerlilik: " + (l.expires?.let { "$it tarihine kadar" } ?: "süresiz"), style = MaterialTheme.typography.bodySmall)
                }
                license.problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (!c.licenses.isVerifierConfigured) {
                    Text(
                        "Bu derlemede lisans açık anahtarı tanımlı değil; Profesyonel lisans etkinleştirilemez.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { showLicense = true }) {
                        Text(if (license.plan == Plan.PRO) "Lisansı değiştir" else "Lisans anahtarı gir")
                    }
                    if (license.license != null) {
                        OutlinedButton(onClick = { confirmRemove = true }) { Text("Lisansı kaldır") }
                    }
                }
            }

            // ---------- Rapor doğrulama ----------
            Section("Rapor doğrulama") {
                Text(
                    "Bir raporun bu cihazdaki kayıtlarla aynı olup olmadığını ve ham verilerin bütünlüğünü denetleyin.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(onClick = onVerify) { Text("Raporu doğrula") }
            }

            // ---------- Büro bilgileri ----------
            Section("Rapor başlığı (Profesyonel)") {
                BrandingSection(license.plan) { msg -> scope.launch { snackbar.showSnackbar(msg) } }
            }

            // ---------- Yedekleme ----------
            Section("Yedekleme") {
                BackupSection { msg -> scope.launch { snackbar.showSnackbar(msg) } }
            }

            // ---------- Tanılama ----------
            Section("Hata kaydı") {
                Text(
                    if (hasCrashLog) "Cihazda bir hata kaydı var. Destek için paylaşabilirsiniz; kayıt dışarı kendiliğinden gönderilmez."
                    else "Kayıtlı hata yok.",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (hasCrashLog) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            val f = CrashLogger.file(context)
                            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", f)
                            val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                                .putExtra(Intent.EXTRA_STREAM, uri)
                                .putExtra(Intent.EXTRA_SUBJECT, "NexYapı hata kaydı ${BuildConfig.VERSION_NAME}")
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            context.startActivity(Intent.createChooser(send, "Hata kaydını paylaş"))
                        }) { Text("Paylaş") }
                        TextButton(onClick = { CrashLogger.clear(context); hasCrashLog = false }) { Text("Temizle") }
                    }
                }
            }

            // ---------- Hakkında ----------
            Section("Hakkında") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    NexYapiLogo(48.dp)
                    Column(Modifier.padding(start = 12.dp)) {
                        Wordmark(24.sp)
                        Text(TAGLINE, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Text(Disclaimer.REPORT, style = MaterialTheme.typography.bodySmall)
                Text("Nex ailesinin bir parçası", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 6.dp))
                NexFamily()
            }
        }
    }

    if (showLicense) {
        LicenseDialog(
            title = "Profesyonel lisans",
            explanation = "Size iletilen lisans anahtarını yapıştırın. Doğrulama çevrimdışı yapılır.",
            onSubmit = { token ->
                when (val r = c.licenses.activate(token)) {
                    is LicenseCheck.Valid -> { showLicense = false; null }
                    is LicenseCheck.Expired -> "Bu lisansın süresi ${r.license.expires} tarihinde dolmuş."
                    is LicenseCheck.Invalid -> r.reason
                }
            },
            onDismiss = { showLicense = false },
        )
    }
    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Lisans kaldırılsın mı?") },
            text = { Text("Uygulama Ücretsiz sürüme döner. Kayıtlı yapılar ve ölçümler silinmez, ancak yeni yapı eklenemez.") },
            confirmButton = { TextButton(onClick = { c.licenses.deactivate(); confirmRemove = false }) { Text("Kaldır") } },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Vazgeç") } },
        )
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun UpdateBody(
    state: UpdateState,
    onCheck: () -> Unit,
    onInstall: (com.eirahoutmoss.nexyapi.core.update.UpdateCandidate) -> Unit,
    onOpenPermission: () -> Unit,
) {
    when (state) {
        UpdateState.Idle -> CheckButton(onCheck)
        UpdateState.Checking -> {
            Text("Denetleniyor…", style = MaterialTheme.typography.bodySmall)
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        UpdateState.UpToDate -> {
            Text("Uygulamanız güncel.", style = MaterialTheme.typography.bodySmall)
            CheckButton(onCheck)
        }
        is UpdateState.Available -> {
            Text("Yeni sürüm var: ${state.candidate.version}", style = MaterialTheme.typography.bodyMedium)
            if (state.candidate.notes.isNotBlank()) {
                Text(state.candidate.notes.take(600), style = MaterialTheme.typography.bodySmall)
            }
            Button(onClick = { onInstall(state.candidate) }) { Text("İndir ve kur") }
        }
        is UpdateState.Downloading -> {
            Text("İndiriliyor… %${(state.progress * 100).toInt()}", style = MaterialTheme.typography.bodySmall)
            LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
        }
        UpdateState.Verifying -> {
            Text("Dosya doğrulanıyor…", style = MaterialTheme.typography.bodySmall)
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        UpdateState.Installing -> Text("Kurulum ekranı açılıyor. Onay verdiğinizde uygulama yeniden başlar.", style = MaterialTheme.typography.bodySmall)
        is UpdateState.NeedsInstallPermission -> {
            Text(
                "Güncellemeyi kurmak için bu uygulamaya \"bilinmeyen uygulamaları yükleme\" izni verilmelidir. İzni verip geri dönün.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onOpenPermission) { Text("İzni aç") }
                OutlinedButton(onClick = { onInstall(state.candidate) }) { Text("Tekrar dene") }
            }
        }
        is UpdateState.Failed -> {
            Text(state.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            CheckButton(onCheck)
        }
    }
}

@Composable
private fun CheckButton(onCheck: () -> Unit) {
    Button(onClick = onCheck) {
        Icon(Icons.Outlined.SystemUpdate, contentDescription = null)
        Spacer(Modifier.size(8.dp))
        Text("Güncellemeleri denetle")
    }
}
