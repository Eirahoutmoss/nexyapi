package com.eirahoutmoss.nexyapi.ui.components

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.core.net.toUri
import com.eirahoutmoss.nexyapi.ui.appContainer
import java.io.File

/** Dosyadaki görüntüyü arka planda küçültüp gösterir; yokken/yüklenirken [placeholder]. */
@Composable
fun FileImage(
    file: File?,
    maxDimension: Int,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    placeholder: @Composable () -> Unit,
) {
    val images = appContainer().images
    // Dosya aynı adla yeniden yazılırsa da yenilensin diye değişiklik zamanı anahtara eklenir.
    val key = file?.let { it.path + ":" + it.lastModified() }
    val bmp by produceState<Bitmap?>(initialValue = null, key) {
        value = if (file != null && file.exists()) runCatching { images.loadPreview(file, maxDimension) }.getOrNull() else null
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        val b = bmp
        if (b != null) {
            Image(b.asImageBitmap(), contentDescription = null, contentScale = contentScale, modifier = Modifier.matchParentSize())
        } else {
            placeholder()
        }
    }
}

/** Kamera ya da galeriden fotoğraf alma. Seçilen görüntünün Uri'si [onPicked] ile döner. */
class PhotoSource internal constructor(
    val takePhoto: () -> Unit,
    val pickFromGallery: () -> Unit,
)

@Composable
fun rememberPhotoSource(onPicked: (Uri) -> Unit, onError: (String) -> Unit): PhotoSource {
    val images = appContainer().images
    // Kamera uygulaması açıkken ekran dönerse ya da süreç yeniden kurulursa hedef yol kaybolmasın.
    var pendingPath by rememberSaveable { mutableStateOf<String?>(null) }

    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val path = pendingPath
        pendingPath = null
        if (ok && path != null && File(path).length() > 0) onPicked(File(path).toUri())
        else if (path != null) File(path).delete()
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) onPicked(uri)
    }
    val launchCamera = {
        val (file, uri) = images.newCaptureTarget()
        pendingPath = file.path
        try {
            camera.launch(uri)
        } catch (e: ActivityNotFoundException) {
            pendingPath = null
            onError("Bu cihazda kamera uygulaması bulunamadı.")
        } catch (e: SecurityException) {
            pendingPath = null
            onError("Kamera izni gerekiyor.")
        }
    }
    // Uygulama CAMERA iznini bildirdiği için (AR modu) Android, izin verilmeden sistem kamerasını
    // açmaya izin vermez. Bu yüzden önce izin istenir.
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchCamera() else onError("Fotoğraf çekmek için kamera izni gerekiyor. Galeriden de seçebilirsiniz.")
    }
    val context = LocalContext.current
    return remember(camera, gallery, permission) {
        PhotoSource(
            takePhoto = {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) launchCamera()
                else permission.launch(Manifest.permission.CAMERA)
            },
            pickFromGallery = {
                try {
                    gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                } catch (e: ActivityNotFoundException) {
                    onError("Galeri açılamadı.")
                }
            },
        )
    }
}
