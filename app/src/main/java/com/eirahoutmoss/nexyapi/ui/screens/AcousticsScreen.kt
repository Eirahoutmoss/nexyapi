package com.eirahoutmoss.nexyapi.ui.screens

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.eirahoutmoss.nexyapi.AppContainer
import com.eirahoutmoss.nexyapi.BuildConfig
import com.eirahoutmoss.nexyapi.core.analysis.AcousticsAdvice
import com.eirahoutmoss.nexyapi.core.analysis.AcousticsResult
import com.eirahoutmoss.nexyapi.core.analysis.Quality
import com.eirahoutmoss.nexyapi.core.analysis.RoomAcoustics
import com.eirahoutmoss.nexyapi.core.analysis.RoomModes
import com.eirahoutmoss.nexyapi.core.analysis.Sweep
import com.eirahoutmoss.nexyapi.core.model.ModuleType
import com.eirahoutmoss.nexyapi.data.MeasurementEntity
import com.eirahoutmoss.nexyapi.data.MeasurementQuality
import com.eirahoutmoss.nexyapi.diagnostics.DeviceInfo
import com.eirahoutmoss.nexyapi.sensors.AcousticCapture
import com.eirahoutmoss.nexyapi.sensors.AcousticRecorder
import com.eirahoutmoss.nexyapi.sensors.RawDataWriter
import com.eirahoutmoss.nexyapi.ui.appContainer
import com.eirahoutmoss.nexyapi.ui.components.DisclaimerBanner
import com.eirahoutmoss.nexyapi.ui.components.ModuleHistory
import com.eirahoutmoss.nexyapi.ui.components.QualityChip
import com.eirahoutmoss.nexyapi.ui.components.ResponseChart
import com.eirahoutmoss.nexyapi.ui.components.color
import com.eirahoutmoss.nexyapi.ui.components.fmtNum
import com.eirahoutmoss.nexyapi.ui.components.formatDate
import com.eirahoutmoss.nexyapi.ui.theme.BigValueStyle
import com.eirahoutmoss.nexyapi.ui.theme.accent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
import java.util.UUID
import kotlin.math.log10

sealed interface AcuPhase {
    data object Setup : AcuPhase
    data class Measuring(val step: String, val fraction: Float) : AcuPhase
    data object Analyzing : AcuPhase
    data class Result(
        val result: AcousticsResult,
        val advice: List<String>,
        val room: AcousticsAdvice.Room?,
        val capture: AcousticCapture,
        val startedAt: Long,
        val ir: DoubleArray,
        val warnings: List<String>,
    ) : AcuPhase
    data object Saving : AcuPhase
    data class Error(val message: String) : AcuPhase
}

class AcousticsViewModel(private val c: AppContainer, private val structureId: String) : ViewModel() {
    private val recorder = AcousticRecorder(c.appContext)

    private val _phase = MutableStateFlow<AcuPhase>(AcuPhase.Setup)
    val phase: StateFlow<AcuPhase> = _phase.asStateFlow()

    var subject by mutableStateOf("")
    var lengthM by mutableStateOf("")
    var widthM by mutableStateOf("")
    var heightM by mutableStateOf("2,7")
    var externalSpeaker by mutableStateOf(false)
    var subjects by mutableStateOf<List<String>>(emptyList()); private set
    var message by mutableStateOf<String?>(null)

    val history: StateFlow<List<MeasurementEntity>> = c.structures.observeModule(structureId, ModuleType.ACOUSTICS)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private var job: Job? = null

    init {
        viewModelScope.launch { subjects = c.structures.subjects(structureId, ModuleType.ACOUSTICS) }
    }

    fun volumeRatio() = recorder.mediaVolumeRatio()

