package io.github.carnager.jot.ui

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import coil3.compose.AsyncImagePainter
import coil3.compose.SubcomposeAsyncImage
import coil3.compose.SubcomposeAsyncImageContent
import coil3.request.ImageRequest
import io.github.carnager.jot.data.Api
import io.github.carnager.jot.data.Images
import io.github.carnager.jot.data.Repo
import io.github.carnager.jot.model.ImageRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import kotlin.math.abs

/** What Coil loads for [ref]: the local file while this device has it, else the server thumbnail. */
fun imageModel(context: Context, ref: ImageRef, width: Int?): Any {
    Images.localFile(context, ref.id)?.let { return it }
    val server = Repo.settings.current.server
    return Api.attachmentUrl(server, ref.id, width)
}

/**
 * One attachment. Crossfades in; while it can't load (e.g. a 404 because another device hasn't
 * uploaded it yet) it shows a neutral placeholder and tries again a little later.
 */
@Composable
fun AttachmentImage(
    ref: ImageRef,
    width: Int,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val ctx = LocalContext.current
    var attempt by remember(ref.id) { mutableIntStateOf(0) }
    var failed by remember(ref.id) { mutableStateOf(false) }
    // One request per image and attempt: same data + size keys every time, so a revisit hits the memory cache.
    val request = remember(ref.id, width, attempt) {
        ImageRequest.Builder(ctx).data(imageModel(ctx, ref, width)).memoryCacheKey("${ref.id}@$width").build()
    }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
        // Plain AsyncImage: no subcomposition per image, unlike SubcomposeAsyncImage.
        coil3.compose.AsyncImage(
            model = request,
            contentDescription = null,
            contentScale = contentScale,
            modifier = Modifier.fillMaxSize(),
            onState = { st -> failed = st is AsyncImagePainter.State.Error },
        )
        if (failed) {
            Icon(Icons.Outlined.Image, null, Modifier.align(Alignment.Center), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
            LaunchedEffect(attempt) { delay(15_000L + attempt * 15_000L); attempt++ }
        }
    }
}

@Composable
private fun ImagePlaceholder(loading: Boolean = false) {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
        if (!loading) Icon(Icons.Outlined.Image, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
    }
}

// ---- the viewer -------------------------------------------------------------------------------

/** Opens the full-screen viewer; [openId] is the image currently shown there (it hides its thumbnail). */
@Stable
class ImageViewerState {
    var images by mutableStateOf<List<ImageRef>>(emptyList())
    var index by mutableIntStateOf(0)
    var visible by mutableStateOf(false)
    val openId: String? get() = if (visible) images.getOrNull(index)?.id else null
    fun open(list: List<ImageRef>, i: Int) { images = list; index = i; visible = true }
    fun close() { visible = false }
}

val LocalImageViewer = compositionLocalOf<ImageViewerState?> { null }

/** A thumbnail that flies into the viewer. */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.sharedImage(id: String): Modifier {
    val shared = LocalSharedScope.current ?: return this
    val viewer = LocalImageViewer.current ?: return this
    return with(shared) {
        this@sharedImage.sharedElementWithCallerManagedVisibility(
            rememberSharedContentState("img-$id"),
            visible = viewer.openId != id,
        )
    }
}

