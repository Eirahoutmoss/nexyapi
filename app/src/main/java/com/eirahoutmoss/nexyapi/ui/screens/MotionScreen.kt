package com.eirahoutmoss.nexyapi.ui.screens

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.eirahoutmoss.nexyapi.AppContainer
import com.eirahoutmoss.nexyapi.core.analysis.MotionAnalyzer
import com.eirahoutmoss.nexyapi.core.analysis.MotionResult
import com.eirahoutmoss.nexyapi.core.analysis.Quality
import com.eirahoutmoss.nexyapi.core.model.ModuleType
import com.eirahoutmoss.nexyapi.data.MeasurementEntity
import com.eirahoutmoss.nexyapi.data.MeasurementQuality
import com.eirahoutmoss.nexyapi.diagnostics.DeviceInfo
import com.eirahoutmoss.nexyapi.media.DecodedVideo
import com.eirahoutmoss.nexyapi.media.VideoFrames
import com.eirahoutmoss.nexyapi.sensors.RawDataWriter
import com.eirahoutmoss.nexyapi.ui.appContainer
import com.eirahoutmoss.nexyapi.ui.components.DisclaimerBanner
import com.eirahoutmoss.nexyapi.ui.components.ModuleHistory
import com.eirahoutmoss.nexyapi.ui.components.QualityChip
import com.eirahoutmoss.nexyapi.ui.components.SpectrumChart
import com.eirahoutmoss.nexyapi.ui.components.fmtNum
import com.eirahoutmoss.nexyapi.ui.components.formatDate
import com.eirahoutmoss.nexyapi.ui.theme.BigValueStyle
import com.eirahoutmoss.nexyapi.ui.theme.accent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID

/** Sistem kamerasıyla en fazla [maxSeconds] saniyelik video; sonuç [EXTRA_OUTPUT] dosyasına yazılır. */
private class CaptureVideoLimited(private val maxSeconds: Int) : ActivityResultContract<Uri, Boolean>() {
    override fun createIntent(context: Context, input: Uri): Intent =
        Intent(MediaStore.ACTION_VIDEO_CAPTURE)
            .putExtra(MediaStore.EXTRA_OUTPUT, input)
            .putExtra(MediaStore.EXTRA_DURATION_LIMIT, maxSeconds)
            .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)

    override fun parseResult(resultCode: Int, intent: Intent?): Boolean = resultCode == android.app.Activity.RESULT_OK
}

sealed interface MotPhase {
    data object Setup : MotPhase
    data class Decoding(val fraction: Float) : MotPhase
    data object Analyzing : MotPhase
    data class Result(val video: DecodedVideo, val result: MotionResult, val heat: Bitmap, val startedAt: Long, val warnings: List<String>) : MotPhase
    data object Saving : MotPhase
    data class Error(val message: String) : MotPhase
}

class MotionViewModel(private val c: AppContainer, private val structureId: String) : ViewModel() {
    private val _phase = MutableStateFlow<MotPhase>(MotPhase.Setup)
    val phase: StateFlow<MotPhase> = _phase.asStateFlow()
    var subject by mutableStateOf("")
    var subjects by mutableStateOf<List<String>>(emptyList()); private set
    var message by mutableStateOf<String?>(null)
    private var job: Job? = null
    private var captureFile: File? = null

    val history: StateFlow<List<MeasurementEntity>> = c.structures.observeModule(structureId, ModuleType.MOTION)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val supported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P

    init {
        viewModelScope.launch { subjects = c.structures.subjects(structureId, ModuleType.MOTION) }
    }

    fun newCaptureUri(): Uri {
        val dir = File(c.appContext.cacheDir, "capture").apply { mkdirs() }
        val f = File(dir, "vid_${UUID.randomUUID()}.mp4")
        captureFile = f
        return FileProvider.getUriForFile(c.appContext, "${c.appContext.packageName}.fileprovider", f)
    }

    fun onCaptured(ok: Boolean) {
        val f = captureFile
        if (ok && f != null && f.length() > 0) process(f.toUri()) else f?.delete()
    }

