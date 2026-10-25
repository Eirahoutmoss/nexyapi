package com.eirahoutmoss.nexyapi.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapRegionDecoder
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.eirahoutmoss.nexyapi.AppContainer
import com.eirahoutmoss.nexyapi.core.analysis.CrackMeasure
import com.eirahoutmoss.nexyapi.core.analysis.Pt
import com.eirahoutmoss.nexyapi.core.model.ModuleType
import com.eirahoutmoss.nexyapi.data.MeasurementEntity
import com.eirahoutmoss.nexyapi.data.MeasurementQuality
import com.eirahoutmoss.nexyapi.diagnostics.DeviceInfo
import com.eirahoutmoss.nexyapi.media.ImageStore
import com.eirahoutmoss.nexyapi.report.ReferenceLabelPdf
import com.eirahoutmoss.nexyapi.sensors.RawDataWriter
import com.eirahoutmoss.nexyapi.ui.appContainer
import com.eirahoutmoss.nexyapi.ui.components.DisclaimerBanner
import com.eirahoutmoss.nexyapi.ui.components.QualityChip
import com.eirahoutmoss.nexyapi.ui.components.fmtNum
import com.eirahoutmoss.nexyapi.ui.components.formatDate
import com.eirahoutmoss.nexyapi.ui.theme.BigValueStyle
import com.eirahoutmoss.nexyapi.ui.theme.NexColors
import com.eirahoutmoss.nexyapi.ui.theme.accent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import kotlin.math.hypot

