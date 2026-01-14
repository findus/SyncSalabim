package pootis.bepis.lol

import android.content.Context
import android.net.Uri
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
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
                showFinishedNotification("Sync Finished", "Everything is up to date.")
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
            for ((index, item) in itemsToSync.withIndex()) {
                val current = index + 1
                val progressValue = index.toFloat() / total
                
                log("Uploading ($current/$total): ${item.name}")
                setProgress(workDataOf("progress" to progressValue, "current" to current, "total" to total, "name" to item.name))
                updateProgressNotification(current, total, item.name, "Syncing Photos")

                if (uploadMedia(item, baseUrl, basicAuth)) {
                    db.photoDao().insert(SyncedPhoto(item.id, item.name, System.currentTimeMillis()))
                    log("Successfully synced: ${item.name}")
                    successCount++
                } else {
                    log("Failed to upload: ${item.name}")
                }
            }

            setProgress(workDataOf("progress" to 1f, "current" to total, "total" to total, "name" to "Done"))
            log(" ${bgt} Sync completed successfully. Synced $successCount items.")
            showFinishedNotification("$bgt Sync Finished", "Successfully synced $successCount items.")
            
            return Result.success()
        } catch (e: Exception) {
            log("Sync failed with exception", e)
            showFinishedNotification("Sync Failed", e.message ?: "An unexpected error occurred.")
            return Result.retry()
        }
    }

    private fun uploadMedia(item: MediaItem, baseUrl: okhttp3.HttpUrl, basicAuth: String): Boolean {
        val targetUrl = getRemoteUrl(item, baseUrl)
        val uri = Uri.withAppendedPath(item.collection, item.id.toString())

        return try {
            val inputStream: InputStream? = applicationContext.contentResolver.openInputStream(uri)
            val bytes = inputStream?.readBytes() ?: run {
                log("Could not read bytes for ${item.name}")
                return false
            }

            val request = okhttp3.Request.Builder()
                .url(targetUrl)
                .put(bytes.toRequestBody(item.mimeType?.toMediaTypeOrNull()))
                .addHeader("Authorization", basicAuth)
                .build()

            client.newCall(request).execute().use { response ->
                response.isSuccessful || response.code == 201 || response.code == 204
            }
        } catch (e: Exception) {
            log("Exception during upload of ${item.name}", e)
            false
        }
    }
}
