package com.eirahoutmoss.nexyapi.ui.screens

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.opengl.GLSurfaceView
import android.os.Build
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eirahoutmoss.nexyapi.ar.ArRenderer
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Session
import kotlinx.coroutines.delay

private sealed interface ArState {
    data object Checking : ArState
    data object NeedPermission : ArState
    data object Installing : ArState
    data class Unavailable(val message: String) : ArState
    data class Ready(val session: Session) : ArState
}

/**
 * Deneysel AR modu: telefonun geçtiği yerler, manyetik sapmaya göre renkli noktalar olarak sahnede
 * kalır. Önce Dedektör sekmesinde kalibrasyon yapılmalıdır. Düz, desensiz duvarlarda ARCore
 * konum bulamayabilir; bu durum ekranda açıkça yazılır.
 */
@Composable
fun ArScanPanel(vm: DrillViewModel) {
    val context = LocalContext.current
    val activity = context as? Activity
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var state by remember { mutableStateOf<ArState>(ArState.Checking) }
    var retry by remember { mutableIntStateOf(0) }
    var userRequestedInstall by remember { mutableStateOf(true) }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) retry++ else state = ArState.Unavailable("AR modu için kamera izni gerekiyor.")
    }

    LaunchedEffect(retry) {
        if (activity == null) { state = ArState.Unavailable("AR başlatılamadı."); return@LaunchedEffect }
        var availability = ArCoreApk.getInstance().checkAvailability(context)
        var tries = 0
        while (availability.isTransient && tries < 20) { delay(200); availability = ArCoreApk.getInstance().checkAvailability(context); tries++ }
        if (!availability.isSupported) {
            state = ArState.Unavailable("Bu telefon ARCore'u desteklemiyor. Dedektör ve fotoğraf işaretleme modlarını kullanın.")
            return@LaunchedEffect
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            state = ArState.NeedPermission
            return@LaunchedEffect
        }
        try {
            when (ArCoreApk.getInstance().requestInstall(activity, userRequestedInstall)) {
                ArCoreApk.InstallStatus.INSTALL_REQUESTED -> { userRequestedInstall = false; state = ArState.Installing; return@LaunchedEffect }
                ArCoreApk.InstallStatus.INSTALLED -> Unit
                else -> Unit
            }
            val session = Session(context)
            val cfg = Config(session).apply {
                updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                focusMode = Config.FocusMode.AUTO
                planeFindingMode = Config.PlaneFindingMode.DISABLED
                lightEstimationMode = Config.LightEstimationMode.DISABLED
            }
            session.configure(cfg)
            state = ArState.Ready(session)
        } catch (e: Exception) {
            state = ArState.Unavailable("AR başlatılamadı: ${e.javaClass.simpleName}. Google Play Hizmetleri (AR) yüklü ve güncel olmalı.")
        }
    }

    // ARCore kurulumundan dönünce yeniden dene
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME && state is ArState.Installing) retry++ }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    when (val s = state) {
        ArState.Checking, ArState.Installing -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(if (s is ArState.Installing) "ARCore kuruluyor…" else "AR denetleniyor…")
        }
        ArState.NeedPermission -> Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text("AR modu kamerayı kullanır. Görüntü yalnızca cihazda işlenir.", style = MaterialTheme.typography.bodyMedium)
            Button(onClick = { permission.launch(Manifest.permission.CAMERA) }, modifier = Modifier.padding(top = 12.dp)) { Text("Kamera izni ver") }
        }
        is ArState.Unavailable -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(s.message, style = MaterialTheme.typography.bodyMedium)
        }
        is ArState.Ready -> ArView(vm, s.session)
    }
}

@Composable
private fun ArView(vm: DrillViewModel, session: Session) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var status by remember { mutableStateOf<String?>("AR konumu arıyor…") }
    var count by remember { mutableIntStateOf(0) }
    var recording by remember { mutableStateOf(true) }

    val glView = remember {
        GLSurfaceView(context).apply {
            preserveEGLContextOnPause = true
            setEGLContextClientVersion(2)
            setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        }
    }
    val renderer = remember {
        ArRenderer(
            session = session,
            displayRotation = { displayRotation(context) },
            reading = {
                val r = vm.reading.value
                if (r == null) null else r.level.toFloat() to r.detected
            },
            onStatus = { s -> glView.post { status = s } },
        ).also {
            glView.setRenderer(it)
            glView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
    }

    DisposableEffect(lifecycle) {
        fun resume() {
            try { session.resume(); glView.onResume() } catch (e: Exception) { status = "Kamera açılamadı: ${e.javaClass.simpleName}" }
        }
        fun pause() { glView.onPause(); runCatching { session.pause() } }
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_RESUME -> resume()
                Lifecycle.Event.ON_PAUSE -> pause()
                else -> Unit
            }
        }
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) resume()
        lifecycle.addObserver(obs)
        val window = (context as? Activity)?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            lifecycle.removeObserver(obs)
            pause()
            runCatching { session.close() }
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
    LaunchedEffect(Unit) { while (true) { count = renderer.pointCount; delay(300) } }

    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { glView }, modifier = Modifier.fillMaxSize())
        Column(Modifier.align(Alignment.TopCenter).padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            val reading by vm.reading.collectAsStateWithLifecycle()
            val r = reading
            Surface(color = Color.Black.copy(alpha = 0.55f), shape = MaterialTheme.shapes.small) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Text(
                        if (r == null) "Önce Dedektör sekmesinde kalibre edin." else "Sapma: ${String.format(java.util.Locale.forLanguageTag("tr"), "%.1f", r.anomaly)} µT" + if (r.detected) " · METAL" else "",
                        color = Color.White, style = MaterialTheme.typography.bodyMedium,
                    )
                    status?.let { Text(it, color = Color(0xFFF2B134), style = MaterialTheme.typography.bodySmall) }
                    Text("$count nokta · DENEYSEL", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Button(onClick = { recording = !recording; renderer.recording = recording }, modifier = Modifier.weight(1f)) {
                Text(if (recording) "Kaydı duraklat" else "Kayda devam")
            }
            OutlinedButton(onClick = { renderer.clear() }, modifier = Modifier.weight(1f)) { Text("Temizle", color = Color.White) }
        }
    }
}

@Suppress("DEPRECATION")
private fun displayRotation(context: Context): Int =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) context.display?.rotation ?: 0
    else (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation
