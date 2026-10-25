package com.eirahoutmoss.nexyapi.ui.screens

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.outlined.IosShare
import com.eirahoutmoss.nexyapi.backup.BackupService
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.outlined.AddAPhoto
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import com.eirahoutmoss.nexyapi.ui.components.FileImage
import com.eirahoutmoss.nexyapi.ui.components.rememberPhotoSource
import com.eirahoutmoss.nexyapi.ui.theme.accent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.eirahoutmoss.nexyapi.AppContainer
import com.eirahoutmoss.nexyapi.core.integrity.Sha256
import com.eirahoutmoss.nexyapi.core.model.ModuleType
import com.eirahoutmoss.nexyapi.data.ReportEntity
import com.eirahoutmoss.nexyapi.data.StructureSummary
import com.eirahoutmoss.nexyapi.report.GeneratedReport
import com.eirahoutmoss.nexyapi.ui.appContainer
import com.eirahoutmoss.nexyapi.ui.components.DisclaimerBanner
import com.eirahoutmoss.nexyapi.ui.components.formatDate
import com.eirahoutmoss.nexyapi.ui.components.formatDateTime
import com.eirahoutmoss.nexyapi.ui.components.icon
import com.eirahoutmoss.nexyapi.ui.theme.MeasurementValueStyle
import com.eirahoutmoss.nexyapi.ui.theme.NexColors
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

/** Yükleniyor / bulundu / silinmiş ya da yok. */
sealed interface DetailState {
    data object Loading : DetailState
    data class Ready(val summary: StructureSummary) : DetailState
    data object Missing : DetailState
}