    private fun parse(s: String): Double? = s.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it in 1.0..50.0 }

    fun room(): AcousticsAdvice.Room? {
        val l = parse(lengthM); val w = parse(widthM); val h = parse(heightM)
        return if (l != null && w != null && h != null) AcousticsAdvice.Room(l, w, h) else null
    }

    /** Oda adı seçilince önceki ölçümdeki boyutları getir. */
    fun pickSubject(s: String) {
        subject = s
        val prev = history.value.firstOrNull { it.subject == s }?.params ?: return
        runCatching {
            val o = JSONObject(prev).optJSONObject("room") ?: return
            lengthM = fmtNum(o.getDouble("l"), 2); widthM = fmtNum(o.getDouble("w"), 2); heightM = fmtNum(o.getDouble("h"), 2)
        }
    }

    fun start() {
        if (job?.isActive == true) return
        val roomNow = room()
        job = viewModelScope.launch {
            try {
                val sweep = Sweep.create(fs = 48_000, durationS = 5.0)
                val startedAt = System.currentTimeMillis()
                val cap = recorder.capture(sweep, repeats = 2, tailS = 2.5) { step, f -> _phase.value = AcuPhase.Measuring(step, f) }
                _phase.value = AcuPhase.Analyzing
                val (result, ir) = withContext(Dispatchers.Default) {
                    val irs = cap.sweeps.map { RoomAcoustics.impulseResponse(it, sweep) }
                    val avg = RoomAcoustics.average(irs)
                    RoomAcoustics.analyze(avg, sweep.fs, externalSpeaker) to avg
                }
                val warnings = ArrayList<String>()
                if (cap.peakLevel > 0.98) warnings += "Kayıt sırasında ses çok yüksekti (kırpılma). Ses seviyesini biraz kısıp tekrarlayın."
                if (cap.peakLevel < 0.05) warnings += "Tarama sesi mikrofona çok zayıf ulaştı. Medya sesini açın."
                if (cap.mediaVolumeRatio < 0.6) warnings += "Medya sesi %${(cap.mediaVolumeRatio * 100).toInt()}; daha doğru sonuç için sesi en az %70'e getirin."
                if (cap.backgroundDbfs > -45) warnings += "Ortam gürültüsü yüksek (${cap.backgroundDbfs.toInt()} dBFS)."
                _phase.value = AcuPhase.Result(
                    result, AcousticsAdvice.suggest(result, roomNow, externalSpeaker), roomNow, cap, startedAt, ir, warnings,
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: OutOfMemoryError) {
                _phase.value = AcuPhase.Error("Cihaz belleği bu ölçüm için yetersiz kaldı.")
            } catch (e: Exception) {
                _phase.value = AcuPhase.Error("Ölçüm yapılamadı: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    fun abort(reason: String?) {
        val active = job?.isActive == true
        job?.cancel()
        if (active) _phase.value = reason?.let { AcuPhase.Error(it) } ?: AcuPhase.Setup
    }

    fun discard() { _phase.value = AcuPhase.Setup }

    fun save() {
        val p = _phase.value as? AcuPhase.Result ?: return
        _phase.value = AcuPhase.Saving
        viewModelScope.launch {
            try {
                val id = UUID.randomUUID().toString()
                val file = File(c.structures.dataDir(structureId), "$id.w3.ir.csv.gz")
                val sha = withContext(Dispatchers.IO) {
                    RawDataWriter.writeSeries(
                        file, p.ir, "h",
                        listOf(
                            "NexYapı ${BuildConfig.VERSION_NAME} · W3 Oda Akustiği · ortalama dürtü yanıtı (2 tarama)",
                            "fs=48000", "kaynak=${p.capture.audioSource}", "harici_hoparlor=$externalSpeaker",
                            "cihaz=${DeviceInfo.model}", "konu=${subject.trim()}",
                        ),
                    )
                }
                val r = p.result
                val bands = JSONArray()
                r.bands.forEach { b ->
                    bands.put(
                        JSONObject().put("f", b.centerHz).put("rt60", b.rt60S ?: JSONObject.NULL)
                            .put("dr", b.dynamicRangeDb).put("r2", b.fitR2).put("q", b.quality.name),
                    )
                }
                val params = JSONObject()
                    .put("bands", bands)
                    .put("externalSpeaker", externalSpeaker)
                    .put("audioSource", p.capture.audioSource)
                    .put("backgroundDbfs", p.capture.backgroundDbfs)
                    .put("peakLevel", p.capture.peakLevel)
                    .put("bassPeaksHz", JSONArray(r.bassPeaksHz))
                p.room?.let { params.put("room", JSONObject().put("l", it.lengthM).put("w", it.widthM).put("h", it.heightM)) }
                val quality = if (p.warnings.isNotEmpty() && r.quality == Quality.OK) Quality.LOW else r.quality
                c.structures.saveMeasurement(
                    MeasurementEntity(
                        id = id, structureId = structureId, moduleCode = ModuleType.ACOUSTICS.code,
                        takenAt = p.startedAt, value = r.rt60MidS, unit = ModuleType.ACOUSTICS.unit,
                        summary = summaryOf(r), quality = quality.toStored(),
                        deviceModel = DeviceInfo.model, deviceInfo = DeviceInfo.describe(c.appContext).toString(),
                        dataFile = c.structures.relative(file), dataSha256 = sha,
                        note = (r.notes + p.warnings).joinToString(" "),
                        subject = subject.trim().ifEmpty { null }, params = params.toString(),
                    ),
                )
                subjects = c.structures.subjects(structureId, ModuleType.ACOUSTICS)
                _phase.value = AcuPhase.Setup
                message = "Ölçüm kaydedildi."
            } catch (e: Exception) {
                _phase.value = p
                message = "Kaydedilemedi: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    fun delete(m: MeasurementEntity) { viewModelScope.launch { c.structures.deleteMeasurement(m.id) } }

    companion object {
        fun summaryOf(r: AcousticsResult) = r.rt60MidS?.let { "RT60 ${fmtNum(it, 2)} sn" } ?: "Ölçülemedi"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AcousticsScreen(structureId: String, onBack: () -> Unit) {
    val container = appContainer()
    val vm: AcousticsViewModel = viewModel(
        key = "w3-$structureId",
        factory = viewModelFactory { initializer { AcousticsViewModel(container, structureId) } },
    )
    val phase by vm.phase.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val busy = phase is AcuPhase.Measuring || phase is AcuPhase.Analyzing

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.start() else vm.message = "Ölçüm için mikrofon izni gerekiyor."
    }
    fun startWithPermission() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) vm.start()
        else permission.launch(Manifest.permission.RECORD_AUDIO)
    }

    DisposableEffect(busy) {
        val window = (context as? Activity)?.window
        if (busy) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_STOP) vm.abort("Uygulama arka plana geçtiği için ölçüm iptal edildi.")
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    LaunchedEffect(vm.message) { vm.message?.let { snackbar.showSnackbar(it); vm.message = null } }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Oda Akustiği") },
                navigationIcon = { IconButton(onClick = { vm.abort(null); onBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Geri") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            when (val p = phase) {
                AcuPhase.Setup -> AcuSetup(vm, ::startWithPermission)
                is AcuPhase.Measuring -> Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(p.step, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.size(16.dp))
                    LinearProgressIndicator(progress = { p.fraction.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.size(12.dp))
                    Text("Konuşmayın ve hareket etmeyin.", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.size(16.dp))
                    OutlinedButton(onClick = { vm.abort(null) }) { Text("İptal") }
                }
                AcuPhase.Analyzing -> Box(Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) { CircularProgressIndicator(); Spacer(Modifier.size(12.dp)); Text("Yankı hesaplanıyor…") }
                }
                is AcuPhase.Result -> AcuResult(vm, p)
                AcuPhase.Saving -> Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                is AcuPhase.Error -> Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(p.message, color = MaterialTheme.colorScheme.error)
                        Button(onClick = { vm.discard() }) { Text("Tamam") }
                    }
                }
            }
            if (!busy && phase !is AcuPhase.Result) {
                DisclaimerBanner()
                Text("Ölçüm geçmişi", style = MaterialTheme.typography.titleMedium)
                ModuleHistory(history, "sn", 2, ModuleType.ACOUSTICS.accent, onDelete = vm::delete)
            }
        }
    }
}

@Composable
private fun AcuSetup(vm: AcousticsViewModel, onStart: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Nasıl ölçülür?", style = MaterialTheme.typography.titleSmall)
            listOf(
                "Kapı ve pencereleri kapatın; klima, buzdolabı gibi sürekli ses kaynaklarını mümkünse kapatın.",
                "Medya sesini en az %70'e getirin. Telefonu odanın ortasına yakın, kulak hizasında bir yere koyun.",
                "Ölçüm iki kez 5 saniyelik bir tarama sesi çalar; bu sırada konuşmayın ve hareket etmeyin.",
                "Yankı süresi konuma göre biraz değişir: aynı odayı 2–3 farklı noktada ölçmek daha güvenilir sonuç verir.",
            ).forEachIndexed { i, t -> Text("${i + 1}. $t", style = MaterialTheme.typography.bodySmall) }
        }
    }
    OutlinedTextField(
        value = vm.subject, onValueChange = { vm.subject = it.take(60) },
        label = { Text("Oda") }, placeholder = { Text("Örn. Salon") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
    )
    if (vm.subjects.isNotEmpty()) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            vm.subjects.forEach { s -> AssistChip(onClick = { vm.pickSubject(s) }, label = { Text(s) }) }
        }
    }
    Text("Oda boyutları (isteğe bağlı, metre)", style = MaterialTheme.typography.labelLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val kb = KeyboardOptions(keyboardType = KeyboardType.Decimal)
        OutlinedTextField(vm.lengthM, { vm.lengthM = it.take(6) }, label = { Text("Uzunluk") }, singleLine = true, keyboardOptions = kb, modifier = Modifier.weight(1f))
        OutlinedTextField(vm.widthM, { vm.widthM = it.take(6) }, label = { Text("Genişlik") }, singleLine = true, keyboardOptions = kb, modifier = Modifier.weight(1f))
        OutlinedTextField(vm.heightM, { vm.heightM = it.take(6) }, label = { Text("Yükseklik") }, singleLine = true, keyboardOptions = kb, modifier = Modifier.weight(1f))
    }
    Text(
        "Boyutlar girilirse bas birikmesine yol açan oda modları hesaplanır.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Harici hoparlör kullanıyorum", style = MaterialTheme.typography.bodyMedium)
            Text("Bluetooth ya da kablolu hoparlör: bas ölçümü mümkün olur.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = vm.externalSpeaker, onCheckedChange = { vm.externalSpeaker = it })
    }
    val vol = vm.volumeRatio()
    if (vol < 0.6) {
        Text("Medya sesi şu an %${(vol * 100).toInt()}. Ölçümden önce sesi açın.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
    Button(
        onClick = onStart,
        modifier = Modifier.fillMaxWidth().height(52.dp),
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
    ) { Text("Ölçümü Başlat") }
}

@Composable
private fun AcuResult(vm: AcousticsViewModel, p: AcuPhase.Result) {
    val r = p.result
    val accent = ModuleType.ACOUSTICS.accent
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Yankı süresi (RT60, 500 Hz–1 kHz)", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(r.rt60MidS?.let { "${fmtNum(it, 2)} sn" } ?: "Ölçülemedi", style = BigValueStyle, color = accent)
            r.rt60MidS?.let { Text(AcousticsAdvice.verdict(it).label, style = MaterialTheme.typography.titleMedium) }
            QualityChip((if (p.warnings.isNotEmpty() && r.quality == Quality.OK) Quality.LOW else r.quality).toStored())
            (r.notes + p.warnings).forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
            val prev = vm.history.value.firstOrNull { it.value != null && it.quality != MeasurementQuality.UNRELIABLE && it.subject == vm.subject.trim() }
            if (prev != null && r.rt60MidS != null) {
                Text("Aynı odadaki önceki ölçüm: ${fmtNum(prev.value!!, 2)} sn (${formatDate(prev.takenAt)}).", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Frekans bantlarına göre yankı süresi", style = MaterialTheme.typography.titleSmall)
            val maxRt = r.bands.mapNotNull { it.rt60S }.maxOrNull()?.coerceAtLeast(0.1) ?: 1.0
            r.bands.forEach { b ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (b.centerHz >= 1000) "${(b.centerHz / 1000).toInt()} kHz" else "${b.centerHz.toInt()} Hz", style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(64.dp))
                    val stored = b.quality.toStored()
                    val frac = ((b.rt60S ?: 0.0) / maxRt).toFloat().coerceIn(0f, 1f)
                    LinearProgressIndicator(progress = { frac }, color = stored.color, modifier = Modifier.weight(1f).height(8.dp))
                    Text(
                        b.rt60S?.takeIf { b.quality != Quality.UNRELIABLE }?.let { " ${fmtNum(it, 2)} sn" } ?: " —",
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(64.dp),
                    )
                }
            }
            Text("Renk: yeşil normal, sarı düşük kalite, kırmızı güvenilmez.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (r.responseHz.size > 2) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(16.dp)) {
                Text("Frekans yanıtı (hoparlör + oda)", style = MaterialTheme.typography.titleSmall)
                ResponseChart(r.responseHz, r.responseDb, accent, marks = r.bassPeaksHz, modifier = Modifier.padding(top = 8.dp))
                if (!vm.externalSpeaker) {
                    Text("Telefon hoparlörüyle ölçüldü; 200 Hz altındaki düşüş hoparlörden kaynaklanır.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    p.room?.let { room ->
        val modes = RoomModes.axial(room.lengthM, room.widthM, room.heightM)
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Oda modları (hesaplanan, 300 Hz'e kadar)", style = MaterialTheme.typography.titleSmall)
                Text(modes.take(12).joinToString(" · ") { "${fmtNum(it.frequencyHz, 0)} Hz" }, style = MaterialTheme.typography.bodySmall)
                val volume = room.lengthM * room.widthM * room.heightM
                Text("Oda hacmi: ${fmtNum(volume, 1)} m³", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Öneriler", style = MaterialTheme.typography.titleSmall)
            p.advice.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
        }
    }
    Text(
        "Kayıt kaynağı: ${p.capture.audioSource} · Ortam gürültüsü: ${p.capture.backgroundDbfs.toInt()} dBFS · " +
            "Tepe seviye: ${fmtNum(20 * log10(p.capture.peakLevel.coerceAtLeast(1e-6)), 0)} dBFS",
        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedButton(onClick = { vm.discard() }, modifier = Modifier.weight(1f)) { Text("Sil, tekrarla") }
        Button(onClick = { vm.save() }, modifier = Modifier.weight(1f)) { Text("Kaydet") }
    }
}