/** Fotoğraf üzerinde dokunulan noktalar: ilk 4'ü referans köşeleri, sonraki 2'si çatlak kenarları. */
class CrackMeasureViewModel(
    private val c: AppContainer,
    private val structureId: String,
    pendingRelative: String,
) : ViewModel() {

    val photo: File? = c.structures.resolve(pendingRelative)
    var preview by mutableStateOf<Bitmap?>(null); private set
    var imageW by mutableStateOf(0); private set
    var imageH by mutableStateOf(0); private set
    var loadError by mutableStateOf<String?>(null); private set

    val points = mutableStateListOf<Pt>()
    var selected by mutableStateOf<Int?>(null); private set
    var subject by mutableStateOf("")
    var subjects by mutableStateOf<List<String>>(emptyList()); private set
    var saving by mutableStateOf(false); private set
    var message by mutableStateOf<String?>(null)

    /** Ekrandaki 1 pikselin görüntüde kaç piksel olduğu. */
    var screenToImage by mutableStateOf(1.0)

    private var decoder: BitmapRegionDecoder? = null
    private val loupeCenter = MutableStateFlow<Pt?>(null)
    var loupe by mutableStateOf<Bitmap?>(null); private set
    var loupeAt by mutableStateOf<Pt?>(null); private set

    val history: StateFlow<List<MeasurementEntity>> = c.structures.observeModule(structureId, ModuleType.CRACK)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        viewModelScope.launch {
            val f = photo
            if (f == null || !f.exists()) { loadError = "Fotoğraf bulunamadı."; return@launch }
            val size = withContext(Dispatchers.IO) { c.images.sizeOf(f) }
            if (size == null) { loadError = "Fotoğraf okunamadı."; return@launch }
            imageW = size.first; imageH = size.second
            preview = c.images.loadPreview(f, 2048)
            decoder = withContext(Dispatchers.IO) { c.images.regionDecoder(f) }
            subjects = c.structures.subjects(structureId, ModuleType.CRACK)
        }
        viewModelScope.launch {
            loupeCenter.collectLatest { p ->
                val d = decoder
                if (p == null || d == null) { loupe = null; loupeAt = null; return@collectLatest }
                val bmp = withContext(Dispatchers.IO) { ImageStore.decodeRegion(d, p.x.toInt(), p.y.toInt(), LOUPE_PX) }
                loupe = bmp; loupeAt = p
            }
        }
    }

    val step: String
        get() = when {
            points.size < 4 -> "Referans etiketin köşelerine dokunun (${points.size}/4)"
            points.size < 6 -> "Çatlağın ${if (points.size == 4) "bir kenarına" else "karşı kenarına"} dokunun (${points.size - 4}/2)"
            else -> "Noktaları sürükleyerek ince ayar yapabilirsiniz"
        }

    val result: CrackMeasure.Outcome?
        get() = if (points.size == 6) {
            CrackMeasure.measure(points.subList(0, 4).toList(), ReferenceLabelPdf.SIDE_MM, points[4], points[5])
        } else null

    fun hit(screen: Offset, radiusScreenPx: Float): Int? {
        val p = toImage(screen)
        val r = radiusScreenPx * screenToImage
        return points.indices.minByOrNull { hypot(points[it].x - p.x, points[it].y - p.y) }
            ?.takeIf { hypot(points[it].x - p.x, points[it].y - p.y) <= r }
    }

    fun tap(screen: Offset) {
        if (points.size >= 6) return
        val p = clamp(toImage(screen))
        points.add(p)
        loupeCenter.value = p
    }

    fun beginDrag(i: Int) { selected = i; loupeCenter.value = points[i] }

    /** Parmak hareketinin üçte biri kadar taşır: büyüteçle piksel düzeyinde ince ayar. */
    fun dragBy(delta: Offset) {
        val i = selected ?: return
        val p = points[i]
        val np = clamp(Pt(p.x + delta.x * screenToImage * FINE, p.y + delta.y * screenToImage * FINE))
        points[i] = np
        loupeCenter.value = np
    }

    fun endDrag() { selected = null; loupeCenter.value = null }

    fun undo() { if (points.isNotEmpty()) points.removeAt(points.lastIndex); loupeCenter.value = null }
    fun reset() { points.clear(); loupeCenter.value = null }

    private fun toImage(o: Offset) = Pt(o.x * screenToImage, o.y * screenToImage)
    private fun clamp(p: Pt) = Pt(p.x.coerceIn(0.0, imageW - 1.0), p.y.coerceIn(0.0, imageH - 1.0))

    fun save(onSaved: () -> Unit) {
        val ok = (result as? CrackMeasure.Outcome.Ok)?.result ?: return
        val f = photo ?: return
        if (subject.isBlank()) { message = "Çatlağa bir ad verin; aynı çatlağı sonraki ölçümlerde bu adla izleyeceksiniz."; return }
        saving = true
        viewModelScope.launch {
            try {
                val id = UUID.randomUUID().toString()
                val target = File(c.structures.dataDir(structureId), "$id.v3.jpg")
                val sha = withContext(Dispatchers.IO) {
                    target.parentFile?.mkdirs()
                    if (!f.renameTo(target)) {
                        f.copyTo(target, overwrite = true); f.delete()
                    }
                    RawDataWriter.sha256(target)
                }
                val pts = JSONArray()
                points.forEach { pts.put(JSONArray().put(it.x).put(it.y)) }
                val params = JSONObject()
                    .put("referenceMm", ReferenceLabelPdf.SIDE_MM)
                    .put("points", pts)
                    .put("imageW", imageW).put("imageH", imageH)
                    .put("uncertaintyMm", ok.uncertaintyMm)
                    .put("referenceSidePx", ok.referenceSidePx)
                c.structures.saveMeasurement(
                    MeasurementEntity(
                        id = id,
                        structureId = structureId,
                        moduleCode = ModuleType.CRACK.code,
                        takenAt = System.currentTimeMillis(),
                        value = ok.widthMm,
                        unit = ModuleType.CRACK.unit,
                        summary = summaryOf(ok.widthMm, ok.uncertaintyMm),
                        quality = ok.quality.toStored(),
                        deviceModel = DeviceInfo.model,
                        deviceInfo = DeviceInfo.describe(c.appContext).toString(),
                        dataFile = c.structures.relative(target),
                        dataSha256 = sha,
                        note = ok.notes.joinToString(" "),
                        subject = subject.trim(),
                        params = params.toString(),
                    ),
                )
                onSaved()
            } catch (e: Exception) {
                message = "Kaydedilemedi: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                saving = false
            }
        }
    }

    /** Kaydetmeden çıkılırsa bekleyen fotoğrafı sil. */
    fun discard() {
        photo?.let { f -> viewModelScope.launch(Dispatchers.IO) { f.delete() } }
    }

    override fun onCleared() {
        decoder?.recycle()
        decoder = null
    }

    companion object {
        const val LOUPE_PX = 160
        const val FINE = 0.33

        fun summaryOf(widthMm: Double, unc: Double) = "${fmtNum(widthMm, 2)} mm ±${fmtNum(unc, 2)}"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CrackMeasureScreen(structureId: String, pendingRelative: String, onDone: () -> Unit) {
    val container = appContainer()
    val vm: CrackMeasureViewModel = viewModel(
        key = "v3m-$pendingRelative",
        factory = viewModelFactory { initializer { CrackMeasureViewModel(container, structureId, pendingRelative) } },
    )
    val snackbar = remember { SnackbarHostState() }
    var confirmLeave by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val hitRadius = with(density) { 36.dp.toPx() }
    val history by vm.history.collectAsStateWithLifecycle()
    LaunchedEffect(vm.message) { vm.message?.let { snackbar.showSnackbar(it); vm.message = null } }

    fun leave() { if (vm.points.isEmpty()) { vm.discard(); onDone() } else confirmLeave = true }
    androidx.activity.compose.BackHandler { leave() }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Çatlak ölçümü") },
                navigationIcon = { IconButton(onClick = { leave() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Geri") } },
                actions = {
                    IconButton(onClick = { vm.undo() }, enabled = vm.points.isNotEmpty()) {
                        Icon(Icons.AutoMirrored.Outlined.Undo, "Son noktayı geri al")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val bmp = vm.preview
            val loadError = vm.loadError
            if (loadError != null) {
                Text(loadError, color = MaterialTheme.colorScheme.error)
            } else if (bmp == null || vm.imageW == 0) {
                Box(Modifier.fillMaxWidth().height(240.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else {
                MeasureContent(vm, bmp, hitRadius, history, onDone)
            }
        }
    }


    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text("Ölçüm kaydedilmedi") },
            text = { Text("Çıkarsanız işaretlediğiniz noktalar ve bu fotoğraf silinir.") },
            confirmButton = { TextButton(onClick = { confirmLeave = false; vm.discard(); onDone() }) { Text("Çık") } },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text("Devam et") } },
        )
    }
}

@Composable
private fun MeasureContent(
    vm: CrackMeasureViewModel,
    bmp: Bitmap,
    hitRadius: Float,
    history: List<MeasurementEntity>,
    onDone: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(vm.step, style = MaterialTheme.typography.titleSmall)

            Box(
                Modifier.fillMaxWidth()
                    .aspectRatio(vm.imageW.toFloat() / vm.imageH)
                    .clip(MaterialTheme.shapes.medium)
                    .onSizeChanged { if (it.width > 0) vm.screenToImage = vm.imageW.toDouble() / it.width }
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val hit = vm.hit(down.position, hitRadius)
                            if (hit != null) {
                                down.consume()
                                vm.beginDrag(hit)
                                drag(down.id) { ch -> vm.dragBy(ch.positionChange()); ch.consume() }
                                vm.endDrag()
                            } else {
                                // Kaydırma başlarsa (parmak kaydı) bu dokunuş nokta eklemez.
                                val up = waitForUpOrCancellation()
                                if (up != null) vm.tap(up.position)
                            }
                        }
                    },
            ) {
                Image(bmp.asImageBitmap(), contentDescription = "Çatlak fotoğrafı", contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
                PointsOverlay(vm)
                Loupe(vm)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.undo() }, enabled = vm.points.isNotEmpty()) { Text("Geri al") }
                OutlinedButton(onClick = { vm.reset() }, enabled = vm.points.isNotEmpty()) { Text("Baştan") }
            }

            when (val r = vm.result) {
                null -> Unit
                is CrackMeasure.Outcome.Error -> Text(r.message, color = MaterialTheme.colorScheme.error)
                is CrackMeasure.Outcome.Ok -> {
                    val res = r.result
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Çatlak genişliği", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("${fmtNum(res.widthMm, 2)} mm", style = BigValueStyle, color = ModuleType.CRACK.accent)
                            Text("Tahmini belirsizlik: ±${fmtNum(res.uncertaintyMm, 2)} mm", style = MaterialTheme.typography.bodySmall)
                            QualityChip(res.quality.toStored())
                            res.notes.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                            val prev = history.firstOrNull {
                                it.value != null && it.quality != MeasurementQuality.UNRELIABLE && it.subject == vm.subject.trim()
                            }
                            if (prev != null) {
                                val d = res.widthMm - prev.value!!
                                Text(
                                    "Bu çatlağın önceki ölçümü: ${fmtNum(prev.value!!, 2)} mm (${formatDate(prev.takenAt)}). " +
                                        "Fark: ${if (d >= 0) "+" else ""}${fmtNum(d, 2)} mm. Fark belirsizlik payından küçükse anlamlı değildir.",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                    OutlinedTextField(
                        value = vm.subject,
                        onValueChange = { vm.subject = it.take(60) },
                        label = { Text("Çatlağın adı *") },
                        placeholder = { Text("Örn. Salon kuzey duvar, pencere altı") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (vm.subjects.isNotEmpty()) {
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            vm.subjects.forEach { s -> AssistChip(onClick = { vm.subject = s }, label = { Text(s) }) }
                        }
                    }
                    Button(
                        onClick = { vm.save(onDone) },
                        enabled = !vm.saving,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                    ) { Text(if (vm.saving) "Kaydediliyor…" else "Kaydet") }
                }
            }
            DisclaimerBanner()
    }
}

private val CornerColor = NexColors.Amber
private val EdgeColor = NexColors.BlueLight

@Composable
private fun PointsOverlay(vm: CrackMeasureViewModel) {
    val pts = vm.points.toList()
    val sel = vm.selected
    Canvas(Modifier.fillMaxSize()) {
        val k = (1.0 / vm.screenToImage).toFloat()
        fun o(p: Pt) = Offset(p.x.toFloat() * k, p.y.toFloat() * k)
        val corners = pts.take(4)
        if (corners.size >= 2) {
            val ordered = if (corners.size == 4) CrackMeasure.orderCorners(corners) else corners
            val path = Path().apply {
                moveTo(o(ordered[0]).x, o(ordered[0]).y)
                ordered.drop(1).forEach { lineTo(o(it).x, o(it).y) }
                if (ordered.size == 4) close()
            }
            drawPath(path, CornerColor, style = Stroke(width = 2.5f))
        }
        if (pts.size == 6) drawLine(EdgeColor, o(pts[4]), o(pts[5]), strokeWidth = 3f)
        pts.forEachIndexed { i, p ->
            val col = if (i < 4) CornerColor else EdgeColor
            val r = if (i == sel) 16f else 11f
            drawCircle(Color.Black.copy(alpha = 0.45f), radius = r + 3f, center = o(p))
            drawCircle(col, radius = r, center = o(p), style = Stroke(width = 3f))
            drawCircle(col, radius = 2.5f, center = o(p))
        }
    }
}

/** Seçili noktanın çevresini tam çözünürlükte büyütür; parmağın altında kalanı gösterir. */
@Composable
private fun Loupe(vm: CrackMeasureViewModel) {
    val bmp = vm.loupe ?: return
    val at = vm.loupeAt ?: return
    val onLeft = at.x > vm.imageW / 2.0 // nokta sağdaysa büyüteç solda
    Box(Modifier.fillMaxSize().padding(8.dp), contentAlignment = if (onLeft) Alignment.TopStart else Alignment.TopEnd) {
        Box(
            Modifier.size(150.dp).clip(CircleShape).border(3.dp, Color.White, CircleShape),
        ) {
            // Bölge [at] etrafında LOUPE_PX karedir; kenarda kırpılmışsa merkez kayar.
            val left = (at.x - CrackMeasureViewModel.LOUPE_PX / 2).coerceAtLeast(0.0)
            val top = (at.y - CrackMeasureViewModel.LOUPE_PX / 2).coerceAtLeast(0.0)
            Image(bmp.asImageBitmap(), null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
            Canvas(Modifier.fillMaxSize()) {
                val sx = size.width / bmp.width; val sy = size.height / bmp.height
                val cx = ((at.x - left) * sx).toFloat(); val cy = ((at.y - top) * sy).toFloat()
                drawLine(Color.Red, Offset(cx - 18f, cy), Offset(cx + 18f, cy), strokeWidth = 2f)
                drawLine(Color.Red, Offset(cx, cy - 18f), Offset(cx, cy + 18f), strokeWidth = 2f)
            }
        }
    }
}