/** Keep-style images on a card: 1 full width, 2–4 as a mosaic, "+N" beyond. */
@Composable
fun ImageMosaic(images: List<ImageRef>, shape: Shape, modifier: Modifier = Modifier) {
    if (images.isEmpty()) return
    val viewer = LocalImageViewer.current
    val gap = 2.dp
    @Composable
    fun cell(i: Int, m: Modifier) {
        val ref = images[i]
        Box(m.clickable(enabled = viewer != null) { viewer?.open(images, i) }) {
            AttachmentImage(ref, 480, Modifier.fillMaxSize().sharedImage(ref.id))
            val more = images.size - 4
            if (i == 3 && more > 0) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
                    Text("+$more", style = MaterialTheme.typography.headlineSmall, color = Color.White)
                }
            }
        }
    }
    val first = images[0]
    Box(modifier.fillMaxWidth().clip(shape)) {
        when (images.size) {
            1 -> cell(0, Modifier.fillMaxWidth().aspectRatio(first.ratio.coerceIn(0.6f, 2f)))
            2 -> Row(Modifier.fillMaxWidth().aspectRatio(1.6f), horizontalArrangement = Arrangement.spacedBy(gap)) {
                cell(0, Modifier.weight(1f).fillMaxSize()); cell(1, Modifier.weight(1f).fillMaxSize())
            }
            3 -> Row(Modifier.fillMaxWidth().aspectRatio(1.4f), horizontalArrangement = Arrangement.spacedBy(gap)) {
                cell(0, Modifier.weight(1.4f).fillMaxSize())
                Column(Modifier.weight(1f).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(gap)) {
                    cell(1, Modifier.weight(1f).fillMaxWidth()); cell(2, Modifier.weight(1f).fillMaxWidth())
                }
            }
            else -> Column(Modifier.fillMaxWidth().aspectRatio(1.2f), verticalArrangement = Arrangement.spacedBy(gap)) {
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                    cell(0, Modifier.weight(1f).fillMaxSize()); cell(1, Modifier.weight(1f).fillMaxSize())
                }
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                    cell(2, Modifier.weight(1f).fillMaxSize()); cell(3, Modifier.weight(1f).fillMaxSize())
                }
            }
        }
    }
}

