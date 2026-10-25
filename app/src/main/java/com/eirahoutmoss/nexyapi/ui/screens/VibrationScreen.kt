package com.eirahoutmoss.nexyapi.ui.screens

import android.app.Activity
import android.media.AudioManager
import android.media.ToneGenerator
import android.view.WindowManager
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Warning
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.eirahoutmoss.nexyapi.AppContainer
import com.eirahoutmoss.nexyapi.BuildConfig
import com.eirahoutmoss.nexyapi.core.analysis.AccelRecording
import com.eirahoutmoss.nexyapi.core.analysis.Quality
import com.eirahoutmoss.nexyapi.core.analysis.VibrationAnalyzer
import com.eirahoutmoss.nexyapi.core.analysis.VibrationResult
import com.eirahoutmoss.nexyapi.core.model.ModuleType
import com.eirahoutmoss.nexyapi.data.MeasurementEntity
import com.eirahoutmoss.nexyapi.data.MeasurementQuality
import com.eirahoutmoss.nexyapi.diagnostics.DeviceInfo
import com.eirahoutmoss.nexyapi.sensors.AccelCapability
import com.eirahoutmoss.nexyapi.sensors.AccelRecorder
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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

fun Quality.toStored(): MeasurementQuality = when (this) {
    Quality.OK -> MeasurementQuality.OK
    Quality.LOW -> MeasurementQuality.LOW
    Quality.UNRELIABLE -> MeasurementQuality.UNRELIABLE
}

sealed interface VibPhase {
    data object Setup : VibPhase
    data class Countdown(val secondsLeft: Int) : VibPhase
    data class Recording(val elapsedS: Int, val totalS: Int, val level: Double) : VibPhase
    data object Analyzing : VibPhase
    data class Result(val result: VibrationResult, val startedAt: Long, val durationS: Int) : VibPhase
    data object Saving : VibPhase
    data class Error(val message: String) : VibPhase
}

class VibrationViewModel(private val c: AppContainer, private val structureId: String) : ViewModel() {
    private val recorder = AccelRecorder(c.appContext)
    val capability: AccelCapability = recorder.capability()

    var phase by mutableStateOf<VibPhase>(VibPhase.Setup); private set
    var subject by mutableStateOf("")
    var durationS by mutableStateOf(120)
    var subjects by mutableStateOf<List<String>>(emptyList()); private set
    /** Kullanıcıya bir kez gösterilecek mesaj (kayıt edildi vb.). */
    var message by mutableStateOf<String?>(null)

    val history: StateFlow<List<MeasurementEntity>> = c.structures.observeModule(structureId, ModuleType.VIBRATION)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private var job: Job? = null
    private var lastRecording: AccelRecording? = null
    private var tone: ToneGenerator? = null

    init {
        viewModelScope.launch { subjects = c.structures.subjects(structureId, ModuleType.VIBRATION) }
    }

    fun start() {
        if (job?.isActive == true) return
        if (!capability.available) { phase = VibPhase.Error("Bu cihazda ivmeölçer yok."); return }
        job = viewModelScope.launch {
            for (s in 5 downTo 1) { phase = VibPhase.Countdown(s); delay(1000) }
            beep()
            val startedAt = System.currentTimeMillis()
            val total = durationS
            val started = try {
                recorder.start(total)
            } catch (e: Exception) {
                false
            }
            if (!started) { phase = VibPhase.Error("İvmeölçer başlatılamadı. Uygulamayı kapatıp yeniden deneyin."); return@launch }
            val t0 = System.nanoTime()
            while (true) {
                val elapsed = ((System.nanoTime() - t0) / 1_000_000_000L).toInt()
                if (elapsed >= total) break
                phase = VibPhase.Recording(elapsed, total, recorder.recentLevel())
                delay(250)
            }
            val rec = recorder.stop()
            beep(); beep()
            phase = VibPhase.Analyzing
            val result = withContext(Dispatchers.Default) { VibrationAnalyzer.analyze(rec) }
            lastRecording = rec
            phase = VibPhase.Result(result, startedAt, total)
        }
    }

