package io.github.carnager.jot.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.Log
import io.github.carnager.jot.model.Ids
import io.github.carnager.jot.model.ImageRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Images on this device (SPEC §2a). New images are downscaled and written to `pending/`,
 * referenced by the item at once and uploaded in the background ([UploadWorker]). Uploaded
 * originals move to `images/`, so this device keeps showing them without a download.
 */
object Images {
    private const val TAG = "JotImages"
    const val MAX_EDGE = 2560
    const val QUALITY = 85

    private fun dir(context: Context, name: String) = File(context.filesDir, name).apply { mkdirs() }
    fun pendingDir(context: Context) = dir(context, "pending")
    private fun keptDir(context: Context) = dir(context, "images")

    private fun ext(mime: String) = if (mime == "image/gif") "gif" else "jpg"

    /** id → local file (or a miss), so composition never stats files on the main thread twice. */
    private val located = java.util.concurrent.ConcurrentHashMap<String, java.util.Optional<File>>()

    /** The local file for [ref], pending or uploaded, if this device has it. */
    fun localFile(context: Context, id: String): File? =
        located.getOrPut(id) {
            java.util.Optional.ofNullable(
                sequenceOf(pendingDir(context), keptDir(context))
                    .flatMap { d -> sequenceOf(File(d, "$id.jpg"), File(d, "$id.gif")) }
                    .firstOrNull { it.isFile },
            )
        }.orElse(null)?.takeIf { it.isFile }

    fun isPending(context: Context, id: String) =
        File(pendingDir(context), "$id.jpg").isFile || File(pendingDir(context), "$id.gif").isFile

    fun pendingFiles(context: Context): List<File> = pendingDir(context).listFiles()?.filter { it.isFile }.orEmpty()

    fun markUploaded(context: Context, file: File) {
        val target = File(keptDir(context), file.name)
        if (file.renameTo(target)) located[file.nameWithoutExtension] = java.util.Optional.of(target)
        else { file.delete(); located.remove(file.nameWithoutExtension) }
    }

    /** Long edge at most [max]; never upscales. */
    fun targetSize(w: Int, h: Int, max: Int = MAX_EDGE): Pair<Int, Int> {
        val long = maxOf(w, h)
        if (long <= max || long <= 0) return w to h
        val f = max.toDouble() / long
        return maxOf(1, Math.round(w * f).toInt()) to maxOf(1, Math.round(h * f).toInt())
    }

    /**
     * Imports an image from [uri]: GIFs are kept as they are; everything else is decoded with
     * EXIF orientation applied, downscaled and re-encoded as JPEG (which drops all metadata).
     */
    suspend fun import(context: Context, uri: Uri): ImageRef? = withContext(Dispatchers.IO) {
        try {
            val mime = context.contentResolver.getType(uri) ?: ""
            val id = Ids.ulid()
            if (mime == "image/gif" || sniffGif(context, uri)) {
                val out = File(pendingDir(context), "$id.gif")
                context.contentResolver.openInputStream(uri)!!.use { input -> out.outputStream().use { input.copyTo(it) } }
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(out.path, o)
                located[id] = java.util.Optional.of(out)
                return@withContext ImageRef(id, o.outWidth, o.outHeight, "image/gif")
            }
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val (w, h) = targetSize(info.size.width, info.size.height)
                decoder.setTargetSize(w, h)
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.isMutableRequired = false
            }
            // JPEG has no alpha: flatten transparent images onto white.
            val flat = if (bitmap.hasAlpha()) {
                Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888).also {
                    val c = Canvas(it); c.drawColor(Color.WHITE); c.drawBitmap(bitmap, 0f, 0f, null)
                }
            } else bitmap
            val out = File(pendingDir(context), "$id.jpg")
            out.outputStream().use { flat.compress(Bitmap.CompressFormat.JPEG, QUALITY, it) }
            located[id] = java.util.Optional.of(out)
            ImageRef(id, flat.width, flat.height, "image/jpeg")
        } catch (e: Exception) {
            Log.w(TAG, "import failed for $uri", e)
            null
        }
    }

    private fun sniffGif(context: Context, uri: Uri): Boolean = runCatching {
        context.contentResolver.openInputStream(uri)!!.use { val b = ByteArray(6); it.read(b) == 6 && String(b, Charsets.US_ASCII).startsWith("GIF8") }
    }.getOrDefault(false)

    /** A temp file for the camera to write into (shared through the FileProvider). */
    fun cameraFile(context: Context): File = File(File(context.cacheDir, "camera").apply { mkdirs() }, "${Ids.short()}.jpg")
}
