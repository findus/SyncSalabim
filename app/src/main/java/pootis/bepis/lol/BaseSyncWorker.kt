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
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.*

abstract class BaseSyncWorker(appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {

    protected val db = SyncDatabase.getDatabase(appContext)
    protected val notificationManager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    protected val client = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    protected abstract val tag: String
    protected abstract val notificationId: Int
    protected open val channelId = "sync_notification_channel"

    protected fun log(message: String, error: Throwable? = null) {
        if (error != null) {
            Log.e(tag, message, error)
            AppLogger.log("ERROR: $message - ${error.message}")
        } else {
            Log.d(tag, message)
            AppLogger.log(message)
        }
    }

    protected fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Photo Sync Status",
                NotificationManager.IMPORTANCE_LOW
            )
            notificationManager.createNotificationChannel(channel)
        }
    }

    protected fun createForegroundInfo(current: Int, total: Int, fileName: String, titlePrefix: String): ForegroundInfo {
        val title = if (total > 0) "$titlePrefix ($current/$total)" else "$titlePrefix..."
        
        val builder = NotificationCompat.Builder(applicationContext, channelId)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(title)
            .setContentText(fileName)
            .setOngoing(true)
            .setOnlyAlertOnce(true)

        if (total > 0) {
            builder.setProgress(total, current, false)
        } else {
            builder.setProgress(0, 0, true)
        }

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(notificationId, builder.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notificationId, builder.build())
        }
    }

    protected suspend fun updateProgressNotification(current: Int, total: Int, fileName: String, titlePrefix: String) {
        try {
            setForeground(createForegroundInfo(current, total, fileName, titlePrefix))
        } catch (e: Exception) {
            log("Failed to update foreground notification", e)
        }
    }

    protected fun showFinishedNotification(title: String, message: String) {
        val builder = NotificationCompat.Builder(applicationContext, channelId)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setProgress(0, 0, false)

        notificationManager.notify(notificationId + 100, builder.build())
    }

    protected fun showLaunchedNotification(title: String, message: String) {
        val builder = NotificationCompat.Builder(applicationContext, channelId)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)

        notificationManager.notify(notificationId + 200, builder.build())
    }

    protected suspend fun getAllLocalMedia(selectedFolders: Set<String>): List<MediaItem> {
        val items = mutableListOf<MediaItem>()
        items.addAll(queryMedia(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, selectedFolders))
        items.addAll(queryMedia(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, selectedFolders))
        return items
    }

    private fun queryMedia(collection: Uri, selectedFolders: Set<String>): List<MediaItem> {
        val items = mutableListOf<MediaItem>()
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.DATE_TAKEN,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.BUCKET_DISPLAY_NAME
        )

        val selection = if (selectedFolders.isNotEmpty()) {
            val placeholders = selectedFolders.joinToString(",") { "?" }
            "${MediaStore.MediaColumns.BUCKET_DISPLAY_NAME} IN ($placeholders)"
        } else {
            null
        }
        val selectionArgs = if (selectedFolders.isNotEmpty()) {
            selectedFolders.toTypedArray()
        } else {
            null
        }

        applicationContext.contentResolver.query(
            collection,
            projection,
            selection,
            selectionArgs,
            "${MediaStore.MediaColumns.DATE_TAKEN} DESC"
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_TAKEN)
            val mimeColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)

            while (cursor.moveToNext()) {
                items.add(MediaItem(
                    id = cursor.getLong(idColumn),
                    name = cursor.getString(nameColumn),
                    dateTaken = cursor.getLong(dateColumn),
                    mimeType = cursor.getString(mimeColumn),
                    collection = collection
                ))
            }
        }
        return items
    }

    data class MediaItem(
        val id: Long,
        val name: String,
        val dateTaken: Long,
        val mimeType: String?,
        val collection: Uri
    )

    protected fun getRemoteUrl(item: MediaItem, baseUrl: HttpUrl): HttpUrl {
        val date = Date(if (item.dateTaken > 0) item.dateTaken else System.currentTimeMillis())
        val year = SimpleDateFormat("yyyy", Locale.US).format(date)
        val month = SimpleDateFormat("MM", Locale.US).format(date)

        return baseUrl.newBuilder()
            .addPathSegment(year)
            .addPathSegment(month)
            .addPathSegment(item.name)
            .build()
    }

    protected fun remoteFileExists(url: String, auth: String): Boolean {
        val request = Request.Builder()
            .url(url)
            .head()
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

    protected fun createDirectory(parentUrl: String, dirName: String, auth: String) {
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
            Log.v(tag, "MKCOL failed for $url: ${e.message}")
        }
    }
}