    /** Ölçümü iptal eder (kullanıcı ya da uygulama arka plana geçti). */
    fun abort(reason: String?) {
        val active = job?.isActive == true && (phase is VibPhase.Countdown || phase is VibPhase.Recording || phase is VibPhase.Analyzing)
        job?.cancel()
        recorder.stop()
        lastRecording = null
        if (active) phase = if (reason != null) VibPhase.Error(reason) else VibPhase.Setup
    }

    fun discard() {
        lastRecording = null
        phase = VibPhase.Setup
    }

    /** Ölçümü veritabanına ve ham veriyi dosyaya yazar. */
    fun save() {
        val p = phase as? VibPhase.Result ?: return
        val rec = lastRecording ?: return
        phase = VibPhase.Saving
        viewModelScope.launch {
            try {
                val id = UUID.randomUUID().toString()
                val file = File(c.structures.dataDir(structureId), "$id.z2.csv.gz")
                val r = p.result
                val device = DeviceInfo.describe(appContext)
                val sha = withContext(Dispatchers.IO) {
                    RawDataWriter.writeAccel(
                        file, rec,
                        listOf(
                            "NexYapı ${BuildConfig.VERSION_NAME} · Z2 Titreşim ham verisi",
                            "cihaz=${DeviceInfo.model}",
                            "sensor=${capability.name} (${capability.vendor})",
                            "baslangic_epoch_ms=${p.startedAt}",
                            "konu=${subject.trim()}",
                        ),
                    )
                }
                val params = JSONObject()
                    .put("durationS", p.durationS)
                    .put("samplingRateHz", r.sampling.rateHz)
                    .put("samplingJitter", r.sampling.jitter)
                    .put("maxGapMs", r.sampling.maxGapMs)
                    .put("analysisRateHz", r.analysisRateHz)
                    .put("resolutionHz", r.frequencyResolutionHz)
                    .put("prominence", r.prominence)
                    .put("otherPeaksHz", JSONArray(r.otherPeaks.map { it.frequencyHz }))
                c.structures.saveMeasurement(
                    MeasurementEntity(
                        id = id,
                        structureId = structureId,
                        moduleCode = ModuleType.VIBRATION.code,
                        takenAt = p.startedAt,
                        value = r.dominantHz,
                        unit = ModuleType.VIBRATION.unit,
                        summary = summaryOf(r),
                        quality = r.quality.toStored(),
                        deviceModel = DeviceInfo.model,
                        deviceInfo = device.toString(),
                        dataFile = c.structures.relative(file),
                        dataSha256 = sha,
                        note = r.notes.joinToString(" "),
                        subject = subject.trim().ifEmpty { null },
                        params = params.toString(),
                    ),
                )
                subjects = c.structures.subjects(structureId, ModuleType.VIBRATION)
                lastRecording = null
                phase = VibPhase.Setup
                message = "Ölçüm kaydedildi."
            } catch (e: Exception) {
                phase = p
                message = "Kaydedilemedi: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    fun delete(m: MeasurementEntity) {
        viewModelScope.launch { c.structures.deleteMeasurement(m.id) }
    }

    /** Aynı konudaki en son güvenilir ölçüm (karşılaştırma için). */
    fun previousFor(subjectText: String): MeasurementEntity? =
        history.value.firstOrNull {
            it.value != null && it.quality != MeasurementQuality.UNRELIABLE &&
                (it.subject ?: "") == subjectText.trim()
        }

    private fun beep() {
        runCatching {
            val t = tone ?: ToneGenerator(AudioManager.STREAM_NOTIFICATION, 70).also { tone = it }
            t.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
        }
    }

    override fun onCleared() {
        recorder.stop()
        tone?.release()
        tone = null
    }

    private val appContext get() = c.appContext

    companion object {
        fun summaryOf(r: VibrationResult): String =
            r.dominantHz?.let { "${fmtNum(it, 2)} Hz" } ?: "Belirgin tepe yok"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VibrationScreen(structureId: String, onBack: () -> Unit) {
    val container = appContainer()
    val vm: VibrationViewModel = viewModel(
        key = "z2-$structureId",
        factory = viewModelFactory { initializer { VibrationViewModel(container, structureId) } },
    )
    val history by vm.history.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val phase = vm.phase
    val busy = phase is VibPhase.Countdown || phase is VibPhase.Recording || phase is VibPhase.Analyzing

    // Ölçüm sürerken ekran kapanmasın; uygulama arka plana geçerse ölçüm iptal edilsin.
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
    LaunchedEffect(vm.message) {
        vm.message?.let { snackbar.showSnackbar(it); vm.message = null }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Titreşim Parmak İzi") },
                navigationIcon = {
                    IconButton(onClick = { vm.abort(null); onBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Geri") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            when (phase) {
                VibPhase.Setup -> SetupPanel(vm)
                is VibPhase.Countdown -> BigStatus(
                    big = phase.secondsLeft.toString(),
                    text = "Telefonu zemine bırakın ve uzaklaşın.\nÖlçüm başlarken bip sesi duyacaksınız.",
                    onCancel = { vm.abort(null) },
                )
                is VibPhase.Recording -> RecordingPanel(phase) { vm.abort(null) }
                VibPhase.Analyzing -> Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.size(12.dp))
                        Text("Spektrum hesaplanıyor…")
                    }
                }
                is VibPhase.Result -> ResultPanel(vm, phase)
                VibPhase.Saving -> Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                is VibPhase.Error -> Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(phase.message, color = MaterialTheme.colorScheme.error)
                        Button(onClick = { vm.discard() }) { Text("Tamam") }
                    }
                }
            }

            if (!busy && phase !is VibPhase.Result) {
                DisclaimerBanner()
                Text("Ölçüm geçmişi", style = MaterialTheme.typography.titleMedium)
                ModuleHistory(history, "Hz", 2, ModuleType.VIBRATION.accent, onDelete = vm::delete)
            }
        }
    }
}