class StructureDetailViewModel(private val c: AppContainer, private val id: String) : ViewModel() {
    val state: StateFlow<DetailState> = c.structures.observeSummary(id)
        .map { if (it == null) DetailState.Missing else DetailState.Ready(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DetailState.Loading)

    val reports: StateFlow<List<ReportEntity>> = c.structures.observeReports(id)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    var generating by mutableStateOf(false); private set
    var lastReport by mutableStateOf<GeneratedReport?>(null); private set

    val reportService get() = c.reports

    fun reportFile(r: ReportEntity): File = File(c.structures.reportsDir(id), r.fileName)

    suspend fun generate(): Result<GeneratedReport> {
        if (generating) return Result.failure(IllegalStateException("Rapor zaten hazırlanıyor."))
        generating = true
        return try {
            Result.success(c.reports.create(id)).also { lastReport = it.getOrNull() }
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            generating = false
        }
    }

    fun delete(onDone: () -> Unit) {
        viewModelScope.launch {
            c.structures.delete(id)
            onDone()
        }
    }

    var photoBusy by mutableStateOf(false); private set

    fun photoFile(rel: String?): File? = rel?.let { c.structures.resolve(it) }

    /** Kapak fotoğrafını değiştirir. Hata mesajı döner; başarılıysa null. */
    suspend fun setPhoto(uri: android.net.Uri): String? {
        photoBusy = true
        return try {
            val f = File(c.structures.photosDir(), "${id.filter { it.isLetterOrDigit() || it == '-' }}_${System.currentTimeMillis()}.jpg")
            c.images.importImage(uri, f, maxDimension = 1600, quality = 88)
            if (uri.scheme == "file") uri.path?.let { File(it).delete() }
            c.structures.setPhoto(id, c.structures.relative(f))
            null
        } catch (e: Exception) {
            "Fotoğraf eklenemedi: ${e.message ?: e.javaClass.simpleName}"
        } finally {
            photoBusy = false
        }
    }

    fun removePhoto() {
        viewModelScope.launch { c.structures.setPhoto(id, null) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StructureDetailScreen(
    structureId: String,
    onEdit: () -> Unit,
    onOpenModule: (ModuleType) -> Unit,
    onDeleted: () -> Unit,
    onBack: () -> Unit,
) {
    val container = appContainer()
    val vm: StructureDetailViewModel = viewModel(
        key = "detail-$structureId",
        factory = viewModelFactory { initializer { StructureDetailViewModel(container, structureId) } },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val reports by vm.reports.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var confirmDelete by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var photoMenu by remember { mutableStateOf(false) }
    // Tek yapıyı dışa aktarma (ör. büroya ya da meslektaşa göndermek için)
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(BackupService.MIME)) { uri ->
        if (uri != null) scope.launch {
            try {
                val r = container.backup.export(uri, structureId)
                snackbar.showSnackbar("Dışa aktarıldı: ${r.measurements} ölçüm, ${r.files} dosya.")
            } catch (e: Exception) {
                snackbar.showSnackbar("Dışa aktarılamadı: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }
    val photos = rememberPhotoSource(
        onPicked = { uri -> scope.launch { vm.setPhoto(uri)?.let { snackbar.showSnackbar(it) } } },
        onError = { msg -> scope.launch { snackbar.showSnackbar(msg) } },
    )

    fun open(file: File) {
        if (!file.exists()) {
            scope.launch { snackbar.showSnackbar("Rapor dosyası bulunamadı.") }
            return
        }
        try {
            context.startActivity(vm.reportService.viewIntent(file))
        } catch (e: ActivityNotFoundException) {
            // PDF görüntüleyici yoksa paylaşma menüsüne düş.
            context.startActivity(vm.reportService.shareIntent(file))
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    val s = (state as? DetailState.Ready)?.summary?.structure
                    Text(s?.name ?: "Yapı Karnesi", maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Geri") } },
                actions = {
                    if (state is DetailState.Ready) {
                        IconButton(onClick = {
                            val name = (state as? DetailState.Ready)?.summary?.structure?.name.orEmpty()
                                .replace(Regex("[^\\p{L}\\p{N}]+"), "_").trim('_').take(40).ifEmpty { "yapi" }
                            exporter.launch("NexYapi_$name.nexyapi")
                        }) { Icon(Icons.Outlined.IosShare, "Bu yapıyı dışa aktar") }
                        IconButton(onClick = onEdit) { Icon(Icons.Outlined.Edit, "Düzenle") }
                        IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Outlined.Delete, "Sil") }
                    }
                },
            )
        },
    ) { padding ->
        when (val s = state) {
            DetailState.Loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            DetailState.Missing -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(if (deleting) "Silindi." else "Bu yapı bulunamadı.")
            }
            is DetailState.Ready -> Column(
                Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                HeaderPhoto(
                    summary = s.summary,
                    file = vm.photoFile(s.summary.structure.photoFile),
                    busy = vm.photoBusy,
                    onClick = { photoMenu = true },
                )
                ModuleGrid(s.summary) { m ->
                    if (m in AVAILABLE_MODULES) {
                        onOpenModule(m)
                    } else {
                        scope.launch {
                            val extra = if (m.requiresAccessory) " Bu modül harici termal sensör gerektirir." else ""
                            snackbar.showSnackbar("${m.code} · ${m.title} ölçümü sonraki aşamada eklenecek.$extra")
                        }
                    }
                }
                Button(
                    onClick = {
                        scope.launch {
                            vm.generate()
                                .onSuccess { open(it.file) }
                                .onFailure { snackbar.showSnackbar("Rapor oluşturulamadı: ${it.message ?: it.javaClass.simpleName}") }
                        }
                    },
                    enabled = !vm.generating,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = NexColors.Blue, contentColor = Color.White),
                ) {
                    Icon(Icons.Outlined.PictureAsPdf, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text(if (vm.generating) "Rapor hazırlanıyor…" else "PDF Rapor Oluştur")
                }
                DisclaimerBanner()
                if (reports.isNotEmpty()) ReportList(reports, onOpen = { open(vm.reportFile(it)) }, onShare = { r ->
                    val f = vm.reportFile(r)
                    if (f.exists()) context.startActivity(vm.reportService.shareIntent(f))
                    else scope.launch { snackbar.showSnackbar("Rapor dosyası bulunamadı.") }
                })
            }
        }
    }

    if (photoMenu) {
        val hasPhoto = (state as? DetailState.Ready)?.summary?.structure?.photoFile != null
        AlertDialog(
            onDismissRequest = { photoMenu = false },
            title = { Text("Yapı fotoğrafı") },
            text = {
                Column {
                    TextButton(onClick = { photoMenu = false; photos.takePhoto() }) { Text("Kamerayla çek") }
                    TextButton(onClick = { photoMenu = false; photos.pickFromGallery() }) { Text("Galeriden seç") }
                    if (hasPhoto) {
                        TextButton(onClick = { photoMenu = false; vm.removePhoto() }) {
                            Text("Fotoğrafı kaldır", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { photoMenu = false }) { Text("Vazgeç") } },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Yapı silinsin mi?") },
            text = { Text("Bu yapıya ait bütün ölçümler ve raporlar da silinir. Bu işlem geri alınamaz.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    deleting = true
                    vm.delete(onDeleted)
                }) { Text("Sil", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Vazgeç") } },
        )
    }
}

/** Şu an ölçüm ekranı olan modüller; diğerleri sonraki aşamalarda. */
val AVAILABLE_MODULES = setOf(ModuleType.VIBRATION, ModuleType.CRACK, ModuleType.MOTION, ModuleType.ACOUSTICS, ModuleType.BEFORE_DRILL)

/** Konseptteki gibi: fotoğraf (yoksa lacivert zemin) üzerinde yapı adı, adres ve kat bilgisi. */
@Composable
private fun HeaderPhoto(summary: StructureSummary, file: File?, busy: Boolean, onClick: () -> Unit) {
    val st = summary.structure
    Box(
        Modifier.fillMaxWidth().height(190.dp).clip(RoundedCornerShape(18.dp))
            .background(Brush.linearGradient(listOf(NexColors.Navy, NexColors.NavyDeep)))
            .clickable(onClick = onClick),
    ) {
        FileImage(file = file, maxDimension = 1600, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.padding(bottom = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Outlined.AddAPhoto, contentDescription = null, tint = Color.White.copy(alpha = 0.8f))
                Text("Fotoğraf ekle", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.8f))
            }
        }
        // Okunurluk için alttan karartma
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0.35f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.72f)),
            ),
        )
        Column(Modifier.align(Alignment.BottomStart).padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(st.name, style = MaterialTheme.typography.headlineSmall, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (st.address.isNotBlank()) {
                Text(st.address, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.85f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            val parts = listOfNotNull(
                st.floor.takeIf { it.isNotBlank() }?.let { "Kat $it" },
                st.buildYear?.let { "Yapım yılı: $it" },
            )
            if (parts.isNotEmpty()) Text(parts.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.75f))
        }
        if (busy) CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
        if (file != null) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = Color.Black.copy(alpha = 0.45f),
                modifier = Modifier.align(Alignment.TopEnd).padding(10.dp),
            ) {
                Icon(Icons.Outlined.PhotoCamera, contentDescription = "Fotoğrafı değiştir", tint = Color.White, modifier = Modifier.padding(6.dp).size(18.dp))
            }
        }
    }
}

@Composable
private fun ModuleGrid(s: StructureSummary, onClick: (ModuleType) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ModuleType.entries.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { m -> ModuleTile(m, s.latest[m], Modifier.weight(1f)) { onClick(m) } }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun ModuleTile(
    m: ModuleType,
    latest: com.eirahoutmoss.nexyapi.data.ModuleLatest?,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val available = m in AVAILABLE_MODULES
    Card(
        modifier = modifier.height(128.dp).clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(
            Modifier.padding(10.dp).fillMaxSize().alpha(if (available) 1f else 0.5f),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Surface(shape = RoundedCornerShape(10.dp), color = m.accent.copy(alpha = 0.16f)) {
                Icon(m.icon, contentDescription = null, tint = m.accent, modifier = Modifier.padding(7.dp).size(22.dp))
            }
            Text(
                m.title, style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center, minLines = 2,
            )
            if (latest != null) {
                Text(latest.summary.substringBefore(" ±"), style = MaterialTheme.typography.titleSmall, color = m.accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(formatDate(latest.takenAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            } else {
                Text(
                    when {
                        !available -> "Yakında"
                        m.requiresAccessory -> "Ek cihaz"
                        else -> "Ölçülmedi"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun ReportList(reports: List<ReportEntity>, onOpen: (ReportEntity) -> Unit, onShare: (ReportEntity) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Önceki raporlar", style = MaterialTheme.typography.titleSmall)
        reports.take(10).forEach { r ->
            Card(Modifier.fillMaxWidth().clickable { onOpen(r) }) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(formatDateTime(r.generatedAt), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Parmak izi: " + Sha256.grouped(r.fingerprint).take(19) + " …",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { onShare(r) }) { Icon(Icons.Outlined.Share, "Paylaş") }
                }
            }
        }
    }
}