/**
 * Full-screen viewer: swipe between images, pinch or double-tap to zoom, swipe down to close.
 * Shown above everything in the shared transition, so the open image flies out of its thumbnail.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun ImageViewer(state: ImageViewerState) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val shared = LocalSharedScope.current
    val dismiss = remember { Animatable(0f) }
    BackHandler(state.visible) { state.close() }
    LaunchedEffect(state.visible) { if (state.visible) dismiss.snapTo(0f) }
    val bgAlpha = (1f - abs(dismiss.value) / 900f).coerceIn(0f, 1f)

    AnimatedVisibility(state.visible, enter = fadeIn(tween(Motion.MEDIUM)), exit = fadeOut(tween(Motion.MEDIUM))) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = bgAlpha))) {
            val pager = rememberPagerState(initialPage = state.index) { state.images.size }
            LaunchedEffect(pager.currentPage) { state.index = pager.currentPage }
            HorizontalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = 1, key = { state.images[it].id }) { page ->
                val ref = state.images[page]
                ZoomableImage(
                    ref,
                    sharedModifier = if (shared != null) with(shared) {
                        Modifier.sharedElementWithCallerManagedVisibility(rememberSharedContentState("img-${ref.id}"), visible = state.visible && state.index == page)
                    } else Modifier,
                    dismissOffset = if (page == pager.currentPage) dismiss.value else 0f,
                    onDismissDrag = { dy -> scope.launch { dismiss.snapTo(dismiss.value + dy) } },
                    onDismissEnd = {
                        if (abs(dismiss.value) > 220f) state.close()
                        else scope.launch { dismiss.animateTo(0f, tween(Motion.MEDIUM)) }
                    },
                )
            }
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 4.dp)
                    .graphicsLayer { alpha = bgAlpha },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { state.close() }) { Icon(Icons.Rounded.Close, "Close", tint = Color.White) }
                if (state.images.size > 1) Text("${state.index + 1} / ${state.images.size}", color = Color.White, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                val current = state.images.getOrNull(state.index)
                IconButton(onClick = { current?.let { r -> scope.launch { shareImage(ctx, r) } } }) {
                    Icon(Icons.Outlined.Share, "Share", tint = Color.White)
                }
                IconButton(onClick = { current?.let { r -> scope.launch { saveImage(ctx, r) } } }) {
                    Icon(Icons.Outlined.Download, "Save to gallery", tint = Color.White)
                }
            }
            Box(Modifier.fillMaxWidth().navigationBarsPadding().height(8.dp).align(Alignment.BottomCenter))
        }
    }
}

@Composable
private fun ZoomableImage(
    ref: ImageRef,
    sharedModifier: Modifier,
    dismissOffset: Float,
    onDismissDrag: (Float) -> Unit,
    onDismissEnd: () -> Unit,
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val scope = rememberCoroutineScope()
    val anim = remember { Animatable(1f) }
    val slop = with(LocalDensity.current) { 10.dp.toPx() }
    Box(
        Modifier.fillMaxSize()
            .pointerInput(ref.id) {
                detectTapGestures(onDoubleTap = { tap ->
                    scope.launch {
                        val target = if (scale > 1.05f) 1f else 2.5f
                        val startScale = scale
                        val startOffset = offset
                        // Zoom towards the tapped point.
                        val center = Offset(size.width / 2f, size.height / 2f)
                        val endOffset = if (target == 1f) Offset.Zero else (center - tap) * (target - 1f)
                        anim.snapTo(0f)
                        anim.animateTo(1f, tween(Motion.MEDIUM)) {
                            scale = startScale + (target - startScale) * value
                            offset = startOffset + (endOffset - startOffset) * value
                        }
                    }
                })
            }
            .pointerInput(ref.id) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var mode = 0 // 0 undecided, 1 transform, 2 dismiss, 3 let the pager have it
                    var total = Offset.Zero
                    do {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break
                        if (pressed.size > 1 || scale > 1.01f) mode = 1
                        when (mode) {
                            1 -> {
                                val zoom = event.calculateZoom()
                                val pan = event.calculatePan()
                                val centroid = event.calculateCentroid()
                                val newScale = (scale * zoom).coerceIn(1f, 5f)
                                val center = Offset(size.width / 2f, size.height / 2f)
                                offset = (offset + pan + (centroid - center - offset) * (1 - newScale / scale))
                                scale = newScale
                                if (scale <= 1.01f) offset = Offset.Zero
                                event.changes.forEach { it.consume() }
                            }
                            0 -> {
                                total += pressed[0].positionChange()
                                if (total.getDistance() > slop) mode = if (abs(total.y) > abs(total.x) * 1.4f) 2 else 3
                            }
                            2 -> {
                                onDismissDrag(pressed[0].positionChange().y)
                                event.changes.forEach { it.consume() }
                            }
                        }
                    } while (true)
                    if (mode == 2) onDismissEnd()
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        AttachmentImage(
            ref, 1600,
            Modifier.fillMaxWidth().aspectRatio(ref.ratio)
                .then(sharedModifier)
                .graphicsLayer {
                    scaleX = scale * (1f - abs(dismissOffset) / 3000f); scaleY = scaleX
                    translationX = offset.x; translationY = offset.y + dismissOffset
                },
            contentScale = ContentScale.Fit,
        )
    }
}

/** The original bytes in a shareable file: the local copy, or downloaded once into the cache. */
private suspend fun originalFile(ctx: Context, ref: ImageRef): File? = withContext(Dispatchers.IO) {
    Images.localFile(ctx, ref.id)?.let { return@withContext it }
    val p = Repo.settings.current
    if (!p.signedIn) return@withContext null
    val out = File(File(ctx.cacheDir, "shared").apply { mkdirs() }, ref.id + if (ref.mime == "image/gif") ".gif" else ".jpg")
    if (out.isFile) return@withContext out
    runCatching {
        Api.client.newCall(Request.Builder().url(Api.attachmentUrl(p.server, ref.id)).header("Authorization", "Bearer ${p.token}").build()).execute().use { r ->
            if (!r.isSuccessful) return@withContext null
            out.outputStream().use { r.body.byteStream().copyTo(it) }
        }
        out
    }.getOrNull()
}

private suspend fun shareImage(ctx: Context, ref: ImageRef) {
    val file = originalFile(ctx, ref) ?: run { Toast.makeText(ctx, "Image isn't available yet", Toast.LENGTH_SHORT).show(); return }
    val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", file)
    val send = Intent(Intent.ACTION_SEND).setType(ref.mime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    ctx.startActivity(Intent.createChooser(send, null))
}

private suspend fun saveImage(ctx: Context, ref: ImageRef) {
    val file = originalFile(ctx, ref) ?: run { Toast.makeText(ctx, "Image isn't available yet", Toast.LENGTH_SHORT).show(); return }
    val ok = withContext(Dispatchers.IO) {
        runCatching {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "jot-${ref.id}.${file.extension}")
                put(MediaStore.Images.Media.MIME_TYPE, ref.mime)
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Jot")
            }
            val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
            ctx.contentResolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
        }.isSuccess
    }
    Toast.makeText(ctx, if (ok) "Saved to Pictures/Jot" else "Couldn't save the image", Toast.LENGTH_SHORT).show()
}