@Composable
private fun SetupPanel(vm: VibrationViewModel) {
    val cap = vm.capability
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Cihaz yeterliliği", style = MaterialTheme.typography.titleSmall)
            if (!cap.available) {
                Text("Bu cihazda ivmeölçer bulunamadı; titreşim ölçümü yapılamaz.", color = MaterialTheme.colorScheme.error)
            } else {
                Text("İvmeölçer: ${cap.name}", style = MaterialTheme.typography.bodySmall)
                Text(
                    "Bildirilen en yüksek hız: " + (cap.maxRateHz?.let { "${it.toInt()} örnek/sn" } ?: "bilinmiyor"),
                    style = MaterialTheme.typography.bodySmall,
                )
                if (!cap.sufficient) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Warning, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                        Text(
                            " Bu sensör yavaş; sonuçlar güvenilmez olarak işaretlenebilir.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Nasıl ölçülür?", style = MaterialTheme.typography.titleSmall)
            listOf(
                "Telefonu sert bir zemine (halı değil), ekranı yukarı bakacak şekilde yatay koyun.",
                "Mümkünse dairenin ortasına yakın, taşıyıcı duvara yakın bir yer seçin; her seferinde aynı yeri kullanın.",
                "Ölçüm boyunca telefona dokunmayın, yürümeyin. Çamaşır makinesi, klima gibi cihazları kapatın.",
                "Gece ya da trafiğin az olduğu saatler daha temiz sonuç verir.",
            ).forEachIndexed { i, t -> Text("${i + 1}. $t", style = MaterialTheme.typography.bodySmall) }
        }
    }
    OutlinedTextField(
        value = vm.subject,
        onValueChange = { vm.subject = it.take(60) },
        label = { Text("Ölçüm yeri") },
        placeholder = { Text("Örn. Salon, pencere önü") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    if (vm.subjects.isNotEmpty()) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            vm.subjects.forEach { s -> AssistChip(onClick = { vm.subject = s }, label = { Text(s) }) }
        }
    }
    Text("Süre", style = MaterialTheme.typography.labelLarge)
    val options = listOf(60 to "1 dk", 120 to "2 dk", 300 to "5 dk")
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { i, (sec, label) ->
            SegmentedButton(
                selected = vm.durationS == sec,
                onClick = { vm.durationS = sec },
                shape = SegmentedButtonDefaults.itemShape(i, options.size),
            ) { Text(label) }
        }
    }
    Text(
        "Uzun ölçüm, baskın frekansı gürültüden daha iyi ayırır. Önerilen: 2 dk.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Button(
        onClick = { vm.start() },
        enabled = vm.capability.available,
        modifier = Modifier.fillMaxWidth().height(52.dp),
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
    ) { Text("Ölçümü Başlat") }
}