    fun process(uri: Uri) {
        if (!supported) { _phase.value = MotPhase.Error("Bu modül Android 9 ve üstünü gerektirir."); return }
        if (job?.isActive == true) return
        job = viewModelScope.launch {
            try {
                val startedAt = System.currentTimeMillis()
                _phase.value = MotPhase.Decoding(0f)
                val video = VideoFrames.decode(c.appContext, uri) { f -> _phase.value = MotPhase.Decoding(f) }
                _phase.value = MotPhase.Analyzing
                val result = withContext(Dispatchers.Default) { MotionAnalyzer.analyze(video.gray, magnify = true) }
                val heat = withContext(Dispatchers.Default) { heatmap(result) }
                val warnings = ArrayList<String>()
                if (video.frameJitter > 0.15) warnings += "Video değişken kare hızıyla kaydedilmiş (düşük ışık?). Frekans değeri bu yüzden daha az kesin."
                _phase.value = MotPhase.Result(video, result, heat, startedAt, warnings)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: OutOfMemoryError) {
                _phase.value = MotPhase.Error("Video bu cihaz için çok büyük. Daha kısa bir video deneyin.")
            } catch (e: Exception) {
                _phase.value = MotPhase.Error("Video işlenemedi: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                captureFile?.delete(); captureFile = null
            }
        }
    }

    fun discard() { job?.cancel(); _phase.value = MotPhase.Setup }

    fun save() {
        val p = _phase.value as? MotPhase.Result ?: return
        _phase.value = MotPhase.Saving
        viewModelScope.launch {
            try {
                val id = UUID.randomUUID().toString()
                val file = File(c.structures.dataDir(structureId), "$id.v11.png")
                val sha = withContext(Dispatchers.IO) {
                    val out = composite(p.video.preview, p.heat)
                    file.parentFile?.mkdirs()
                    val tmp = File(file.parentFile, file.name + ".part")
                    tmp.outputStream().use { if (!out.compress(Bitmap.CompressFormat.PNG, 100, it)) throw IOException("Görüntü kaydedilemedi.") }
                    out.recycle()
                    file.delete()
                    if (!tmp.renameTo(file)) throw IOException("Görüntü kaydedilemedi.")
                    RawDataWriter.sha256(file)
                }
                val r = p.result
                val params = JSONObject()
                    .put("fps", p.video.gray.fps)
                    .put("frames", p.video.gray.frames.size)
                    .put("analysisSize", "${p.video.gray.width}x${p.video.gray.height}")
                    .put("frameJitter", p.video.frameJitter)
                    .put("prominence", r.prominence)
                    .put("videoSha256", p.video.videoSha256)
                val quality = if (p.warnings.isNotEmpty() && r.quality == Quality.OK) Quality.LOW else r.quality
                c.structures.saveMeasurement(
                    MeasurementEntity(
                        id = id, structureId = structureId, moduleCode = ModuleType.MOTION.code,
                        takenAt = p.startedAt, value = r.dominantHz, unit = ModuleType.MOTION.unit,
                        summary = summaryOf(r), quality = quality.toStored(),
                        deviceModel = DeviceInfo.model, deviceInfo = DeviceInfo.describe(c.appContext).toString(),
                        dataFile = c.structures.relative(file), dataSha256 = sha,
                        note = (r.notes + p.warnings).joinToString(" "),
                        subject = subject.trim().ifEmpty { null }, params = params.toString(),
                    ),
                )
                subjects = c.structures.subjects(structureId, ModuleType.MOTION)
                _phase.value = MotPhase.Setup
                message = "Ölçüm kaydedildi."
            } catch (e: Exception) {
                _phase.value = p
                message = "Kaydedilemedi: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    fun delete(m: MeasurementEntity) { viewModelScope.launch { c.structures.deleteMeasurement(m.id) } }

    companion object {
        fun summaryOf(r: MotionResult) = r.dominantHz?.let { "${fmtNum(it, 2)} Hz" } ?: "Belirgin titreşim yok"

        /** Hareket haritasını şeffaf renkli bir görüntüye çevirir (mavi → sarı → kırmızı). */
        fun heatmap(r: MotionResult): Bitmap {
            val px = IntArray(r.width * r.height) { i ->
                val v = r.motionMap[i]
                if (v < 0.12f) 0 else {
                    val a = (90 + 150 * v).toInt().coerceIn(0, 255)
                    val (cr, cg, cb) = when {
                        v < 0.5f -> Triple((v / 0.5f * 255).toInt(), 200, 255 - (v / 0.5f * 255).toInt())
                        else -> Triple(255, (200 * (1 - (v - 0.5f) / 0.5f)).toInt(), 0)
                    }
                    (a shl 24) or (cr shl 16) or (cg shl 8) or cb
                }
            }
            return Bitmap.createBitmap(px, r.width, r.height, Bitmap.Config.ARGB_8888)
        }

        fun composite(preview: Bitmap, heat: Bitmap): Bitmap {
            val out = preview.copy(Bitmap.Config.ARGB_8888, true)
            Canvas(out).drawBitmap(heat, null, Rect(0, 0, out.width, out.height), Paint(Paint.FILTER_BITMAP_FLAG))
            return out
        }

        fun grayToBitmap(frame: ByteArray, w: Int, h: Int): Bitmap {
            val px = IntArray(w * h) { val g = frame[it].toInt() and 0xFF; (0xFF shl 24) or (g shl 16) or (g shl 8) or g }
            return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MotionScreen(structureId: String, onBack: () -> Unit) {
    val container = appContainer()
    val vm: MotionViewModel = viewModel(
        key = "v11-$structureId",
        factory = viewModelFactory { initializer { MotionViewModel(container, structureId) } },
    )
    val phase by vm.phase.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(vm.message) { vm.message?.let { snackbar.showSnackbar(it); vm.message = null } }

    val camera = rememberLauncherForActivityResult(CaptureVideoLimited(15)) { ok -> vm.onCaptured(ok) }
    val context = androidx.compose.ui.platform.LocalContext.current
    fun launchCamera() {
        try { camera.launch(vm.newCaptureUri()) } catch (e: ActivityNotFoundException) { vm.message = "Kamera uygulaması bulunamadı." }
        catch (e: SecurityException) { vm.message = "Kamera izni gerekiyor." }
    }
    // CAMERA izni bildirildiği için (AR modu) sistem kamerası ancak izin verildikten sonra açılabilir.
    val camPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) launchCamera() else vm.message = "Video çekmek için kamera izni gerekiyor. Galeriden de seçebilirsiniz."
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) vm.process(uri) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Görünmez Hareket") },
                navigationIcon = { IconButton(onClick = { vm.discard(); onBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Geri") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            when (val p = phase) {
                MotPhase.Setup -> {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Nasıl ölçülür?", style = MaterialTheme.typography.titleSmall)
                            listOf(
                                "Telefonu bir tripoda ya da sabit bir yere dayayın; elde tutulan çekim sonucu bozar.",
                                "Titreşimini görmek istediğiniz elemanı (korkuluk, kiriş, boru, makine) kadrajın büyük kısmına alın.",
                                "10–15 saniye kımıldamadan çekin. Gün ışığı en iyisidir; bazı LED lambalar titreme yapar.",
                                "Video çözünürlüğü yüksek olmak zorunda değil; sabitlik çok daha önemlidir.",
                            ).forEachIndexed { i, t -> Text("${i + 1}. $t", style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                    if (!vm.supported) {
                        Text("Bu modül Android 9 ve üstünü gerektirir.", color = MaterialTheme.colorScheme.error)
                    }
                    OutlinedTextField(
                        value = vm.subject, onValueChange = { vm.subject = it.take(60) },
                        label = { Text("Ölçülen eleman") }, placeholder = { Text("Örn. Balkon korkuluğu") },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                    if (vm.subjects.isNotEmpty()) {
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            vm.subjects.forEach { s -> AssistChip(onClick = { vm.subject = s }, label = { Text(s) }) }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = {
                                if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) ==
                                    android.content.pm.PackageManager.PERMISSION_GRANTED) launchCamera()
                                else camPermission.launch(android.Manifest.permission.CAMERA)
                            },
                            enabled = vm.supported,
                            modifier = Modifier.weight(1f).height(52.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                        ) { Icon(Icons.Outlined.Videocam, null); Spacer(Modifier.size(8.dp)); Text("Video çek") }
                        OutlinedButton(
                            onClick = {
                                try { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) } catch (e: ActivityNotFoundException) { vm.message = "Galeri açılamadı." }
                            },
                            enabled = vm.supported,
                            modifier = Modifier.weight(1f).height(52.dp),
                        ) { Icon(Icons.Outlined.PhotoLibrary, null); Spacer(Modifier.size(8.dp)); Text("Galeriden") }
                    }
                }
                is MotPhase.Decoding -> Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Video kareleri okunuyor…", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.size(12.dp))
                    LinearProgressIndicator(progress = { p.fraction }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.size(16.dp))
                    OutlinedButton(onClick = { vm.discard() }) { Text("İptal") }
                }
                MotPhase.Analyzing -> Box(Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) { CircularProgressIndicator(); Spacer(Modifier.size(12.dp)); Text("Hareket analiz ediliyor…") }
                }
                is MotPhase.Result -> MotionResultPanel(vm, p)
                MotPhase.Saving -> Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                is MotPhase.Error -> Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(p.message, color = MaterialTheme.colorScheme.error)
                        Button(onClick = { vm.discard() }) { Text("Tamam") }
                    }
                }
            }
            if (phase is MotPhase.Setup || phase is MotPhase.Error) {
                DisclaimerBanner()
                Text("Ölçüm geçmişi", style = MaterialTheme.typography.titleMedium)
                ModuleHistory(history, "Hz", 2, ModuleType.MOTION.accent, onDelete = vm::delete)
            }
        }
    }
}

@Composable
private fun MotionResultPanel(vm: MotionViewModel, p: MotPhase.Result) {
    val r = p.result
    val accent = ModuleType.MOTION.accent
    var view by rememberSaveable { mutableIntStateOf(0) } // 0 harita, 1 normal, 2 büyütülmüş
    val gray = p.video.gray
    val aspect = gray.width.toFloat() / gray.height

    Box(Modifier.fillMaxWidth().aspectRatio(aspect).clip(MaterialTheme.shapes.medium)) {
        when (view) {
            0 -> {
                Image(p.video.preview.asImageBitmap(), null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
                Image(p.heat.asImageBitmap(), "Hareket haritası", contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
            }
            else -> {
                val frames = if (view == 2) r.magnified ?: gray.frames else gray.frames
                var idx by remember(view) { mutableIntStateOf(0) }
                LaunchedEffect(view, frames) {
                    val step = (1000 / gray.fps).toLong().coerceAtLeast(16)
                    while (true) { delay(step); idx = (idx + 1) % frames.size }
                }
                val bmp = remember(idx, frames) { MotionViewModel.grayToBitmap(frames[idx], gray.width, gray.height) }
                Image(
                    bmp.asImageBitmap(), null, contentScale = ContentScale.FillBounds,
                    filterQuality = FilterQuality.Low, modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
    val labels = listOf("Hareket haritası", "Normal", "Büyütülmüş")
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        labels.forEachIndexed { i, l ->
            SegmentedButton(selected = view == i, onClick = { view = i }, shape = SegmentedButtonDefaults.itemShape(i, labels.size)) { Text(l) }
        }
    }
    if (view == 2) {
        Text(
            "Büyütülmüş görüntü düşük çözünürlüktedir; yalnızca baskın frekanstaki hareket 25 kat büyütülür.",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Baskın titreşim frekansı", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(MotionViewModel.summaryOf(r), style = BigValueStyle, color = accent)
            QualityChip((if (p.warnings.isNotEmpty() && r.quality == Quality.OK) Quality.LOW else r.quality).toStored())
            (r.notes + p.warnings).forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
            if (r.spectrumFreqs.isNotEmpty()) SpectrumChart(r.spectrumFreqs, r.spectrumPower, r.dominantHz, accent)
            val prev = vm.history.value.firstOrNull { it.value != null && it.quality != MeasurementQuality.UNRELIABLE && it.subject == vm.subject.trim() }
            if (prev != null && r.dominantHz != null) {
                Text("Aynı elemanın önceki ölçümü: ${fmtNum(prev.value!!, 2)} Hz (${formatDate(prev.takenAt)}).", style = MaterialTheme.typography.bodySmall)
            }
            Text(
                "Kare hızı: ${fmtNum(gray.fps, 1)} kare/sn · ${gray.frames.size} kare · Ölçülebilen en yüksek frekans: ${fmtNum(gray.fps / 2, 1)} Hz",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedButton(onClick = { vm.discard() }, modifier = Modifier.weight(1f)) { Text("Sil, tekrarla") }
        Button(onClick = { vm.save() }, modifier = Modifier.weight(1f)) { Text("Kaydet") }
    }
    DisclaimerBanner()
}
