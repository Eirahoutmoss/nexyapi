package com.eirahoutmoss.nexyapi.ui.screens

import android.graphics.Bitmap
import android.graphics.DashPathEffect
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.eirahoutmoss.nexyapi.AppContainer
import com.eirahoutmoss.nexyapi.core.analysis.MagneticDetector
import com.eirahoutmoss.nexyapi.core.analysis.Pt
import com.eirahoutmoss.nexyapi.core.model.ModuleType
import com.eirahoutmoss.nexyapi.data.MeasurementEntity
import com.eirahoutmoss.nexyapi.data.MeasurementQuality
import com.eirahoutmoss.nexyapi.diagnostics.DeviceInfo
import com.eirahoutmoss.nexyapi.sensors.MagRecorder
import com.eirahoutmoss.nexyapi.sensors.RawDataWriter
import com.eirahoutmoss.nexyapi.ui.appContainer
import com.eirahoutmoss.nexyapi.ui.components.DisclaimerBanner
import com.eirahoutmoss.nexyapi.ui.components.ModuleHistory
import com.eirahoutmoss.nexyapi.ui.components.fmtNum
import com.eirahoutmoss.nexyapi.ui.components.rememberPhotoSource
import com.eirahoutmoss.nexyapi.ui.theme.BigValueStyle
import com.eirahoutmoss.nexyapi.ui.theme.NexColors
import com.eirahoutmoss.nexyapi.ui.theme.accent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID

/** Fotoğraf üzerinde işaret türü. Kodlar kayda yazılır; değiştirilmemeli. */
enum class MarkType(val code: String, val label: String, val color: Color, val dashed: Boolean) {
    PIPE("pipe", "Boru", Color(0xFF2F80ED), false),
    REBAR("rebar", "Demir", Color(0xFFF07A2E), false),
    CABLE("cable", "Olası kablo", Color(0xFFF2B134), true),
}

/** Görüntüye göre 0..1 arası koordinatlarla bir işaret: tek nokta ya da doğru parçası. */
data class Mark(val type: MarkType, val a: Pt, val b: Pt?)

class DrillViewModel(private val c: AppContainer, private val structureId: String) : ViewModel() {
    val mag = MagRecorder(c.appContext)
    val detector = MagneticDetector()

    private val _reading = MutableStateFlow<MagneticDetector.Reading?>(null)
    val reading: StateFlow<MagneticDetector.Reading?> = _reading.asStateFlow()
    private val _raw = MutableStateFlow(0.0)
    /** Ham alan büyüklüğü (µT), kalibrasyondan önce de gösterilir. */
    val raw: StateFlow<Double> = _raw.asStateFlow()

    var calibrating by mutableStateOf(false); private set
    var calibrated by mutableStateOf(false); private set
    var sessionPeak by mutableStateOf(0.0); private set
    var beep by mutableStateOf(true)
    var message by mutableStateOf<String?>(null)

    // Fotoğraf işaretleme
    var photo by mutableStateOf<File?>(null); private set
    var preview by mutableStateOf<Bitmap?>(null); private set
    var importing by mutableStateOf(false); private set
    val marks = mutableStateListOf<Mark>()
    var markType by mutableStateOf(MarkType.REBAR)
    var subject by mutableStateOf("")
    var saving by mutableStateOf(false); private set

