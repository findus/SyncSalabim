package pootis.bepis.lol

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.*

private const val TAG = "SyncWorker"
private const val CHANNEL_ID = "sync_notification_channel"

class SyncWorker(appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {

    private val db = SyncDatabase.getDatabase(appContext)

    private val client = OkHttpClient.Builder()
        .build()

    private fun log(message: String, error: Throwable? = null) {
        if (error != null) {
            Log.e(TAG, message, error)
            AppLogger.log("ERROR: $message - ${error.message}")
        } else {
            Log.d(TAG, message)
            AppLogger.log(message)
        }
    }

    override suspend fun doWork(): Result {
        log("Starting sync worker...")
        val baseUrlStr = inputData.getString("baseUrl")?.removeSuffix("/") ?: run {
            log("Sync failed: Missing baseUrl")
            return Result.failure()
        }
        val user = inputData.getString("user") ?: run {
            log("Sync failed: Missing user")
            return Result.failure()
        }
        val password = inputData.getString("password") ?: run {
            log("Sync failed: Missing password")
            return Result.failure()
        }

        val basicAuth = Credentials.basic(user, password)
        val baseUrl = baseUrlStr.toHttpUrl()

        try {
            val itemsToSync = mutableListOf<MediaItem>()
            itemsToSync.addAll(getPendingItems(MediaStore.Images.Media.EXTERNAL_CONTENT_URI))
            itemsToSync.addAll(getPendingItems(MediaStore.Video.Media.EXTERNAL_CONTENT_URI))

            val total = itemsToSync.size
            log("Total items to sync: $total")

            if (total == 0) {
                setProgress(workDataOf("progress" to 1f, "current" to 0, "total" to 0, "name" to "Done"))
                showNotification("Sync Finished", "Everything is up to date.")
                return Result.success()
            }

            // Step 1: Pre-create all required folders
            log("Pre-creating folder structure...")
            val requiredPaths = itemsToSync.map { item ->
                val date = Date(if (item.dateTaken > 0) item.dateTaken else System.currentTimeMillis())
                val year = SimpleDateFormat("yyyy", Locale.US).format(date)
                val month = SimpleDateFormat("MM", Locale.US).format(date)
                year to month
            }.distinct()

            val createdYears = mutableSetOf<String>()
            for ((year, month) in requiredPaths) {
                if (year !in createdYears) {
                    createDirectory(baseUrl.toString(), year, basicAuth)
                    createdYears.add(year)
                }
                createDirectory("$baseUrl/$year", month, basicAuth)
            }

            // Step 2: Upload items
            var successCount = 0
            for ((index, item) in itemsToSync.withIndex()) {
                val current = index + 1
                log("Uploading ($current/$total): ${item.name}")

                setProgress(workDataOf(
                    "progress" to (index.toFloat() / total),
                    "current" to current,
                    "total" to total,
                    "name" to item.name
                ))

                if (uploadMedia(item, baseUrl, basicAuth)) {
                    db.photoDao().insert(SyncedPhoto(item.id, item.name, System.currentTimeMillis()))
                    log("Successfully synced: ${item.name}")
                    successCount++
                } else {
                    log("Failed to upload: ${item.name}")
                }
            }

            // Ensure we hit exactly 100% and show "Done" at the very end
            setProgress(workDataOf(
                "progress" to 1f,
                "current" to total,
                "total" to total,
                "name" to "Done"
            ))

            log("Sync completed successfully. Synced $successCount items.")
            showNotification("Sync Finished", "Successfully synced $successCount items.")
            
            return Result.success()
        } catch (e: Exception) {
            log("Sync failed with exception", e)
            showNotification("Sync Failed", e.message ?: "An unexpected error occurred.")
            return Result.retry()
        }
    }

    private fun showNotification(title: String, message: String) {
        val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Photo Sync Status",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            notificationManager.createNotificationChannel(channel)
        }

        val builder = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)

        notificationManager.notify(System.currentTimeMillis().toInt(), builder.build())
    }

    private suspend fun getPendingItems(collection: Uri): List<MediaItem> {
        val pending = mutableListOf<MediaItem>()
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.DATE_TAKEN,
            MediaStore.MediaColumns.MIME_TYPE
        )

        applicationContext.contentResolver.query(
            collection,
            projection,
            null,
            null,
            "${MediaStore.MediaColumns.DATE_TAKEN} DESC"
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_TAKEN)
            val mimeColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                if (!db.photoDao().isSynced(id)) {
                    pending.add(MediaItem(
                        id = id,
                        name = cursor.getString(nameColumn),
                        dateTaken = cursor.getLong(dateColumn),
                        mimeType = cursor.getString(mimeColumn),
                        collection = collection
                    ))
                }
            }
        }
        return pending
    }

    private fun uploadMedia(item: MediaItem, baseUrl: okhttp3.HttpUrl, basicAuth: String): Boolean {
        val date = Date(if (item.dateTaken > 0) item.dateTaken else System.currentTimeMillis())
        val year = SimpleDateFormat("yyyy", Locale.US).format(date)
        val month = SimpleDateFormat("MM", Locale.US).format(date)

        val targetUrl = baseUrl.newBuilder()
            .addPathSegment(year)
            .addPathSegment(month)
            .addPathSegment(item.name)
            .build()

        return try {
            val uri = Uri.withAppendedPath(item.collection, item.id.toString())
            val inputStream: InputStream? = applicationContext.contentResolver.openInputStream(uri)
            val bytes = inputStream?.readBytes() ?: run {
                log("Could not read bytes for ${item.name}")
                return false
            }

            val request = Request.Builder()
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

    private fun createDirectory(parentUrl: String, dirName: String, auth: String) {
        val url = "$parentUrl/$dirName"
        val request = Request.Builder()
            .url(url)
            .method("MKCOL", null)
            .addHeader("Authorization", auth)
            .build()
        try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    log("Created directory: $url")
                }
            }
        } catch (e: Exception) {
            Log.v(TAG, "MKCOL failed for $url: ${e.message}")
        }
    }

    data class MediaItem(
        val id: Long,
        val name: String,
        val dateTaken: Long,
        val mimeType: String?,
        val collection: Uri
    )
}
