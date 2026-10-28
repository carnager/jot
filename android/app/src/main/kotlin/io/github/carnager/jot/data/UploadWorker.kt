package io.github.carnager.jot.data

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** Uploads pending images, then syncs so the items that reference them can go out (SPEC §2a order). */
class UploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        Repo.init(applicationContext)
        val p = Repo.settings.current
        if (!p.signedIn) return Result.success()
        val referenced = Repo.items.value.filter { !it.deleted }.flatMap { it.images }.map { it.id }.toSet()
        var failed = false
        for (file in Images.pendingFiles(applicationContext)) {
            val id = file.nameWithoutExtension
            if (id !in referenced) {
                // Removed again before it was uploaded.
                file.delete(); continue
            }
            val mime = if (file.extension == "gif") "image/gif" else "image/jpeg"
            try {
                Api.upload(p.server, p.token, id, file, mime)
                Images.markUploaded(applicationContext, file)
            } catch (e: ApiException) {
                Log.w("JotUpload", "upload $id: ${e.status}")
                if (e.status == 401) return Result.failure()
                failed = true
            } catch (e: Exception) {
                Log.w("JotUpload", "upload $id: ${e.message}")
                failed = true
            }
        }
        Repo.requestSync(0)
        return if (failed) Result.retry() else Result.success()
    }

    companion object {
        fun enqueue(context: Context) {
            val req = OneTimeWorkRequestBuilder<UploadWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("uploads", ExistingWorkPolicy.APPEND_OR_REPLACE, req)
        }
    }
}
