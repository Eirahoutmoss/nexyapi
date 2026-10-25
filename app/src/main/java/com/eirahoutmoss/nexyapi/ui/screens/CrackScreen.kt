package com.eirahoutmoss.nexyapi.ui.screens

import android.content.ActivityNotFoundException
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Print
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.eirahoutmoss.nexyapi.AppContainer
import com.eirahoutmoss.nexyapi.core.model.ModuleType
import com.eirahoutmoss.nexyapi.data.MeasurementEntity
import com.eirahoutmoss.nexyapi.report.ReferenceLabelPdf
import com.eirahoutmoss.nexyapi.ui.appContainer
import com.eirahoutmoss.nexyapi.ui.components.DisclaimerBanner
import com.eirahoutmoss.nexyapi.ui.components.ModuleHistory
import com.eirahoutmoss.nexyapi.ui.components.rememberPhotoSource
import com.eirahoutmoss.nexyapi.ui.theme.accent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class CrackViewModel(private val c: AppContainer, private val structureId: String) : ViewModel() {
    val history: StateFlow<List<MeasurementEntity>> = c.structures.observeModule(structureId, ModuleType.CRACK)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    var importing by mutableStateOf(false); private set

    init {
        // Yarıda bırakılmış ölçümlerin bekleyen fotoğraflarını temizle.
        viewModelScope.launch(Dispatchers.IO) {
            pendingDir().listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 24 * 3600_000L }?.forEach { it.delete() }
        }
    }

    fun pendingDir(): File = File(c.structures.dataDir(structureId), "pending")

    /** Seçilen fotoğrafı düzeltip bekleyen dosyaya alır; uygulama klasörüne göre yolunu döndürür. */
    suspend fun import(uri: Uri): Result<String> {
        importing = true
        return try {
            val f = File(pendingDir(), "${UUID.randomUUID()}.jpg")
            c.images.importImage(uri, f, maxDimension = 4096, quality = 95)
            // Kamera geçici dosyası artık gereksiz.
            if (uri.scheme == "file") uri.path?.let { withContext(Dispatchers.IO) { File(it).delete() } }
            Result.success(c.structures.relative(f))
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            importing = false
        }
    }

    suspend fun labelFile(): File = withContext(Dispatchers.IO) {
        val f = File(c.appContext.filesDir, "labels/NexYapi_referans_etiketi_20mm_v1.pdf")
        if (!f.exists()) ReferenceLabelPdf.generate(f)
        f
    }

    fun delete(m: MeasurementEntity) {
        viewModelScope.launch { c.structures.deleteMeasurement(m.id) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CrackScreen(structureId: String, onMeasure: (String) -> Unit, onBack: () -> Unit) {
    val container = appContainer()
    val vm: CrackViewModel = viewModel(
        key = "v3-$structureId",
        factory = viewModelFactory { initializer { CrackViewModel(container, structureId) } },
    )
    val history by vm.history.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(error) { error?.let { snackbar.showSnackbar(it); error = null } }

    val photos = rememberPhotoSource(
        onPicked = { uri ->
            scope.launch {
                vm.import(uri)
                    .onSuccess(onMeasure)
                    .onFailure { error = "Fotoğraf alınamadı: ${it.message ?: it.javaClass.simpleName}" }
            }
        },
        onError = { error = it },
    )

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Çatlak İzleyici") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Geri") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Nasıl ölçülür?", style = MaterialTheme.typography.titleSmall)
                    listOf(
                        "Referans etiket sayfasını gerçek boyutta yazdırın, 100 mm cetvelini kontrol edin.",
                        "Bir etiketi çatlağın hemen yanına yapıştırın ve orada bırakın.",
                        "Telefonu duvara karşıdan, çatlak ve etiket kareyi dolduracak kadar yakın tutup çekin (yaklaşık 10 cm'lik alan).",
                        "Fotoğrafta etiketin 4 köşesine, sonra çatlağın iki kenarına dokunun; noktaları sürükleyerek ince ayar yapın.",
                        "Aynı çatlağı her ay aynı adla ölçün; değişim grafikte görünür.",
                    ).forEachIndexed { i, t -> Text("${i + 1}. $t", style = MaterialTheme.typography.bodySmall) }
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                try {
                                    val f = vm.labelFile()
                                    try {
                                        context.startActivity(container.reports.viewIntent(f))
                                    } catch (e: ActivityNotFoundException) {
                                        context.startActivity(container.reports.shareIntent(f))
                                    }
                                } catch (e: Exception) {
                                    error = "Etiket sayfası hazırlanamadı: ${e.message}"
                                }
                            }
                        },
                        modifier = Modifier.padding(top = 4.dp),
                    ) {
                        Icon(Icons.Outlined.Print, null)
                        Spacer(Modifier.size(8.dp))
                        Text("Referans etiketini yazdır")
                    }
                }
            }

            if (vm.importing) {
                Row { CircularProgressIndicator(Modifier.size(22.dp)); Text("  Fotoğraf hazırlanıyor…") }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = photos.takePhoto,
                        modifier = Modifier.weight(1f).height(52.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                    ) {
                        Icon(Icons.Outlined.PhotoCamera, null); Spacer(Modifier.size(8.dp)); Text("Fotoğraf çek")
                    }
                    OutlinedButton(onClick = photos.pickFromGallery, modifier = Modifier.weight(1f).height(52.dp)) {
                        Icon(Icons.Outlined.PhotoLibrary, null); Spacer(Modifier.size(8.dp)); Text("Galeriden")
                    }
                }
            }

            DisclaimerBanner()
            Text("Ölçüm geçmişi", style = MaterialTheme.typography.titleMedium)
            ModuleHistory(history, "mm", 2, ModuleType.CRACK.accent, onDelete = vm::delete)
            Spacer(Modifier.fillMaxWidth().height(24.dp))
        }
    }
}