@Composable
private fun BigStatus(big: String, text: String, onCancel: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(big, fontSize = 72.sp, style = BigValueStyle)
        Spacer(Modifier.size(12.dp))
        Text(text, textAlign = TextAlign.Center)
        Spacer(Modifier.size(20.dp))
        OutlinedButton(onClick = onCancel) { Text("İptal") }
    }
}

@Composable
private fun RecordingPanel(p: VibPhase.Recording, onCancel: () -> Unit) {
    val remaining = p.totalS - p.elapsedS
    Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Ölçülüyor", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.size(8.dp))
        Text("%d:%02d".format(remaining / 60, remaining % 60), style = BigValueStyle)
        Spacer(Modifier.size(12.dp))
        LinearProgressIndicator(progress = { p.elapsedS.toFloat() / p.totalS }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.size(16.dp))
        val (label, color) = when {
            p.level < 0.01 -> "Sakin" to ModuleType.VIBRATION.accent
            p.level < 0.05 -> "Hafif sarsıntı" to com.eirahoutmoss.nexyapi.ui.theme.NexColors.Amber
            else -> "Güçlü sarsıntı — telefona dokunulmuş olabilir" to MaterialTheme.colorScheme.error
        }
        Text("Ortam: $label", color = color, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.size(6.dp))
        Text("Telefona dokunmayın.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.size(20.dp))
        OutlinedButton(onClick = onCancel) { Text("İptal") }
    }
}

@Composable
private fun ResultPanel(vm: VibrationViewModel, p: VibPhase.Result) {
    val r = p.result
    val accent = ModuleType.VIBRATION.accent
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Baskın frekans", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(VibrationViewModel.summaryOf(r), style = BigValueStyle, color = accent)
            QualityChip(r.quality.toStored())
            r.notes.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
            if (r.spectrumFreqs.isNotEmpty()) {
                Text("Frekans spektrumu", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                SpectrumChart(r.spectrumFreqs, r.spectrumPower, r.dominantHz, accent)
            }
            val prev = vm.previousFor(vm.subject)
            if (prev != null && r.dominantHz != null) {
                val diff = r.dominantHz!! - prev.value!!
                val pct = diff / prev.value!! * 100
                Text(
                    "Aynı yerdeki önceki ölçüm: ${fmtNum(prev.value!!, 2)} Hz (${formatDate(prev.takenAt)}). " +
                        "Fark: ${if (diff >= 0) "+" else ""}${fmtNum(diff, 2)} Hz (${if (pct >= 0) "+" else ""}${fmtNum(pct, 1)}%).",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(
                "Örnekleme: ${r.sampling.rateHz.toInt()} örnek/sn · Süre: ${p.durationS} sn · Çözünürlük: ±${fmtNum(r.frequencyResolutionHz / 2, 3)} Hz",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (r.quality == Quality.UNRELIABLE) {
        Text(
            "Bu ölçüm \"güvenilmez\" olarak kaydedilir ve geçmiş grafiğinde gösterilmez. Tekrarlamanız önerilir.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedButton(onClick = { vm.discard() }, modifier = Modifier.weight(1f)) { Text("Sil, tekrarla") }
        Button(onClick = { vm.save() }, modifier = Modifier.weight(1f)) { Text("Kaydet") }
    }
    DisclaimerBanner()
}
