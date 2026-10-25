package com.eirahoutmoss.nexyapi

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.eirahoutmoss.nexyapi.ui.NexYapiNavHost
import com.eirahoutmoss.nexyapi.ui.components.SplashOverlay
import com.eirahoutmoss.nexyapi.ui.theme.NexYapiTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NexYapiTheme {
                // Açılış ekranı yalnızca uygulama soğuk başlarken; ekran dönünce yeniden çıkmaz.
                var splash by rememberSaveable { mutableStateOf(savedInstanceState == null) }
                Box(Modifier.fillMaxSize()) {
                    NexYapiNavHost()
                    if (splash) SplashOverlay(onFinished = { splash = false })
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Gün değişmiş olabilir: lisans süresini yeniden değerlendir.
        (application as NexYapiApp).container.licenses.refresh()
    }
}