    val history: StateFlow<List<MeasurementEntity>> = c.structures.observeModule(structureId, ModuleType.BEFORE_DRILL)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        mag.onSample = { v ->
            _raw.value = kotlin.math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
            synchronized(detector) { detector.update(v[0], v[1], v[2]) }?.let { r ->
                _reading.value = r
            }
        }
    }

    fun startSensor(): Boolean = mag.start()
    fun stopSensor() = mag.stop()

    fun calibrate() {
        if (calibrating) return
        calibrating = true
        viewModelScope.launch {
            mag.freezeBias()
            mag.clearSamples()
            delay(2000)
            val samples = mag.lastSamples(100)
            when (val r = synchronized(detector) { detector.calibrate(samples) }) {
                is MagneticDetector.Calibration.Ok -> { calibrated = true; sessionPeak = 0.0; message = "Kalibre edildi. Telefonu aynı yönde tutarak duvarda yavaşça gezdirin." }
                is MagneticDetector.Calibration.Error -> { calibrated = false; message = r.message }
            }
            calibrating = false
        }
    }

    fun notePeak(v: Double) { if (v > sessionPeak) sessionPeak = v }

    fun importPhoto(uri: Uri) {
        importing = true
        viewModelScope.launch {
            try {
                val f = File(c.structures.dataDir(structureId), "pending/${UUID.randomUUID()}.jpg")
                c.images.importImage(uri, f, maxDimension = 2048, quality = 92)
                if (uri.scheme == "file") uri.path?.let { withContext(Dispatchers.IO) { File(it).delete() } }
                photo?.let { old -> withContext(Dispatchers.IO) { old.delete() } }
                photo = f
                preview = c.images.loadPreview(f, 2048)
                marks.clear()
            } catch (e: Exception) {
                message = "Fotoğraf alınamadı: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                importing = false
            }
        }
    }

    fun undoMark() { if (marks.isNotEmpty()) marks.removeAt(marks.lastIndex) }

    fun save() {
        val f = photo
        val bmp = preview
        if (f == null || bmp == null) { message = "Önce duvarın fotoğrafını ekleyin."; return }
        if (marks.isEmpty()) { message = "Fotoğrafa en az bir işaret koyun."; return }
        saving = true
        viewModelScope.launch {
            try {
                val id = UUID.randomUUID().toString()
                val out = File(c.structures.dataDir(structureId), "$id.w1.png")
                val sha = withContext(Dispatchers.IO) {
                    val composite = render(bmp, marks.toList())
                    val tmp = File(out.parentFile, out.name + ".part")
                    out.parentFile?.mkdirs()
                    tmp.outputStream().use { if (!composite.compress(Bitmap.CompressFormat.PNG, 100, it)) throw IOException("Görüntü kaydedilemedi.") }
                    composite.recycle()
                    out.delete()
                    if (!tmp.renameTo(out)) throw IOException("Görüntü kaydedilemedi.")
                    f.delete()
                    RawDataWriter.sha256(out)
                }
                val arr = JSONArray()
                marks.forEach { m ->
                    val o = JSONObject().put("type", m.type.code).put("ax", m.a.x).put("ay", m.a.y)
                    m.b?.let { o.put("bx", it.x).put("by", it.y) }
                    arr.put(o)
                }
                val counts = MarkType.entries.mapNotNull { t -> marks.count { it.type == t }.takeIf { it > 0 }?.let { "$it ${t.label.lowercase()}" } }
                val peak = sessionPeak.takeIf { it > 0 }
                c.structures.saveMeasurement(
                    MeasurementEntity(
                        id = id, structureId = structureId, moduleCode = ModuleType.BEFORE_DRILL.code,
                        takenAt = System.currentTimeMillis(), value = peak, unit = ModuleType.BEFORE_DRILL.unit,
                        summary = counts.joinToString(", ") + (peak?.let { " · en yüksek ${fmtNum(it, 0)} µT" } ?: ""),
                        quality = if (calibrated) MeasurementQuality.OK else MeasurementQuality.LOW,
                        deviceModel = DeviceInfo.model, deviceInfo = DeviceInfo.describe(c.appContext).toString(),
                        dataFile = c.structures.relative(out), dataSha256 = sha,
                        note = if (calibrated) "" else "İşaretler dedektör kalibre edilmeden konuldu.",
                        subject = subject.trim().ifEmpty { null },
                        params = JSONObject().put("marks", arr).put("sensor", mag.sensorName).toString(),
                    ),
                )
                photo = null; preview = null; marks.clear()
                message = "Kaydedildi."
            } catch (e: Exception) {
                message = "Kaydedilemedi: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                saving = false
            }
        }
    }

    fun delete(m: MeasurementEntity) { viewModelScope.launch { c.structures.deleteMeasurement(m.id) } }

    override fun onCleared() {
        mag.onSample = null
        mag.stop()
        photo?.delete()
    }

    companion object {
        /** İşaretleri fotoğrafın üzerine çizer (kayıt ve rapor için). */
        fun render(src: Bitmap, marks: List<Mark>): Bitmap {
            val out = src.copy(Bitmap.Config.ARGB_8888, true)
            val cv = android.graphics.Canvas(out)
            val w = out.width.toFloat(); val h = out.height.toFloat()
            val stroke = (minOf(w, h) / 90f).coerceAtLeast(4f)
            for (m in marks) {
                val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    color = m.type.color.toArgb(); strokeWidth = stroke; style = android.graphics.Paint.Style.STROKE
                    strokeCap = android.graphics.Paint.Cap.ROUND
                    if (m.type.dashed) pathEffect = DashPathEffect(floatArrayOf(stroke * 3, stroke * 2), 0f)
                }
                val shadow = android.graphics.Paint(p).apply { color = android.graphics.Color.argb(120, 0, 0, 0); strokeWidth = stroke * 1.8f; pathEffect = null }
                val ax = (m.a.x * w).toFloat(); val ay = (m.a.y * h).toFloat()
                val b = m.b
                if (b == null) {
                    cv.drawCircle(ax, ay, stroke * 2.2f, shadow)
                    cv.drawCircle(ax, ay, stroke * 2.2f, p)
                } else {
                    val bx = (b.x * w).toFloat(); val by = (b.y * h).toFloat()
                    cv.drawLine(ax, ay, bx, by, shadow)
                    cv.drawLine(ax, ay, bx, by, p)
                }
            }
            return out
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DrillScreen(structureId: String, onBack: () -> Unit) {
    val container = appContainer()
    val vm: DrillViewModel = viewModel(
        key = "w1-$structureId",
        factory = viewModelFactory { initializer { DrillViewModel(container, structureId) } },
    )
    val history by vm.history.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(vm.message) { vm.message?.let { snackbar.showSnackbar(it); vm.message = null } }

    // Sensör yalnızca bu ekran açıkken çalışır.
    DisposableEffect(Unit) {
        if (!vm.startSensor()) vm.message = "Bu cihazda manyetometre bulunamadı."
        onDispose { vm.stopSensor() }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Matkaptan Önce") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Geri") } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = tab) {
                listOf("Dedektör", "Fotoğrafta işaretle", "AR (deneysel)").forEachIndexed { i, t ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t, maxLines = 1) })
                }
            }
            if (tab == 2) {
                ArScanPanel(vm)
            } else {
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    LimitsCard()
                    if (tab == 0) DetectorPanel(vm) else MarkPanel(vm)
                    DisclaimerBanner()
                    Text("Kayıtlar", style = MaterialTheme.typography.titleMedium)
                    ModuleHistory(history, "µT", 0, ModuleType.BEFORE_DRILL.accent, onDelete = vm::delete)
                }
            }
        }
    }
}

@Composable
private fun LimitsCard() {
    Card(colors = CardDefaults.cardColors(containerColor = NexColors.Amber.copy(alpha = 0.12f))) {
        Row(Modifier.padding(12.dp)) {
            Icon(Icons.Outlined.Warning, null, tint = NexColors.Amber, modifier = Modifier.size(20.dp))
            Text(
                " Demir, çelik boru ve çivi bulunur. Bakır/plastik borular ve akım geçmeyen kablolar bulunamaz. " +
                    "Sonuç kesinlik taşımaz; delmeden önce mutlaka ek önlem alın ve elektriği kesin.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun DetectorPanel(vm: DrillViewModel) {
    val reading by vm.reading.collectAsStateWithLifecycle()
    val raw by vm.raw.collectAsStateWithLifecycle()
    val r = reading
    LaunchedEffect(r?.anomaly) { r?.let { vm.notePeak(it.anomaly) } }

    // Yaklaştıkça sıklaşan bip (Geiger sayacı gibi)
    val current by rememberUpdatedState(r)
    val beepOn by rememberUpdatedState(vm.beep)
    LaunchedEffect(Unit) {
        val tone = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 60) }.getOrNull()
        try {
            while (true) {
                val rd = current
                if (beepOn && rd != null && rd.detected) {
                    tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 40)
                    delay((650 - 580 * rd.level).toLong().coerceAtLeast(60))
                } else {
                    delay(120)
                }
            }
        } finally {
            tone?.release()
        }
    }

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("1. Kalibrasyon", style = MaterialTheme.typography.titleSmall)
            Text(
                "Telefonu duvarda boş olduğunu düşündüğünüz bir noktaya düz koyun ve 2 saniye kıpırdatmayın.",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                if (vm.mag.tiltCompensated) "Eğilme dengelemesi açık: telefon taramada hafifçe eğilse de sonuç etkilenmez."
                else "Bu cihazda eğilme dengelemesi yok: taramada telefonu hep aynı yönde, duvara düz tutun.",
                style = MaterialTheme.typography.labelSmall,
                color = if (vm.mag.tiltCompensated) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            )
            Button(onClick = { vm.calibrate() }, enabled = !vm.calibrating && vm.mag.available) {
                if (vm.calibrating) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.size(8.dp)); Text("Kıpırdatmayın…") }
                else Text(if (vm.calibrated) "Yeniden kalibre et" else "Kalibre et")
            }
        }
    }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("2. Tarama", style = MaterialTheme.typography.titleSmall, modifier = Modifier.fillMaxWidth())
            if (r == null) {
                Text("Önce kalibre edin. Ortam alanı: ${fmtNum(raw, 0)} µT", style = MaterialTheme.typography.bodyMedium)
            } else {
                val color = when {
                    !r.detected -> ModuleType.BEFORE_DRILL.accent
                    r.level < 0.5 -> NexColors.Amber
                    else -> NexColors.Red
                }
                Text(if (r.detected) "METAL ALGILANDI" else "Temiz", style = MaterialTheme.typography.titleMedium, color = color)
                Text("${fmtNum(r.anomaly, 1)} µT", style = BigValueStyle, color = color)
                Gauge(r.level.toFloat(), color)
                Text(
                    "Eşik ${fmtNum(vm.detector.threshold, 1)} µT · Bu taramada en yüksek ${fmtNum(r.peak, 1)} µT",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (r.passedPeak && r.detected) {
                    Text("Tepe noktası az önce geçildi: kalemle işaretleyin.", style = MaterialTheme.typography.bodySmall, color = color)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("Sesli uyarı", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Switch(checked = vm.beep, onCheckedChange = { vm.beep = it })
            }
        }
    }
    Text(
        "İpucu: Telefonu duvarda yavaşça yatay, sonra dikey gezdirin. Bip sıklaştıkça cisme yaklaşıyorsunuz; " +
            "en sık bip noktası cismin üzeridir. Bulduğunuz yerleri işaretleyip \"Fotoğrafta işaretle\" sekmesinde kaydedin.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun Gauge(level: Float, color: Color) {
    Box(Modifier.fillMaxWidth().height(18.dp).clip(RoundedCornerShape(9.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(level.coerceIn(0.02f, 1f)).clip(RoundedCornerShape(9.dp)).background(color))
    }
}

@Composable
private fun MarkPanel(vm: DrillViewModel) {
    val photos = rememberPhotoSource(onPicked = { vm.importPhoto(it) }, onError = { vm.message = it })
    val bmp = vm.preview
    if (vm.importing) {
        Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    if (bmp == null) {
        Text(
            "Dedektörle bulduğunuz yerleri duvarda kalemle işaretleyin, sonra duvarın fotoğrafını çekin ve işaretleri çizin.",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = photos.takePhoto, modifier = Modifier.weight(1f).height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)) {
                Icon(Icons.Outlined.PhotoCamera, null); Spacer(Modifier.size(8.dp)); Text("Fotoğraf çek")
            }
            OutlinedButton(onClick = photos.pickFromGallery, modifier = Modifier.weight(1f).height(52.dp)) {
                Icon(Icons.Outlined.PhotoLibrary, null); Spacer(Modifier.size(8.dp)); Text("Galeriden")
            }
        }
        return
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        MarkType.entries.forEach { t ->
            FilterChip(selected = vm.markType == t, onClick = { vm.markType = t }, label = { Text(t.label) },
                leadingIcon = { Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(t.color)) })
        }
    }
    Text("Dokunarak nokta koyun, sürükleyerek hat çizin.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    AnnotatedPhoto(vm, bmp)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { vm.undoMark() }, enabled = vm.marks.isNotEmpty()) { Text("Geri al") }
        OutlinedButton(onClick = photos.takePhoto) { Text("Yeni fotoğraf") }
    }
    OutlinedTextField(
        value = vm.subject, onValueChange = { vm.subject = it.take(60) },
        label = { Text("Duvar") }, placeholder = { Text("Örn. Mutfak, tezgâh üstü") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
    )
    Button(onClick = { vm.save() }, enabled = !vm.saving, modifier = Modifier.fillMaxWidth().height(52.dp)) {
        Text(if (vm.saving) "Kaydediliyor…" else "Kaydet")
    }
}

@Composable
private fun AnnotatedPhoto(vm: DrillViewModel, bmp: Bitmap) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    var dragStart by remember { mutableStateOf<Offset?>(null) }
    var dragEnd by remember { mutableStateOf<Offset?>(null) }
    val type by rememberUpdatedState(vm.markType)
    Box(
        Modifier.fillMaxWidth().aspectRatio(bmp.width.toFloat() / bmp.height).clip(MaterialTheme.shapes.medium)
            .onSizeChanged { size = it }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val start = down.position
                    var end = start
                    var moved = false
                    dragStart = start; dragEnd = start
                    drag(down.id) { ch ->
                        end = ch.position
                        if ((end - start).getDistance() > 24f) moved = true
                        dragEnd = end
                        ch.consume()
                    }
                    val w = size.width.toFloat().coerceAtLeast(1f); val h = size.height.toFloat().coerceAtLeast(1f)
                    fun n(o: Offset) = Pt((o.x / w).toDouble().coerceIn(0.0, 1.0), (o.y / h).toDouble().coerceIn(0.0, 1.0))
                    vm.marks.add(Mark(type, n(start), if (moved) n(end) else null))
                    dragStart = null; dragEnd = null
                }
            },
    ) {
        Image(bmp.asImageBitmap(), "Duvar fotoğrafı", contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
        Canvas(Modifier.fillMaxSize()) {
            val stroke = (kotlin.math.min(this.size.width, this.size.height) / 90f).coerceAtLeast(5f)
            fun o(p: Pt) = Offset((p.x * this.size.width).toFloat(), (p.y * this.size.height).toFloat())
            fun draw(t: MarkType, a: Offset, b: Offset?) {
                val effect = if (t.dashed) PathEffect.dashPathEffect(floatArrayOf(stroke * 3, stroke * 2)) else null
                if (b == null) {
                    drawCircle(Color.Black.copy(alpha = 0.45f), stroke * 2.6f, a)
                    drawCircle(t.color, stroke * 2.2f, a)
                } else {
                    drawLine(Color.Black.copy(alpha = 0.45f), a, b, stroke * 1.8f, StrokeCap.Round)
                    drawLine(t.color, a, b, stroke, StrokeCap.Round, pathEffect = effect)
                }
            }
            vm.marks.forEach { m -> draw(m.type, o(m.a), m.b?.let { o(it) }) }
            val s = dragStart; val e = dragEnd
            if (s != null && e != null && (e - s).getDistance() > 24f) draw(type, s, e)
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        MarkType.entries.forEach { t ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(t.color))
                Text(" ${t.label} (${vm.marks.count { it.type == t }})", fontSize = 12.sp)
            }
        }
    }
}
