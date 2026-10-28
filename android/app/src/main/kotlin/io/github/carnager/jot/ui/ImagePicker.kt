package io.github.carnager.jot.ui

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import io.github.carnager.jot.data.Images
import io.github.carnager.jot.model.ImageRef
import kotlinx.coroutines.launch
import java.io.File

/** Camera (into a FileProvider uri) and the system Photo Picker; both hand back imported [ImageRef]s. */
class ImagePicker(val camera: () -> Unit, val gallery: () -> Unit, val busy: () -> Boolean)

@Composable
fun rememberImagePicker(onImages: (List<ImageRef>) -> Unit): ImagePicker {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val deliver by rememberUpdatedState(onImages)
    var busy by remember { mutableStateOf(false) }
    var cameraPath by rememberSaveable { mutableStateOf<String?>(null) }

    fun importAll(uris: List<Uri>, cleanup: File? = null) {
        if (uris.isEmpty()) return
        busy = true
        scope.launch {
            val refs = uris.mapNotNull { Images.import(ctx, it) }
            cleanup?.delete()
            busy = false
            if (refs.size < uris.size) Toast.makeText(ctx, "Some images couldn't be read", Toast.LENGTH_SHORT).show()
            if (refs.isNotEmpty()) deliver(refs)
        }
    }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(20)) { uris -> importAll(uris) }
    val shoot = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val f = cameraPath?.let(::File)
        if (ok && f != null && f.length() > 0) importAll(listOf(Uri.fromFile(f)), cleanup = f) else f?.delete()
    }
    return remember {
        ImagePicker(
            camera = {
                val f = Images.cameraFile(ctx)
                cameraPath = f.path
                runCatching { shoot.launch(FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)) }
                    .onFailure { Toast.makeText(ctx, "No camera app available", Toast.LENGTH_SHORT).show() }
            },
            gallery = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            busy = { busy },
        )
    }
}
