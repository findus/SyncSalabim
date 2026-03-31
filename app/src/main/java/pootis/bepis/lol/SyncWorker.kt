package pootis.bepis.lol

import android.content.Context
import android.net.Uri
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okio.source
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.*

class SyncWorker(appContext: Context, workerParams: WorkerParameters) :
    BaseSyncWorker(appContext, workerParams) {

    override val tag = "SyncWorker"
    override val notificationId = 1

    override suspend fun doWork(): Result {
        log("Starting sync worker...")

        val baseUrlStr = inputData.getString("baseUrl")?.removeSuffix("/") ?: return Result.failure()
        val user = inputData.getString("user") ?: return Result.failure()
        val password = inputData.getString("password") ?: return Result.failure()
        val selectedFolders = inputData.getStringArray("selectedFolders")?.toSet() ?: emptySet()
        val isBackgroundTask = inputData.getBoolean("isBackgroundTask", false)

        val bgt = if (isBackgroundTask) { "Background" } else { "" };

        createNotificationChannel()
        
        // Show launched notification if triggered in background
        if (runAttemptCount == 0) {
            showLaunchedNotification("$bgt Photo Sync", "Synchronization started.")
        }

        try {
            setForeground(createForegroundInfo(0, 0, "Initializing sync...", "Syncing Photos"))
        } catch (e: Exception) {
            log("Failed to set foreground info", e)
        }


        val basicAuth = Credentials.basic(user, password)
        val baseUrl = baseUrlStr.toHttpUrl()

        try {
            val allLocalItems = getAllLocalMedia(selectedFolders)
            val itemsToSync = allLocalItems.filter { !db.photoDao().isSynced(it.id) }

            val total = itemsToSync.size
            log("Total items to sync: $total")

            if (total == 0) {
                setProgress(workDataOf("progress" to 1f, "current" to 0, "total" to 0, "name" to "Done"))
                showFinishedNotification("$bgt Sync Finished", "Everything is up to date.")
                return Result.success()
            }

            // Step 1: Pre-create folders
            log("Pre-creating folder structure...")
            val requiredPaths = itemsToSync.map { item ->
                val date = Date(if (item.dateTaken > 0) item.dateTaken else System.currentTimeMillis())
                val year = SimpleDateFormat("yyyy", Locale.US).format(date)
                val month = SimpleDateFormat("MM", Locale.US).format(date)
                year to month
            }.distinct()

            for ((year, month) in requiredPaths) {
                createDirectory(baseUrl.toString(), year, basicAuth)
                createDirectory("$baseUrl/$year", month, basicAuth)
            }

            // Step 2: Upload items
            var successCount = 0
            var errorCount = 0
            for ((index, item) in itemsToSync.withIndex()) {
                val current = index + 1
                val progressValue = index.toFloat() / total

                log("Uploading ($current/$total): ${item.name}")
                setProgress(workDataOf("progress" to progressValue, "current" to current, "total" to total, "name" to item.name, "errorCount" to errorCount))
                updateProgressNotification(current, total, item.name, "Syncing Photos")

                val responseCode = uploadMedia(item, baseUrl, basicAuth)
                if (responseCode in 200..299) {
                    db.photoDao().insert(SyncedPhoto(item.id, item.name, System.currentTimeMillis()))
                    log("Successfully synced: ${item.name} [$responseCode]")
                    successCount++
                } else {
                    errorCount++
                    log("ERROR: Failed to upload ${item.name} [HTTP $responseCode] ($errorCount error(s) so far)")
                    setProgress(workDataOf("progress" to progressValue, "current" to current, "total" to total, "name" to item.name, "errorCount" to errorCount))
                }
            }

            setProgress(workDataOf("progress" to 1f, "current" to total, "total" to total, "name" to "Done", "errorCount" to errorCount))
            if (errorCount > 0) {
                log("ERROR: ${bgt}Sync finished with $errorCount error(s). Synced $successCount/$total items.")
                showFinishedNotification("$bgt Sync Finished", "Synced $successCount items, $errorCount failed.")
            } else {
                log("${bgt}Sync completed. Synced $successCount items.")
                showFinishedNotification("$bgt Sync Finished", "Successfully synced $successCount items.")
            }
            
            return Result.success()
        } catch (e: Exception) {
            log("$bgt Sync failed with exception", e)
            showFinishedNotification("$bgt Sync Failed", e.message ?: "An unexpected error occurred.")
            return Result.retry()
        }
    }

    private fun uploadMedia(item: MediaItem, baseUrl: okhttp3.HttpUrl, basicAuth: String): Int {
        val targetUrl = getRemoteUrl(item, baseUrl)
        val uri = Uri.withAppendedPath(item.collection, item.id.toString())

        return try {
            val inputStream: InputStream = applicationContext.contentResolver.openInputStream(uri) ?: run {
                log("ERROR: Could not open stream for ${item.name}")
                return -1
            }

            val requestBody = object : okhttp3.RequestBody() {
                override fun contentType() = item.mimeType?.toMediaTypeOrNull()
                override fun contentLength() = item.size
                override fun writeTo(sink: okio.BufferedSink) {
                    inputStream.use { sink.writeAll(it.source()) }
                }
            }

            val request = okhttp3.Request.Builder()
                .url(targetUrl)
                .put(requestBody)
                .addHeader("Authorization", basicAuth)
                .build()

            client.newCall(request).execute().use { response -> response.code }
        } catch (t: Throwable) {
            log("ERROR: Exception during upload of ${item.name}: ${t::class.simpleName}: ${t.message}")
            -1
        }
    }
}
