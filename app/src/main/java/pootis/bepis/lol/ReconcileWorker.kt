package pootis.bepis.lol

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
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
import okhttp3.OkHttpClient
import androidx.work.ForegroundInfo
import okhttp3.Request
import okhttp3.logging.HttpLoggingInterceptor
import java.text.SimpleDateFormat
import java.util.*

private const val TAG = "ReconcileWorker"
private const val CHANNEL_ID = "reconcile_notification_channel"
private const val NOTIFICATION_ID = 2

class ReconcileWorker(appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {

    private val db = SyncDatabase.getDatabase(appContext)

    private val notificationManager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager


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
        AppLogger.log("Starting full database reconciliation...")

        createNotificationChannel()
        // Start as foreground service to ensure it keeps running and show initial notification
        try {
            setForeground(createForegroundInfo(0, 0, "Initializing..."))
        } catch (e: Exception) {
            log("Failed to set foreground info", e)
        }

        val baseUrlStr = inputData.getString("baseUrl")?.removeSuffix("/") ?: return Result.failure()
        val user = inputData.getString("user") ?: return Result.failure()
        val password = inputData.getString("password") ?: return Result.failure()
        val auth = Credentials.basic(user, password)
        val baseUrl = baseUrlStr.toHttpUrl()

        try {
            // 1. Delete all entries in local database
            db.photoDao().deleteAll()
            AppLogger.log("Local sync cache cleared.")

            // 2. Get all local media files
            val allLocalItems = getAllLocalMedia()
            val total = allLocalItems.size
            AppLogger.log("Found $total local media files to verify.")

            var processed = 0
            var reconciled = 0

            for (item in allLocalItems) {
                processed++

                // Progress update
                setProgress(workDataOf(
                    "progress" to (processed.toFloat() / total),
                    "current" to processed,
                    "total" to total,
                    "name" to "Verifying: ${item.name}"
                ))

                // Update Foreground Notification with progress bar
                updateProgressNotification(processed, total, item.name)

                // 3. Extract path (Year/Month)
                val date = Date(if (item.dateTaken > 0) item.dateTaken else System.currentTimeMillis())
                val year = SimpleDateFormat("yyyy", Locale.US).format(date)
                val month = SimpleDateFormat("MM", Locale.US).format(date)

                val remoteUrl = baseUrl.newBuilder()
                    .addPathSegment(year)
                    .addPathSegment(month)
                    .addPathSegment(item.name)
                    .build()

                // 4. Check if file exists remotely (using HEAD)
                if (remoteFileExists(remoteUrl.toString(), auth)) {
                    // 5. Add to local database if it exists remotely
                    db.photoDao().insert(SyncedPhoto(item.id, item.name, System.currentTimeMillis()))
                    reconciled++
                }
            }

            // Final Progress update
            setProgress(workDataOf("progress" to 1f, "current" to total, "total" to total, "name" to "Done"))
            showFinishedNotification("Rebuilding Finished", "Successfully reconciled database.")

            AppLogger.log("Reconciliation finished. $reconciled items marked as synced.")
            return Result.success()
        } catch (e: Exception) {
            AppLogger.log("ERROR during reconciliation: ${e.message}")
            showFinishedNotification("Sync Failed", e.message ?: "An unexpected error occurred.")
            return Result.failure()
        }
    }

    private fun getAllLocalMedia(): List<LocalItem> {
        val items = mutableListOf<LocalItem>()
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.DATE_TAKEN
        )

        fun query(uri: Uri) {
            applicationContext.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_TAKEN)
                while (cursor.moveToNext()) {
                    items.add(LocalItem(
                        id = cursor.getLong(idCol),
                        name = cursor.getString(nameCol),
                        dateTaken = cursor.getLong(dateCol)
                    ))
                }
            }
        }

        query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
        return items
    }

    private fun remoteFileExists(url: String, auth: String): Boolean {
        val request = Request.Builder()
            .url(url)
            .head() // HEAD request is efficient for existence checks
            .addHeader("Authorization", auth)
            .build()
        
        return try {
            client.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        } catch (e: Exception) {
            false
        }
    }

    data class LocalItem(val id: Long, val name: String, val dateTaken: Long)

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Photo Sync Status",
                NotificationManager.IMPORTANCE_LOW // Use LOW so it doesn't make noise on every update
            )
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createForegroundInfo(current: Int, total: Int, fileName: String): ForegroundInfo {
        val title = if (total > 0) "Rebuilding Database ($current/$total)" else "Start rebuilding database..."

        val builder = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(title)
            .setContentText(fileName)
            .setOngoing(true)
            .setOnlyAlertOnce(true) // Don't buzz on every update

        if (total > 0) {
            builder.setProgress(total, current, false)
        } else {
            builder.setProgress(0, 0, true) // Indeterminate
        }

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, builder.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, builder.build())
        }
    }

    private suspend fun updateProgressNotification(current: Int, total: Int, fileName: String) {
        try {
            setForeground(createForegroundInfo(current, total, fileName))
        } catch (e: Exception) {
            log("Failed to update foreground notification", e)
        }
    }

    private fun showFinishedNotification(title: String, message: String) {
        // Use a different notification for finished state so it's not ongoing
        val builder = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setProgress(0, 0, false) // Remove progress bar

        notificationManager.notify(NOTIFICATION_ID + 1, builder.build())
    }
}
